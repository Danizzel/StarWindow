package com.starwindow.app.data.weather

import android.content.Context
import com.starwindow.app.data.windows.SettingsStore
import com.starwindow.app.domain.NightOutlook
import com.starwindow.app.domain.NightOutlooks
import java.time.LocalDate

/**
 * Die Vorhersage für eine Nacht, aus der Sicht eines Empfängers.
 *
 * Ein `BroadcastReceiver` hat kein GPS, keine Berechtigungsabfrage und keine Zeit. Was er hat, sind
 * die Einstellungen — der Wetterort steht dort ohnehin, weil die Wetteransicht ihn braucht — und
 * die Ablage auf der Platte. Diese Klasse bündelt beides, damit die Empfänger sich nicht jeder für
 * sich denselben Weg zusammensuchen.
 *
 * Getrennt in [stored] und [fresh], und die Trennung ist der Zweck: Der abgelegte Stand ist
 * **sofort** da und kann eine Benachrichtigung ergänzen, bevor irgendetwas ans Netz geht; der
 * frische Abruf darf danach noch einmal überschreiben. Wer beides in einer Methode zusammenfasst,
 * bekommt entweder eine Wartezeit oder einen alten Wert, aber nie beides richtig.
 */
class NightWeatherSource(
    private val settings: SettingsStore,
    private val repository: WeatherRepository,
) {

    /**
     * Der Ort, für den gerechnet wird.
     *
     * Der Wetterort geht vor: Wer einen gesetzt hat, meint das dunkle Feld, zu dem er fährt, und
     * nicht das Sofa, auf dem er gerade sitzt. Danach der von Hand eingetragene Standort. Ist
     * beides leer, gibt es keine Aussage — und dann steht in der Benachrichtigung eben nichts über
     * das Wetter, statt einer Prognose für einen geratenen Ort.
     */
    val place: WeatherPlace?
        get() = settings.current.let { current ->
            current.weatherPlace ?: current.manualLocation?.let { WeatherPlace.fromObserver(it) }
        }

    val hasPlace: Boolean get() = place != null

    /** Der letzte bekannte Stand, ohne Netz und ohne Wartezeit. */
    suspend fun stored(date: LocalDate, nowMillis: Long = System.currentTimeMillis()): NightOutlook? {
        val place = place ?: return null
        val forecast = repository.lastKnown(
            place = place,
            model = settings.current.weatherModel,
            // Hat jemand das Modell gewechselt und seither kein Netz gehabt, ist der Lauf des alten
            // Modells alles, was da ist. Welches gerechnet hat, steht in der Zeile mit dabei.
            fallbackToAnyModel = true,
        ) ?: return null
        return outlook(forecast, place, date, nowMillis)
    }

    /**
     * Frisch geholt, mit Rückfall auf die Ablage — das übernimmt [WeatherRepository.forecast]
     * bereits, weshalb hier kein zweiter Versuch nötig ist.
     */
    suspend fun fresh(date: LocalDate, nowMillis: Long = System.currentTimeMillis()): NightOutlook? {
        val place = place ?: return null
        val forecast = repository
            .forecast(place, settings.current.weatherModel)
            .getOrNull() ?: return null
        return outlook(forecast, place, date, nowMillis)
    }

    private fun outlook(
        forecast: WeatherForecast,
        place: WeatherPlace,
        date: LocalDate,
        nowMillis: Long,
    ): NightOutlook? = NightOutlooks.forDate(
        forecast = forecast,
        location = place.toObserverLocation(),
        date = date,
        // Die Zeitzone des Ortes, nicht die des Geräts: Wer von Zürich aus eine Nacht in Namibia
        // plant, meint dort den Abend.
        zone = place.zone ?: forecast.zone,
        nowMillis = nowMillis,
    )

    companion object {
        /**
         * Baut die Quelle aus einem beliebigen `Context`.
         *
         * Beide Bestandteile sind billig: die Einstellungen sind eine SharedPreferences-Datei, das
         * Wetterarchiv eine Datei, die erst beim Lesen angefasst wird. Ein Empfänger darf das im
         * Hauptthread anlegen — gelesen wird danach ausschließlich in einer Koroutine.
         */
        fun forContext(context: Context): NightWeatherSource {
            val appContext = context.applicationContext
            return NightWeatherSource(
                settings = SettingsStore(appContext),
                repository = WeatherRepository(
                    diskCache = ForecastCache(
                        java.io.File(appContext.filesDir, ForecastCache.FILE_NAME)
                    )
                ),
            )
        }
    }
}
