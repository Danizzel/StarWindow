package com.starwindow.app.data.planning

import com.starwindow.app.core.astro.Equatorial
import com.starwindow.app.data.catalog.ObjectType
import com.starwindow.app.data.catalog.SkyObject
import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * Wie streng die Wetterlage sein muss, damit sich die App meldet.
 *
 * Zwei Stufen und keine Prozentzahl. Die Frage dahinter ist keine Feineinstellung, sondern eine
 * Haltung: Wer eine Stunde fährt, will nur die sichere Nacht; wer die Kamera auf dem Balkon
 * aufbaut, nimmt die Lücke mit. Ein Schieberegler von 0 bis 100 würde so tun, als sei die
 * Bewertung genauer, als sie ist.
 */
@Serializable
enum class WeatherDemand(val label: String, val hint: String) {
    GOOD_ONLY("nur bei guter Nacht", "Meldet sich nur, wenn die Vorhersage die Nacht als geeignet einstuft."),
    ALSO_PARTLY("auch bei Lücken", "Meldet sich auch, wenn nur ein Teil der Nacht klar wird.");

    val acceptsPartly: Boolean get() = this == ALSO_PARTLY
}

/**
 * Ein Objekt, auf das jemand wartet.
 *
 * Der Unterschied zu [PlannedSession] ist der Zeitpunkt der Entscheidung, und er ist der ganze
 * Zweck dieser Klasse. Ein Termin ist eine **Festlegung**: die Nacht steht, die Erinnerung kommt,
 * ob es passt oder nicht. Ein Vormerken ist eine **Bedingung**: die Nacht ist noch unbekannt, und
 * gemeldet wird erst, wenn zwei Dinge gleichzeitig zutreffen — das Objekt steht günstig, und die
 * kommende Nacht taugt zum Fotografieren. Für die meisten Ziele ist das die ehrlichere Form der
 * Planung: „irgendwann im Herbst, wenn es passt" ist, wie Astrofotografie tatsächlich abläuft.
 *
 * Die Koordinaten stehen mit im Eintrag. Das ist bewusst redundant zum Katalog, spart der
 * nächtlichen Prüfung aber, 22.528 Einträge von der Platte zu holen, nur um bei einem davon
 * nachzusehen — in einem Prozess, den das System für einen einzelnen Alarm gestartet hat und nach
 * ein paar Sekunden wieder beendet.
 */
@Serializable
data class WatchedObject(
    val id: String,
    /** Katalogkennung, z. B. `M31` — verbindet den Eintrag zurück mit dem Objekt. */
    val objectId: String,
    val label: String,
    val type: ObjectType = ObjectType.OTHER,
    /** Katalogposition, J2000. */
    val raDeg: Double,
    val decDeg: Double,
    /**
     * Ab welcher Höhe das Objekt als „günstig stehend" gilt.
     *
     * 30° als Vorgabe, wie in der Jahresplanung: Dort ist die Luftmasse auf das Doppelte des
     * Zenitwerts gefallen, darunter entscheiden Dunst und Lichtglocke über das Bild.
     */
    val minAltitudeDeg: Double = DEFAULT_MIN_ALTITUDE_DEG,
    /** So viel gleichzeitig brauchbare Zeit muss eine Nacht hergeben, sonst schweigt die App. */
    val minUsableHours: Double = DEFAULT_MIN_HOURS,
    val weatherDemand: WeatherDemand = WeatherDemand.GOOD_ONLY,
    /**
     * So viele Nächte Ruhe nach einer Meldung.
     *
     * Ohne das meldet sich eine stabile Hochdrucklage fünf Abende hintereinander für dasselbe
     * Objekt — und ab der zweiten Meldung liest sie niemand mehr, auch die nicht, die zählt.
     */
    val quietNights: Int = DEFAULT_QUIET_NIGHTS,
    /** Wann zuletzt gemeldet wurde, als Epochentag der Nacht. */
    val lastNotifiedEpochDay: Long? = null,
    /** Ausgeschaltet statt gelöscht — für die Saison, in der das Objekt ohnehin nicht zu sehen ist. */
    val enabled: Boolean = true,
    val createdAtMillis: Long = 0L,
) {
    val position: Equatorial get() = Equatorial(raDeg, decDeg)

    val minUsableMillis: Long get() = (minUsableHours * 3_600_000.0).toLong()

    /**
     * Darf für die Nacht auf [date] gemeldet werden?
     *
     * Die Ruhezeit zählt in Nächten und nicht in Stunden: Zwei Meldungen für dasselbe Objekt in
     * derselben Nacht wären eine Wiederholung, zwei an aufeinanderfolgenden Abenden auch.
     */
    fun mayNotifyFor(date: LocalDate): Boolean {
        if (!enabled) return false
        val last = lastNotifiedEpochDay ?: return true
        return date.toEpochDay() - last >= quietNights
    }

    companion object {
        const val DEFAULT_MIN_ALTITUDE_DEG = 30.0
        const val DEFAULT_MIN_HOURS = 1.0
        const val DEFAULT_QUIET_NIGHTS = 3

        /** Aus einem Katalogeintrag. Alles Weitere sind Vorgaben, die sich nachträglich ändern lassen. */
        fun of(obj: SkyObject, id: String, nowMillis: Long = System.currentTimeMillis()) = WatchedObject(
            id = id,
            objectId = obj.id,
            label = obj.displayName,
            type = obj.type,
            raDeg = obj.raDeg,
            decDeg = obj.decDeg,
            createdAtMillis = nowMillis,
        )
    }
}
