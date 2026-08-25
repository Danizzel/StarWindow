package com.starwindow.app.ui.weather

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.starwindow.app.domain.AstroHour
import com.starwindow.app.domain.AstroNight
import com.starwindow.app.domain.AstroWeather
import com.starwindow.app.ui.theme.StarWindowColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Der Verlauf einer Nacht.
 *
 * Eine Achse, eine Einheit: **Prozent, und oben ist gut.** Die Bewölkung hängt deshalb von oben
 * herein statt von unten aufzusteigen — die Unterkante des Wolkenbandes liest sich damit auf
 * derselben Skala als „so viel Himmel ist frei", und die Eignungskurve darunter zeigt in dieselbe
 * Richtung. Zwei Kurven mit entgegengesetzter Bedeutung auf einer Achse wären der sicherste Weg,
 * ein Diagramm falsch zu lesen.
 *
 * Der Hintergrund trägt die Dämmerung: Je heller der Himmel zu dieser Stunde ist, desto heller die
 * Fläche. Die astronomische Dunkelheit bleibt schwarz wie der Rest der App — das ist die Zeit, um
 * die es geht, und sie braucht keine Markierung, sondern die Abwesenheit einer.
 *
 * Mit dem Finger lässt sich die Kurve abfahren: Der Wert an der berührten Stelle steht dann als
 * Kasten daneben. Senkrechte Bewegungen gibt die Fläche wieder frei, damit die Liste darunter
 * weiter scrollen kann.
 */
@Composable
fun AstroWeatherChart(
    night: AstroNight,
    modifier: Modifier = Modifier,
    nowMillis: Long = System.currentTimeMillis(),
) {
    val density = LocalDensity.current
    val paints = remember(density) { WeatherChartPaints(density) }

    // Beim Wechsel der Nacht ist die alte Fingerposition bedeutungslos.
    var cursorX by remember(night.date) { mutableStateOf<Float?>(null) }

    val window = remember(night) { ChartWindow.of(night) }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(CHART_HEIGHT_DP.dp)
            .pointerInput(night.date) {
                awaitEachGesture {
                    // Die erste Berührung wird bewusst **nicht** konsumiert: sonst käme ein
                    // senkrechter Wisch nie bei der Liste an, und das Diagramm wäre eine Sperre
                    // mitten im Bildschirm.
                    val down = awaitFirstDown(requireUnconsumed = false)
                    cursorX = down.position.x
                    var claimed = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val dx = abs(change.position.x - down.position.x)
                        val dy = abs(change.position.y - down.position.y)
                        if (!claimed) {
                            // Senkrecht gewischt heißt: die Liste soll scrollen, nicht die Kurve
                            // gelesen werden.
                            if (dy > viewConfiguration.touchSlop && dy > dx) {
                                cursorX = null
                                break
                            }
                            if (dx > viewConfiguration.touchSlop) claimed = true
                        }
                        if (claimed) change.consume()
                        cursorX = change.position.x
                    }
                }
            },
    ) {
        val axisBandPx = AXIS_BAND_DP.dp.toPx()
        val plot = PlotArea(
            left = LEFT_GUTTER_DP.dp.toPx(),
            top = 10.dp.toPx(),
            right = size.width - 6.dp.toPx(),
            bottom = size.height - axisBandPx,
        )
        if (plot.width <= 0f || plot.height <= 0f) return@Canvas

        val points = window.points
        if (points.size < 2) {
            drawLabel("Für diese Nacht liegen keine Stundenwerte vor", plot.left, plot.top + 20.dp.toPx(), paints.axis)
            return@Canvas
        }

        val scale = ChartScale(window.fromMillis, window.toMillis, plot)

        drawTwilight(points, scale, plot)
        drawMoonBand(points, scale, plot, paints)
        drawGrid(scale, plot, paints, window)
        drawCloudBand(points, scale, plot, paints)
        drawScoreCurve(points, scale, plot, paints)
        drawDarknessMarkers(night, scale, plot, paints)
        drawNow(nowMillis, scale, plot, paints)
        cursorX?.let { drawCursor(it, points, scale, plot, paints, window) }
    }
}

private const val CHART_HEIGHT_DP = 218
private const val AXIS_BAND_DP = 22
private const val LEFT_GUTTER_DP = 30

/** Der gezeigte Ausschnitt der Nacht: vom Abend vor Sonnenuntergang bis nach Sonnenaufgang. */
private class ChartWindow(
    val fromMillis: Long,
    val toMillis: Long,
    val points: List<AstroHour>,
    /** Zeitzone des Ortes; alle Uhrzeiten an der Achse und im Kasten laufen darüber. */
    val zone: ZoneId,
) {
    companion object {
        private const val MARGIN_MILLIS = 60 * 60_000L

        fun of(night: AstroNight): ChartWindow {
            val hours = night.hours
            if (hours.isEmpty()) {
                return ChartWindow(night.times.fromMillis, night.times.toMillis, emptyList(), night.zone)
            }

            val firstHour = hours.first().millis
            val lastHour = hours.last().millis
            val from = (night.times.sunsetMillis?.minus(MARGIN_MILLIS) ?: firstHour)
                .coerceIn(firstHour, lastHour)
            val to = (night.times.sunriseMillis?.plus(MARGIN_MILLIS) ?: lastHour)
                .coerceIn(firstHour, lastHour)

            // Zu kurze Fenster (Polarsommer, fehlende Zeiten) fallen auf die ganze Nacht zurück.
            if (to - from < 3 * MARGIN_MILLIS) {
                return ChartWindow(firstHour, lastHour, hours, night.zone)
            }
            return ChartWindow(from, to, hours.filter { it.millis in from..to }, night.zone)
        }
    }
}

private class PlotArea(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** Zeit auf x, Prozent auf y — 0 unten, 100 oben. */
private class ChartScale(
    private val fromMillis: Long,
    private val toMillis: Long,
    private val plot: PlotArea,
) {
    fun x(millis: Long): Float {
        val span = (toMillis - fromMillis).coerceAtLeast(1L).toDouble()
        return plot.left + (plot.width * ((millis - fromMillis) / span)).toFloat()
    }

    fun y(percent: Double): Float =
        plot.bottom - (plot.height * (percent.coerceIn(0.0, 100.0) / 100.0)).toFloat()

    val startMillis: Long get() = fromMillis
    val endMillis: Long get() = toMillis
}

/**
 * Die Dämmerung als Hintergrund.
 *
 * Gezeichnet wird derselbe Faktor, den auch die Bewertung benutzt — die Fläche und die Zahl können
 * also nicht auseinanderlaufen.
 */
private fun DrawScope.drawTwilight(points: List<AstroHour>, scale: ChartScale, plot: PlotArea) {
    for (i in 0 until points.size - 1) {
        val left = scale.x(points[i].millis)
        val right = scale.x(points[i + 1].millis)
        val darkness = (
            AstroWeather.darknessFactor(points[i].sunAltitudeDeg) +
                AstroWeather.darknessFactor(points[i + 1].sunAltitudeDeg)
            ) / 2.0
        val alpha = ((1.0 - darkness) * 0.34).toFloat()
        if (alpha <= 0.01f) continue
        drawRect(
            color = TWILIGHT_COLOR.copy(alpha = alpha),
            topLeft = Offset(left, plot.top),
            size = Size((right - left).coerceAtLeast(1f), plot.height),
        )
    }
}

/** Ein schmaler Streifen oben, solange der Mond über dem Horizont steht; heller je voller er ist. */
private fun DrawScope.drawMoonBand(
    points: List<AstroHour>,
    scale: ChartScale,
    plot: PlotArea,
    paints: WeatherChartPaints,
) {
    val bandHeight = 5.dp.toPx()
    var drewLabel = false
    for (i in 0 until points.size - 1) {
        if (!points[i].moonUp || !points[i + 1].moonUp) continue
        val left = scale.x(points[i].millis)
        val right = scale.x(points[i + 1].millis)
        val fraction = (points[i].moonIllumination.fraction + points[i + 1].moonIllumination.fraction) / 2.0
        drawRect(
            color = StarWindowColors.AnchorPoint.copy(alpha = (0.18 + 0.62 * fraction).toFloat()),
            topLeft = Offset(left, plot.top),
            size = Size((right - left).coerceAtLeast(1f), bandHeight),
        )
        if (!drewLabel) {
            drawLabel("Mond über dem Horizont", left + 3.dp.toPx(), plot.top + bandHeight + 9.dp.toPx(), paints.moon)
            drewLabel = true
        }
    }
}

private fun DrawScope.drawGrid(
    scale: ChartScale,
    plot: PlotArea,
    paints: WeatherChartPaints,
    window: ChartWindow,
) {
    val gridColor = StarWindowColors.Graticule.copy(alpha = 0.24f)
    for (percent in listOf(0, 25, 50, 75, 100)) {
        val y = scale.y(percent.toDouble())
        drawLine(gridColor, Offset(plot.left, y), Offset(plot.right, y), strokeWidth = 1f)
        drawLabel("$percent", 2.dp.toPx(), y + 4.dp.toPx(), paints.axis)
    }

    val spanHours = (window.toMillis - window.fromMillis) / 3_600_000.0
    val hourStep = if (spanHours <= 10) 1 else 2
    val hourMillis = 3_600_000L
    var tick = (scale.startMillis / hourMillis + 1) * hourMillis
    while (tick <= scale.endMillis) {
        val zoned = Instant.ofEpochMilli(tick).atZone(window.zone)
        if (zoned.hour % hourStep == 0) {
            val x = scale.x(tick)
            drawLine(gridColor, Offset(x, plot.top), Offset(x, plot.bottom), strokeWidth = 1f)
            drawCenteredLabel(
                hourFormatter.withZone(window.zone).format(Instant.ofEpochMilli(tick)),
                x,
                plot.bottom + 14.dp.toPx(),
                paints.axis,
            )
        }
        tick += hourMillis
    }
}

/**
 * Die Bewölkung, von oben hereinhängend.
 *
 * Die Fläche reicht vom oberen Rand bis auf die Höhe „100 minus Bedeckung" — ihre Unterkante ist
 * also der freie Himmel und liegt auf derselben Prozentachse wie die Eignungskurve.
 */
private fun DrawScope.drawCloudBand(
    points: List<AstroHour>,
    scale: ChartScale,
    plot: PlotArea,
    paints: WeatherChartPaints,
) {
    // Ohne Bewölkungsangabe bleibt die Fläche leer statt eine Schätzung zu zeichnen; der Kasten am
    // Finger sagt an derselben Stelle „Bewölkung unbekannt", damit die Lücke nicht als Null durchgeht.
    val clearAt = { hour: AstroHour -> 100.0 - (hour.cloudPercent ?: 0.0) }

    val area = Path().apply {
        moveTo(scale.x(points.first().millis), plot.top)
        points.forEach { lineTo(scale.x(it.millis), scale.y(clearAt(it))) }
        lineTo(scale.x(points.last().millis), plot.top)
        close()
    }
    drawPath(area, CLOUD_FILL)

    val edge = Path().apply {
        points.forEachIndexed { index, hour ->
            val x = scale.x(hour.millis)
            val y = scale.y(clearAt(hour))
            if (index == 0) moveTo(x, y) else lineTo(x, y)
        }
    }
    drawPath(edge, CLOUD_EDGE, style = Stroke(width = 1.5.dp.toPx()))

    drawLabel("Bewölkung", plot.left + 4.dp.toPx(), plot.top + 22.dp.toPx(), paints.cloud)
}

/** Die Eignung als Kurve; über der Brauchbarkeitsschwelle bekommt sie Gewicht. */
private fun DrawScope.drawScoreCurve(
    points: List<AstroHour>,
    scale: ChartScale,
    plot: PlotArea,
    paints: WeatherChartPaints,
) {
    val threshold = scale.y(AstroNight.USABLE_SCORE.toDouble())
    drawLine(
        StarWindowColors.WindowStroke.copy(alpha = 0.35f),
        Offset(plot.left, threshold),
        Offset(plot.right, threshold),
        strokeWidth = 1f,
    )

    val path = Path()
    points.forEachIndexed { index, hour ->
        val x = scale.x(hour.millis)
        val y = scale.y(hour.score.toDouble())
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, StarWindowColors.WindowStroke, style = Stroke(width = 2.dp.toPx()))

    // Der brauchbare Teil ist dieselbe Reihe und behält die Farbe; er gewinnt nur an Gewicht.
    for (i in 0 until points.size - 1) {
        if (points[i].score < AstroNight.USABLE_SCORE || points[i + 1].score < AstroNight.USABLE_SCORE) continue
        drawLine(
            StarWindowColors.WindowStroke,
            Offset(scale.x(points[i].millis), scale.y(points[i].score.toDouble())),
            Offset(scale.x(points[i + 1].millis), scale.y(points[i + 1].score.toDouble())),
            strokeWidth = 4.dp.toPx(),
        )
    }

    drawLabel("Eignung", plot.left + 4.dp.toPx(), plot.bottom - 6.dp.toPx(), paints.score)
}

/** Anfang und Ende der astronomischen Dunkelheit, als die Schwellen, die sie sind. */
private fun DrawScope.drawDarknessMarkers(
    night: AstroNight,
    scale: ChartScale,
    plot: PlotArea,
    paints: WeatherChartPaints,
) {
    listOfNotNull(
        night.times.astronomicalDuskMillis?.let { it to "Dunkelheit" },
        night.times.astronomicalDawnMillis?.let { it to "Dämmerung" },
    ).forEach { (millis, label) ->
        if (millis < scale.startMillis || millis > scale.endMillis) return@forEach
        val x = scale.x(millis)
        drawLine(
            StarWindowColors.Muted.copy(alpha = 0.7f),
            Offset(x, plot.top),
            Offset(x, plot.bottom),
            strokeWidth = 1.dp.toPx(),
        )
        val width = paints.axis.measureText(label)
        drawLabel(
            label,
            (x - width / 2f).coerceIn(plot.left, plot.right - width),
            plot.bottom - 20.dp.toPx(),
            paints.axis,
        )
    }
}

private fun DrawScope.drawNow(
    nowMillis: Long,
    scale: ChartScale,
    plot: PlotArea,
    paints: WeatherChartPaints,
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

/**
 * Der abgefahrene Wert.
 *
 * Die Linie rastet auf die nächste Stützstelle ein, statt zwischen zwei Stunden zu interpolieren:
 * Das Modell rechnet Stundenwerte, und eine Zwischenzahl wäre erfunden.
 */
private fun DrawScope.drawCursor(
    cursorX: Float,
    points: List<AstroHour>,
    scale: ChartScale,
    plot: PlotArea,
    paints: WeatherChartPaints,
    window: ChartWindow,
) {
    val nearest = points.minByOrNull { abs(scale.x(it.millis) - cursorX) } ?: return
    val x = scale.x(nearest.millis)
    val clearY = scale.y(100.0 - (nearest.cloudPercent ?: 0.0))
    val scoreY = scale.y(nearest.score.toDouble())

    drawLine(
        StarWindowColors.Starlight.copy(alpha = 0.8f),
        Offset(x, plot.top),
        Offset(x, plot.bottom),
        strokeWidth = 1.dp.toPx(),
    )
    drawCircle(CLOUD_EDGE, 4.dp.toPx(), Offset(x, clearY))
    drawCircle(StarWindowColors.WindowStroke, 4.dp.toPx(), Offset(x, scoreY))
    drawCircle(StarWindowColors.Night, 1.8.dp.toPx(), Offset(x, scoreY))

    val lines = listOf(
        clockFormatter.withZone(window.zone).format(Instant.ofEpochMilli(nearest.millis)),
        nearest.cloudPercent?.let { "Bewölkung ${it.roundToInt()} %" } ?: "Bewölkung unbekannt",
        "Eignung ${nearest.score} %",
        if (nearest.moonUp) {
            "Mond ${nearest.moonAltitudeDeg.roundToInt()}°, ${nearest.moonIllumination.percent.roundToInt()} %"
        } else {
            "Mond unter dem Horizont"
        },
    )
    drawReadout(lines, x, plot, paints)
}

/** Der Kasten neben dem Finger. Er weicht zur Seite aus, statt am Rand abgeschnitten zu werden. */
private fun DrawScope.drawReadout(
    lines: List<String>,
    cursorX: Float,
    plot: PlotArea,
    paints: WeatherChartPaints,
) {
    val padding = 6.dp.toPx()
    val lineHeight = 13.dp.toPx()
    val width = lines.maxOf { paints.readout.measureText(it) } + 2 * padding
    val height = lines.size * lineHeight + padding

    val gap = 10.dp.toPx()
    val left = if (cursorX + gap + width <= plot.right) cursorX + gap else cursorX - gap - width
    val boxLeft = left.coerceIn(plot.left, (plot.right - width).coerceAtLeast(plot.left))
    val boxTop = plot.top + 8.dp.toPx()

    drawRect(
        color = StarWindowColors.NightSurfaceHigh.copy(alpha = 0.94f),
        topLeft = Offset(boxLeft, boxTop),
        size = Size(width, height),
    )
    drawRect(
        color = StarWindowColors.Graticule.copy(alpha = 0.5f),
        topLeft = Offset(boxLeft, boxTop),
        size = Size(width, height),
        style = Stroke(width = 1f),
    )
    lines.forEachIndexed { index, text ->
        drawLabel(text, boxLeft + padding, boxTop + padding + (index + 1) * lineHeight - 3.dp.toPx(), paints.readout)
    }
}

private fun DrawScope.drawLabel(text: String, x: Float, y: Float, paint: Paint) {
    if (text.isEmpty()) return
    drawIntoCanvas { it.nativeCanvas.drawText(text, x, y, paint) }
}

private fun DrawScope.drawCenteredLabel(text: String, centerX: Float, y: Float, paint: Paint) {
    drawLabel(text, centerX - paint.measureText(text) / 2f, y, paint)
}

/** Grau-blau: die Wolke ist kein Messwert mit Wertung, sondern das, was im Weg steht. */
private val CLOUD_FILL = Color(0xFF7C8AA6).copy(alpha = 0.45f)
private val CLOUD_EDGE = Color(0xFFA9B6CE)

/** Der aufgehellte Himmel der Dämmerung. */
private val TWILIGHT_COLOR = Color(0xFF6E8CE8)

private val hourFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH")
private val clockFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE HH:mm")

private class WeatherChartPaints(density: Density) {
    private val scale = density.density

    val axis = paint(StarWindowColors.Muted, 10f)
    val cloud = paint(CLOUD_EDGE, 10f)
    val score = paint(StarWindowColors.WindowStroke, 10f)
    val moon = paint(StarWindowColors.AnchorPoint, 9f)
    val now = paint(StarWindowColors.Crosshair, 10f)
    val readout = paint(StarWindowColors.Starlight, 11f)

    private fun paint(color: Color, sizeSp: Float) = Paint().apply {
        isAntiAlias = true
        this.color = color.toArgb()
        textSize = sizeSp * scale
        setShadowLayer(3f * scale, 0f, 0f, android.graphics.Color.BLACK)
    }
}
