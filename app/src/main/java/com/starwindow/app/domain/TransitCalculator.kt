package com.starwindow.app.domain

import com.starwindow.app.core.astro.AstroTime
import com.starwindow.app.core.astro.CoordinateTransforms
import com.starwindow.app.core.astro.Horizontal
import com.starwindow.app.core.geometry.SkyWindow
import com.starwindow.app.data.catalog.SkyObject
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One continuous stretch during which an object sits inside the window. */
data class TransitInterval(
    val enterMillis: Long,
    val exitMillis: Long,
    /** True when the object was already inside when the search started. */
    val clippedAtStart: Boolean,
    /** True when the object was still inside when the search ended. */
    val clippedAtEnd: Boolean,
    /** Highest altitude reached while inside, and when. */
    val bestAltitudeDeg: Double,
    val bestMillis: Long,
) {
    val durationMillis: Long get() = exitMillis - enterMillis
}

/** Everything the search found for one catalogue object. */
data class ObjectTransit(
    val obj: SkyObject,
    val intervals: List<TransitInterval>,
) {
    val totalDurationMillis: Long get() = intervals.sumOf { it.durationMillis }
    val firstEntryMillis: Long get() = intervals.minOf { it.enterMillis }
    val isInsideAtStart: Boolean get() = intervals.any { it.clippedAtStart }
}

data class TransitSearchResult(
    val window: SkyWindow,
    val fromMillis: Long,
    val toMillis: Long,
    val transits: List<ObjectTransit>,
    val objectsConsidered: Int,
    val computeMillis: Long,
)

/**
 * Works out which catalogue objects drift through a window, and for how long.
 *
 * The window is fixed to the horizon, the sky rotates past it, so this is a straightforward sweep
 * over time: sample the position of every candidate on a coarse grid, then bisect around each
 * crossing to pin the entry and exit times down to a second. Sampling beats solving analytically
 * here because the window can be an arbitrary polygon — there is no closed form to solve against.
 *
 * Positions include atmospheric refraction, matching what the camera saw when the window was drawn.
 */
class TransitCalculator(
    /** Coarse sampling step. 60 s ≈ 0.25° of sky rotation, small next to any usable window. */
    private val stepSeconds: Long = 60L,
    /** Bisection depth for the entry/exit times; 12 steps take 60 s down to well under a second. */
    private val refineIterations: Int = 12,
) {

    suspend fun search(
        window: SkyWindow,
        objects: List<SkyObject>,
        fromMillis: Long,
        toMillis: Long,
    ): TransitSearchResult = withContext(Dispatchers.Default) {
        val started = System.currentTimeMillis()
        require(toMillis > fromMillis) { "Der Suchzeitraum muss positiv sein" }

        val latitude = window.observer.latitudeDeg
        val longitude = window.observer.longitudeDeg
        val inside = window.shape.membershipTest()
        val bounds = window.altitudeBounds()

        val candidates = objects.filter { it.canReach(bounds, latitude) }

        val transits = candidates.mapNotNull { obj ->
            val intervals = intervalsFor(obj, inside, latitude, longitude, fromMillis, toMillis)
            if (intervals.isEmpty()) null else ObjectTransit(obj, intervals)
        }.sortedBy { it.firstEntryMillis }

        TransitSearchResult(
            window = window,
            fromMillis = fromMillis,
            toMillis = toMillis,
            transits = transits,
            objectsConsidered = candidates.size,
            computeMillis = System.currentTimeMillis() - started,
        )
    }

    /** Which of the given objects sit inside the window right now. */
    fun objectsInsideAt(
        window: SkyWindow,
        objects: List<SkyObject>,
        atMillis: Long,
    ): List<Pair<SkyObject, Horizontal>> {
        val inside = window.shape.membershipTest()
        val lst = AstroTime.lstDeg(atMillis, window.observer.longitudeDeg)
        return objects.mapNotNull { obj ->
            val position = CoordinateTransforms.apparentHorizontalAtLst(
                obj.equatorial,
                window.observer.latitudeDeg,
                lst,
            )
            if (inside(position)) obj to position else null
        }
    }

    private fun intervalsFor(
        obj: SkyObject,
        inside: (Horizontal) -> Boolean,
        latitudeDeg: Double,
        longitudeDeg: Double,
        fromMillis: Long,
        toMillis: Long,
    ): List<TransitInterval> = IntervalScanner.scan(
        fromMillis = fromMillis,
        toMillis = toMillis,
        stepMillis = stepSeconds * 1000L,
        refineIterations = refineIterations,
        isInside = { millis -> inside(positionAt(obj, latitudeDeg, longitudeDeg, millis)) },
        score = { millis -> positionAt(obj, latitudeDeg, longitudeDeg, millis).altitudeDeg },
    ).map { interval ->
        TransitInterval(
            enterMillis = interval.enterMillis,
            exitMillis = interval.exitMillis,
            clippedAtStart = interval.clippedAtStart,
            clippedAtEnd = interval.clippedAtEnd,
            bestAltitudeDeg = interval.bestScore,
            bestMillis = interval.bestMillis,
        )
    }

    private fun positionAt(
        obj: SkyObject,
        latitudeDeg: Double,
        longitudeDeg: Double,
        millis: Long,
    ): Horizontal = CoordinateTransforms.apparentHorizontalAtLst(
        obj.equatorial,
        latitudeDeg,
        AstroTime.lstDeg(millis, longitudeDeg),
    )
}

/**
 * Cheap rejection: an object's altitude oscillates between two fixed values set by its declination
 * and the observer's latitude. If that band misses the window's altitude band, no amount of Earth
 * rotation will ever bring it inside.
 */
private fun SkyObject.canReach(
    altitudeBounds: ClosedFloatingPointRange<Double>,
    latitudeDeg: Double,
): Boolean {
    val maxAltitude = 90.0 - abs(latitudeDeg - decDeg)
    val minAltitude = abs(latitudeDeg + decDeg) - 90.0
    return maxAltitude >= altitudeBounds.start && minAltitude <= altitudeBounds.endInclusive
}
