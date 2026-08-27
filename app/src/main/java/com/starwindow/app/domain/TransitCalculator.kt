package com.starwindow.app.domain

import com.starwindow.app.core.astro.AstroTime
import com.starwindow.app.core.astro.CoordinateTransforms
import com.starwindow.app.core.astro.Equatorial
import com.starwindow.app.core.astro.Precession
import com.starwindow.app.core.astro.Horizontal
import com.starwindow.app.core.geometry.SkyWindow
import com.starwindow.app.data.catalog.SkyObject
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

        // Two rejections before any object is scanned over time, because the scan is what costs:
        // each survivor is sampled several hundred times, each non-survivor not at all.
        val windowDec = declinationOfDirection(window.shape.center(), latitude)
        val reach = window.shape.angularRadiusDeg() + DECLINATION_MARGIN_DEG
        val candidates = objects.filter {
            it.canReachDeclination(windowDec, reach) && it.canReach(bounds, latitude)
        }

        // Precession is computed once for the whole search and each object is brought to date once,
        // rather than inside the scan: it shifts by 0.0001 arcseconds over a night, so recomputing
        // it per sample would cost thousands of trigonometric calls for no change in the answer.
        val precession = Precession.forEpoch(fromMillis)

        // Every object is scanned independently of every other, so the work splits across cores
        // without any coordination. `inside` is a pure closure over immutable geometry and is
        // shared deliberately: building one tangent plane per chunk would undo the point of
        // `membershipTest` existing.
        val transits = scanInParallel(candidates) { obj ->
            val position = obj.positionAt(precession)
            val intervals = intervalsFor(position, inside, latitude, longitude, fromMillis, toMillis)
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
        val precession = Precession.forEpoch(atMillis)
        return objects.mapNotNull { obj ->
            val position = CoordinateTransforms.apparentHorizontalAtLst(
                obj.positionAt(precession),
                window.observer.latitudeDeg,
                lst,
            )
            if (inside(position)) obj to position else null
        }
    }

    private fun intervalsFor(
        equatorialOfDate: Equatorial,
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
        isInside = { millis -> inside(positionAt(equatorialOfDate, latitudeDeg, longitudeDeg, millis)) },
        score = { millis -> positionAt(equatorialOfDate, latitudeDeg, longitudeDeg, millis).altitudeDeg },
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
        equatorialOfDate: Equatorial,
        latitudeDeg: Double,
        longitudeDeg: Double,
        millis: Long,
    ): Horizontal = CoordinateTransforms.apparentHorizontalAtLst(
        equatorialOfDate,
        latitudeDeg,
        AstroTime.lstDeg(millis, longitudeDeg),
    )

    /**
     * Runs [scan] over every candidate, spread across the available cores.
     *
     * Below [PARALLEL_THRESHOLD] candidates it stays on one thread: splitting a list of eighty
     * costs more in coroutine setup than the scan itself takes, and the whole point of the
     * declination filter above is that most searches now land in exactly that range.
     */
    private suspend fun <T : Any> scanInParallel(
        candidates: List<SkyObject>,
        scan: (SkyObject) -> T?,
    ): List<T> {
        if (candidates.size < PARALLEL_THRESHOLD) return candidates.mapNotNull(scan)

        val workers = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val chunkSize = (candidates.size + workers - 1) / workers
        return coroutineScope {
            candidates.chunked(chunkSize)
                .map { chunk -> async { chunk.mapNotNull(scan) } }
                .awaitAll()
                .flatten()
        }
    }

    private companion object {
        /** Below this many candidates the split costs more than it saves. */
        const val PARALLEL_THRESHOLD = 200
    }
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

/**
 * The declination a horizon-fixed direction sits at.
 *
 * This is the observation that makes the whole search cheap, and it is worth stating plainly: a
 * window is nailed to the horizon, so as the sky turns underneath it, its **right ascension drifts
 * but its declination never changes**. A gap over the garage looks out at one fixed band of
 * declination for as long as it exists.
 *
 * From the standard transformation, with the hour angle eliminated:
 *
 *     sin δ = sin φ · sin h + cos φ · cos h · cos A
 */
internal fun declinationOfDirection(direction: Horizontal, latitudeDeg: Double): Double {
    val lat = Math.toRadians(latitudeDeg)
    val altitude = Math.toRadians(direction.altitudeDeg)
    val azimuth = Math.toRadians(direction.azimuthDeg)
    val sinDec = sin(lat) * sin(altitude) + cos(lat) * cos(altitude) * cos(azimuth)
    return Math.toDegrees(asin(sinDec.coerceIn(-1.0, 1.0)))
}

/**
 * Whether the object's declination band can reach the window's at all.
 *
 * Far sharper than the altitude test on its own, and it is the difference between a search that
 * takes a second and one that takes a moment. The altitude test only asks whether the object ever
 * climbs to the right *height*; it says nothing about direction, so from Berlin a circumpolar
 * object at +80° passes it for a window facing south at 45° — it does reach that altitude, just
 * never anywhere near that azimuth. Comparing declinations rejects it immediately, because the two
 * declinations are both constants.
 *
 * The margin covers what the comparison glosses over: refraction lifts an object by up to half a
 * degree near the horizon, catalogue positions are J2000 while the window is of date (0.4° of
 * precession), and a polygon's angular radius is measured to its furthest corner.
 */
private fun SkyObject.canReachDeclination(windowDecDeg: Double, reachDeg: Double): Boolean =
    abs(decDeg - windowDecDeg) <= reachDeg

/** Refraction, precession and a little room to spare. */
private const val DECLINATION_MARGIN_DEG = 1.5
