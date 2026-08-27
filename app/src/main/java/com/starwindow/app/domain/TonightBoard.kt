package com.starwindow.app.domain

import com.starwindow.app.core.astro.AstroTime
import com.starwindow.app.core.astro.CoordinateTransforms
import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.core.astro.Precession
import com.starwindow.app.data.catalog.SkyObject

/** The heading an entry appears under on the suggestion board. */
enum class TonightSection(val title: String) {
    /** Inside a saved window right now — the app's own question, so it comes first. */
    IN_WINDOW("Jetzt im Fenster"),
    HIGH_NOW("Steht jetzt hoch"),
    RISING("Kommt noch hoch"),
}

/** One suggestion, with everything a row needs to render itself. */
data class TonightEntry(
    val obj: SkyObject,
    val altitudeDeg: Double?,
    val feasibility: Feasibility,
    /** For [TonightSection.IN_WINDOW]: how much longer it stays inside, in minutes. */
    val minutesLeftInWindow: Int? = null,
    /** For [TonightSection.RISING]: when it reaches a useful altitude. */
    val risesAtMillis: Long? = null,
)

/** A heading and the handful of entries under it. */
data class TonightGroup(
    val section: TonightSection,
    val entries: List<TonightEntry>,
    /** How many further entries the section holds beyond the ones shown. */
    val moreCount: Int = 0,
)

/**
 * What the search screen shows before anything is typed.
 *
 * The screen used to open on a flat ranked list, which worked while nearly every catalogue entry
 * was a plausible target. With twenty-two thousand entries that stopped being true, and the failure
 * was quiet rather than loud: the old ranking rewarded brightness, the nine thousand new stars are
 * brighter than almost every deep sky object, and so the list of "what should I look at tonight"
 * silently filled up with stars nobody was going to photograph.
 *
 * The fix is not a better sort of the same list. It is to stop showing a catalogue at all and show
 * an *evening* instead — three short sections that answer the three forms the question actually
 * takes: what can I shoot right now through the window I saved, what is well placed at the moment,
 * and what is worth waiting up for. Each section is short enough to read in full, and the catalogue
 * stays behind the search field for anyone who wants a specific thing.
 *
 * The ordering inside a section comes from [PhotographicInterest], so the board, the photo-target
 * filter and the viewfinder overlay all agree on what counts as worth seeing.
 */
object TonightBoard {

    /** Entries shown per section before "und N weitere". Long enough to choose, short enough to read. */
    const val ENTRIES_PER_SECTION = 6

    /** Below this an object is not "up" in any useful sense. */
    const val MIN_ALTITUDE_DEG = 20.0

    /** How far ahead "kommt noch hoch" looks. Past this it is tomorrow's problem. */
    private const val RISING_HORIZON_HOURS = 6

    /** Sampling step for the rise search — a quarter hour is finer than the answer needs to be. */
    private const val RISING_STEP_MILLIS = 15 * 60 * 1000L

    /**
     * Builds the board.
     *
     * @param inWindowNow objects currently inside a saved window, with minutes remaining — computed
     *   by the caller, which is the only place that knows about windows.
     */
    fun build(
        objects: List<SkyObject>,
        observer: ObserverLocation?,
        conditions: SkyConditions,
        nowMillis: Long = System.currentTimeMillis(),
        inWindowNow: List<Pair<SkyObject, Int?>> = emptyList(),
    ): List<TonightGroup> {
        if (observer == null) return emptyList()

        val lst = AstroTime.lstDeg(nowMillis, observer.longitudeDeg)
        val precession = Precession.forEpoch(nowMillis)
        val groups = mutableListOf<TonightGroup>()

        if (inWindowNow.isNotEmpty()) {
            val entries = inWindowNow
                .map { (obj, minutes) ->
                    val altitude = altitudeOf(obj, observer, lst, precession)
                    TonightEntry(
                        obj = obj,
                        altitudeDeg = altitude,
                        feasibility = Feasibility.assess(obj, altitude, conditions),
                        minutesLeftInWindow = minutes,
                    )
                }
                // Time is the scarce thing here: what is about to leave comes first.
                .sortedBy { it.minutesLeftInWindow ?: Int.MAX_VALUE }
            groups += group(TonightSection.IN_WINDOW, entries)
        }

        // Only photographic targets are considered from here on. A star is a fine thing to look at
        // and is findable by name; it is not an answer to "what should I shoot tonight".
        val targets = objects.filter { PhotographicInterest.isPhotoTarget(it) }
        val alreadyShown = inWindowNow.map { it.first.id }.toSet()

        val up = mutableListOf<TonightEntry>()
        val waiting = mutableListOf<TonightEntry>()

        for (obj in targets) {
            if (obj.id in alreadyShown) continue
            val altitude = altitudeOf(obj, observer, lst, precession)
            if (altitude >= MIN_ALTITUDE_DEG) {
                val verdict = Feasibility.assess(obj, altitude, conditions)
                // A verdict of "too faint" or "too low" is a reason not to suggest it at all —
                // the board is a recommendation, and recommending the impossible is noise.
                if (verdict.isPossible) {
                    up += TonightEntry(obj, altitude, verdict)
                }
            } else if (altitude > -30.0) {
                // Cheap pre-filter: only things near the horizon can climb within a few hours.
                risingTime(obj, observer, nowMillis)?.let { millis ->
                    waiting += TonightEntry(
                        obj = obj,
                        altitudeDeg = altitude,
                        feasibility = Feasibility.assess(obj, MIN_ALTITUDE_DEG, conditions),
                        risesAtMillis = millis,
                    )
                }
            }
        }

        groups += group(
            TonightSection.HIGH_NOW,
            up.sortedByDescending { appeal(it) },
        )
        groups += group(
            TonightSection.RISING,
            waiting.sortedWith(
                compareBy<TonightEntry> { it.risesAtMillis }
                    .thenByDescending { PhotographicInterest.score(it.obj) }
            ),
        )
        return groups.filter { it.entries.isNotEmpty() }
    }

    /**
     * How much a currently visible object deserves the top of the section.
     *
     * Photographic worth carries it, with altitude as a modest correction — an object at sixty
     * degrees is genuinely easier than the same one at twenty-five, but not so much easier that it
     * should outrank a better target.
     */
    private fun appeal(entry: TonightEntry): Double {
        val altitude = entry.altitudeDeg ?: 0.0
        val bonus = when (entry.feasibility) {
            Feasibility.EASY -> 10.0
            Feasibility.OK -> 4.0
            else -> 0.0
        }
        return PhotographicInterest.score(entry.obj) + altitude * 0.15 + bonus
    }

    private fun group(section: TonightSection, entries: List<TonightEntry>) = TonightGroup(
        section = section,
        entries = entries.take(ENTRIES_PER_SECTION),
        moreCount = (entries.size - ENTRIES_PER_SECTION).coerceAtLeast(0),
    )

    private fun altitudeOf(
        obj: SkyObject,
        observer: ObserverLocation,
        lst: Double,
        precession: Precession,
    ): Double = CoordinateTransforms.apparentHorizontalAtLst(
        obj.positionAt(precession),
        observer.latitudeDeg,
        lst,
    ).altitudeDeg

    /**
     * When the object next reaches [MIN_ALTITUDE_DEG], or null if not within the horizon.
     *
     * Stepped rather than solved: the closed form for a rise time is short, but it answers "when
     * does altitude cross zero", and the question here is a threshold well above that, on a sky
     * that also refracts. Sampling every quarter hour costs a few dozen trigonometric evaluations
     * and cannot be wrong about the shape of the curve.
     */
    private fun risingTime(
        obj: SkyObject,
        observer: ObserverLocation,
        nowMillis: Long,
    ): Long? {
        val maxAltitude = 90.0 - kotlin.math.abs(observer.latitudeDeg - obj.decDeg)
        if (maxAltitude < MIN_ALTITUDE_DEG) return null

        val precession = Precession.forEpoch(nowMillis)
        val position = obj.positionAt(precession)
        val end = nowMillis + RISING_HORIZON_HOURS * 3_600_000L
        var time = nowMillis + RISING_STEP_MILLIS
        while (time <= end) {
            val altitude = CoordinateTransforms.apparentHorizontalAtLst(
                position,
                observer.latitudeDeg,
                AstroTime.lstDeg(time, observer.longitudeDeg),
            ).altitudeDeg
            if (altitude >= MIN_ALTITUDE_DEG) return time
            time += RISING_STEP_MILLIS
        }
        return null
    }
}
