package com.starwindow.app.data.weather

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.ZoneId

/**
 * Der zuletzt geholte Modelllauf, auf der Platte.
 *
 * Der Zwischenspeicher in [WeatherRepository] sitzt im Arbeitsspeicher, und das ist für die Ansicht
 * genau richtig: eine Vorhersage veraltet in Stunden, und zwischen Modellen hin und her zu springen
 * darf nicht jedes Mal ans Netz gehen. Für zwei andere Fragen reicht er nicht.
 *
 * **Die Erinnerung um 17 Uhr** läuft in einem Prozess, den das System für genau diesen Alarm
 * gestartet hat. Dort ist jeder Speicher leer, und wenn in dem Moment kein Netz da ist, gäbe es
 * ohne Ablage nichts zu sagen — die Benachrichtigung käme ohne ein Wort über das Wetter. Ein
 * Vorhersagestand von gestern Abend ist keine gute Auskunft, aber eine ehrliche: er wird mit seinem
 * Alter angeschrieben, und „gestern 18 Uhr sagte das Modell bedeckt" ist mehr wert als Schweigen.
 *
 * **Die Ansicht ohne Netz** zeigt aus demselben Grund die letzte bekannte Lage statt einer leeren
 * Fläche.
 *
 * Gehalten werden nur [MAX_ENTRIES] Läufe. Ein Lauf sind gut 360 Stundenwerte, also rund 70 kB;
 * eine unbegrenzte Ablage würde bei jemandem, der sich durch dreißig Orte sucht, still auf mehrere
 * Megabyte wachsen, ohne dass je einer davon noch gebraucht wird.
 */
class ForecastCache(private val file: File) {

    private val mutex = Mutex()

    /** Legt einen frisch geholten Lauf ab. Fehler hier dürfen den Abruf nicht kippen. */
    suspend fun store(forecast: WeatherForecast) = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val key = keyOf(forecast.place, forecast.model)
                val kept = read().filterNot { it.key == key }.take(MAX_ENTRIES - 1)
                write(listOf(Entry.of(forecast)) + kept)
            }.onFailure { Log.w(TAG, "Vorhersage konnte nicht abgelegt werden", it) }
            Unit
        }
    }

    /** Der letzte bekannte Lauf für diesen Ort und dieses Modell, oder null. */
    suspend fun load(place: WeatherPlace, model: WeatherModel): WeatherForecast? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val key = keyOf(place, model)
                read().firstOrNull { it.key == key }?.toForecast()
            }
        }

    /**
     * Irgendein Lauf für diesen Ort, egal von welchem Modell.
     *
     * Der Rückfall für den Fall, dass jemand das Modell gewechselt hat, seither aber kein Netz
     * hatte. Für die Frage „wird die Nacht was" ist das Modell zweitrangig — dass überhaupt eine
     * Aussage dasteht, ist die Hauptsache; welches Modell sie gemacht hat, steht daneben.
     */
    suspend fun loadAny(place: WeatherPlace): WeatherForecast? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val prefix = placeKey(place)
            read().firstOrNull { it.key.startsWith(prefix) }?.toForecast()
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock { runCatching { file.delete() }; Unit }
    }

    private fun read(): List<Entry> {
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString<List<Entry>>(file.readText())
        } catch (e: Exception) {
            // Anders als bei der Planung wird hier gelöscht statt gerettet: eine Vorhersage ist in
            // Stunden wertlos, und eine defekte Datei aufzuheben hilft niemandem.
            Log.w(TAG, "Abgelegte Vorhersage nicht lesbar", e)
            runCatching { file.delete() }
            emptyList()
        }
    }

    private fun write(entries: List<Entry>) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(json.encodeToString(entries))
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }

    /**
     * Ein abgelegter Lauf.
     *
     * [WeatherForecast] selbst trägt eine `ZoneId` und ist damit nicht serialisierbar; die Zone
     * fährt hier als Kennung mit und wird beim Lesen wieder aufgelöst. Eine Zone, die es auf diesem
     * Gerät nicht gibt, fällt auf die des Ortes und zuletzt auf die des Systems zurück, statt den
     * ganzen Eintrag zu verlieren.
     */
    @Serializable
    private data class Entry(
        val key: String,
        val place: WeatherPlace,
        val model: WeatherModel,
        val zoneId: String,
        val fetchedAtMillis: Long,
        val hours: List<WeatherHour>,
    ) {
        fun toForecast(): WeatherForecast = WeatherForecast(
            place = place,
            zone = runCatching { ZoneId.of(zoneId) }.getOrNull()
                ?: place.zone
                ?: ZoneId.systemDefault(),
            model = model,
            hours = hours,
            fetchedAtMillis = fetchedAtMillis,
        )

        companion object {
            fun of(forecast: WeatherForecast) = Entry(
                key = keyOf(forecast.place, forecast.model),
                place = forecast.place,
                model = forecast.model,
                zoneId = forecast.zone.id,
                fetchedAtMillis = forecast.fetchedAtMillis,
                hours = forecast.hours,
            )
        }
    }

    companion object {
        private const val TAG = "ForecastCache"
        const val FILE_NAME = "forecast_cache.json"

        /** Der laufende Ort und ein zweiter, zu dem jemand hin und her vergleicht. */
        private const val MAX_ENTRIES = 3

        private val json = Json {
            ignoreUnknownKeys = true
            // Fehlende Größen als fehlende Schlüssel statt als `null`-Zeilen: das halbiert die
            // Datei bei Modellen, die nur einen Teil der Spalten führen.
            explicitNulls = false
            encodeDefaults = false
        }

        /** Drei Nachkommastellen sind gut 100 m — weit unter der Maschenweite jedes Modells. */
        private fun placeKey(place: WeatherPlace) = "%.3f_%.3f".format(
            java.util.Locale.ROOT,
            place.latitudeDeg,
            place.longitudeDeg,
        )

        private fun keyOf(place: WeatherPlace, model: WeatherModel) =
            "${placeKey(place)}_${model.id}"
    }
}
