package com.starwindow.app.ui.windows

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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

    Canvas(modifier = modifier.fillMaxWidth().height(260.dp)) {
        val projectedOutline = outline.mapNotNull { plane.project(it) }
        if (projectedOutline.size < 3) return@Canvas

        val view = ChartViewport.fit(projectedOutline, size.width, size.height, margin = 1.5)
        val space = LabelSpace(size.width, size.height, 2.dp.toPx())

        drawReferenceLines(view, paints, space)
        // Early, so the space they occupy is claimed before any track label goes looking for room.
        drawOrientationLabels(view, paints, space)
        figureSegments.forEach { (a, b) ->
            drawSegment(plane, view, a, b, StarWindowColors.Muted.copy(alpha = 0.5f), 1.dp.toPx())
        }
        drawWindow(projectedOutline, view)

        // Geometry first, all of it. Lines and dots cannot collide in a way that costs information,
        // so they are never dropped.
        tracks.forEach { drawTrackGeometry(plane, view, it) }

        // Then the words, in order of how much they are worth, because there is only so much room:
        // a name identifies a path, an entry time is what the user came for, and an hour mark is a
        // nicety. Whatever no longer fits is left out rather than drawn over something else.
        val ordered = tracks.sortedByDescending { it.emphasised }
        ordered.forEach { drawTrackName(plane, view, it, paints, space) }
        ordered.forEach { drawEntryExitLabels(plane, view, it, paints, space) }
        ordered.forEach { drawHourLabels(plane, view, it, paints, space) }
    }
}

/**
 * Keeps labels off each other.
 *
 * A chart with six paths wants something like sixty labels and has room for perhaps fifteen. Drawing
 * all of them anyway is how the old version ended up with times stacked three deep and none of them
 * readable — so every label claims a rectangle, and one that cannot find free space is simply not
 * drawn. Dropping a label costs one number; overlapping two costs both.
 */
private class LabelSpace(
    private val widthPx: Float,
    private val heightPx: Float,
    private val padPx: Float,
) {
    private val taken = ArrayList<Rect>()

    /** Blocks out an area that is not a label — the grid caption, the orientation hints. */
    fun reserve(rect: Rect) {
        taken += rect
    }

    /**
     * Finds room for [text] near ([x], [y]), trying each anchor in turn.
     *
     * @return the baseline position to draw at, or null when nothing fits.
     */
    fun place(
        x: Float,
        y: Float,
        text: String,
        paint: Paint,
        gap: Float,
        anchors: List<LabelAnchor>,
    ): Offset? {
        val width = paint.measureText(text)
        val ascent = -paint.ascent()
        val descent = paint.descent()

        for (anchor in anchors) {
            val left: Float
            val baseline: Float
            when (anchor) {
                LabelAnchor.RIGHT -> { left = x + gap; baseline = y + ascent / 2f }
                LabelAnchor.LEFT -> { left = x - gap - width; baseline = y + ascent / 2f }
                LabelAnchor.ABOVE -> { left = x - width / 2f; baseline = y - gap }
                LabelAnchor.BELOW -> { left = x - width / 2f; baseline = y + gap + ascent }
            }
            val rect = Rect(
                left - padPx,
                baseline - ascent - padPx,
                left + width + padPx,
                baseline + descent + padPx,
            )
            if (rect.left < 0f || rect.right > widthPx) continue
            if (rect.top < 0f || rect.bottom > heightPx) continue
            if (taken.any { it.overlaps(rect) }) continue
            taken += rect
            return Offset(left, baseline)
        }
        return null
    }
}

private enum class LabelAnchor { RIGHT, LEFT, ABOVE, BELOW }

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

private fun DrawScope.drawReferenceLines(
    view: ChartViewport,
    paints: ChartPaints,
    space: LabelSpace,
) {
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

    val caption = "Raster ${formatDegrees(stepDeg)}"
    val captionX = 6.dp.toPx()
    val captionY = view.heightPx - 6.dp.toPx()
    drawLabel(caption, captionX, captionY, paints.hint)
    space.reserve(
        Rect(
            captionX,
            captionY + paints.hint.ascent(),
            captionX + paints.hint.measureText(caption),
            captionY + paints.hint.descent(),
        )
    )
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

/** The line, the hour dots, the entry and exit markers and the direction arrow — no words. */
private fun DrawScope.drawTrackGeometry(
    plane: TangentPlane,
    view: ChartViewport,
    chartTrack: ChartTrack,
) {
    val track = chartTrack.track
    if (track.isEmpty) return

    // Unselected paths are drawn back a little so the selected one reads as the foreground.
    val alpha = if (chartTrack.emphasised) 1f else 0.75f
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
                color = chartTrack.color.copy(alpha = if (insideWindow) alpha else alpha * 0.55f),
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
        drawCircle(chartTrack.color.copy(alpha = alpha), 3.dp.toPx(), offset)
    }

    entryExitPoints(plane, view, track).forEach { (_, offset) ->
        drawCircle(chartTrack.color, 4.5.dp.toPx(), offset)
        drawCircle(StarWindowColors.Night, 2.dp.toPx(), offset)
    }
}

/** Names the path where it comes in, so several of them stay tellable apart. */
private fun DrawScope.drawTrackName(
    plane: TangentPlane,
    view: ChartViewport,
    chartTrack: ChartTrack,
    paints: ChartPaints,
    space: LabelSpace,
) {
    val track = chartTrack.track
    if (track.isEmpty) return
    val offset = plane.project(track.points.first().position)?.let(view::toCanvas) ?: return
    if (!view.isOnCanvas(offset, 40f)) return

    val paint = if (chartTrack.emphasised) paints.labelStrong else paints.label
    space.place(
        offset.x,
        offset.y,
        track.label,
        paint,
        6.dp.toPx(),
        listOf(LabelAnchor.RIGHT, LabelAnchor.LEFT, LabelAnchor.ABOVE, LabelAnchor.BELOW),
    )?.let { drawLabel(track.label, it.x, it.y, paint) }
}

/** Entry and exit are the numbers the user came for, so they come before the hour marks. */
private fun DrawScope.drawEntryExitLabels(
    plane: TangentPlane,
    view: ChartViewport,
    chartTrack: ChartTrack,
    paints: ChartPaints,
    space: LabelSpace,
) {
    entryExitPoints(plane, view, chartTrack.track).forEach { (label, offset) ->
        space.place(
            offset.x,
            offset.y,
            label,
            paints.time,
            8.dp.toPx(),
            listOf(LabelAnchor.BELOW, LabelAnchor.RIGHT, LabelAnchor.ABOVE, LabelAnchor.LEFT),
        )?.let { drawLabel(label, it.x, it.y, paints.time) }
    }
}

/**
 * Clock times along the path.
 *
 * The dots are always drawn; only some get a number. Labelling every full hour of every path is
 * what turned the chart into soup — and the hours in between can be read off the dots anyway, since
 * they are evenly spaced by construction.
 */
private fun DrawScope.drawHourLabels(
    plane: TangentPlane,
    view: ChartViewport,
    chartTrack: ChartTrack,
    paints: ChartPaints,
    space: LabelSpace,
) {
    val marks = chartTrack.track.hourMarks()
    if (marks.isEmpty()) return
    // On a path nobody selected, a couple of times is orientation enough.
    val step = if (chartTrack.emphasised) 1 else 2
    var placed = 0
    val budget = if (chartTrack.emphasised) MAX_HOUR_LABELS_EMPHASISED else MAX_HOUR_LABELS

    marks.filterIndexed { index, _ -> index % step == 0 }.forEach { mark ->
        if (placed >= budget) return
        val offset = plane.project(mark.position)?.let(view::toCanvas) ?: return@forEach
        if (!view.isOnCanvas(offset, 20f)) return@forEach
        val text = formatClock(mark.millis)
        space.place(
            offset.x,
            offset.y,
            text,
            paints.time,
            6.dp.toPx(),
            listOf(LabelAnchor.ABOVE, LabelAnchor.RIGHT, LabelAnchor.LEFT, LabelAnchor.BELOW),
        )?.let {
            drawLabel(text, it.x, it.y, paints.time)
            placed++
        }
    }
}

private const val MAX_HOUR_LABELS = 2
private const val MAX_HOUR_LABELS_EMPHASISED = 5

/** Where the path enters and leaves the window, with the caption each deserves. */
private fun entryExitPoints(
    plane: TangentPlane,
    view: ChartViewport,
    track: SkyTrack,
): List<Pair<String, Offset>> {
    if (track.isEmpty) return emptyList()
    return listOf(track.enterMillis to "ein", track.exitMillis to "aus").mapNotNull { (millis, tag) ->
        val point = track.points.minByOrNull { kotlin.math.abs(it.millis - millis) }
            ?: return@mapNotNull null
        val offset = plane.project(point.position)?.let(view::toCanvas) ?: return@mapNotNull null
        if (!view.isOnCanvas(offset, 30f)) return@mapNotNull null
        "$tag ${formatClock(millis)}" to offset
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

/**
 * Drawn last but reserved first in spirit: these two never move, so a label that would land on them
 * has to give way rather than the other way round.
 */
private fun DrawScope.drawOrientationLabels(
    view: ChartViewport,
    paints: ChartPaints,
    space: LabelSpace,
) {
    val pad = 8.dp.toPx()
    val baseline = pad + 12.dp.toPx()
    listOf("↑ Zenit" to pad, "Osten →" to view.widthPx - 62.dp.toPx()).forEach { (text, x) ->
        drawLabel(text, x, baseline, paints.hint)
        space.reserve(
            Rect(x, baseline + paints.hint.ascent(), x + paints.hint.measureText(text), baseline + paints.hint.descent())
        )
    }
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
    val labelStrong = paint(StarWindowColors.Starlight, 12.5f).apply { isFakeBoldText = true }
    val hint = paint(StarWindowColors.Muted, 10f)

    private fun paint(color: Color, sizeSp: Float) = Paint().apply {
        isAntiAlias = true
        this.color = color.toArgb()
        textSize = sizeSp * scale
        setShadowLayer(3f * scale, 0f, 0f, android.graphics.Color.BLACK)
    }
}
