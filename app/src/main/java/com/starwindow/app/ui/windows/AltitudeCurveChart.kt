package com.starwindow.app.ui.windows

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.starwindow.app.domain.SkyTrack
import com.starwindow.app.ui.theme.StarWindowColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** A stretch of time to mark on the curve — when the object stands inside the window. */
data class WindowPass(val fromMillis: Long, val toMillis: Long)

/**
 * How high an object stands, over time.
 *
 * One measure on one axis: altitude against the clock, which is the question "wann steht es wie
 * hoch" in its most direct form. The time axis carries labelled hours along the bottom, the horizon
 * is drawn as the threshold it is, and the stretches during which the object actually stands inside
 * the window are banded so the curve and the transit list agree at a glance.
 *
 * Colours follow the app's palette; the curve's blue was checked against the window's green for
 * colour-vision separation rather than picked by eye.
 */
@Composable
fun AltitudeCurveChart(
    track: SkyTrack,
    passes: List<WindowPass>,
    modifier: Modifier = Modifier,
    nowMillis: Long = System.currentTimeMillis(),
) {
    val density = LocalDensity.current
    val paints = remember(density) { CurvePaints(density) }
    val zone = remember { ZoneId.systemDefault() }

    Canvas(modifier = modifier.fillMaxWidth().height(CHART_HEIGHT_DP.dp)) {
        if (track.points.size < 2) return@Canvas

        // The container has to include the axis band, or the hour labels get clipped away.
        val axisBandPx = AXIS_BAND_DP.dp.toPx()
        val leftGutterPx = LEFT_GUTTER_DP.dp.toPx()
        val topPaddingPx = 14.dp.toPx()
        val plot = PlotArea(
            left = leftGutterPx,
            top = topPaddingPx,
            right = size.width - 6.dp.toPx(),
            bottom = size.height - axisBandPx,
        )
        if (plot.width <= 0f || plot.height <= 0f) return@Canvas

        val fromMillis = track.points.first().millis
        val toMillis = track.points.last().millis
        val altitudes = track.points.map { it.position.altitudeDeg }
        val scale = CurveScale.fit(fromMillis, toMillis, altitudes, plot)

        drawPasses(passes, scale, plot)
        drawGrid(scale, plot, paints, zone)
        drawHorizon(scale, plot, paints)
        drawCurve(track, passes, scale, plot)
        drawPeak(track, scale, plot, paints, zone)
        drawNow(nowMillis, scale, plot, paints)
    }
}

private const val CHART_HEIGHT_DP = 210
private const val AXIS_BAND_DP = 24
private const val LEFT_GUTTER_DP = 34

/** The rectangle the curve is drawn in; the axis labels live outside it. */
private class PlotArea(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** Maps time to x and altitude to y. */
private class CurveScale(
    private val fromMillis: Long,
    private val toMillis: Long,
    val minAltitude: Double,
    val maxAltitude: Double,
    private val plot: PlotArea,
) {
    fun x(millis: Long): Float {
        val span = (toMillis - fromMillis).coerceAtLeast(1L).toDouble()
        return plot.left + (plot.width * ((millis - fromMillis) / span)).toFloat()
    }

    fun y(altitudeDeg: Double): Float {
        val span = (maxAltitude - minAltitude).coerceAtLeast(1.0)
        return plot.bottom - (plot.height * ((altitudeDeg - minAltitude) / span)).toFloat()
    }

    val startMillis: Long get() = fromMillis
    val endMillis: Long get() = toMillis

    companion object {
        fun fit(
            fromMillis: Long,
            toMillis: Long,
            altitudes: List<Double>,
            plot: PlotArea,
        ): CurveScale {
            // The horizon is always in view: an altitude curve that never shows zero hides the
            // single most important fact about the object — whether it is up at all.
            val lowest = minOf(altitudes.minOrNull() ?: 0.0, 0.0)
            val highest = maxOf(altitudes.maxOrNull() ?: 0.0, 0.0)
            val min = (floor((lowest - 5.0) / 10.0) * 10.0).coerceAtLeast(-90.0)
            val max = (ceil((highest + 5.0) / 10.0) * 10.0).coerceAtMost(90.0)
            return CurveScale(fromMillis, toMillis, min, maxOf(max, min + 20.0), plot)
        }
    }
}

/** The stretches inside the window, banded behind everything else. */
private fun DrawScope.drawPasses(passes: List<WindowPass>, scale: CurveScale, plot: PlotArea) {
    for (pass in passes) {
        val from = pass.fromMillis.coerceIn(scale.startMillis, scale.endMillis)
        val to = pass.toMillis.coerceIn(scale.startMillis, scale.endMillis)
        if (to <= from) continue
        val left = scale.x(from)
        val width = (scale.x(to) - left).coerceAtLeast(1.5f)
        drawRect(
            color = StarWindowColors.WindowStroke.copy(alpha = 0.16f),
            topLeft = Offset(left, plot.top),
            size = Size(width, plot.height),
        )
    }
}

private fun DrawScope.drawGrid(
    scale: CurveScale,
    plot: PlotArea,
    paints: CurvePaints,
    zone: ZoneId,
) {
    // Hairlines, solid: a dashed grid reads as "threshold" when it is only a grid.
    val gridColor = StarWindowColors.Graticule.copy(alpha = 0.28f)

    val altitudeStep = if (scale.maxAltitude - scale.minAltitude > 70) 30.0 else 15.0
    var altitude = ceil(scale.minAltitude / altitudeStep) * altitudeStep
    while (altitude <= scale.maxAltitude) {
        val y = scale.y(altitude)
        drawLine(gridColor, Offset(plot.left, y), Offset(plot.right, y), strokeWidth = 1f)
        drawLabel(
            "${altitude.roundToInt()}°",
            2.dp.toPx(),
            y + 4.dp.toPx(),
            paints.axis,
        )
        altitude += altitudeStep
    }

    // Hour marks along the bottom, thinned out so the labels never collide.
    val spanHours = (scale.endMillis - scale.startMillis) / 3_600_000.0
    val hourStep = when {
        spanHours <= 8 -> 1
        spanHours <= 18 -> 2
        spanHours <= 30 -> 3
        else -> 6
    }
    val hourMillis = 3_600_000L
    var tick = (scale.startMillis / hourMillis + 1) * hourMillis
    while (tick <= scale.endMillis) {
        val hourOfDay = Instant.ofEpochMilli(tick).atZone(zone).hour
        if (hourOfDay % hourStep == 0) {
            val x = scale.x(tick)
            drawLine(gridColor, Offset(x, plot.top), Offset(x, plot.bottom), strokeWidth = 1f)
            val label = hourFormatter.withZone(zone).format(Instant.ofEpochMilli(tick))
            drawCenteredLabel(label, x, plot.bottom + 15.dp.toPx(), paints.axis)
        }
        tick += hourMillis
    }
}

private fun DrawScope.drawHorizon(scale: CurveScale, plot: PlotArea, paints: CurvePaints) {
    if (scale.minAltitude >= 0.0) return
    val y = scale.y(0.0)
    // Everything below the horizon is simply not observable; shade it back rather than delete it,
    // so rise and set stay readable as the crossings they are.
    drawRect(
        color = Color.Black.copy(alpha = 0.35f),
        topLeft = Offset(plot.left, y),
        size = Size(plot.width, plot.bottom - y),
    )
    drawLine(
        StarWindowColors.Muted,
        Offset(plot.left, y),
        Offset(plot.right, y),
        strokeWidth = 1.5.dp.toPx(),
    )
    drawLabel("Horizont", plot.left + 4.dp.toPx(), y - 4.dp.toPx(), paints.axis)
}

private fun DrawScope.drawCurve(
    track: SkyTrack,
    passes: List<WindowPass>,
    scale: CurveScale,
    plot: PlotArea,
) {
    fun insideWindow(millis: Long) = passes.any { millis in it.fromMillis..it.toMillis }

    val path = Path()
    track.points.forEachIndexed { index, point ->
        val x = scale.x(point.millis)
        val y = scale.y(point.position.altitudeDeg)
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, CURVE_COLOR, style = Stroke(width = 2.dp.toPx()))

    // The part inside the window is the same series, so it keeps the same hue and gains weight —
    // a second colour here would suggest a second measurement.
    var previous: Offset? = null
    var previousInside = false
    for (point in track.points) {
        val current = Offset(scale.x(point.millis), scale.y(point.position.altitudeDeg))
        val inside = insideWindow(point.millis)
        if (previous != null && inside && previousInside) {
            drawLine(CURVE_COLOR, previous, current, strokeWidth = 4.dp.toPx())
        }
        previous = current
        previousInside = inside
    }
}

private fun DrawScope.drawPeak(
    track: SkyTrack,
    scale: CurveScale,
    plot: PlotArea,
    paints: CurvePaints,
    zone: ZoneId,
) {
    // One direct label, on the point that matters. A number on every sample would be unreadable.
    val peak = track.points.maxByOrNull { it.position.altitudeDeg } ?: return
    if (peak.position.altitudeDeg < 0) return

    val x = scale.x(peak.millis)
    val y = scale.y(peak.position.altitudeDeg)
    drawCircle(CURVE_COLOR, 3.5.dp.toPx(), Offset(x, y))
    drawCircle(StarWindowColors.Night, 1.5.dp.toPx(), Offset(x, y))

    val text = "%.0f° um %s".format(
        peak.position.altitudeDeg,
        hourMinuteFormatter.withZone(zone).format(Instant.ofEpochMilli(peak.millis)),
    )
    val width = paints.label.measureText(text)
    val labelX = (x - width / 2f).coerceIn(plot.left, plot.right - width)
    drawLabel(text, labelX, (y - 8.dp.toPx()).coerceAtLeast(plot.top + 9.dp.toPx()), paints.label)
}

private fun DrawScope.drawNow(
    nowMillis: Long,
    scale: CurveScale,
    plot: PlotArea,
    paints: CurvePaints,
) {
    if (nowMillis < scale.startMillis || nowMillis > scale.endMillis) return
    val x = scale.x(nowMillis)
    drawLine(
        StarWindowColors.Crosshair.copy(alpha = 0.8f),
        Offset(x, plot.top),
        Offset(x, plot.bottom),
        strokeWidth = 1.5.dp.toPx(),
    )
    drawLabel("jetzt", x + 3.dp.toPx(), plot.top + 9.dp.toPx(), paints.now)
}

private fun DrawScope.drawLabel(text: String, x: Float, y: Float, paint: Paint) {
    if (text.isEmpty()) return
    drawIntoCanvas { it.nativeCanvas.drawText(text, x, y, paint) }
}

private fun DrawScope.drawCenteredLabel(text: String, centerX: Float, y: Float, paint: Paint) {
    drawLabel(text, centerX - paint.measureText(text) / 2f, y, paint)
}

/** Validated against the window's green — see the note on the composable. */
private val CURVE_COLOR = Color(0xFF5AA9FF)

private val hourFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH")
private val hourMinuteFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private class CurvePaints(density: Density) {
    private val scale = density.density

    /** Labels wear text colours, never the series colour. */
    val axis = paint(StarWindowColors.Muted, 10f)
    val label = paint(StarWindowColors.Starlight, 11f)
    val now = paint(StarWindowColors.Crosshair, 10f)

    private fun paint(color: Color, sizeSp: Float) = Paint().apply {
        isAntiAlias = true
        this.color = color.toArgb()
        textSize = sizeSp * scale
        setShadowLayer(3f * scale, 0f, 0f, android.graphics.Color.BLACK)
    }
}
