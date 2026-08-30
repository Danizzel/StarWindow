package com.starwindow.app.data.weather

import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

/**
 * Der Ort liegt außerhalb des Modellgebiets.
 *
 * Eigener Typ, weil es kein Netzfehler ist und auch nicht so behandelt werden darf: Ein alter
 * Zwischenspeicherstand hilft hier nicht, und die Ansicht soll nicht „nicht erreichbar" sagen,
 * sondern „dieses Modell rechnet dort nicht".
 */
class OutsideModelAreaException(val model: WeatherModel) : IOException(
    "${model.label} deckt diesen Ort nicht ab (${model.coverage})."
)

/**
 * Holt Vorhersage, Modellzustand und Ortstreffer von Open-Meteo.
 *
 * Wie beim Bilddienst von Hand gebaut statt mit einer HTTP-Bibliothek: Es sind drei GET-Anfragen
 * gegen JSON, der Rest des Projekts kommt ohne Netzwerkbibliothek aus, und eine Abhängigkeit für
 * drei Anfragen wäre der teurere Weg.
 *
 * Zwei Zwischenspeicher, und sie beantworten verschiedene Fragen. Der im **Arbeitsspeicher** trägt
 * die laufende Sitzung: zwischen Modellen und Tagen hin und her zu springen darf nicht jedes Mal
 * ans Netz gehen. Der auf der **Platte** ([ForecastCache]) trägt alles, was danach kommt — die
 * Ansicht ohne Netz und vor allem die Erinnerung um 17 Uhr, die in einem frisch gestarteten Prozess
 * läuft und deren Speicher deshalb leer ist. Er ist bewusst nachgeordnet: gefragt wird er erst,
 * wenn das Netz nichts hergibt.
 */
class WeatherRepository(
    private val fetch: suspend (String) -> String = ::httpGet,
    /**
     * Die Ablage auf der Platte. Null in Tests und überall dort, wo kein `Context` zur Hand ist —
     * das Netzverhalten ändert sich dadurch nicht, es fehlt nur der Rückfall.
     */
    private val diskCache: ForecastCache? = null,
) {

    private val cache = LinkedHashMap<String, WeatherForecast>()
    private val statusCache = HashMap<WeatherModel, TimedStatus>()

    /**
     * Vorhersage für [place], gerechnet von [model].
     *
     * Schlägt nie mit einer Ausnahme fehl: Ohne Netz ist die Ansicht leer, und der Grund gehört auf
     * den Bildschirm statt in einen Absturz.
     */
    suspend fun forecast(
        place: WeatherPlace,
        model: WeatherModel,
        forceReload: Boolean = false,
    ): Result<WeatherForecast> = withContext(Dispatchers.IO) {
        val key = cacheKey(place, model)
        if (!forceReload) {
            cache[key]?.takeIf { it.isFresh() }?.let { return@withContext Result.success(it) }
        }

        var lastFailure: Throwable? = null
        for (url in OpenMeteo.forecastUrls(place.latitudeDeg, place.longitudeDeg, model)) {
            try {
                val forecast = OpenMeteo.parseForecast(fetch(url), place, model)
                if (forecast.isEmpty) {
                    // Kein Fehler des Dienstes, sondern die Antwort auf eine Frage außerhalb des
                    // Modellgebiets — und die gehört so gesagt, nicht als Netzproblem.
                    lastFailure = OutsideModelAreaException(model)
                    continue
                }
                remember(key, forecast)
                diskCache?.store(forecast)
                return@withContext Result.success(forecast)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                lastFailure = e
                Log.i(TAG, "Vorhersage nicht abrufbar über $url", e)
            }
        }

        // Lieber eine alte Vorhersage als gar keine — mit dem Abrufzeitpunkt daneben sieht man ja,
        // wie alt sie ist. Bei einem Ort außerhalb des Gebiets gibt es nichts zu retten.
        if (lastFailure !is OutsideModelAreaException) {
            cache[key]?.let { return@withContext Result.success(it) }
            diskCache?.load(place, model)?.let {
                remember(key, it)
                return@withContext Result.success(it)
            }
        }
        Result.failure(lastFailure ?: IOException("Keine Vorhersagedaten erhalten"))
    }

    /**
     * Der letzte bekannte Lauf, ohne das Netz auch nur zu versuchen.
     *
     * Für Aufrufer, die keine Wartezeit haben: eine Benachrichtigung, die gleich gepostet wird, und
     * eine Ansicht, die schon etwas zeigen will, während der Abruf läuft. [fallbackToAnyModel] gibt
     * im Notfall auch den Lauf eines anderen Modells für denselben Ort zurück — für die Frage „wird
     * die Nacht was" ist irgendeine Aussage mehr wert als keine, und welches Modell sie gemacht
     * hat, wird ohnehin dazugeschrieben.
     */
    suspend fun lastKnown(
        place: WeatherPlace,
        model: WeatherModel,
        fallbackToAnyModel: Boolean = false,
    ): WeatherForecast? = withContext(Dispatchers.IO) {
        cache[cacheKey(place, model)]
            ?: diskCache?.load(place, model)
            ?: if (fallbackToAnyModel) diskCache?.loadAny(place) else null
    }

    /**
     * Zustand eines Modells: wann es zuletzt gerechnet hat und wo es überhaupt gilt.
     *
     * Eigener Abruf, weil die Vorhersage-Antwort das nicht mitliefert — und ein geratener
     * Lauf-Zeitpunkt wäre schlimmer als keiner: Wer sieht „aktualisiert vor 20 Minuten", verlässt
     * sich darauf.
     */
    suspend fun modelStatus(
        model: WeatherModel,
        forceReload: Boolean = false,
    ): Result<WeatherModelStatus> = withContext(Dispatchers.IO) {
        if (!forceReload) {
            statusCache[model]?.takeIf { it.isFresh() }?.let {
                return@withContext Result.success(it.status)
            }
        }
        try {
            val status = OpenMeteo.parseModelStatus(fetch(OpenMeteo.metaUrl(model)), model)
            statusCache[model] = TimedStatus(status, System.currentTimeMillis())
            Result.success(status)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.i(TAG, "Modellzustand nicht abrufbar: ${model.id}", e)
            Result.failure(e)
        }
    }

    /**
     * Der Zustand aller Modelle, nebenläufig geholt.
     *
     * Sechs kleine Anfragen nacheinander wären eine spürbare Pause, bevor die Auswahl steht;
     * nebeneinander sind sie eine. Modelle, deren Zustand nicht zu holen ist, fehlen in der Karte —
     * die Auswahl zeigt sie dann ohne Zeitangabe, statt gar nicht.
     */
    suspend fun modelStatuses(
        models: List<WeatherModel> = WeatherModel.ORDERED,
    ): Map<WeatherModel, WeatherModelStatus> = withContext(Dispatchers.IO) {
        models.map { model -> async { modelStatus(model).getOrNull() } }
            .awaitAll()
            .filterNotNull()
            .associateBy { it.model }
    }

    /** Ortssuche. Eine leere Eingabe fragt gar nicht erst. */
    suspend fun searchPlaces(query: String): Result<List<WeatherPlace>> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext Result.success(emptyList())
            try {
                Result.success(OpenMeteo.parsePlaces(fetch(OpenMeteo.geocodingUrl(query))))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.i(TAG, "Ortssuche fehlgeschlagen", e)
                Result.failure(e)
            }
        }

    /** Vorhersage aus dem Zwischenspeicher, ohne Netz — für den ersten Aufbau der Ansicht. */
    fun cached(place: WeatherPlace, model: WeatherModel): WeatherForecast? =
        cache[cacheKey(place, model)]

    /**
     * Legt eine Vorhersage ab und wirft die älteste weg, sobald es zu viele werden.
     *
     * Ein Lauf sind gut 360 Stundenwerte; ein paar davon nebeneinander sind nichts, aber wer sich
     * durch dreißig Orte sucht, soll sie nicht alle bis zum Beenden der App mitschleppen.
     */
    private fun remember(key: String, forecast: WeatherForecast) {
        cache.remove(key)
        cache[key] = forecast
        while (cache.size > MAX_CACHED_PLACES) {
            cache.remove(cache.keys.first())
        }
    }

    private fun WeatherForecast.isFresh(): Boolean =
        System.currentTimeMillis() - fetchedAtMillis < CACHE_TTL_MILLIS

    /**
     * Ort und Modell zusammen — dieselben Koordinaten von zwei Modellen sind zwei Vorhersagen.
     *
     * Drei Nachkommastellen sind gut 100 m und damit weit unter der Maschenweite auch des feinsten
     * Modells der Liste.
     */
    private fun cacheKey(place: WeatherPlace, model: WeatherModel) =
        "%s_%.3f_%.3f".format(
            java.util.Locale.ROOT,
            model.id,
            place.latitudeDeg,
            place.longitudeDeg,
        )

    /** Ein Modellzustand mit dem Zeitpunkt, zu dem er geholt wurde. */
    private class TimedStatus(val status: WeatherModelStatus, private val fetchedAtMillis: Long) {
        /**
         * Kurz gültig, anders als die Vorhersage: Der Zustand *ist* die Aussage darüber, wie
         * frisch die Daten sind — ihn eine halbe Stunde lang aus dem Speicher zu bedienen, hieße
         * genau die Zahl veralten zu lassen, um derentwillen es ihn gibt.
         */
        fun isFresh(): Boolean =
            System.currentTimeMillis() - fetchedAtMillis < STATUS_TTL_MILLIS
    }

    companion object {
        private const val TAG = "WeatherRepository"
        private const val CACHE_TTL_MILLIS = 30 * 60_000L
        private const val STATUS_TTL_MILLIS = 5 * 60_000L
        private const val MAX_CACHED_PLACES = 8
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val USER_AGENT = "StarWindow/0.1 (Android)"

        /**
         * Eine GET-Anfrage, Antwort als Text.
         *
         * Auch der Fehlerstrom wird gelesen: Open-Meteo schickt den Grund einer abgelehnten
         * Anfrage als JSON mit Status 400, und dieser Grund ist die einzige brauchbare Meldung,
         * die es zu einer fehlgeschlagenen Anfrage gibt.
         */
        private suspend fun httpGet(url: String): String = withContext(Dispatchers.IO) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }
            try {
                val status = connection.responseCode
                val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()
                if (status !in 200..299) {
                    throw IOException(reasonFrom(body) ?: "Wetterdienst antwortete mit $status")
                }
                body
            } finally {
                connection.disconnect()
            }
        }

        /** Zieht `"reason"` aus einer Fehlerantwort, ohne dafür ein eigenes Schema zu brauchen. */
        private fun reasonFrom(body: String): String? =
            Regex("\"reason\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
    }
}
