package com.starwindow.app.domain

import com.starwindow.app.core.astro.AstroTime
import com.starwindow.app.core.astro.CoordinateTransforms
import com.starwindow.app.core.astro.Equatorial
import com.starwindow.app.core.astro.Horizontal
import com.starwindow.app.core.astro.ObserverLocation

/** Where something stood at one moment. */
data class TrackPoint(val millis: Long, val position: Horizontal)

/**
 * The path an object takes across the window — the "Laufbahn".
 *
 * Sampled a little before it enters and a little after it leaves, so the drawing shows where the
 * path comes from and where it goes instead of a line that starts and stops at the window's edge.
 */
data class SkyTrack(
    val label: String,
    val points: List<TrackPoint>,
    /** The stretch actually inside the window, which the chart draws solid. */
    val enterMillis: Long,
    val exitMillis: Long,
) {
    val isEmpty: Boolean get() = points.size < 2

    /** Points on full hours, for the time marks along the path. */
    fun hourMarks(): List<TrackPoint> {
        if (points.isEmpty()) return emptyList()
        val hour = 3_600_000L
        val marks = mutableListOf<TrackPoint>()
        var previous = points.first()
        for (point in points.drop(1)) {
            val crossed = point.millis / hour != previous.millis / hour
            if (crossed) {
                // Snap to the point nearest the full hour rather than interpolating: at a minute
                // per sample the difference is far below the width of the marker.
                val boundary = (point.millis / hour) * hour
                marks += if (boundary - previous.millis < point.millis - boundary) previous else point
            }
            previous = point
        }
        return marks
    }
}

object SkyTrackBuilder {

    /** How far beyond the window the path is drawn, as a fraction of the time spent inside. */
    private const val LEAD_FRACTION = 0.45

    private const val MIN_LEAD_MILLIS = 4 * 60_000L
    private const val MAX_LEAD_MILLIS = 90 * 60_000L

    /**
     * Builds the path for one pass through the window.
     *
     * @param samples how many positions to compute; 160 keeps a curved path smooth without making
     *   the chart expensive to redraw.
     */
    fun forInterval(
        label: String,
        equatorial: Equatorial,
        observer: ObserverLocation,
        enterMillis: Long,
        exitMillis: Long,
        samples: Int = 160,
    ): SkyTrack {
        val duration = (exitMillis - enterMillis).coerceAtLeast(1L)
        val lead = (duration * LEAD_FRACTION).toLong().coerceIn(MIN_LEAD_MILLIS, MAX_LEAD_MILLIS)
        val from = enterMillis - lead
        val to = exitMillis + lead

        val step = ((to - from).toDouble() / (samples - 1).coerceAtLeast(1)).toLong().coerceAtLeast(1L)
        val points = ArrayList<TrackPoint>(samples)
        var millis = from
        while (millis <= to) {
            points += TrackPoint(
                millis = millis,
                position = CoordinateTransforms.apparentHorizontalAtLst(
                    equatorial,
                    observer.latitudeDeg,
                    AstroTime.lstDeg(millis, observer.longitudeDeg),
                ),
            )
            millis += step
        }

        return SkyTrack(label, points, enterMillis, exitMillis)
    }
}
