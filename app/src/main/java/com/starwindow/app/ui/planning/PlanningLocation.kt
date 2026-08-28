package com.starwindow.app.ui.planning

import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.core.sensors.LocationTracker
import com.starwindow.app.data.weather.WeatherPlace
import com.starwindow.app.data.windows.SettingsStore
import java.time.ZoneId

/**
 * Der Ort, für den geplant wird — und die Zeitzone, in der seine Nächte gezählt werden.
 *
 * **Nicht dort, wo das Handy liegt.** Wer im Oktober eine Nacht plant, sitzt dabei zu Hause und
 * denkt an das dunkle Feld, zu dem er fährt. `Settings.weatherPlace` trägt genau diese
 * Unterscheidung schon, weil die Wettervorhersage sie braucht; die Planung übernimmt sie, damit
 * beide von derselben Stelle reden. Erst wenn kein Ort gewählt ist, zählt der tatsächliche
 * Standort — von Hand eingetragen oder vom GPS.
 *
 * Die Zeitzone hängt am Ort und nicht am Gerät: Eine Nacht heißt nach dem Abend, an dem sie
 * beginnt, und zwei Zonen weiter wäre dieser Abend sonst um einen Tag verschoben.
 *
 * Zusammengefasst, weil dieselbe Auflösung an drei Stellen gebraucht wird — Jahresplanung,
 * Kalender und die nächtliche Prüfung der Merkliste — und drei Kopien davon irgendwann drei
 * verschiedene Orte bedeuten würden.
 */
data class PlanningLocation(
    val place: WeatherPlace?,
    val observer: ObserverLocation?,
    val zone: ZoneId,
) {
    val hasLocation: Boolean get() = observer != null

    /** Wie der Ort angeschrieben wird, damit die Zahlen zuzuordnen sind. */
    val label: String get() = place?.label ?: "aktueller Standort"

    companion object {
        fun resolve(
            settings: SettingsStore,
            locationTracker: LocationTracker,
        ): PlanningLocation {
            val current = settings.current
            val place = current.weatherPlace
            val observer = place?.toObserverLocation()
                ?: current.manualLocation
                ?: locationTracker.lastKnown()
            return PlanningLocation(
                place = place,
                observer = observer,
                zone = place?.zone ?: ZoneId.systemDefault(),
            )
        }
    }
}
