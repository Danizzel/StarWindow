package com.starwindow.app.ui.planning

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.starwindow.app.domain.DarknessQuality
import com.starwindow.app.domain.ObservationNight
import com.starwindow.app.ui.theme.StarWindowColors
import com.starwindow.app.ui.windows.formatClock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * When to photograph one object, over the coming year.
 *
 * The screen exists because the obvious answer is the wrong one. "When is it highest" is easy and
 * useless on its own: an object at its yearly best altitude in June is unphotographable from
 * Central Europe, because there is barely any darkness that month. What matters is the **overlap**
 * — the object high enough and the sky dark enough at the same time — and the year chart at the top
 * shows exactly that quantity, night by night, so the season reads at a glance before a single row
 * is scrolled.
 */
@Composable
fun PlanningScreen(
    viewModel: PlanningViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Planung", style = MaterialTheme.typography.titleLarge)
                state.obj?.let {
                    Text(
                        it.displayName,
                        style = MaterialTheme.typography.labelMedium,
                        color = StarWindowColors.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        when {
            state.isLoading -> Box_Centered { CircularProgressIndicator() }

            state.error != null -> Box_Centered {
                Text(
                    requireNotNull(state.error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = StarWindowColors.Crosshair,
                    textAlign = TextAlign.Center,
                )
            }

            else -> LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { PlaceLine(state) }
                item { YearChart(state.nights) }
                item { SeasonSummary(state) }

                if (!state.hasUsableNights) {
                    item {
                        Text(
                            "Von diesem Ort aus steht das Objekt in keiner Nacht des kommenden " +
                                "Jahres hoch genug über dem Horizont, um es zu fotografieren.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = StarWindowColors.Muted,
                        )
                    }
                } else {
                    item {
                        Text(
                            "Beste Nächte",
                            style = MaterialTheme.typography.titleSmall,
                            color = StarWindowColors.AnchorPoint,
                        )
                    }
                    items(state.bestNights.size) { index ->
                        val night = state.bestNights[index]
                        NightRow(
                            night = night,
                            zoneLabel = state.zone.id,
                            planned = night.date.toEpochDay() in state.plannedDates,
                            onToggle = { viewModel.togglePlan(night) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Box_Centered(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun PlaceLine(state: PlanningUiState) {
    Text(
        text = "Gerechnet für ${state.placeLabel} · Zeiten in ${state.zone.id}",
        style = MaterialTheme.typography.labelSmall,
        color = StarWindowColors.Muted,
    )
}

/**
 * A year of nights as a bar per night: how many hours of exposure each one allows.
 *
 * One pixel-thin bar per night rather than a smoothed curve, because the quantity really is
 * per-night and jagged — the Moon cycles through it every four weeks, and flattening that away
 * would hide the very thing someone is choosing between.
 */
@Composable
private fun YearChart(nights: List<ObservationNight>) {
    if (nights.isEmpty()) return
    val density = LocalDensity.current
    val labels = remember(nights) { monthLabels(nights) }

    Column {
        Text(
            "Belichtbare Stunden je Nacht",
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(6.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(StarWindowColors.NightSurface)
                .padding(horizontal = 6.dp, vertical = 8.dp)
        ) {
            val maxHours = nights.maxOf { it.usableHours }.coerceAtLeast(1.0)
            val barWidth = size.width / nights.size

            // Gridlines every two hours, so the bars can be read as a quantity, not just compared.
            var hour = 2.0
            while (hour <= maxHours) {
                val y = size.height * (1.0 - hour / maxHours).toFloat()
                drawLine(
                    color = StarWindowColors.Graticule,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = with(density) { 0.5.dp.toPx() },
                )
                hour += 2.0
            }

            nights.forEachIndexed { index, night ->
                val height = (size.height * (night.usableHours / maxHours)).toFloat()
                if (height <= 0f) return@forEachIndexed
                drawRect(
                    color = barColor(night),
                    topLeft = Offset(index * barWidth, size.height - height),
                    size = Size(barWidth.coerceAtLeast(1f), height),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            labels.forEach { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = StarWindowColors.Muted,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Grün: mondfrei · Bernstein: Mond stört · Grau: nur nautische Dämmerung",
            style = MaterialTheme.typography.labelSmall,
            color = StarWindowColors.Muted,
        )
    }
}

/**
 * The colour carries what the height cannot: *why* a night is short.
 *
 * Two nights of four hours each are not the same night if one of them has a full Moon in it, and
 * the bar height alone would say they are.
 */
private fun barColor(night: ObservationNight): Color = when {
    night.darkness == DarknessQuality.NAUTICAL -> StarWindowColors.Muted
    night.moonInterferes -> StarWindowColors.AnchorPoint
    else -> StarWindowColors.WindowStroke
}

/** Twelve evenly spaced month initials under the chart. */
private fun monthLabels(nights: List<ObservationNight>): List<String> {
    val first = nights.first().date
    return (0 until 12).map { offset ->
        first.plusMonths(offset.toLong())
            .month
            .getDisplayName(TextStyle.NARROW, Locale.GERMAN)
    }
}

@Composable
private fun SeasonSummary(state: PlanningUiState) {
    val season = state.season
    val peak = state.peak
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(StarWindowColors.NightSurface)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when {
            state.isYearRound -> Text(
                text = "Ganzjährig erreichbar – von hier aus gibt es keinen Monat, in dem das " +
                    "Objekt ausfällt.",
                style = MaterialTheme.typography.bodyMedium,
                color = StarWindowColors.Starlight,
            )
            season != null -> Text(
                text = "Saison: ${dateFormat.format(season.start)} bis " +
                    dateFormat.format(season.endInclusive),
                style = MaterialTheme.typography.bodyMedium,
                color = StarWindowColors.Starlight,
            )
        }
        if (peak != null) {
            Text(
                text = "Am besten in der Nacht auf den ${dateFormat.format(peak.date.plusDays(1))}: " +
                    "%.1f Stunden belichtbar, bis %.0f° hoch.".format(peak.usableHours, peak.bestAltitudeDeg),
                style = MaterialTheme.typography.bodyMedium,
                color = StarWindowColors.Starlight,
            )
        }
        Text(
            text = "„Belichtbar\" heißt: das Objekt steht über " +
                "${com.starwindow.app.domain.ObservationPlanner.MIN_USEFUL_ALTITUDE_DEG.toInt()}° " +
                "und der Himmel ist zugleich dunkel. Im Sommer ist das kurz oder gar nicht, im " +
                "Winter lang – deshalb ist die höchste Stellung allein noch kein guter Termin.",
            style = MaterialTheme.typography.labelSmall,
            color = StarWindowColors.Muted,
        )
    }
}

/** One candidate night, with everything needed to decide and one tap to keep it. */
@Composable
private fun NightRow(
    night: ObservationNight,
    zoneLabel: String,
    planned: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (planned) StarWindowColors.NightSurfaceHigh else StarWindowColors.NightSurface)
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                // A night belongs to the evening it starts on, which is how people speak about it.
                text = "Nacht auf ${weekdayFormat.format(night.date.plusDays(1))}, " +
                    dateFormat.format(night.date.plusDays(1)),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = StarWindowColors.Starlight,
            )
            Text(
                text = buildString {
                    append("%.1f h belichtbar".format(night.usableHours))
                    append(" · bis %.0f°".format(night.bestAltitudeDeg))
                    night.bestMillis?.let { append(" gegen ").append(formatClock(it)) }
                },
                style = MaterialTheme.typography.labelSmall,
                color = StarWindowColors.Muted,
            )
            Text(
                text = buildString {
                    append("Mond %.0f %%".format(night.moonIlluminationPercent))
                    append(if (night.moonInterferes) ", stört" else ", stört nicht")
                    if (night.darkness != DarknessQuality.ASTRONOMICAL) {
                        append(" · ").append(night.darkness.label)
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (night.moonInterferes) StarWindowColors.AnchorPoint else StarWindowColors.Muted,
            )
        }
        Icon(
            imageVector = if (planned) Icons.Filled.CheckCircle else Icons.Outlined.AddCircleOutline,
            contentDescription = if (planned) "Aus dem Kalender nehmen" else "In den Kalender",
            tint = if (planned) StarWindowColors.WindowStroke else StarWindowColors.Muted,
            modifier = Modifier.size(26.dp),
        )
    }
}

private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d. MMM yyyy", Locale.GERMAN)
private val weekdayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE", Locale.GERMAN)

private fun DateTimeFormatter.format(date: LocalDate): String = date.format(this)
