package com.starwindow.app.ui.windows

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.starwindow.app.core.astro.Horizontal
import com.starwindow.app.core.geometry.PlanarPoint
import com.starwindow.app.core.geometry.SkyWindow
import com.starwindow.app.core.geometry.TangentPlane
import com.starwindow.app.domain.SkyTrack
import com.starwindow.app.ui.theme.StarWindowColors
import kotlin.math.atan2
import kotlin.math.hypot

/** A path to draw, with the colour it should get. */
data class ChartTrack(val track: SkyTrack, val color: Color, val emphasised: Boolean = false)

/**
 * The window as the observer sees it, with the paths of everything that crosses it.
 *
 * This is the "Laufbahn" view: the window sits still, and each object sweeps across it left or
 * right depending on where in the sky the window points. Drawn on the tangent plane of the window's
 * own centre, so the outline keeps its real shape and the paths keep their real curvature — a plain
 * azimuth/altitude plot would distort both, badly so high up.
 *
 * Time runs along each path: the part inside the window is solid, the lead-in and lead-out are
 * dotted, and the dots mark full hours.
 */
@Composable
fun WindowTrackChart(
    window: SkyWindow,
    tracks: List<ChartTrack>,
    modifier: Modifier = Modifier,
    figureSegments: List<Pair<Horizontal, Horizontal>> = emptyList(),
) {
    val density = LocalDensity.current
    val paints = remember(density) { ChartPaints(density) }
    val plane = remember(window.id) { TangentPlane(window.centerHorizontal) }
    val outline = remember(window.id) { window.shape.outline(96) }

    Canvas(modifier = modifier.fillMaxWidth().height(240.dp)) {
        val projectedOutline = outline.mapNotNull { plane.project(it) }
        if (projectedOutline.size < 3) return@Canvas

        val view = ChartViewport.fit(projectedOutline, size.width, size.height, margin = 1.5)

        drawReferenceLines(view, paints)
        figureSegments.forEach { (a, b) ->
            drawSegment(plane, view, a, b, StarWindowColors.Muted.copy(alpha = 0.5f), 1.dp.toPx())
        }
        drawWindow(projectedOutline, view)
        tracks.forEach { drawTrack(plane, view, it, paints) }
        drawOrientationLabels(view, paints)
    }
}

/** Maps tangent-plane coordinates onto the canvas with a single uniform scale. */
private class ChartViewport(
    private val centerX: Double,
    private val centerY: Double,
    private val scale: Double,
    val widthPx: Float,
    val heightPx: Float,
) {
    fun toCanvas(point: PlanarPoint): Offset = Offset(
        x = (widthPx / 2.0 + (point.x - centerX) * scale).toFloat(),
        // Tangent plane y grows towards the zenith; canvas y grows downwards.
        y = (heightPx / 2.0 - (point.y - centerY) * scale).toFloat(),
    )

    /** Degrees per canvas pixel, for the reference grid spacing. */
    val degreesPerPixel: Double get() = Math.toDegrees(1.0 / scale)

    fun isOnCanvas(offset: Offset, marginPx: Float = 0f): Boolean =
        offset.x >= -marginPx && offset.x <= widthPx + marginPx &&
            offset.y >= -marginPx && offset.y <= heightPx + marginPx

    companion object {
        fun fit(
            points: List<PlanarPoint>,
            widthPx: Float,
            heightPx: Float,
            margin: Double,
        ): ChartViewport {
            val minX = points.minOf { it.x }
            val maxX = points.maxOf { it.x }
            val minY = points.minOf { it.y }
            val maxY = points.maxOf { it.y }
            val spanX = ((maxX - minX) * margin).coerceAtLeast(1e-4)
            val spanY = ((maxY - minY) * margin).coerceAtLeast(1e-4)
            // One scale for both axes, or the window's shape would be misrepresented.
            val scale = minOf(widthPx / spanX, heightPx / spanY)
            return ChartViewport((minX + maxX) / 2.0, (minY + maxY) / 2.0, scale, widthPx, heightPx)
        }
    }
}

private fun DrawScope.drawReferenceLines(view: ChartViewport, paints: ChartPaints) {
    drawRect(StarWindowColors.Night)

    // A grid at a round number of degrees, chosen so it never becomes a moiré pattern.
    val targetSpacingPx = 56.0
    val rawDegrees = view.degreesPerPixel * targetSpacingPx
    val stepDeg = listOf(0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0)
        .firstOrNull { it >= rawDegrees } ?: 30.0
    val stepPx = (stepDeg / view.degreesPerPixel).toFloat()
    if (stepPx < 8f) return

    val color = StarWindowColors.Graticule.copy(alpha = 0.35f)
    var x = view.widthPx / 2f
    while (x <= view.widthPx) {
        drawLine(color, Offset(x, 0f), Offset(x, view.heightPx), strokeWidth = 1f)
        drawLine(
            color,
            Offset(view.widthPx - x, 0f),
            Offset(view.widthPx - x, view.heightPx),
            strokeWidth = 1f,
        )
        x += stepPx
    }
    var y = view.heightPx / 2f
    while (y <= view.heightPx) {
        drawLine(color, Offset(0f, y), Offset(view.widthPx, y), strokeWidth = 1f)
        drawLine(
            color,
            Offset(0f, view.heightPx - y),
            Offset(view.widthPx, view.heightPx - y),
            strokeWidth = 1f,
        )
        y += stepPx
    }

    drawLabel("Raster ${formatDegrees(stepDeg)}", 6.dp.toPx(), view.heightPx - 6.dp.toPx(), paints.hint)
}

private fun DrawScope.drawWindow(outline: List<PlanarPoint>, view: ChartViewport) {
    val path = Path()
    outline.forEachIndexed { index, point ->
        val offset = view.toCanvas(point)
        if (index == 0) path.moveTo(offset.x, offset.y) else path.lineTo(offset.x, offset.y)
    }
    path.close()
    drawPath(path, StarWindowColors.WindowFill)
    drawPath(path, StarWindowColors.WindowStroke, style = Stroke(width = 2.5.dp.toPx()))
}

private fun DrawScope.drawSegment(
    plane: TangentPlane,
    view: ChartViewport,
    a: Horizontal,
    b: Horizontal,
    color: Color,
    width: Float,
) {
    val pa = plane.project(a)?.let(view::toCanvas) ?: return
    val pb = plane.project(b)?.let(view::toCanvas) ?: return
    if (!view.isOnCanvas(pa, 400f) && !view.isOnCanvas(pb, 400f)) return
    drawLine(color, pa, pb, strokeWidth = width)
}

private fun DrawScope.drawTrack(
    plane: TangentPlane,
    view: ChartViewport,
    chartTrack: ChartTrack,
    paints: ChartPaints,
) {
    val track = chartTrack.track
    if (track.isEmpty) return

    val width = if (chartTrack.emphasised) 3.dp.toPx() else 1.8.dp.toPx()
    val dashed = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx()))
    val margin = 600f

    var previousOffset: Offset? = null
    for (point in track.points) {
        val offset = plane.project(point.position)?.let(view::toCanvas)
        if (offset != null && previousOffset != null &&
            (view.isOnCanvas(offset, margin) || view.isOnCanvas(previousOffset, margin))
        ) {
            // Solid while inside the window, dotted for the approach and the exit.
            val insideWindow = point.millis in track.enterMillis..track.exitMillis
            drawLine(
                color = chartTrack.color.copy(alpha = if (insideWindow) 1f else 0.55f),
                start = previousOffset,
                end = offset,
                strokeWidth = if (insideWindow) width else width * 0.7f,
                pathEffect = if (insideWindow) null else dashed,
            )
        }
        previousOffset = offset
    }

    // Which way time runs: an arrowhead on the last pair of points.
    val tail = track.points.takeLast(2).mapNotNull { plane.project(it.position)?.let(view::toCanvas) }
    if (tail.size == 2) drawArrowHead(tail[0], tail[1], chartTrack.color, 7.dp.toPx())

    track.hourMarks().forEach { mark ->
        val offset = plane.project(mark.position)?.let(view::toCanvas) ?: return@forEach
        if (!view.isOnCanvas(offset, 20f)) return@forEach
        drawCircle(chartTrack.color, 3.dp.toPx(), offset)
        drawLabel(formatClock(mark.millis), offset.x + 5.dp.toPx(), offset.y - 4.dp.toPx(), paints.time)
    }

    // Entry and exit are the numbers the user came for, so they are always labelled.
    listOf(track.enterMillis to "ein", track.exitMillis to "aus").forEach { (millis, tag) ->
        val point = track.points.minByOrNull { kotlin.math.abs(it.millis - millis) } ?: return@forEach
        val offset = plane.project(point.position)?.let(view::toCanvas) ?: return@forEach
        if (!view.isOnCanvas(offset, 30f)) return@forEach
        drawCircle(chartTrack.color, 4.5.dp.toPx(), offset)
        drawCircle(StarWindowColors.Night, 2.dp.toPx(), offset)
        drawLabel(
            "$tag ${formatClock(millis)}",
            offset.x + 7.dp.toPx(),
            offset.y + 12.dp.toPx(),
            paints.time,
        )
    }

    // Name the path where it comes in, so several of them stay tellable apart.
    plane.project(track.points.first().position)?.let(view::toCanvas)?.let { offset ->
        if (view.isOnCanvas(offset, 40f)) {
            drawLabel(track.label, offset.x + 6.dp.toPx(), offset.y, paints.label)
        }
    }
}

private fun DrawScope.drawArrowHead(from: Offset, to: Offset, color: Color, size: Float) {
    val dx = to.x - from.x
    val dy = to.y - from.y
    if (hypot(dx, dy) < 0.5f) return
    val angle = atan2(dy, dx)
    val spread = 0.45f
    drawLine(
        color,
        to,
        Offset(to.x - size * kotlin.math.cos(angle - spread), to.y - size * kotlin.math.sin(angle - spread)),
        strokeWidth = 2.dp.toPx(),
    )
    drawLine(
        color,
        to,
        Offset(to.x - size * kotlin.math.cos(angle + spread), to.y - size * kotlin.math.sin(angle + spread)),
        strokeWidth = 2.dp.toPx(),
    )
}

private fun DrawScope.drawOrientationLabels(view: ChartViewport, paints: ChartPaints) {
    val pad = 8.dp.toPx()
    drawLabel("↑ Zenit", pad, pad + 12.dp.toPx(), paints.hint)
    drawLabel("Osten →", view.widthPx - 62.dp.toPx(), pad + 12.dp.toPx(), paints.hint)
}

private fun DrawScope.drawLabel(text: String, x: Float, y: Float, paint: Paint) {
    if (text.isEmpty()) return
    drawIntoCanvas { it.nativeCanvas.drawText(text, x, y, paint) }
}

private fun formatDegrees(deg: Double): String =
    if (deg < 1.0) "%.2f°".format(deg) else "%.0f°".format(deg)

private class ChartPaints(density: Density) {
    private val scale = density.density

    val time = paint(StarWindowColors.Starlight, 10f)
    val label = paint(StarWindowColors.Starlight, 11f)
    val hint = paint(StarWindowColors.Muted, 10f)

    private fun paint(color: Color, sizeSp: Float) = Paint().apply {
        isAntiAlias = true
        this.color = color.toArgb()
        textSize = sizeSp * scale
        setShadowLayer(3f * scale, 0f, 0f, android.graphics.Color.BLACK)
    }
}
