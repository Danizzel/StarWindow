package com.starwindow.app.domain

import com.starwindow.app.core.astro.AstroTime
import com.starwindow.app.core.astro.CoordinateTransforms
import com.starwindow.app.core.astro.Precession
import com.starwindow.app.core.astro.Horizontal
import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.data.catalog.SkyObject

/** How the result list is ordered. */
enum class ObjectSort(val label: String) {
    /** Best match first — only meaningful while something is typed. */
    RELEVANCE("Treffer"),
    BRIGHTNESS("Hell"),
    ALTITUDE("Höhe jetzt"),
    SIZE("Größe"),
    NAME("Name"),
}

/** One catalogue entry as the result list shows it. */
data class ObjectHit(
    val obj: SkyObject,
    /** Where it stands right now, or null without a known observer position. */
    val position: Horizontal?,
    /** Match quality; 0 when the list is not the answer to a query. */
    val score: Int = 0,
) {
    val altitudeDeg: Double? get() = position?.altitudeDeg
    val isUp: Boolean get() = (position?.altitudeDeg ?: -90.0) > 0.0
}

/**
 * Finding an object by what the user calls it.
 *
 * Catalogue designations are written every which way — `M45`, `NGC 292`, `IC 2391`, `Mel022` — so
 * every identifier is folded to a comparable form (lower case, no spaces or dashes) before it is
 * compared. Typing `m45` or `M 45` therefore both land on the Pleiades.
 *
 * Matches are scored rather than merely filtered: `M31` has to put the Andromeda galaxy first even
 * though other entries contain those characters somewhere. An exact designation beats a name, a
 * name beats a prefix, a prefix beats a match in the middle of a word, and equal scores fall back
 * to brightness — the brighter object is nearly always the one that was meant.
 *
 * Pure Kotlin on purpose: this is the part worth testing, and it tests without an emulator.
 */
object ObjectSearch {

    /** No match at all. */
    const val NO_MATCH = -1

    private const val EXACT_ID = 1000
    private const val EXACT_NAME = 900
    private const val ID_PREFIX = 700
    private const val NAME_PREFIX = 620
    private const val WORD_PREFIX = 520
    private const val ID_CONTAINS = 360
    private const val NAME_CONTAINS = 300

    /**
     * Ranks the catalogue against a query.
     *
     * @param nowMillis the moment the "altitude now" column refers to.
     */
    fun search(
        query: String,
        objects: List<SkyObject>,
        observer: ObserverLocation?,
        nowMillis: Long = System.currentTimeMillis(),
        sort: ObjectSort = ObjectSort.RELEVANCE,
        limit: Int = 200,
    ): List<ObjectHit> {
        val needle = fold(query)
        val lst = observer?.let { AstroTime.lstDeg(nowMillis, it.longitudeDeg) }
        val precession = Precession.forEpoch(nowMillis)

        val hits = ArrayList<ObjectHit>()
        for (obj in objects) {
            val score = if (needle.isEmpty()) 0 else score(needle, obj)
            if (score == NO_MATCH) continue
            hits += ObjectHit(obj, positionOf(obj, observer, lst, precession), score)
        }
        return sorted(hits, sort, needle.isNotEmpty()).take(limit)
    }

    /**
     * What is worth pointing at right now: up, reasonably high, and big or bright enough to be
     * worth the trouble. This is what the search screen offers before anything is typed — an empty
     * screen would waste the one moment the user has no idea what to look for.
     */
    fun visibleNow(
        objects: List<SkyObject>,
        observer: ObserverLocation?,
        nowMillis: Long = System.currentTimeMillis(),
        minAltitudeDeg: Double = 15.0,
        limit: Int = 40,
    ): List<ObjectHit> {
        if (observer == null) return emptyList()
        val lst = AstroTime.lstDeg(nowMillis, observer.longitudeDeg)
        val precession = Precession.forEpoch(nowMillis)
        return objects
            .map { ObjectHit(it, positionOf(it, observer, lst, precession), 0) }
            .filter { (it.altitudeDeg ?: -90.0) >= minAltitudeDeg }
            .sortedByDescending { showpieceScore(it) }
            .take(limit)
    }

    /**
     * How well [obj] answers to an already folded [needle], or [NO_MATCH].
     *
     * The best of the individual matches wins; summing them would let three weak matches outrank
     * one exact designation.
     */
    fun score(needle: String, obj: SkyObject): Int {
        if (needle.isEmpty()) return 0
        var best = NO_MATCH

        for (identifier in obj.allIdentifiers) {
            val folded = fold(identifier)
            if (folded.isEmpty()) continue
            val score = when {
                folded == needle -> EXACT_ID
                folded.startsWith(needle) -> ID_PREFIX + prefixBonus(needle, folded)
                folded.contains(needle) -> ID_CONTAINS
                else -> NO_MATCH
            }
            if (score > best) best = score
        }

        for (name in listOf(obj.name) + obj.alternativeNames) {
            if (name.isBlank()) continue
            val folded = fold(name)
            val words = foldWords(name)
            val score = when {
                folded == needle -> EXACT_NAME
                folded.startsWith(needle) -> NAME_PREFIX + prefixBonus(needle, folded)
                words.any { it.startsWith(needle) } -> WORD_PREFIX
                folded.contains(needle) -> NAME_CONTAINS
                else -> NO_MATCH
            }
            if (score > best) best = score
        }

        // Someone typing "Ori" is usually after something in Orion, so the constellation counts —
        // but only faintly, well below any real name match.
        obj.constellation?.let {
            if (fold(it) == needle && best < NAME_CONTAINS) best = NAME_CONTAINS
        }

        return best
    }

    /** Lower case, umlauts folded, everything that is not a letter or digit dropped. */
    fun fold(text: String): String {
        val builder = StringBuilder(text.length)
        for (character in text.lowercase()) {
            when (character) {
                'ä' -> builder.append("ae")
                'ö' -> builder.append("oe")
                'ü' -> builder.append("ue")
                'ß' -> builder.append("ss")
                else -> if (character.isLetterOrDigit()) builder.append(character)
            }
        }
        return builder.toString()
    }

    /** The same folding, but word by word, so "nebel" still finds "Nordamerika-Nebel". */
    private fun foldWords(text: String): List<String> =
        text.split(' ', '-', '/', ',', '(', ')').mapNotNull { fold(it).ifEmpty { null } }

    /** A prefix covering most of the candidate is a better match than one covering a tenth. */
    private fun prefixBonus(needle: String, candidate: String): Int =
        (100.0 * needle.length / candidate.length.coerceAtLeast(1)).toInt()

    private fun positionOf(
        obj: SkyObject,
        observer: ObserverLocation?,
        lst: Double?,
        precession: Precession,
    ): Horizontal? {
        if (observer == null || lst == null) return null
        return CoordinateTransforms.apparentHorizontalAtLst(
            obj.positionAt(precession),
            observer.latitudeDeg,
            lst,
        )
    }

    private fun sorted(hits: List<ObjectHit>, sort: ObjectSort, scored: Boolean): List<ObjectHit> =
        when (sort) {
            ObjectSort.RELEVANCE ->
                if (scored) {
                    hits.sortedWith(
                        compareByDescending<ObjectHit> { it.score }
                            .thenBy { it.obj.magnitude ?: 99.0 }
                    )
                } else {
                    // Nothing typed: the brightest entries are the most useful default.
                    hits.sortedBy { it.obj.magnitude ?: 99.0 }
                }

            ObjectSort.BRIGHTNESS -> hits.sortedBy { it.obj.magnitude ?: 99.0 }

            // Below the horizon is below the horizon; entries without a position sort last.
            ObjectSort.ALTITUDE -> hits.sortedByDescending { it.altitudeDeg ?: -999.0 }

            ObjectSort.SIZE -> hits.sortedByDescending { it.obj.sizeArcmin ?: -1.0 }

            ObjectSort.NAME -> hits.sortedBy { fold(it.obj.name.ifBlank { it.obj.id }) }
        }

    /**
     * How much of a showpiece an entry is: high in the sky, bright, and large enough to see or
     * photograph. Deliberately rough — it only has to order a suggestion list sensibly.
     */
    private fun showpieceScore(hit: ObjectHit): Double {
        val altitude = hit.altitudeDeg ?: return -1.0
        val magnitude = hit.obj.magnitude ?: 9.0
        val size = hit.obj.sizeArcmin ?: 0.0
        val named = if (hit.obj.name.isNotBlank()) 12.0 else 0.0
        return altitude * 0.35 + (9.0 - magnitude) * 6.0 + kotlin.math.min(size, 120.0) * 0.12 + named
    }
}
