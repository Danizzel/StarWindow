package com.starwindow.app.ui.weather

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.starwindow.app.domain.AstroNight
import com.starwindow.app.ui.components.SectionCard
import com.starwindow.app.ui.components.StatusPill
import com.starwindow.app.ui.theme.MetricTextStyle
import com.starwindow.app.ui.theme.StarWindowColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Die Karte, mit der die Wetteransicht aufmacht.
 *
 * Drei Dinge, und alle drei beantworten dieselbe Frage aus einem anderen Blickwinkel: **Wie gut
 * wird die Nacht** (die Bewertung), **wie hell ist der Störfaktor, der sich nicht wegwarten lässt**
 * (der Mond), und **wann genau ist eigentlich Nacht** (das Band). Die Zahlen dazu standen vorher
 * schon in der Ansicht — verteilt über die Kurve, die Datentafel und die Zeile mit dem Mondanteil.
 * Zusammengezogen beantworten sie die Frage in einer Sekunde statt nach dreimal Scrollen.
 *
 * Die Bewertung ist bewusst die große Zahl. Sie ist das Einzige auf dem Bildschirm, das eine
 * Entscheidung ersetzt; alles darunter ist die Begründung, die man liest, wenn man ihr nicht
 * glaubt — und die Ansicht ist so gebaut, dass man ihr nicht glauben muss.
 */
@Composable
fun NightRatingCard(
    night: AstroNight,
    bortleLevel: Int?,
    bortleIsManual: Boolean,
    onEditBortle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = LocalDate.now(night.zone)
    val rating = night.stargazingRating
    val tint = ratingColor(rating, night)

    SectionCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (night.date == today) "Heute Abend" else "Diese Nacht",
                    style = MaterialTheme.typography.labelMedium,
                    color = StarWindowColors.WindowStroke,
                )
                Text(
                    text = dateFormatter.format(night.date),
                    style = MaterialTheme.typography.titleMedium,
                    color = StarWindowColors.Starlight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            bortleLevel?.let { level ->
                StatusPill(
                    text = "Bortle $level" + if (bortleIsManual) "" else " (gesch.)",
                    tint = bortleTint(level),
                    modifier = Modifier.clickable(onClick = onEditBortle),
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$rating %",
                    style = MetricTextStyle,
                    color = tint,
                )
                Text(
                    text = night.ratingLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = tint,
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { rating / 100f },
                    modifier = Modifier.fillMaxWidth(0.82f),
                    color = tint,
                    trackColor = StarWindowColors.NightSurfaceHigh,
                )
            }

            Spacer(Modifier.width(12.dp))

            night.moonIllumination?.let { illumination ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    MoonPhaseDisc(illumination = illumination, size = 76.dp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "${illumination.percent.roundToInt()} %",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = StarWindowColors.Starlight,
                    )
                    Text(
                        text = illumination.phaseName,
                        style = MaterialTheme.typography.labelSmall,
                        color = StarWindowColors.Muted,
                        maxLines = 1,
                    )
                }
            }
        }

        Text(
            text = summaryOf(night),
            style = MaterialTheme.typography.bodySmall,
            color = StarWindowColors.Muted,
        )

        Spacer(Modifier.height(2.dp))
        TwilightBand(times = night.times, zone = night.zone)
    }
}

/**
 * Die Farbe der Zahl.
 *
 * Dieselben Schwellen wie beim Wort darunter, damit nicht „Gut" in Rot dasteht. Eine Nacht ohne
 * Dunkelheit ist grau und nicht rot: Da ist nichts schiefgegangen, es ist Juni.
 */
private fun ratingColor(rating: Int, night: AstroNight): Color = when {
    night.times.darkDurationMillis <= 0L -> StarWindowColors.Muted
    rating >= 55 -> StarWindowColors.WindowStroke
    rating >= 35 -> StarWindowColors.AnchorPoint
    else -> StarWindowColors.Crosshair
}

/** Je heller der Himmel, desto wärmer die Plakette — dieselbe Skala wie überall in der App. */
private fun bortleTint(level: Int): Color = when {
    level <= 3 -> StarWindowColors.WindowStroke
    level <= 5 -> StarWindowColors.CatalogMarker
    level <= 7 -> StarWindowColors.AnchorPoint
    else -> StarWindowColors.Crosshair
}

private val dateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMAN)
