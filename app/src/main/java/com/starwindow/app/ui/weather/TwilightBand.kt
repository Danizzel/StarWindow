package com.starwindow.app.ui.weather

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.starwindow.app.core.astro.NightTimes
import com.starwindow.app.ui.theme.StarWindowColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max

/**
 * Die Nacht als Band, Mitternacht in der Mitte.
 *
 * Was hier gezeigt wird, steht sonst als acht Uhrzeiten untereinander — und acht Uhrzeiten sind
 * eine Tabelle, keine Nacht. Als Band wird aus denselben Zahlen eine **Form**: Man sieht auf einen
 * Blick, ob die Dunkelheit ein breiter Block oder ein schmaler Spalt ist, ob sie mittig liegt oder
 * an den Morgen gedrängt, und wie viel von der Nacht die Dämmerung frisst. Das ist die Frage, mit
 * der man auf die Uhrzeiten schaut, und die Tabelle beantwortet sie erst nach dem Kopfrechnen.
 *
 * **Mitternacht liegt fest in der Mitte.** Das Fenster wird symmetrisch um sie gelegt und so weit
 * aufgezogen, dass Sonnenunter- und -aufgang hineinpassen. Damit heißt „links vom Mittelpunkt"
 * immer „vor Mitternacht", über alle Jahreszeiten hinweg — und die Asymmetrie einer Nacht, deren
 * Dunkelheit erst um zwei Uhr beginnt, wird sichtbar, statt sich im Maßstab zu verstecken.
 *
 * **Die Farben sind die Dämmerungsstufen selbst.** Der Verlauf hat seine Stützstellen genau an den
 * acht Grenzen aus [NightTimes], nicht an geschätzten Zwischenwerten: Wo das Band die Farbe
 * wechselt, steht auch die Markierung, und beides kommt aus derselben Zahl.
 */
@Composable
fun TwilightBand(
    times: NightTimes,
    zone: ZoneId,
    modifier: Modifier = Modifier,
    nowMillis: Long = System.currentTimeMillis(),
) {
    val window = bandWindow(times) ?: return
    val (fromMillis, toMillis) = window
    val span = (toMillis - fromMillis).toFloat()

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(modifier = Modifier.fillMaxWidth().height(56.dp)) {
            val width = size.width
            fun x(millis: Long) = ((millis - fromMillis) / span * width).coerceIn(0f, width)

            drawRect(
                brush = Brush.horizontalGradient(
                    *gradientStops(times, fromMillis, span).toTypedArray()
                ),
                size = Size(width, size.height),
            )

            // Die acht Grenzen als Striche. Sie stehen auf dem Band und nicht darunter, weil sie
            // die Farbwechsel markieren — daneben wären sie eine zweite Behauptung.
            boundaries(times).forEach { (millis, major) ->
                val position = x(millis)
                drawLine(
                    color = Color.White.copy(alpha = if (major) 0.75f else 0.4f),
                    start = Offset(position, if (major) 0f else size.height * 0.28f),
                    end = Offset(position, size.height),
                    strokeWidth = if (major) 1.6f else 1f,
                )
            }

            // Mitternacht: der einzige Strich, der nicht aus dem Himmel kommt, sondern aus der Uhr.
            val midnight = x((fromMillis + toMillis) / 2)
            drawLine(
                color = Color.White.copy(alpha = 0.28f),
                start = Offset(midnight, 0f),
                end = Offset(midnight, size.height),
                strokeWidth = 1f,
            )

            if (nowMillis in fromMillis..toMillis) {
                val position = x(nowMillis)
                drawLine(
                    color = StarWindowColors.Crosshair,
                    start = Offset(position, 0f),
                    end = Offset(position, size.height),
                    strokeWidth = 2.5f,
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            BandCaption(times.sunsetMillis?.let { clock(it, zone) } ?: "kein Untergang")
            Spacer(Modifier.weight(1f))
            BandCaption("Mitternacht", emphasised = true)
            Spacer(Modifier.weight(1f))
            BandCaption(times.sunriseMillis?.let { clock(it, zone) } ?: "kein Aufgang")
        }

        Spacer(Modifier.height(8.dp))
        // Alle acht Grenzen ausgeschrieben, in zwei Zeilen statt unter den Strichen: Vier davon
        // liegen innerhalb von zwei Stunden, ihre Beschriftungen lägen im Band übereinander.
        //
        // Beschriftet wird, was **beginnt**, nicht was endet — auch wenn beides dieselbe Uhrzeit
        // ist. „Nautisch ab 20:43" sagt, welche Farbe ab dort im Band steht; „bürgerliche
        // Dämmerung endet 20:43" sagt dasselbe und lässt den Leser die Farbe selbst zuordnen.
        StageLine(
            title = "Abend",
            entries = listOfNotNull(
                times.sunsetMillis?.let { "bürgerl." to it },
                times.civilDuskMillis?.let { "naut." to it },
                times.nauticalDuskMillis?.let { "astron." to it },
                times.astronomicalDuskMillis?.let { "dunkel" to it },
            ),
            zone = zone,
        )
        StageLine(
            title = "Morgen",
            entries = listOfNotNull(
                times.astronomicalDawnMillis?.let { "astron." to it },
                times.nauticalDawnMillis?.let { "naut." to it },
                times.civilDawnMillis?.let { "bürgerl." to it },
                times.sunriseMillis?.let { "Tag" to it },
            ),
            zone = zone,
        )
    }
}

@Composable
private fun BandCaption(text: String, emphasised: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (emphasised) FontWeight.SemiBold else FontWeight.Normal,
        color = if (emphasised) StarWindowColors.Starlight else StarWindowColors.Muted,
    )
}

@Composable
private fun StageLine(title: String, entries: List<Pair<String, Long>>, zone: ZoneId) {
    if (entries.isEmpty()) return
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = StarWindowColors.Starlight,
            modifier = Modifier.width(52.dp),
        )
        Text(
            text = entries.joinToString(" · ") { (label, millis) ->
                "$label ab ${clock(millis, zone)}"
            },
            style = MaterialTheme.typography.labelSmall,
            color = StarWindowColors.Muted,
        )
    }
}

/**
 * Das Zeitfenster des Bandes: symmetrisch um Mitternacht, weit genug für die ganze Nacht.
 *
 * Die halbe Breite richtet sich nach dem weiter entfernten der beiden Horizontdurchgänge, plus
 * einer knappen Stunde Rand. Ohne die Symmetrie läge Mitternacht mal links, mal rechts — und der
 * Vergleich zweier Nächte, für den das Band überhaupt da ist, wäre dahin.
 */
private fun bandWindow(times: NightTimes): Pair<Long, Long>? {
    val midnight = (times.fromMillis + times.toMillis) / 2
    val edges = listOfNotNull(
        times.sunsetMillis,
        times.sunriseMillis,
        times.astronomicalDuskMillis,
        times.astronomicalDawnMillis,
    )
    val reach = edges.maxOfOrNull { abs(it - midnight) } ?: DEFAULT_HALF_WIDTH_MILLIS
    val half = max(reach + MARGIN_MILLIS, MIN_HALF_WIDTH_MILLIS)
        .coerceAtMost(MAX_HALF_WIDTH_MILLIS)
    return (midnight - half) to (midnight + half)
}

/** Die Grenzen mit ihrer Wichtigkeit: Auf- und Untergang tragen den kräftigeren Strich. */
private fun boundaries(times: NightTimes): List<Pair<Long, Boolean>> = listOfNotNull(
    times.sunsetMillis?.let { it to true },
    times.civilDuskMillis?.let { it to false },
    times.nauticalDuskMillis?.let { it to false },
    times.astronomicalDuskMillis?.let { it to true },
    times.astronomicalDawnMillis?.let { it to true },
    times.nauticalDawnMillis?.let { it to false },
    times.civilDawnMillis?.let { it to false },
    times.sunriseMillis?.let { it to true },
)

/**
 * Die Farbstützstellen des Verlaufs.
 *
 * Jede Grenze bekommt zwei dicht beieinanderliegende Stützstellen — die Farbe der Stufe davor und
 * die der Stufe danach. Ein weicher Verlauf über die ganze Breite sähe hübscher aus und wäre eine
 * Lüge: Zwischen nautischer und astronomischer Dämmerung liegt ein Faktor in der
 * Himmelshelligkeit, kein sanfter Übergang, und die Markierung soll dort stehen, wo sich auch die
 * Farbe ändert.
 *
 * Bleibt eine Grenze aus, fällt ihre Stützstelle weg und die Nachbarfarben laufen ineinander — in
 * einer Sommernacht, die nie astronomisch dunkel wird, ist das genau die richtige Aussage.
 */
private fun gradientStops(
    times: NightTimes,
    fromMillis: Long,
    span: Float,
): List<Pair<Float, Color>> {
    fun at(millis: Long) = ((millis - fromMillis) / span).coerceIn(0f, 1f)

    val stops = mutableListOf<Pair<Float, Color>>()
    stops += 0f to DAY

    times.sunsetMillis?.let { stops += at(it) to DAY; stops += at(it) + EPSILON to CIVIL }
    times.civilDuskMillis?.let { stops += at(it) to CIVIL; stops += at(it) + EPSILON to NAUTICAL }
    times.nauticalDuskMillis?.let { stops += at(it) to NAUTICAL; stops += at(it) + EPSILON to ASTRONOMICAL }
    times.astronomicalDuskMillis?.let { stops += at(it) to ASTRONOMICAL; stops += at(it) + EPSILON to NIGHT }

    times.astronomicalDawnMillis?.let { stops += at(it) to NIGHT; stops += at(it) + EPSILON to ASTRONOMICAL }
    times.nauticalDawnMillis?.let { stops += at(it) to ASTRONOMICAL; stops += at(it) + EPSILON to NAUTICAL }
    times.civilDawnMillis?.let { stops += at(it) to NAUTICAL; stops += at(it) + EPSILON to CIVIL }
    times.sunriseMillis?.let { stops += at(it) to CIVIL; stops += at(it) + EPSILON to DAY }

    stops += 1f to DAY

    // Der Verlauf verlangt streng steigende Stützstellen; durch das Epsilon kann eine knapp hinter
    // der nächsten landen, wenn zwei Grenzen dicht beieinanderliegen.
    var previous = -1f
    return stops.map { (position, color) ->
        val fixed = max(position, previous + 1e-5f)
        previous = fixed
        fixed.coerceAtMost(1f) to color
    }
}

/** Wie weit hinter einer Grenze die neue Farbe voll da ist. Ein Prozent der Breite: eine Kante. */
private const val EPSILON = 0.006f

private const val HOUR = 3_600_000L
private const val DEFAULT_HALF_WIDTH_MILLIS = 6 * HOUR
private const val MIN_HALF_WIDTH_MILLIS = 4 * HOUR
private const val MAX_HALF_WIDTH_MILLIS = 12 * HOUR
private const val MARGIN_MILLIS = 45 * 60_000L

/**
 * Die Farben der Stufen.
 *
 * Vom Tageshimmelblau über das Orange der bürgerlichen Dämmerung ins Tiefblaue und schließlich ins
 * fast Schwarze. Das Orange steht dort, wo es am Himmel auch steht — knapp nach Sonnenuntergang —
 * und nicht als Verzierung: Es ist die einzige Stufe, die man von drinnen aus dem Fenster erkennt.
 */
private val DAY = Color(0xFF4A87C8)
private val CIVIL = Color(0xFFD98A4A)
private val NAUTICAL = Color(0xFF2B4A86)
private val ASTRONOMICAL = Color(0xFF1B2750)
private val NIGHT = Color(0xFF090C1C)

private val bandClock = DateTimeFormatter.ofPattern("HH:mm")

private fun clock(millis: Long, zone: ZoneId): String =
    bandClock.withZone(zone).format(Instant.ofEpochMilli(millis))
