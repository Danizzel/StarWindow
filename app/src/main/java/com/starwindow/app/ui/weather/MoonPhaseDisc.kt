package com.starwindow.app.ui.weather

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.starwindow.app.core.astro.MoonIllumination
import kotlin.math.cos
import kotlin.math.sin

/**
 * Der Mond, wie er heute Nacht aussieht.
 *
 * Ein gezeichneter Mond statt eines Symbols oder eines Fotos, aus einem einzigen Grund: Die Phase
 * ist eine **Zahl, die sich täglich ändert**, und die App kennt sie ohnehin auf ein Prozent genau.
 * Ein Satz Symbolbilder in Achtelschritten würde daraus wieder eine grobe Einteilung machen, und
 * ein Foto wäre für jede Nacht außer einer falsch.
 *
 * **Die Form.** Der Terminator — die Grenze zwischen Tag- und Nachtseite des Mondes — ist ein
 * Halbkreis, den man schräg sieht, und projiziert sich damit als halbe Ellipse. Ihre waagerechte
 * Halbachse ist `R · (1 − 2k)` mit `k` als beleuchtetem Anteil, und diese eine Formel erledigt alle
 * Fälle von allein: Bei Halbmond wird sie null (der Terminator ist eine Gerade), bei zunehmender
 * Sichel positiv (er wölbt sich zur hellen Seite und schneidet die Sichel schmal), beim Dreiviertel-
 * mond negativ (er wölbt sich zur dunklen Seite und lässt eine Beule stehen). Deshalb wird hier
 * nichts nach Phasen unterschieden, sondern eine Kurve gezeichnet.
 *
 * **Die Richtung.** Zunehmend ist rechts hell — so steht der Mond auf der Nordhalbkugel, und dort
 * wird diese App benutzt. Auf der Südhalbkugel stünde er andersherum; das wäre ein Vorzeichen mehr,
 * aber auch eine Behauptung über den Betrachter, die der Bildschirm nicht prüfen kann.
 */
@Composable
fun MoonPhaseDisc(
    illumination: MoonIllumination,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
) {
    val percent = illumination.percent
    val description = "%s, %.0f %% beleuchtet".format(illumination.phaseName, percent)

    Canvas(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = description }
    ) {
        drawMoon(fraction = illumination.fraction, waxing = illumination.waxing)
    }
}

/** Wie viele Stützpunkte der Terminator bekommt. Bei dieser Größe ist das weit jenseits sichtbar. */
private const val TERMINATOR_STEPS = 72

/**
 * Auf welcher Seite die helle Sichel liegt: `+1` rechts (zunehmend), `-1` links (abnehmend).
 *
 * Nordhalbkugel. Siehe die Anmerkung zur Richtung im Klassenkommentar.
 */
internal fun moonLimbDirection(waxing: Boolean): Float = if (waxing) 1f else -1f

/**
 * Wo der Terminator die Äquatorlinie schneidet, in Einheiten des Radius.
 *
 * Das Vorzeichen erledigt die Fallunterscheidung, die man sonst schreiben müsste: Bei weniger als
 * halbem Mond liegt der Schnittpunkt auf der hellen Seite (die Sichel wird schmal), bei mehr als
 * halbem auf der dunklen (es bleibt eine Beule stehen).
 */
internal fun moonTerminatorOffset(fraction: Double, waxing: Boolean): Float =
    moonLimbDirection(waxing) * (1f - 2f * fraction.coerceIn(0.0, 1.0).toFloat())

/**
 * Wo die helle Seite auf der Äquatorlinie liegt, in Einheiten des Radius.
 *
 * Herausgezogen, weil eine gezeichnete Fläche sich nicht prüfen lässt und der Fehler, den man hier
 * macht, genau der ist, den man nicht sieht: ein seitenverkehrter Mond. Bei 92 % ist der Unterschied
 * zwischen zu- und abnehmend ein Sichelchen am Rand — das fällt auf dem Bildschirm niemandem auf und
 * wäre trotzdem falsch. Als Zahlenpaar ist es eine Zeile Test.
 *
 * Gerechnet aus denselben zwei Größen, die [drawMoon] zeichnet: `-1` ist der linke Rand der Scheibe,
 * `+1` der rechte.
 */
internal fun litSpan(fraction: Double, waxing: Boolean): ClosedFloatingPointRange<Float> {
    val limb = moonLimbDirection(waxing)
    val terminator = moonTerminatorOffset(fraction, waxing)
    return minOf(limb, terminator)..maxOf(limb, terminator)
}

private fun DrawScope.drawMoon(fraction: Double, waxing: Boolean) {
    val radius = kotlin.math.min(size.width, size.height) / 2f
    val center = Offset(size.width / 2f, size.height / 2f)
    val lit = fraction.coerceIn(0.0, 1.0)

    // Die Nachtseite. Nicht schwarz, sondern ein sehr dunkles Blaugrau: Der unbeleuchtete Teil ist
    // am echten Himmel durch Erdschein schwach sichtbar, und eine schwarze Scheibe auf dunklem
    // Grund verschwindet ganz — dann fehlte dem Bild der Umriss, an dem man die Phase abliest.
    drawCircle(color = MOON_DARK, radius = radius, center = center)

    if (lit <= 0.005) return

    if (lit >= 0.995) {
        drawCircle(brush = fullMoonBrush(center, radius), radius = radius, center = center)
        return
    }

    // Dieselben zwei Größen, die `litSpan` prüfbar macht — sonst prüfte der Test eine
    // Parallelrechnung und der gezeichnete Mond könnte trotzdem seitenverkehrt sein.
    val direction = moonLimbDirection(waxing)
    val terminatorX = radius * moonTerminatorOffset(fraction, waxing)

    val path = Path()
    // Der helle Rand: vom oberen Pol über die Seite zum unteren.
    for (step in 0..TERMINATOR_STEPS) {
        val angle = -Math.PI / 2.0 + Math.PI * step / TERMINATOR_STEPS
        val x = center.x + direction * radius * cos(angle).toFloat()
        val y = center.y + radius * sin(angle).toFloat()
        if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    // Und zurück über den Terminator zum oberen Pol.
    for (step in TERMINATOR_STEPS downTo 0) {
        val angle = -Math.PI / 2.0 + Math.PI * step / TERMINATOR_STEPS
        val x = center.x + terminatorX * cos(angle).toFloat()
        val y = center.y + radius * sin(angle).toFloat()
        path.lineTo(x, y)
    }
    path.close()

    drawPath(path = path, brush = fullMoonBrush(center, radius))
}

/**
 * Die helle Seite, leicht plastisch.
 *
 * Ein Verlauf und keine flache Fläche, weil eine flache Scheibe wie ein Aufkleber aussieht. Die
 * Helligkeit fällt zum Rand hin ab, wie beim echten Mond auch — dort blickt man streifend auf die
 * Oberfläche.
 */
private fun fullMoonBrush(center: Offset, radius: Float): Brush = Brush.radialGradient(
    colors = listOf(MOON_LIGHT, MOON_LIGHT, MOON_EDGE),
    center = Offset(center.x - radius * 0.15f, center.y - radius * 0.2f),
    radius = radius * 1.35f,
)

private val MOON_LIGHT = Color(0xFFF3EFE2)
private val MOON_EDGE = Color(0xFFB9B3A2)
private val MOON_DARK = Color(0xFF23283A)
