package com.starwindow.app.domain

import com.starwindow.app.core.astro.AstroTime
import com.starwindow.app.core.astro.Angles
import com.starwindow.app.core.astro.CoordinateTransforms
import com.starwindow.app.core.astro.LunarEphemeris
import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.core.astro.Precession
import com.starwindow.app.core.astro.SolarEphemeris
import com.starwindow.app.data.catalog.SkyObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * How dark a night actually gets.
 *
 * Not a detail: north of about 49° there are weeks around midsummer when the Sun never reaches 18°
 * below the horizon, and in Berlin that is most of June. A planner that quietly treated those
 * nights like any other would be recommending sessions that cannot happen.
 */
enum class DarknessQuality(val label: String, val explanation: String) {
    ASTRONOMICAL(
        "astronomisch dunkel",
        "Die Sonne steht mehr als 18° unter dem Horizont – so dunkel, wie es an diesem Ort wird.",
    ),
    NAUTICAL(
        "nur nautische Dämmerung",
        "Die Sonne bleibt zwischen 12° und 18° unter dem Horizont. Der Himmel behält eine Resthelligkeit; " +
            "helle Objekte gehen, schwache Nebel nicht.",
    ),
    NONE(
        "wird nicht dunkel",
        "Die Sonne bleibt zu hoch – in diesen Wochen gibt es hier keine brauchbare Nacht.",
    );

    val isUsable: Boolean get() = this != NONE
}

/** What one night offers for one object. */
data class ObservationNight(
    val date: LocalDate,
    /** The dark window, or null when the night never gets dark enough. */
    val darkFromMillis: Long?,
    val darkToMillis: Long?,
    val darkness: DarknessQuality,
    /** The best moment to expose, or null when the object is not up during darkness at all. */
    val bestMillis: Long?,
    /** How high the object stands at [bestMillis]. */
    val bestAltitudeDeg: Double,
    /**
     * How long the object stays both above [ObservationPlanner.MIN_USEFUL_ALTITUDE_DEG] **and** in
     * the dark — the number an astrophotographer actually plans by, because it is the total
     * exposure the night allows.
     */
    val usableMillis: Long,
    /** Illuminated fraction of the Moon in percent. */
    val moonIlluminationPercent: Double,
    /** How high the Moon stands at [bestMillis]; below zero it does not interfere. */
    val moonAltitudeDeg: Double,
    /** Overall quality, 0 (useless) to 100 (as good as this object gets here). */
    val score: Double,
) {
    val usableHours: Double get() = usableMillis / 3_600_000.0

    val moonInterferes: Boolean get() = moonAltitudeDeg > 0.0 && moonIlluminationPercent >= 30.0

    val isWorthwhile: Boolean get() = usableMillis > 0 && darkness.isUsable
}

/**
 * When a given object is best photographed from a given place, night by night.
 *
 * Two things move against each other over a year, and the whole point of this class is that neither
 * alone answers the question. **The object** rises about four minutes earlier each night, so it
 * drifts steadily from the morning sky into the evening sky and back. **The darkness** swings the
 * other way: a summer night in Central Europe offers a couple of hours of real darkness — or none
 * at all — while a winter night offers thirteen. An object at its yearly highest in June may be
 * unphotographable, and the same object in November, a little lower, may give five hours.
 *
 * What comes out is therefore not "when is it highest" but **how many hours can I actually
 * expose** — the object above a useful altitude and the sky genuinely dark, at the same time.
 *
 * ### Why this is computed rather than sampled
 *
 * [com.starwindow.app.core.astro.Twilight] finds the same boundaries by sampling every five
 * minutes and bisecting, which is right for one night and hopeless for 365 of them: that is around
 * two hundred thousand solar and lunar positions for a single planning screen. Here the crossings
 * are solved instead. An object at declination δ seen from latitude φ stands at altitude h exactly
 * at the hour angles satisfying
 *
 *     cos H = (sin h − sin φ · sin δ) / (cos φ · cos δ)
 *
 * which is one arccosine rather than a search. The Sun's own declination barely moves within a
 * night, so the same formula gives dusk and dawn. That turns a year into a few hundred
 * trigonometric evaluations, and it runs while the screen is opening.
 */
object ObservationPlanner {

    /** Sun altitude defining astronomical darkness. */
    const val ASTRONOMICAL_TWILIGHT_DEG = -18.0

    /** The fallback when astronomical darkness never arrives — a short summer night still works. */
    const val NAUTICAL_TWILIGHT_DEG = -12.0

    /**
     * Below this an object sits in the thick air near the horizon.
     *
     * Thirty degrees is where the air mass has dropped to twice the zenith value; lower than that
     * and haze, light domes and seeing decide the picture rather than the exposure.
     */
    const val MIN_USEFUL_ALTITUDE_DEG = 30.0

    /** A night has to offer at least this much to be worth listing as an opportunity. */
    const val MIN_WORTHWHILE_MILLIS = 45 * 60_000L

    /**
     * The night-by-night outlook for one object.
     *
     * @param days how far ahead to look; a full year by default, which is what makes seasonal
     *   planning possible at all.
     */
    fun plan(
        obj: SkyObject,
        observer: ObserverLocation,
        zone: ZoneId,
        from: LocalDate,
        days: Int = 365,
    ): List<ObservationNight> {
        val precession = Precession.forEpoch(
            from.atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
        )
        val position = obj.positionAt(precession)
        return (0 until days).map { offset ->
            nightOf(from.plusDays(offset.toLong()), position, observer, zone)
        }
    }

    /**
     * The best nights, most rewarding first.
     *
     * Deliberately spread out: without [minimumGapDays] the answer would be one good week listed
     * seven times, since consecutive nights differ by four minutes of sky rotation and a sliver of
     * Moon. What someone planning a season wants is distinct opportunities.
     */
    fun bestNights(
        nights: List<ObservationNight>,
        limit: Int = 12,
        minimumGapDays: Long = 5,
    ): List<ObservationNight> {
        val chosen = mutableListOf<ObservationNight>()
        for (night in nights.sortedByDescending { it.score }) {
            if (!night.isWorthwhile) continue
            if (chosen.any { abs(it.date.toEpochDay() - night.date.toEpochDay()) < minimumGapDays }) {
                continue
            }
            chosen += night
            if (chosen.size == limit) break
        }
        return chosen.sortedBy { it.date }
    }

    /**
     * The stretch of the year in which the object is worth photographing.
     *
     * The **longest unbroken run** of usable nights, not simply the first and the last. Almost every
     * object has a dead stretch when it sits in the daytime sky, and taking the outer bounds would
     * report the gap as part of the season: M31 from Berlin is usable from midsummer through to
     * March and hopeless in April and May, and first-to-last would answer "all year", which is
     * exactly backwards about the two months that matter.
     */
    fun season(nights: List<ObservationNight>): ClosedRange<LocalDate>? {
        var bestStart: LocalDate? = null
        var bestEnd: LocalDate? = null
        var bestLength = 0

        var runStart: LocalDate? = null
        var runLength = 0

        fun closeRun(end: LocalDate?) {
            val start = runStart ?: return
            if (runLength > bestLength && end != null) {
                bestLength = runLength
                bestStart = start
                bestEnd = end
            }
            runStart = null
            runLength = 0
        }

        var previous: LocalDate? = null
        for (night in nights) {
            if (night.usableMillis >= MIN_WORTHWHILE_MILLIS) {
                if (runStart == null) runStart = night.date
                runLength++
                previous = night.date
            } else {
                closeRun(previous)
            }
        }
        closeRun(previous)

        val start = bestStart ?: return null
        val end = bestEnd ?: return null
        return start..end
    }

    /**
     * True when the object is essentially available all year from this place.
     *
     * Worth saying differently in the interface: "Saison: 3. Juli bis 28. Juni" is a strange way to
     * write "circumpolar, take your pick".
     */
    fun isYearRound(nights: List<ObservationNight>): Boolean {
        if (nights.isEmpty()) return false
        val usable = nights.count { it.usableMillis >= MIN_WORTHWHILE_MILLIS }
        return usable >= nights.size * 0.95
    }

    private fun nightOf(
        date: LocalDate,
        position: com.starwindow.app.core.astro.Equatorial,
        observer: ObserverLocation,
        zone: ZoneId,
    ): ObservationNight {
        // Local midnight *after* the evening in question: a night belongs to the day it starts on,
        // the way people speak about it ("Freitagnacht"), not to the calendar day it ends on.
        val midnight = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val sun = SolarEphemeris.at(midnight)

        val (darkFrom, darkTo, darkness) = darkWindow(sun, observer, midnight)

        if (darkFrom == null || darkTo == null) {
            return ObservationNight(
                date = date,
                darkFromMillis = null,
                darkToMillis = null,
                darkness = darkness,
                bestMillis = null,
                bestAltitudeDeg = 0.0,
                usableMillis = 0L,
                moonIlluminationPercent = LunarEphemeris.illuminationAt(midnight).percent,
                moonAltitudeDeg = -90.0,
                score = 0.0,
            )
        }

        // The object transits when the local sidereal time equals its right ascension.
        val transit = timeOfHourAngle(position.raDeg, 0.0, observer, midnight)
        val maxAltitude = 90.0 - abs(observer.latitudeDeg - position.decDeg)

        // The best moment is the transit if it falls inside the dark window, otherwise whichever
        // end of that window is closest to it — altitude falls off monotonically either side.
        val best = transit.coerceIn(darkFrom, darkTo)
        val bestAltitude = altitudeAt(position, observer, best)

        val usable = usableOverlap(position, observer, midnight, darkFrom, darkTo)

        val moon = LunarEphemeris.at(best)
        val moonAltitude = LunarEphemeris.topocentricAltitudeDeg(moon, observer, best)
        val illumination = LunarEphemeris.illuminationAt(best).percent

        return ObservationNight(
            date = date,
            darkFromMillis = darkFrom,
            darkToMillis = darkTo,
            darkness = darkness,
            bestMillis = best,
            bestAltitudeDeg = bestAltitude,
            usableMillis = usable,
            moonIlluminationPercent = illumination,
            moonAltitudeDeg = moonAltitude,
            score = score(usable, bestAltitude, maxAltitude, darkness, illumination, moonAltitude),
        )
    }

    /**
     * Dusk and dawn, and how dark it gets in between.
     *
     * Falls back to nautical twilight when astronomical darkness never arrives, rather than
     * reporting nothing: a short bright summer night is still a night, and saying so honestly is
     * more useful than an empty calendar from late May to mid-July.
     */
    private fun darkWindow(
        sun: com.starwindow.app.core.astro.SunPosition,
        observer: ObserverLocation,
        midnightMillis: Long,
    ): Triple<Long?, Long?, DarknessQuality> {
        for ((threshold, quality) in listOf(
            ASTRONOMICAL_TWILIGHT_DEG to DarknessQuality.ASTRONOMICAL,
            NAUTICAL_TWILIGHT_DEG to DarknessQuality.NAUTICAL,
        )) {
            val hourAngle = hourAngleAtAltitude(threshold, sun.equatorial.decDeg, observer.latitudeDeg)
                ?: continue
            // The Sun is below the threshold between +H (dusk) and −H (dawn) of its own transit,
            // and its transit is local solar noon — half a day from the midnight we started at.
            val dusk = timeOfHourAngle(sun.equatorial.raDeg, hourAngle, observer, midnightMillis)
            val dawn = timeOfHourAngle(sun.equatorial.raDeg, -hourAngle, observer, midnightMillis)
            if (dawn > dusk) return Triple(dusk, dawn, quality)
        }
        return Triple(null, null, DarknessQuality.NONE)
    }

    /**
     * How much of the dark window the object spends above [MIN_USEFUL_ALTITUDE_DEG].
     *
     * The object is high enough between −H and +H of its transit, so this is the overlap of two
     * intervals — no search, and it handles the two edge cases by construction: an object that
     * never gets that high yields no interval at all, and a circumpolar one that never drops below
     * it gets the whole dark window.
     */
    private fun usableOverlap(
        position: com.starwindow.app.core.astro.Equatorial,
        observer: ObserverLocation,
        midnightMillis: Long,
        darkFrom: Long,
        darkTo: Long,
    ): Long {
        val hourAngle = hourAngleAtAltitude(
            MIN_USEFUL_ALTITUDE_DEG, position.decDeg, observer.latitudeDeg,
        )
        if (hourAngle == null) {
            // No crossing: either always above the threshold, or never.
            val always = 90.0 - abs(observer.latitudeDeg - position.decDeg) >= MIN_USEFUL_ALTITUDE_DEG
            return if (always) darkTo - darkFrom else 0L
        }

        val transit = timeOfHourAngle(position.raDeg, 0.0, observer, midnightMillis)
        val halfWidth = (hourAngle * AstroTime.SECONDS_PER_DEGREE_OF_HOUR_ANGLE * 1000.0).toLong()

        // The object is up around its transit, and the transit itself may sit a sidereal day either
        // side of our midnight, so neighbouring passes are checked too.
        val siderealDay = (360.0 * AstroTime.SECONDS_PER_DEGREE_OF_HOUR_ANGLE * 1000.0).toLong()
        return (-1..1).sumOf { turn ->
            val centre = transit + turn * siderealDay
            val overlap = min(darkTo, centre + halfWidth) - max(darkFrom, centre - halfWidth)
            max(0L, overlap)
        }
    }

    /**
     * The hour angle at which an object of declination [decDeg] reaches [altitudeDeg], or null when
     * it never does — either because it stays above that altitude all day or never climbs to it.
     */
    fun hourAngleAtAltitude(altitudeDeg: Double, decDeg: Double, latitudeDeg: Double): Double? {
        val lat = Math.toRadians(latitudeDeg)
        val dec = Math.toRadians(decDeg)
        val denominator = cos(lat) * cos(dec)
        if (abs(denominator) < 1e-9) return null
        val cosH = (sin(Math.toRadians(altitudeDeg)) - sin(lat) * sin(dec)) / denominator
        if (cosH !in -1.0..1.0) return null
        return Math.toDegrees(acos(cosH))
    }

    /**
     * When the given right ascension next sits at the given hour angle, nearest to [nearMillis].
     *
     * Sidereal time advances 360.98565° per solar day, which is the whole conversion; the result is
     * wrapped to within half a sidereal day of the reference so it lands on the intended night
     * rather than the one before or after.
     */
    private fun timeOfHourAngle(
        raDeg: Double,
        hourAngleDeg: Double,
        observer: ObserverLocation,
        nearMillis: Long,
    ): Long {
        val targetLst = Angles.normalizeDeg(raDeg + hourAngleDeg)
        val currentLst = AstroTime.lstDeg(nearMillis, observer.longitudeDeg)
        val delta = Angles.wrapDeg180(targetLst - currentLst)
        return nearMillis + (delta * AstroTime.SECONDS_PER_DEGREE_OF_HOUR_ANGLE * 1000.0).toLong()
    }

    private fun altitudeAt(
        position: com.starwindow.app.core.astro.Equatorial,
        observer: ObserverLocation,
        millis: Long,
    ): Double = CoordinateTransforms.apparentHorizontalAtLst(
        position,
        observer.latitudeDeg,
        AstroTime.lstDeg(millis, observer.longitudeDeg),
    ).altitudeDeg

    /**
     * How good a night is, from 0 to 100.
     *
     * Exposure time carries it, because that is what a night is *for*. Altitude enters twice over:
     * once directly, and once through the hours, since an object that climbs higher stays useful
     * longer. The Moon subtracts rather than disqualifies — a bright Moon ruins a faint nebula and
     * barely troubles a globular cluster, and which of those this is depends on the object, not on
     * the night.
     */
    private fun score(
        usableMillis: Long,
        bestAltitudeDeg: Double,
        maxAltitudeDeg: Double,
        darkness: DarknessQuality,
        moonIlluminationPercent: Double,
        moonAltitudeDeg: Double,
    ): Double {
        if (usableMillis <= 0L || !darkness.isUsable) return 0.0

        // Six hours is a full night's worth of exposure; more is a bonus, not a doubling.
        val hours = (usableMillis / 3_600_000.0).coerceAtMost(6.0)
        val hourScore = 55.0 * (hours / 6.0)

        // Measured against what this object can ever reach here, not against the zenith: a target
        // that culminates at 35° from this latitude should still score well on its best night.
        val ceiling = maxAltitudeDeg.coerceAtLeast(1.0)
        val altitudeScore = 30.0 * (bestAltitudeDeg / ceiling).coerceIn(0.0, 1.0)

        val darknessScore = if (darkness == DarknessQuality.ASTRONOMICAL) 15.0 else 5.0

        val moonPenalty = if (moonAltitudeDeg <= 0.0) {
            0.0
        } else {
            // Scaled by how high it is as well as how full: a full Moon just above the horizon is
            // far less trouble than the same Moon overhead.
            25.0 * (moonIlluminationPercent / 100.0) * (moonAltitudeDeg / 60.0).coerceAtMost(1.0)
        }

        return (hourScore + altitudeScore + darknessScore - moonPenalty).coerceIn(0.0, 100.0)
    }
}
