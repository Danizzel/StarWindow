package com.starwindow.app.core.astro

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Wie dunkel es ist. Die Grenzen sind die üblichen Sonnenhöhen.
 *
 * Für Astrofotografie zählt praktisch nur [ASTRONOMICAL_DARKNESS]: in der nautischen Dämmerung
 * steht der Himmelshintergrund noch messbar über dem natürlichen Niveau, und jede Aufnahme daraus
 * trägt einen Verlauf, den keine Bearbeitung sauber wegbekommt.
 */
enum class TwilightPhase(val label: String, val sunAltitudeCeilingDeg: Double) {
    DAY("Tag", 90.0),
    CIVIL("bürgerliche Dämmerung", -0.833),
    NAUTICAL("nautische Dämmerung", -6.0),
    ASTRONOMICAL("astronomische Dämmerung", -12.0),
    ASTRONOMICAL_DARKNESS("astronomische Dunkelheit", -18.0);

    companion object {
        /** Die Phase zu einer Sonnenhöhe. */
        fun forSunAltitude(altitudeDeg: Double): TwilightPhase = when {
            altitudeDeg > -0.833 -> DAY
            altitudeDeg > -6.0 -> CIVIL
            altitudeDeg > -12.0 -> NAUTICAL
            altitudeDeg > -18.0 -> ASTRONOMICAL
            else -> ASTRONOMICAL_DARKNESS
        }
    }
}

/**
 * Die Zeiten einer Nacht.
 *
 * Eine „Nacht" läuft hier von Mittag bis Mittag, nicht von Mitternacht bis Mitternacht. Das ist
 * der einzige Zuschnitt, bei dem der Abend und der zugehörige Morgen im selben Eintrag stehen —
 * und genau danach fragt man, wenn man wissen will, ob sich der Aufbau heute lohnt.
 *
 * Jeder Zeitpunkt kann fehlen: In hohen Breiten geht die Sonne im Sommer nicht unter und im Winter
 * nicht auf, und astronomische Dunkelheit gibt es zwischen Ende Mai und Mitte Juli nördlich von
 * etwa 49° überhaupt nicht mehr. Null heißt hier immer „findet in dieser Nacht nicht statt".
 */
data class NightTimes(
    val fromMillis: Long,
    val toMillis: Long,
    val sunsetMillis: Long?,
    val civilDuskMillis: Long?,
    val nauticalDuskMillis: Long?,
    val astronomicalDuskMillis: Long?,
    val astronomicalDawnMillis: Long?,
    val nauticalDawnMillis: Long?,
    val civilDawnMillis: Long?,
    val sunriseMillis: Long?,
    val moonriseMillis: Long?,
    val moonsetMillis: Long?,
    /** Tiefster Sonnenstand der Nacht — sagt auch dann etwas, wenn keine Grenze überschritten wird. */
    val lowestSunAltitudeDeg: Double,
) {
    /** Beginn der astronomischen Dunkelheit, oder null wenn sie ausbleibt. */
    val darkFromMillis: Long? get() = astronomicalDuskMillis

    /** Ende der astronomischen Dunkelheit. */
    val darkToMillis: Long? get() = astronomicalDawnMillis

    /** Dauer der astronomischen Dunkelheit in Millisekunden; 0 wenn es nicht dunkel wird. */
    val darkDurationMillis: Long
        get() {
            val from = darkFromMillis ?: return 0L
            val to = darkToMillis ?: return 0L
            return (to - from).coerceAtLeast(0L)
        }

    /** True, wenn die Sonne zu diesem Zeitpunkt tiefer als -18° steht. */
    fun isDark(millis: Long): Boolean {
        val from = darkFromMillis ?: return false
        val to = darkToMillis ?: return false
        return millis in from..to
    }
}

/**
 * Dämmerungs-, Auf- und Untergangszeiten.
 *
 * Gerechnet wird wie überall in dieser App, wo eine Bedingung über der Zeit gesucht wird: grob
 * abtasten, dann um jeden Vorzeichenwechsel herum halbieren (siehe `TransitCalculator`). Das ist
 * hier sogar der einzig gangbare Weg — der Mond wandert schnell genug, dass die geschlossenen
 * Formeln für Auf- und Untergang iteriert werden müssten, und die Halbierung tut genau das, nur
 * ohne Sonderfälle.
 */
object Twilight {

    /** 0,833° unter dem Horizont: Refraktion am Horizont plus scheinbarer Radius der Scheibe. */
    const val HORIZON_DEG = -0.833

    private const val SAMPLE_STEP_MILLIS = 5 * 60_000L
    private const val REFINE_ITERATIONS = 14

    /**
     * Die Nacht, die am [date] beginnt: Fenster von Mittag bis Mittag des Folgetags.
     *
     * [zone] ist die Zeitzone **des Ortes**, nicht die des Geräts — wer das Wetter für einen Ort
     * zwei Zeitzonen weiter abruft, will dessen Abend sehen, nicht den eigenen.
     */
    fun nightOf(date: LocalDate, location: ObserverLocation, zone: ZoneId): NightTimes {
        val from = date.atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
        val to = date.plusDays(1).atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
        return between(from, to, location)
    }

    /** Dieselbe Rechnung für ein beliebiges Zeitfenster. */
    fun between(fromMillis: Long, toMillis: Long, location: ObserverLocation): NightTimes {
        val sunAltitudes = sample(fromMillis, toMillis) {
            SolarEphemeris.altitudeDeg(it, location)
        }
        val sunAltitudeAt = { millis: Long -> SolarEphemeris.altitudeDeg(millis, location) }
        val moonAltitudeAt = { millis: Long ->
            LunarEphemeris.topocentricAltitudeDeg(LunarEphemeris.at(millis), location, millis)
        }
        val moonAltitudes = sample(fromMillis, toMillis, moonAltitudeAt)

        return NightTimes(
            fromMillis = fromMillis,
            toMillis = toMillis,
            sunsetMillis = firstCrossing(sunAltitudes, HORIZON_DEG, Direction.FALLING, sunAltitudeAt),
            civilDuskMillis = firstCrossing(sunAltitudes, -6.0, Direction.FALLING, sunAltitudeAt),
            nauticalDuskMillis = firstCrossing(sunAltitudes, -12.0, Direction.FALLING, sunAltitudeAt),
            astronomicalDuskMillis = firstCrossing(sunAltitudes, -18.0, Direction.FALLING, sunAltitudeAt),
            astronomicalDawnMillis = firstCrossing(sunAltitudes, -18.0, Direction.RISING, sunAltitudeAt),
            nauticalDawnMillis = firstCrossing(sunAltitudes, -12.0, Direction.RISING, sunAltitudeAt),
            civilDawnMillis = firstCrossing(sunAltitudes, -6.0, Direction.RISING, sunAltitudeAt),
            sunriseMillis = firstCrossing(sunAltitudes, HORIZON_DEG, Direction.RISING, sunAltitudeAt),
            moonriseMillis = firstCrossing(moonAltitudes, HORIZON_DEG, Direction.RISING, moonAltitudeAt),
            moonsetMillis = firstCrossing(moonAltitudes, HORIZON_DEG, Direction.FALLING, moonAltitudeAt),
            lowestSunAltitudeDeg = sunAltitudes.minOf { it.altitudeDeg },
        )
    }

    private enum class Direction { RISING, FALLING }

    private class Sample(val millis: Long, val altitudeDeg: Double)

    private fun sample(
        fromMillis: Long,
        toMillis: Long,
        altitude: (Long) -> Double,
    ): List<Sample> {
        val samples = ArrayList<Sample>(((toMillis - fromMillis) / SAMPLE_STEP_MILLIS).toInt() + 2)
        var millis = fromMillis
        while (millis < toMillis) {
            samples += Sample(millis, altitude(millis))
            millis += SAMPLE_STEP_MILLIS
        }
        samples += Sample(toMillis, altitude(toMillis))
        return samples
    }

    /**
     * Der erste Durchgang durch [thresholdDeg] in der gesuchten Richtung, oder null.
     *
     * „Erster" ist hier die richtige Wahl, weil das Fenster mittags anfängt: Untergänge kommen
     * abends, Aufgänge morgens, und ein zweiter Durchgang derselben Richtung passt in ein
     * Mittag-zu-Mittag-Fenster nur beim Mond — dessen zweiter Aufgang aber schon zur nächsten Nacht
     * gehört.
     */
    private fun firstCrossing(
        samples: List<Sample>,
        thresholdDeg: Double,
        direction: Direction,
        altitude: (Long) -> Double,
    ): Long? {
        for (i in 0 until samples.size - 1) {
            val a = samples[i]
            val b = samples[i + 1]
            val crosses = when (direction) {
                Direction.RISING -> a.altitudeDeg <= thresholdDeg && b.altitudeDeg > thresholdDeg
                Direction.FALLING -> a.altitudeDeg >= thresholdDeg && b.altitudeDeg < thresholdDeg
            }
            if (crosses) return refine(a.millis, b.millis, thresholdDeg, direction, altitude)
        }
        return null
    }

    private fun refine(
        fromMillis: Long,
        toMillis: Long,
        thresholdDeg: Double,
        direction: Direction,
        altitude: (Long) -> Double,
    ): Long {
        var low = fromMillis
        var high = toMillis
        repeat(REFINE_ITERATIONS) {
            val mid = (low + high) / 2
            val above = altitude(mid) > thresholdDeg
            val beforeCrossing = when (direction) {
                Direction.RISING -> !above
                Direction.FALLING -> above
            }
            if (beforeCrossing) low = mid else high = mid
        }
        return (low + high) / 2
    }
}
