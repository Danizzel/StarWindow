package com.starwindow.app.domain

import com.starwindow.app.core.astro.AstroTime
import com.starwindow.app.core.astro.CoordinateTransforms
import com.starwindow.app.core.astro.Horizontal
import com.starwindow.app.core.geometry.SkyWindow
import com.starwindow.app.data.catalog.Constellation
import com.starwindow.app.data.catalog.FigureStar
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One stretch during which part of a constellation figure was inside the window. */
data class ConstellationInterval(
    val enterMillis: Long,
    val exitMillis: Long,
    val clippedAtStart: Boolean,
    val clippedAtEnd: Boolean,
    /** Most figure stars inside at once, and when — how much of the figure actually showed. */
    val peakStarsInside: Int,
    val peakMillis: Long,
) {
    val durationMillis: Long get() = exitMillis - enterMillis
}

data class ConstellationTransit(
    val constellation: Constellation,
    val intervals: List<ConstellationInterval>,
) {
    val totalDurationMillis: Long get() = intervals.sumOf { it.durationMillis }
    val firstEntryMillis: Long get() = intervals.minOf { it.enterMillis }
    val peakStarsInside: Int get() = intervals.maxOf { it.peakStarsInside }
    val figureStarCount: Int get() = constellation.stars.size

    /** True when most of the figure fits through at once, not just a corner of it. */
    val showsMostOfTheFigure: Boolean
        get() = figureStarCount > 0 && peakStarsInside * 2 >= figureStarCount
}

/**
 * Works out which constellations drift through a window.
 *
 * A window a few degrees across almost never holds a whole constellation, so "is the figure inside"
 * would answer no for everything worth knowing. The useful question is which *part* of the figure
 * passes, and that is what this reports: a constellation counts as passing while at least one of
 * its figure stars is inside, and each stretch records how much of the figure was visible at once.
 */
class ConstellationTransitCalculator(
    private val stepSeconds: Long = 60L,
    private val refineIterations: Int = 12,
) {

    suspend fun search(
        window: SkyWindow,
        constellations: List<Constellation>,
        fromMillis: Long,
        toMillis: Long,
    ): List<ConstellationTransit> = withContext(Dispatchers.Default) {
        require(toMillis > fromMillis) { "Der Suchzeitraum muss positiv sein" }

        val inside = window.shape.membershipTest()
        val bounds = window.altitudeBounds()
        val latitude = window.observer.latitudeDeg
        val longitude = window.observer.longitudeDeg

        constellations.mapNotNull { constellation ->
            // Only the figure stars that can ever reach the window's altitude band matter; for a
            // window low in the north that discards most of the sky before any sampling happens.
            val reachable = constellation.stars.filter { it.canReach(bounds, latitude) }
            if (reachable.isEmpty()) return@mapNotNull null

            fun countInside(millis: Long): Int {
                val lst = AstroTime.lstDeg(millis, longitude)
                return reachable.count { star ->
                    inside(CoordinateTransforms.apparentHorizontalAtLst(star.equatorial, latitude, lst))
                }
            }

            val intervals = IntervalScanner.scan(
                fromMillis = fromMillis,
                toMillis = toMillis,
                stepMillis = stepSeconds * 1000L,
                refineIterations = refineIterations,
                isInside = { countInside(it) > 0 },
                score = { countInside(it).toDouble() },
            ).map { interval ->
                ConstellationInterval(
                    enterMillis = interval.enterMillis,
                    exitMillis = interval.exitMillis,
                    clippedAtStart = interval.clippedAtStart,
                    clippedAtEnd = interval.clippedAtEnd,
                    peakStarsInside = interval.bestScore.toInt().coerceAtLeast(1),
                    peakMillis = interval.bestMillis,
                )
            }

            if (intervals.isEmpty()) null else ConstellationTransit(constellation, intervals)
        }.sortedBy { it.firstEntryMillis }
    }

    /** Where a constellation's figure stands right now, for drawing it into the window preview. */
    fun figureAt(
        constellation: Constellation,
        window: SkyWindow,
        atMillis: Long,
    ): List<Pair<Horizontal, Horizontal>> {
        val lst = AstroTime.lstDeg(atMillis, window.observer.longitudeDeg)
        val latitude = window.observer.latitudeDeg
        return constellation.segments.map { (a, b) ->
            CoordinateTransforms.apparentHorizontalAtLst(a.equatorial, latitude, lst) to
                CoordinateTransforms.apparentHorizontalAtLst(b.equatorial, latitude, lst)
        }
    }
}

/** Altitude band the window covers, padded so grazing passes are not missed. */
internal fun SkyWindow.altitudeBounds(): ClosedFloatingPointRange<Double> {
    val outline = shape.outline()
    val min = outline.minOf { it.altitudeDeg }
    val max = outline.maxOf { it.altitudeDeg }
    return (min - 0.5)..(max + 0.5)
}

/** Same cheap declination rejection as for catalogue objects. */
internal fun FigureStar.canReach(
    altitudeBounds: ClosedFloatingPointRange<Double>,
    latitudeDeg: Double,
): Boolean {
    val maxAltitude = 90.0 - abs(latitudeDeg - decDeg)
    val minAltitude = abs(latitudeDeg + decDeg) - 90.0
    return maxAltitude >= altitudeBounds.start && minAltitude <= altitudeBounds.endInclusive
}
