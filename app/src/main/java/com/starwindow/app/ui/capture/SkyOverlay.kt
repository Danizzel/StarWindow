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
import com.starwindow.app.core.astro.Precession
import com.starwindow.app.core.camera.EdgeInsets
import com.starwindow.app.core.camera.ScreenPoint
import com.starwindow.app.core.camera.SkyProjection
import com.starwindow.app.core.camera.TargetIndicator
import com.starwindow.app.core.camera.TargetMarker
import com.starwindow.app.core.geometry.WindowShape
import com.starwindow.app.core.sensors.DeviceAttitude
import com.starwindow.app.data.catalog.ObjectType
import com.starwindow.app.data.catalog.SkyObject
import com.starwindow.app.ui.components.drawObjectSymbol
import com.starwindow.app.ui.theme.ObjectPalette
import com.starwindow.app.ui.theme.StarWindowColors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The thing the viewfinder is leading the user to, already resolved to a direction.
 *
 * Resolved outside the overlay on purpose. A catalogue object moves with the sky and a saved window
 * does not, and that difference belongs in the view model where sidereal time and precession live —
 * not in a draw lambda that runs fifty times a second. The sky moves fifteen arcseconds per second,
 * so a direction recomputed once a second is four thousandths of a degree stale at worst: far below
 * anything the sensor can resolve, and it keeps the draw phase free of astronomy.
 */
data class SkyTarget(
    val label: String,
    val direction: Horizontal,
    /** Set for a catalogue object, so the marker can carry the same chart symbol as the list. */
    val type: ObjectType? = null,
    /** Set for a saved window, so the overlay can draw the outline the user is walking back to. */
    val shape: WindowShape? = null,
)

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
    /** The object picked in the search; the overlay leads the user to it. */
    trackedTarget: SkyTarget? = null,
    /**
     * How much of each edge the screen's own controls cover, in view pixels. The edge arrow is kept
     * out of those bands — an arrow drawn behind the control panel points at nothing.
     */
    chromeInsets: EdgeInsets = EdgeInsets.NONE,
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
        highlightDirection?.let {
            drawTargetMarker(
                projection = projection,
                direction = it,
                label = highlightLabel,
                color = StarWindowColors.AnchorPoint,
                paints = paints,
                chromeInsets = chromeInsets,
            )
        }
        trackedTarget?.let { target ->
            // A tracked window gets its outline drawn as well: the arrow brings the user round, and
            // the outline is what tells them they have arrived.
            target.shape?.let { drawWindowShape(projection, it, StarWindowColors.TrackTarget) }
            drawTargetMarker(
                projection = projection,
                direction = target.direction,
                label = target.label,
                color = StarWindowColors.TrackTarget,
                paints = paints,
                chromeInsets = chromeInsets,
                type = target.type,
                belowHorizon = target.direction.altitudeDeg < 0.0,
            )
        }
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

private fun DrawScope.drawWindowShape(
    projection: SkyProjection,
    shape: WindowShape,
    color: Color = StarWindowColors.WindowStroke,
) {
    val path = skyPath(projection, shape.outline(96)) ?: return
    drawPath(path, color.copy(alpha = 0.13f))
    drawPath(path, color, style = Stroke(width = 3.dp.toPx()))
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
 * Leads the user to one direction in the sky.
 *
 * Two states, and the second is the one that matters. While the target is in the picture it gets a
 * ring and its name, which is a nicety. While it is *not*, an arrow sits on the edge of the screen
 * pointing the way, together with how far the phone still has to swing — and that is the difference
 * between finding a faint galaxy and sweeping the sky at random until the battery dies.
 *
 * The arrow keeps working when the target is behind the observer, where the projection has no
 * answer at all: [TargetIndicator] takes the direction from the target's vector in the display
 * frame rather than from a projected point.
 */
private fun DrawScope.drawTargetMarker(
    projection: SkyProjection,
    direction: Horizontal,
    label: String,
    color: Color,
    paints: OverlayPaints,
    chromeInsets: EdgeInsets = EdgeInsets.NONE,
    type: ObjectType? = null,
    belowHorizon: Boolean = false,
) {
    // The controls the screen reports, plus enough room for the arrow and its caption not to be cut
    // in half by the edge of the display.
    val insets = chromeInsets + EdgeInsets.uniform(34.dp.toPx())
    val marker = TargetIndicator.locate(projection, direction, insets) ?: return
    val caption = "$label  ·  ${formatSeparation(marker.separationDeg)}"

    if (marker.onScreen) {
        val radius = 24.dp.toPx()
        val center = Offset(marker.x, marker.y)
        drawCircle(color, radius, center, style = Stroke(width = 2.dp.toPx()))

        // Four ticks pointing inwards: the eye lands on the gap between them, which is where the
        // object is, instead of on the ring itself.
        val from = radius * 1.05f
        val to = radius * 1.5f
        val tick = 1.5.dp.toPx()
        drawLine(color, center - Offset(to, 0f), center - Offset(from, 0f), tick)
        drawLine(color, center + Offset(from, 0f), center + Offset(to, 0f), tick)
        drawLine(color, center - Offset(0f, to), center - Offset(0f, from), tick)
        drawLine(color, center + Offset(0f, from), center + Offset(0f, to), tick)

        // The same chart symbol as in the search list, so the row and the marker match.
        type?.let { drawObjectSymbol(it, color, center, radius * 0.9f) }

        drawLabel(caption, marker.x, marker.y + radius * 1.5f + 14.dp.toPx(), paints.centeredPaint(color))
        if (belowHorizon) {
            drawLabel(
                "unter dem Horizont",
                marker.x,
                marker.y + radius * 1.5f + 30.dp.toPx(),
                paints.centeredSmallPaint(StarWindowColors.Muted),
            )
        }
        return
    }

    drawEdgeArrow(marker, color)

    // The caption is pulled back inside the view along the arrow's own direction, so it never ends
    // up half off the screen or under the controls however the phone is held. Centred text needs
    // half its own width of clearance on each side, hence the wider horizontal margin.
    val pullIn = 30.dp.toPx()
    val captionHalfWidth = 80.dp.toPx()
    drawLabel(
        caption,
        clamp(marker.x - marker.dirX * pullIn, captionHalfWidth, size.width - captionHalfWidth),
        clamp(marker.y - marker.dirY * pullIn, insets.top, size.height - insets.bottom),
        paints.centeredPaint(color),
    )
}

/** Keeps a value inside a range that may itself have collapsed on a small screen. */
private fun clamp(value: Float, min: Float, max: Float): Float =
    value.coerceIn(min, max.coerceAtLeast(min))

/** A filled triangle sitting on the edge of the screen, nose pointing at the target. */
private fun DrawScope.drawEdgeArrow(marker: TargetMarker, color: Color) {
    val length = 20.dp.toPx()
    val halfWidth = 11.dp.toPx()
    val tip = Offset(marker.x, marker.y)
    // Perpendicular to the direction, for the two base corners.
    val sideX = -marker.dirY
    val sideY = marker.dirX
    val baseX = marker.x - marker.dirX * length
    val baseY = marker.y - marker.dirY * length

    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(baseX + sideX * halfWidth, baseY + sideY * halfWidth)
        lineTo(baseX - sideX * halfWidth, baseY - sideY * halfWidth)
        close()
    }
    // A dark outline underneath keeps it visible over a bright horizon or a street lamp.
    drawPath(path, StarWindowColors.Night.copy(alpha = 0.7f), style = Stroke(width = 5.dp.toPx()))
    drawPath(path, color)
}

/** Degrees to swing, written the way it is worth reading: coarse when far, fine when close. */
private fun formatSeparation(separationDeg: Double): String = when {
    separationDeg >= 10.0 -> "%.0f°".format(separationDeg)
    else -> "%.1f°".format(separationDeg)
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
    val now = System.currentTimeMillis()
    val lst = AstroTime.lstDeg(now, observer.longitudeDeg)
    // One precession for the whole frame — it is the same rotation for every object in it.
    val precession = Precession.forEpoch(now)
    val margin = 24.dp.toPx()
    val unit = 1.dp.toPx()

    for (obj in catalog) {
        val position = CoordinateTransforms.apparentHorizontalAtLst(
            obj.positionAt(precession),
            observer.latitudeDeg,
            lst,
        )
        if (position.altitudeDeg < -2.0) continue
        val point = projection.skyToScreen(position) ?: continue
        if (point.x < -margin || point.x > size.width + margin) continue
        if (point.y < -margin || point.y > size.height + margin) continue

        val magnitude = obj.magnitude ?: 6.0
        val radius = (7.0 - magnitude).coerceIn(1.5, 7.0).toFloat() * unit * 0.6f
        // Coloured by type, the same colours the search list uses: a marker and the row that
        // describes it can then be matched without reading either.
        val color = ObjectPalette.colorFor(obj.type)
        drawCircle(
            color = color,
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

    /**
     * The target caption. Recoloured rather than reallocated: this is called on every frame, and a
     * `Paint` per frame is exactly the kind of allocation that turns a smooth overlay into a
     * stuttering one.
     */
    private val centered = textPaint(StarWindowColors.Starlight, 14f).apply {
        textAlign = Paint.Align.CENTER
    }
    private val centeredSmall = textPaint(StarWindowColors.Muted, 11f).apply {
        textAlign = Paint.Align.CENTER
    }

    fun centeredPaint(color: Color): Paint = centered.apply { this.color = color.toArgb() }

    fun centeredSmallPaint(color: Color): Paint = centeredSmall.apply { this.color = color.toArgb() }

    private fun textPaint(color: Color, sizeSp: Float) = Paint().apply {
        isAntiAlias = true
        this.color = color.toArgb()
        textSize = sizeSp * scale
        setShadowLayer(3f * scale, 0f, 0f, android.graphics.Color.BLACK)
    }
}
