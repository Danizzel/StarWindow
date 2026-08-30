package com.starwindow.app.data.weather

import com.starwindow.app.core.astro.ObserverLocation
import java.time.ZoneId
import kotlinx.serialization.Serializable

/**
 * Ein Beobachtungsort für die Wettervorhersage.
 *
 * Bewusst nicht dieselbe Klasse wie [ObserverLocation]: dort steht eine reine Position mit ihrer
 * Messgenauigkeit, hier ein *benannter* Ort mit Zeitzone und Einwohnerzahl. Beides brauchen wir —
 * die Zeitzone, damit „heute Abend" der Abend am Zielort ist und nicht der am Gerät, und die
 * Einwohnerzahl für die Bortle-Schätzung.
 */
@Serializable
data class WeatherPlace(
    val name: String,
    /** Bundesland, Kanton, Département — was der Geocoder als erste Verwaltungsebene liefert. */
    val region: String? = null,
    val country: String? = null,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val elevationM: Double = 0.0,
    /** IANA-Zeitzone des Ortes, z. B. `Europe/Zurich`. */
    val timezoneId: String? = null,
    val population: Int? = null,
    /**
     * Entfernung von hier zur Mitte der Ortschaft, deren [population] eingetragen ist.
     *
     * Bei einem eingetippten Ort ist sie null — Ort und Ortschaft sind dasselbe. Kommt die Position
     * vom GPS, steht hier der Abstand zur nächsten benannten Ortschaft, und genau der entscheidet
     * über die Bortle-Schätzung: fünf Kilometer vor der Stadt ist ein anderer Himmel als mittendrin.
     */
    val populationDistanceKm: Double = 0.0,
    /**
     * Selbst gesetzte Bortle-Stufe. Sie hängt am Ort und nicht an den Einstellungen, damit sie
     * beim Ortswechsel verschwindet statt stillschweigend weiterzugelten.
     */
    val bortleOverride: Int? = null,
) {
    /**
     * „Zürich, Schweiz" — Region nur, wenn sie etwas Neues sagt.
     *
     * Der Geocoder gibt als Region gern „Kanton Zürich" zu „Zürich" zurück; das doppelt den Namen,
     * ohne den Ort besser zu treffen, und fliegt deshalb raus.
     */
    val label: String
        get() = buildString {
            append(name)
            region?.takeIf { it.isNotBlank() && !it.contains(name, ignoreCase = true) }
                ?.let { append(", ").append(it) }
            country?.takeIf { it.isNotBlank() && !it.contains(name, ignoreCase = true) }
                ?.let { append(", ").append(it) }
        }

    /** Kurzform für die Kopfzeile, wo die Breite fehlt. */
    val shortLabel: String
        get() = listOfNotNull(name, country?.takeIf { !it.equals(name, ignoreCase = true) })
            .joinToString(", ")

    val zone: ZoneId?
        get() = timezoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() }

    fun toObserverLocation(): ObserverLocation = ObserverLocation(
        latitudeDeg = latitudeDeg,
        longitudeDeg = longitudeDeg,
        elevationM = elevationM,
        manual = true,
    )

    companion object {
        /** Aus einer GPS-Position, wenn kein Name bekannt ist. */
        fun fromObserver(location: ObserverLocation, name: String = "Aktuelle Position") =
            WeatherPlace(
                name = name,
                latitudeDeg = location.latitudeDeg,
                longitudeDeg = location.longitudeDeg,
                elevationM = location.elevationM,
            )
    }
}

/**
 * Eine Stunde Vorhersage, so wie das Modell sie liefert.
 *
 * Jeder Wert ist optional, und das ist kein Übereifer: Welche Größen ein Lauf mitbringt, hängt am
 * Modell und am Vorhersagehorizont, und eine fehlende Bö ist etwas anderes als eine Bö von 0 km/h.
 * Die Anzeige lässt fehlende Zeilen weg, statt Nullen zu erfinden.
 *
 * Serialisierbar, weil der zuletzt geholte Lauf auf der Platte landet ([ForecastCache]): Eine
 * Erinnerung um 17 Uhr läuft in einem frisch gestarteten Prozess und hätte ohne Ablage nichts, aus
 * dem sie etwas über die Nacht sagen könnte. Alle Felder haben `null` als Vorgabe, damit ein alter
 * Ablagestand auch dann noch lesbar bleibt, wenn hier später eine Größe dazukommt.
 */
@Serializable
data class WeatherHour(
    val millis: Long,
    /** Gesamtbedeckung in Prozent. */
    val cloudTotalPercent: Double? = null,
    val cloudLowPercent: Double? = null,
    val cloudMidPercent: Double? = null,
    val cloudHighPercent: Double? = null,
    val temperatureC: Double? = null,
    val dewPointC: Double? = null,
    val humidityPercent: Double? = null,
    val windSpeedKmh: Double? = null,
    val windGustsKmh: Double? = null,
    val precipitationMm: Double? = null,
    val pressureHpa: Double? = null,
    /**
     * Windgeschwindigkeit auf 250 hPa, also in Höhe des Jetstreams. Kein Bewölkungswert, sondern
     * der beste frei verfügbare Anhaltspunkt für das **Seeing**: Ein Jet direkt über dem Standort
     * bedeutet Scherung in der Höhe und damit unruhige Sterne, auch bei völlig klarem Himmel.
     */
    val jetStreamKmh: Double? = null,
) {
    /**
     * Die Bedeckung, mit der gerechnet wird.
     *
     * Normalerweise die Gesamtangabe des Modells. Fehlt sie, tritt die dichteste der drei Schichten
     * an ihre Stelle — das ist die vorsichtige Annahme, denn eine geschlossene Schicht in beliebiger
     * Höhe verdeckt den Himmel vollständig, egal was darüber oder darunter liegt.
     */
    val effectiveCloudPercent: Double?
        get() = cloudTotalPercent
            ?: listOfNotNull(cloudLowPercent, cloudMidPercent, cloudHighPercent).maxOrNull()

    /** Taupunktdifferenz in Kelvin — unter etwa 2 K beschlägt die Optik. */
    val dewSpreadK: Double?
        get() {
            val t = temperatureC ?: return null
            val d = dewPointC ?: return null
            return t - d
        }
}

/** Ein kompletter Modelllauf für einen Ort. */
data class WeatherForecast(
    val place: WeatherPlace,
    /** Zeitzone des Ortes; alle Anzeigen laufen darüber. */
    val zone: ZoneId,
    /** Das Modell, das gerechnet hat. */
    val model: WeatherModel,
    val hours: List<WeatherHour>,
    val fetchedAtMillis: Long,
) {
    val isEmpty: Boolean get() = hours.isEmpty()

    val firstMillis: Long? get() = hours.firstOrNull()?.millis

    /**
     * Bis hierher trägt die Vorhersage.
     *
     * Das ist nicht dasselbe wie das Ende des letzten Modelllaufs: Die Schnittstelle setzt mehrere
     * Läufe zusammen und reicht damit oft weiter, als der jüngste allein. Wie weit — steht hier.
     */
    val lastMillis: Long? get() = hours.lastOrNull()?.millis
}
