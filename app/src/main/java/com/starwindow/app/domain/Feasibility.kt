package com.starwindow.app.domain

import com.starwindow.app.core.astro.LunarEphemeris
import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.data.catalog.SkyObject

/**
 * The verdict on one object for tonight, from here.
 *
 * Ordered from best to worst, so a list can be sorted by it directly.
 */
enum class Feasibility(val label: String, val explanation: String) {
    EASY(
        "leicht",
        "Hell und groß genug, dass es unter diesem Himmel ohne Mühe aufs Bild kommt.",
    ),
    OK(
        "geht",
        "Machbar, aber nicht geschenkt – länger belichten und den Himmel im Blick behalten.",
    ),
    HARD(
        "schwierig",
        "Am Rand des Möglichen: entweder lichtschwach für diesen Himmel oder sehr klein.",
    ),
    LOW(
        "zu tief",
        "Steht so flach über dem Horizont, dass Dunst und Luftunruhe das Bild bestimmen.",
    ),
    TOO_FAINT(
        "zu schwach",
        "Unter diesem Himmel und mit dieser Aufhellung verschwindet es im Himmelshintergrund.",
    ),
    BELOW(
        "unter Horizont",
        "Steht gerade nicht am Himmel.",
    );

    val isPossible: Boolean get() = this == EASY || this == OK || this == HARD

    /**
     * Whether an object is worth pointing a camera at tonight.
     *
     * The problem this solves is one the bigger catalogue created. A search for `NGC 7` now returns
     * two hundred rows, each carrying a name, a type, a magnitude and an altitude — four facts that
     * have to be combined in the reader's head, every row, to answer the only question that
     * matters. The verdict does that combining once and puts the answer where it can be skimmed
     * down the edge of the list, which is what makes a long list usable instead of merely long.
     *
     * Three things decide it, in this order:
     *
     * 1. **Altitude.** Below the horizon nothing else matters, and below about fifteen degrees the
     *    atmosphere decides the picture rather than the object.
     * 2. **Surface brightness against the sky.** An object fainter per square arcminute than the
     *    sky it sits on cannot be photographed out of it, however long the exposure — this is the
     *    one hard physical limit in the list, and it is why a bright suburban sky rules out more
     *    targets than a small telescope does.
     * 3. **Size.** What is left is a question of resolution, and that is where an anonymous
     *    half-arcminute galaxy quietly drops out.
     */
    companion object {

        /** Below this the atmosphere is the subject, not the object. */
        const val LOW_ALTITUDE_DEG = 15.0

        /**
         * Contrast thresholds, in magnitudes of the object above the sky background.
         *
         * Note the signs: two of the three are **negative**, meaning the object is fainter per
         * square arcminute than the sky it sits on — and is photographed anyway. That is the whole
         * difference between judging a night for an eye and for a camera. An eye integrates for a
         * fraction of a second and sees only what is brighter than its background, which is why
         * visual guides stop at zero contrast. A sensor integrates for minutes and stacks frames,
         * and pulls signal out from well underneath the background; the limit is not the background
         * itself but its shot noise. Every large faint nebula anyone photographs — the Integrated
         * Flux Nebula, most of Sharpless — lives in this negative range.
         *
         * Roughly two and a half magnitudes under the sky is where a phone-sized sensor stops
         * getting anything back for the extra exposure. Past that the honest answer is no.
         */
        private const val EASY_CONTRAST_MAG = 0.5
        private const val WORKABLE_CONTRAST_MAG = -1.0
        private const val REACHABLE_CONTRAST_MAG = -2.5

        fun assess(
            obj: SkyObject,
            altitudeDeg: Double?,
            conditions: SkyConditions,
        ): Feasibility {
            if (altitudeDeg == null) return BELOW
            if (altitudeDeg <= 0.0) return BELOW
            if (altitudeDeg < LOW_ALTITUDE_DEG) return LOW

            // Stars are point sources: brightness alone decides, and almost all of them are easy.
            if (obj.type.isStar) {
                val magnitude = obj.magnitude ?: 6.0
                return when {
                    magnitude <= 4.0 -> EASY
                    magnitude <= 6.0 -> OK
                    else -> HARD
                }
            }

            val contrast = contrastAgainstSky(obj, conditions)
            if (contrast != null && contrast < REACHABLE_CONTRAST_MAG) return TOO_FAINT

            val size = obj.sizeArcmin ?: 0.0
            val tiny = size in 0.0..PhotographicInterest.MIN_USEFUL_SIZE_ARCMIN

            return when {
                // Too small to resolve is its own kind of hard, and no amount of sky darkness
                // fixes it — so it is checked before the contrast is graded.
                tiny -> HARD
                contrast == null -> {
                    // No surface brightness in the catalogue: fall back on the integrated
                    // magnitude, which for a compact object is a fair stand-in and for a large
                    // one is optimistic.
                    when {
                        (obj.magnitude ?: 99.0) <= 9.0 -> EASY
                        (obj.magnitude ?: 99.0) <= 12.0 -> OK
                        else -> HARD
                    }
                }
                // Standing low still costs a grade even when the object itself is bright enough.
                contrast >= EASY_CONTRAST_MAG && altitudeDeg >= 30.0 -> EASY
                contrast >= WORKABLE_CONTRAST_MAG -> OK
                else -> HARD
            }
        }

        /**
         * How much darker the sky is than the object, in magnitudes per square arcminute.
         *
         * Positive means the object stands above the background. Null when the catalogue gives no
         * surface brightness, which is the honest answer rather than a guessed one.
         */
        fun contrastAgainstSky(obj: SkyObject, conditions: SkyConditions): Double? {
            val surface = obj.surfaceBrightness ?: return null
            // Both are magnitudes per square arcminute, where larger means fainter. The object
            // stands out when it is the brighter — the smaller — of the two.
            return conditions.skyBrightness - surface
        }
    }
}

/**
 * What the sky is doing tonight, as far as the app can tell.
 *
 * [bortleLevel] is usually an *estimate* from settlement size and distance, not a measurement —
 * [isEstimated] carries that distinction so the interface can say so. Presenting a guessed sky as a
 * measured one is exactly the failure mode this project avoids elsewhere, and a verdict is more
 * tempting to believe than a number.
 */
data class SkyConditions(
    /** Bortle class 1 (pristine) to 9 (inner city). */
    val bortleLevel: Int = 5,
    /** Illuminated fraction of the Moon in percent, 0 to 100. */
    val moonIlluminationPercent: Double = 0.0,
    /** How high the Moon stands; below the horizon it does not brighten the sky. */
    val moonAltitudeDeg: Double = -90.0,
    /** True when [bortleLevel] comes from the population model rather than from the user. */
    val isEstimated: Boolean = true,
) {
    /**
     * The faintest surface brightness that still stands out, in mag per square arcminute.
     *
     * A dark site reaches about 22 mag/□′, an inner city barely 18 — roughly half a magnitude per
     * Bortle class. A moon above the horizon costs up to a further one and a half classes' worth,
     * scaled by how much of it is lit.
     */
    val skyBrightness: Double
        get() {
            val base = 22.3 - (bortleLevel - 1) * 0.52
            if (moonAltitudeDeg <= 0.0) return base
            val moon = (moonIlluminationPercent / 100.0) * 1.6 *
                (moonAltitudeDeg / 45.0).coerceAtMost(1.0)
            return base - moon
        }

    /** True when the Moon is up and lit enough to matter. */
    val moonInterferes: Boolean
        get() = moonAltitudeDeg > 0.0 && moonIlluminationPercent >= 25.0

    companion object {
        /** Used until a location and a forecast are known: a middling rural sky, no Moon. */
        val UNKNOWN = SkyConditions()

        /**
         * The conditions at a given moment, with the Moon worked out from the ephemeris.
         *
         * @param bortleLevel from the weather screen's estimate or the user's own setting; null
         *   falls back to the middling default rather than inventing a dark sky.
         * @param userSetBortle true when the level came from the user rather than the population
         *   model, so the interface can stop calling it an estimate.
         */
        fun at(
            observer: ObserverLocation?,
            bortleLevel: Int?,
            nowMillis: Long = System.currentTimeMillis(),
            userSetBortle: Boolean = false,
        ): SkyConditions {
            val level = bortleLevel ?: UNKNOWN.bortleLevel
            if (observer == null) {
                return SkyConditions(bortleLevel = level, isEstimated = !userSetBortle)
            }
            val moon = LunarEphemeris.at(nowMillis)
            return SkyConditions(
                bortleLevel = level,
                moonIlluminationPercent = LunarEphemeris.illuminationAt(nowMillis).percent,
                moonAltitudeDeg = LunarEphemeris.topocentricAltitudeDeg(moon, observer, nowMillis),
                isEstimated = !userSetBortle,
            )
        }
    }
}

