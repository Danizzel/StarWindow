package com.starwindow.app.ui.capture

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
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
import com.starwindow.app.core.astro.Angles
import com.starwindow.app.core.astro.AstroTime
import com.starwindow.app.core.astro.CoordinateTransforms
import com.starwindow.app.core.astro.Horizontal
import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.core.camera.ScreenPoint
import com.starwindow.app.core.camera.SkyProjection
import com.starwindow.app.core.geometry.WindowShape
import com.starwindow.app.core.sensors.DeviceAttitude
import com.starwindow.app.data.catalog.SkyObject
import com.starwindow.app.ui.theme.StarWindowColors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Everything drawn on top of the viewfinder.
 *
 * The overlay is rendered from sky coordinates, never from screen coordinates: a point the user
 * placed is stored as an azimuth/altitude and projected back every frame. The markers therefore
 * stay where they were placed *relative to the horizon* while the phone moves, sliding across the
 * screen and eventually out of it.
 *
 * They do not follow the stars. Over a pan, lasting seconds, the two are indistinguishable — which
 * is what makes this the visual check that the screen ⇄ sky mapping is right: a marker that slides
 * off the feature it was placed on means the field of view is wrong. Over minutes the difference
 * shows: the sky turns roughly 15° per hour and drifts through the standing markers, and that
 * relative motion is exactly what the transit search reports on.
 *
 * [attitudeState] is read inside the draw lambda on purpose: that keeps sensor updates in the draw
 * phase instead of triggering a recomposition fifty times a second.
 */
@Composable
fun SkyOverlay(
    attitudeState: State<DeviceAttitude?>,
    focalPx: Double,
    anchors: List<Horizontal>,
    shape: WindowShape?,
    catalog: List<SkyObject>,
    observer: ObserverLocation?,
    showGraticule: Boolean,
    showCatalog: Boolean,
    modifier: Modifier = Modifier,
    /** Ringed and named, so the user knows which star the crosshair is meant to find. */
    highlightDirection: Horizontal? = null,
    highlightLabel: String = "",
) {
    val density = LocalDensity.current
    val paints = remember(density) { OverlayPaints(density) }

    Canvas(modifier = modifier) {
        val attitude = attitudeState.value ?: return@Canvas
        val projection = SkyProjection(attitude, focalPx, size.width, size.height)
        if (!projection.isUsable) return@Canvas

        if (showGraticule) drawGraticule(projection, paints)
        if (showCatalog && observer != null) drawCatalog(projection, catalog, observer, paints)
        shape?.let { drawWindowShape(projection, it) }
        highlightDirection?.let { drawHighlight(projection, it, highlightLabel, paints) }
        drawAnchors(projection, anchors, paints)
        drawCrosshair(projection, paints)
    }
}

// --- Graticule -------------------------------------------------------------------------------

/**
 * The altitude/azimuth graticule: parallels every 10° of altitude and meridians every 10° of
 * azimuth, with the horizon and the cardinal directions picked out. Only the lines that can
 * plausibly cross the viewfinder are traced, so the cost stays flat whatever the field of view.
 */
private fun DrawScope.drawGraticule(projection: SkyProjection, paints: OverlayPaints) {
    val center = projection.centerDirection
    val reach = maxOf(projection.visibleHorizontalFovDeg, projection.visibleVerticalFovDeg) * 0.75 + 12.0
    val thin = 1.dp.toPx()
    val thick = 2.dp.toPx()

    val gridStepDeg = 10
    val altFrom = (((center.altitudeDeg - reach) / gridStepDeg).toInt() - 1) * gridStepDeg
    val altTo = (((center.altitudeDeg + reach) / gridStepDeg).toInt() + 1) * gridStepDeg

    for (alt in altFrom..altTo step gridStepDeg) {
        if (alt < -90 || alt > 90) continue
        val isHorizon = alt == 0
        val color = if (isHorizon) {
            StarWindowColors.Crosshair.copy(alpha = 0.55f)
        } else {
            StarWindowColors.Graticule
        }
        val parallel = buildList {
            var az = center.azimuthDeg - reach
            while (az <= center.azimuthDeg + reach) {
                add(Horizontal(az, alt.toDouble()))
                az += 2.0
            }
        }
        drawSkyPolyline(projection, parallel, color, if (isHorizon) thick else thin, closed = false)
    }

    val azFrom = ((center.azimuthDeg - reach) / gridStepDeg).toInt() * gridStepDeg
    val azTo = ((center.azimuthDeg + reach) / gridStepDeg).toInt() * gridStepDeg
    for (az in azFrom..azTo step gridStepDeg) {
        val normalized = Angles.normalizeDeg(az.toDouble())
        val isCardinal = normalized.roundToInt() % 90 == 0
        val color = if (isCardinal) {
            StarWindowColors.Crosshair.copy(alpha = 0.45f)
        } else {
            StarWindowColors.Graticule
        }
        val meridian = buildList {
            var alt = (center.altitudeDeg - reach).coerceAtLeast(-88.0)
            val top = (center.altitudeDeg + reach).coerceAtMost(88.0)
            while (alt <= top) {
                add(Horizontal(normalized, alt))
                alt += 2.0
            }
        }
        drawSkyPolyline(projection, meridian, color, thin, closed = false)

        if (isCardinal) {
            val anchor = Horizontal(normalized, center.altitudeDeg.coerceIn(-80.0, 80.0))
            projection.skyToScreen(anchor)?.let { point ->
                drawLabel(cardinalLabel(normalized), point.x, point.y, paints.cardinalPaint)
            }
        }
    }
}

private fun cardinalLabel(azimuthDeg: Double): String = when (azimuthDeg.roundToInt()) {
    0, 360 -> "N"
    90 -> "O"
    180 -> "S"
    270 -> "W"
    else -> ""
}

// --- Window ----------------------------------------------------------------------------------

private fun DrawScope.drawWindowShape(projection: SkyProjection, shape: WindowShape) {
    val path = skyPath(projection, shape.outline(96)) ?: return
    drawPath(path, StarWindowColors.WindowFill)
    drawPath(path, StarWindowColors.WindowStroke, style = Stroke(width = 3.dp.toPx()))
}

private fun DrawScope.drawAnchors(
    projection: SkyProjection,
    anchors: List<Horizontal>,
    paints: OverlayPaints,
) {
    if (anchors.isEmpty()) return

    // While a polygon is still open, join the placed points so the outline is visible as it forms.
    if (anchors.size >= 2) {
        drawSkyPolyline(
            projection = projection,
            points = anchors,
            color = StarWindowColors.AnchorPoint.copy(alpha = 0.7f),
            strokeWidth = 2.dp.toPx(),
            closed = false,
            dashed = true,
        )
    }

    val radius = 7.dp.toPx()
    anchors.forEachIndexed { index, direction ->
        val point = projection.skyToScreen(direction) ?: return@forEachIndexed
        val offset = Offset(point.x, point.y)
        drawCircle(StarWindowColors.AnchorPoint, radius, offset)
        drawCircle(StarWindowColors.Night, radius * 0.45f, offset)
        drawLabel("${index + 1}", point.x + radius * 1.8f, point.y - radius, paints.anchorPaint)
    }
}

/**
 * Ring around the direction the user is being asked to aim at. When it sits off screen, an arrow at
 * the edge points the way — otherwise finding a named star means sweeping the sky at random.
 */
private fun DrawScope.drawHighlight(
    projection: SkyProjection,
    direction: Horizontal,
    label: String,
    paints: OverlayPaints,
) {
    val point = projection.skyToScreen(direction)
    val radius = 22.dp.toPx()

    if (point != null &&
        point.x >= 0f && point.x <= size.width &&
        point.y >= 0f && point.y <= size.height
    ) {
        drawCircle(
            color = StarWindowColors.AnchorPoint,
            radius = radius,
            center = Offset(point.x, point.y),
            style = Stroke(width = 2.dp.toPx()),
        )
        drawLabel(label, point.x + radius + 6.dp.toPx(), point.y, paints.highlightPaint)
        return
    }

    // Off screen: point at it from the middle of the view.
    val centerX = size.width / 2f
    val centerY = size.height / 2f
    val display = projection.attitude.worldToDisplay(projection.attitude.toMagneticVector(direction))
    val length = kotlin.math.sqrt(display.x * display.x + display.y * display.y)
    if (length < 1e-6) return
    val dirX = (display.x / length).toFloat()
    val dirY = -(display.y / length).toFloat()
    val arrow = minOf(size.width, size.height) * 0.3f

    drawLine(
        color = StarWindowColors.AnchorPoint,
        start = Offset(centerX + dirX * arrow * 0.55f, centerY + dirY * arrow * 0.55f),
        end = Offset(centerX + dirX * arrow, centerY + dirY * arrow),
        strokeWidth = 3.dp.toPx(),
    )
    drawLabel(
        label,
        centerX + dirX * arrow + 8.dp.toPx(),
        centerY + dirY * arrow,
        paints.highlightPaint,
    )
}

private fun DrawScope.drawCrosshair(projection: SkyProjection, paints: OverlayPaints) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val arm = 14.dp.toPx()
    val width = 2.dp.toPx()
    val color = StarWindowColors.Crosshair

    drawLine(color, Offset(cx - arm, cy), Offset(cx - arm * 0.3f, cy), strokeWidth = width)
    drawLine(color, Offset(cx + arm * 0.3f, cy), Offset(cx + arm, cy), strokeWidth = width)
    drawLine(color, Offset(cx, cy - arm), Offset(cx, cy - arm * 0.3f), strokeWidth = width)
    drawLine(color, Offset(cx, cy + arm * 0.3f), Offset(cx, cy + arm), strokeWidth = width)

    val direction = projection.centerDirection
    drawLabel(
        "Az %.1f°  Alt %.1f°".format(direction.azimuthDeg, direction.altitudeDeg),
        cx + arm * 1.4f,
        cy - arm * 0.6f,
        paints.readoutPaint,
    )
}

// --- Catalogue -------------------------------------------------------------------------------

/**
 * Draws the bundled catalogue where it currently stands in the sky. This doubles as the sanity
 * check for the whole pipeline: point the phone at a bright star and its marker should sit on it.
 */
private fun DrawScope.drawCatalog(
    projection: SkyProjection,
    catalog: List<SkyObject>,
    observer: ObserverLocation,
    paints: OverlayPaints,
) {
    val lst = AstroTime.lstDeg(System.currentTimeMillis(), observer.longitudeDeg)
    val margin = 24.dp.toPx()
    val unit = 1.dp.toPx()

    for (obj in catalog) {
        val position = CoordinateTransforms.apparentHorizontalAtLst(
            obj.equatorial,
            observer.latitudeDeg,
            lst,
        )
        if (position.altitudeDeg < -2.0) continue
        val point = projection.skyToScreen(position) ?: continue
        if (point.x < -margin || point.x > size.width + margin) continue
        if (point.y < -margin || point.y > size.height + margin) continue

        val magnitude = obj.magnitude ?: 6.0
        val radius = (7.0 - magnitude).coerceIn(1.5, 7.0).toFloat() * unit * 0.6f
        drawCircle(
            color = StarWindowColors.CatalogMarker,
            radius = radius,
            center = Offset(point.x, point.y),
            style = Stroke(width = 1.5f * unit),
        )
        // Labelling everything turns the sky into soup; name the bright and the extended objects.
        if (magnitude < 3.5 || obj.sizeArcmin != null) {
            drawLabel(
                obj.name.ifBlank { obj.id },
                point.x + radius + 4f * unit,
                point.y,
                paints.catalogPaint,
            )
        }
    }
}

// --- Drawing helpers --------------------------------------------------------------------------

/**
 * Traces sky directions as a screen polyline, dropping segments whose endpoints fall behind the
 * camera or project absurdly far away — both happen close to the 90° edge of a pinhole projection,
 * where the maths is still correct but useless.
 */
private fun DrawScope.drawSkyPolyline(
    projection: SkyProjection,
    points: List<Horizontal>,
    color: Color,
    strokeWidth: Float,
    closed: Boolean,
    dashed: Boolean = false,
) {
    if (points.size < 2) return
    val limit = maxOf(size.width, size.height) * 4f
    val unit = 1.dp.toPx()
    val effect = if (dashed) {
        PathEffect.dashPathEffect(floatArrayOf(10f * unit, 8f * unit))
    } else {
        null
    }

    var previous = projection.skyToScreen(points.first())
    val last = if (closed) points.size else points.size - 1
    for (i in 1..last) {
        val current = projection.skyToScreen(points[i % points.size])
        val from = previous
        if (from != null && current != null && from.withinLimit(limit) && current.withinLimit(limit)) {
            drawLine(
                color = color,
                start = Offset(from.x, from.y),
                end = Offset(current.x, current.y),
                strokeWidth = strokeWidth,
                pathEffect = effect,
            )
        }
        previous = current
    }
}

private fun ScreenPoint.withinLimit(limit: Float): Boolean = abs(x) < limit && abs(y) < limit

/** Closed path through sky directions, or null when any part is behind the camera or way off. */
private fun DrawScope.skyPath(projection: SkyProjection, points: List<Horizontal>): Path? {
    val limit = maxOf(size.width, size.height) * 4f
    val screen = points.map { projection.skyToScreen(it) ?: return null }
    if (screen.any { !it.withinLimit(limit) }) return null

    val path = Path()
    screen.forEachIndexed { index, point ->
        if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
    }
    path.close()
    return path
}

private fun DrawScope.drawLabel(text: String, x: Float, y: Float, paint: Paint) {
    if (text.isEmpty()) return
    drawIntoCanvas { canvas -> canvas.nativeCanvas.drawText(text, x, y, paint) }
}

/** Text paints, built once per density instead of on every frame. */
private class OverlayPaints(density: Density) {
    private val scale = density.density

    val readoutPaint = textPaint(StarWindowColors.Crosshair, 13f)
    val anchorPaint = textPaint(StarWindowColors.AnchorPoint, 12f)
    val cardinalPaint = textPaint(StarWindowColors.Crosshair, 16f)
    val catalogPaint = textPaint(StarWindowColors.CatalogMarker, 11f)
    val highlightPaint = textPaint(StarWindowColors.AnchorPoint, 14f)

    private fun textPaint(color: Color, sizeSp: Float) = Paint().apply {
        isAntiAlias = true
        this.color = color.toArgb()
        textSize = sizeSp * scale
        setShadowLayer(3f * scale, 0f, 0f, android.graphics.Color.BLACK)
    }
}
