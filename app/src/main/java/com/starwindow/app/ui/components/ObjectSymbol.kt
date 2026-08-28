package com.starwindow.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.starwindow.app.data.catalog.ObjectType
import com.starwindow.app.ui.theme.ObjectPalette

/**
 * The little chart symbol in front of an entry.
 *
 * These are the symbols used on printed star charts — a filled dot for a star, an ellipse for a
 * galaxy, a dashed circle for an open cluster, a square for a nebula — rather than icons invented
 * for the app. Anyone who has held a star atlas already reads them, and even someone who has not
 * only needs to learn them once to be able to skim a list of forty entries by shape alone instead
 * of reading forty type labels.
 *
 * Drawn rather than taken from an icon font because the symbols are geometric and tiny; at 20 dp
 * an exact circle with a cross beats an icon scaled down until its detail turns to mush.
 */
@Composable
fun ObjectSymbol(
    type: ObjectType,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    color: Color = ObjectPalette.colorFor(type),
) {
    Canvas(modifier = modifier.size(size)) {
        drawObjectSymbol(
            type = type,
            color = color,
            center = Offset(this.size.width / 2f, this.size.height / 2f),
            extent = kotlin.math.min(this.size.width, this.size.height),
        )
    }
}

/**
 * The same symbol as a plain draw call, for the overlay over the camera image.
 *
 * @param extent the box the symbol is drawn into, centred on [center].
 */
fun DrawScope.drawObjectSymbol(
    type: ObjectType,
    color: Color,
    center: Offset,
    extent: Float,
) {
    val radius = extent * 0.36f
    val stroke = Stroke(width = extent * 0.09f)
    val dashed = Stroke(
        width = extent * 0.09f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(extent * 0.16f, extent * 0.12f)),
    )

    when (type) {
        ObjectType.STAR -> drawCircle(color, radius * 0.7f, center)

        ObjectType.DOUBLE_STAR -> {
            drawCircle(color, radius * 0.45f, center - Offset(radius * 0.5f, 0f))
            drawCircle(color, radius * 0.45f, center + Offset(radius * 0.5f, 0f))
        }

        // An elongated, tilted ellipse: the shape of a galaxy seen at an angle.
        ObjectType.GALAXY, ObjectType.GALAXY_GROUP -> rotate(-25f, center) {
            drawOval(
                color = color,
                topLeft = Offset(center.x - radius, center.y - radius * 0.52f),
                size = Size(radius * 2f, radius * 1.04f),
                style = stroke,
            )
        }

        ObjectType.OPEN_CLUSTER -> drawCircle(color, radius, center, style = dashed)

        // Circle with a cross through it, the classic globular symbol.
        ObjectType.GLOBULAR_CLUSTER -> {
            drawCircle(color, radius, center, style = stroke)
            drawLine(color, center - Offset(radius, 0f), center + Offset(radius, 0f), stroke.width)
            drawLine(color, center - Offset(0f, radius), center + Offset(0f, radius), stroke.width)
        }

        ObjectType.NEBULA, ObjectType.EMISSION_NEBULA, ObjectType.REFLECTION_NEBULA ->
            drawSquare(center, radius, color, stroke)

        ObjectType.DARK_NEBULA -> drawSquare(center, radius, color, dashed)

        // Small disc with four ticks — a planetary nebula on any chart.
        ObjectType.PLANETARY_NEBULA -> {
            drawCircle(color, radius * 0.6f, center, style = stroke)
            val from = radius * 0.6f
            val to = radius * 1.05f
            drawLine(color, center - Offset(to, 0f), center - Offset(from, 0f), stroke.width)
            drawLine(color, center + Offset(from, 0f), center + Offset(to, 0f), stroke.width)
            drawLine(color, center - Offset(0f, to), center - Offset(0f, from), stroke.width)
            drawLine(color, center + Offset(0f, from), center + Offset(0f, to), stroke.width)
        }

        // A broken ring with what is left of the star in the middle.
        ObjectType.SUPERNOVA_REMNANT -> {
            drawCircle(color, radius, center, style = dashed)
            drawCircle(color, radius * 0.18f, center)
        }

        // A cluster embedded in nebulosity: both symbols, one inside the other.
        ObjectType.CLUSTER_NEBULA -> {
            drawSquare(center, radius, color, stroke)
            drawCircle(color, radius * 0.42f, center, style = dashed)
        }

        // Volle Scheibe mit Strahlenkranz: das eine Zeichen, das für Sonne und Mond zugleich
        // funktioniert, und das einzige gefüllte in der ganzen Reihe — beide sind am Himmel keine
        // Punkte, sondern Flächen.
        ObjectType.SOLAR_SYSTEM -> {
            drawCircle(color, radius * 0.62f, center)
            val from = radius * 0.82f
            val to = radius * 1.1f
            drawLine(color, center - Offset(to, 0f), center - Offset(from, 0f), stroke.width)
            drawLine(color, center + Offset(from, 0f), center + Offset(to, 0f), stroke.width)
            drawLine(color, center - Offset(0f, to), center - Offset(0f, from), stroke.width)
            drawLine(color, center + Offset(0f, from), center + Offset(0f, to), stroke.width)
        }

        ObjectType.OTHER -> rotate(45f, center) {
            drawSquare(center, radius * 0.85f, color, stroke)
        }
    }
}

private fun DrawScope.drawSquare(center: Offset, radius: Float, color: Color, style: Stroke) {
    val rect = Rect(center = center, radius = radius)
    drawRect(
        color = color,
        topLeft = rect.topLeft,
        size = Size(rect.width, rect.height),
        style = style,
    )
}
