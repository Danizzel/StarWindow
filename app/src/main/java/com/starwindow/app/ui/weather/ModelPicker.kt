package com.starwindow.app.ui.weather

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.starwindow.app.data.weather.WeatherModel
import com.starwindow.app.data.weather.WeatherModelStatus
import com.starwindow.app.ui.theme.StarWindowColors
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Die Modellauswahl.
 *
 * Warum sie überhaupt sichtbar ist und nicht in den Einstellungen verschwindet: Das Modell ist bei
 * dieser Frage keine Voreinstellung, sondern Teil der Antwort. „Morgen Abend klar" aus einem
 * 25-km-Gitter und dasselbe aus einem 2-km-Gitter sind zwei verschiedene Aussagen, und wer die
 * Nacht danach plant, muss sehen können, welche er gerade liest — samt Alter des Laufs.
 *
 * Sortiert ist nach Maschenweite, das feinste zuerst. Das ist die Reihenfolge, in der man sucht,
 * wenn es um heute Abend geht; für die Zwei-Wochen-Frage steht ECMWF am Ende und ist voreingestellt.
 */
@Composable
fun ModelRow(
    state: WeatherUiState,
    onSelect: (WeatherModel) -> Unit,
    onOpenDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(WeatherModel.ORDERED, key = { it.name }) { model ->
                val covered = state.covers(model)
                FilterChip(
                    selected = model == state.model,
                    enabled = covered || model == state.model,
                    onClick = { onSelect(model) },
                    label = { Text("${model.label} · ${model.resolutionLabel}") },
                    leadingIcon = if (model == state.model) {
                        {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    } else {
                        null
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = StarWindowColors.NightSurfaceHigh,
                        selectedLabelColor = StarWindowColors.WindowStroke,
                        selectedLeadingIconColor = StarWindowColors.WindowStroke,
                    ),
                )
            }
        }
        ModelStatusLine(state = state, onOpenDetails = onOpenDetails)
    }
}

/**
 * Eine Zeile unter der Auswahl: welches Modell, wie alt, wie weit.
 *
 * Das Alter steht hier und nicht nur im Dialog, weil es die Zahl ist, die stillschweigend
 * veraltet. Ein Lauf von vor zehn Stunden sieht auf dem Bildschirm genauso aus wie einer von vor
 * zehn Minuten — außer, es steht daneben.
 */
@Composable
private fun ModelStatusLine(state: WeatherUiState, onOpenDetails: () -> Unit) {
    val status = state.selectedStatus
    val model = state.model

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenDetails)
            .padding(start = 16.dp, end = 4.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${model.provider} ${model.label} · ${model.coverage}",
                style = MaterialTheme.typography.labelSmall,
                color = StarWindowColors.Muted,
            )
            Text(
                text = buildString {
                    if (status?.runMillis != null) {
                        append("Lauf ").append(utcClock(status.runMillis))
                        status.ageMillis()?.let { append(" · aktualisiert ").append(formatAge(it)) }
                    } else {
                        append("Lauf-Zeitpunkt nicht abrufbar")
                    }
                    // „Vorhersage bis" und das „Lauf bis" im Dialog sind zwei verschiedene Zahlen:
                    // Die Schnittstelle setzt mehrere Läufe zusammen und reicht darum weiter als
                    // der jüngste allein. Beide Beschriftungen sagen ausdrücklich, welche gemeint ist.
                    state.forecastEndMillis?.let {
                        append(" · Vorhersage bis ").append(localDay(it, state.zone))
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (status?.isOverdue() == true) {
                    StarWindowColors.AnchorPoint
                } else {
                    StarWindowColors.Muted
                },
            )
        }
        IconButton(onClick = onOpenDetails) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = "Modelle vergleichen",
                tint = StarWindowColors.Muted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Alle Modelle nebeneinander, mit dem, was sie unterscheidet — und wann sie zuletzt gerechnet haben. */
@Composable
fun ModelDialog(
    state: WeatherUiState,
    onSelect: (WeatherModel) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StarWindowColors.NightSurface,
        title = { Text("Wettermodell") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "Feines Gitter heißt kurze Reichweite — das ist keine Einstellung, " +
                        "sondern eine Abwägung. Für heute Abend das oberste, für die Planung das " +
                        "unterste.",
                    style = MaterialTheme.typography.bodySmall,
                    color = StarWindowColors.Muted,
                )
                Spacer(Modifier.height(10.dp))
                WeatherModel.ORDERED.forEach { model ->
                    ModelDetailRow(
                        model = model,
                        status = state.modelStatuses[model],
                        selected = model == state.model,
                        covered = state.covers(model),
                        placeName = state.place?.name,
                        zone = state.zone,
                        onClick = { onSelect(model) },
                    )
                    HorizontalDivider(color = StarWindowColors.NightSurfaceHigh)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
    )
}

@Composable
private fun ModelDetailRow(
    model: WeatherModel,
    status: WeatherModelStatus?,
    selected: Boolean,
    covered: Boolean,
    placeName: String?,
    zone: ZoneId,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = covered || selected, onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = model.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) StarWindowColors.WindowStroke else StarWindowColors.Starlight,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = model.resolutionLabel,
                style = MaterialTheme.typography.labelMedium,
                color = StarWindowColors.CatalogMarker,
            )
        }
        Text(
            text = "${model.provider} · ${model.coverage}",
            style = MaterialTheme.typography.labelSmall,
            color = StarWindowColors.Muted,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = model.about,
            style = MaterialTheme.typography.bodySmall,
            color = StarWindowColors.Starlight,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = when {
                !covered -> "Rechnet nicht über ${placeName ?: "diesem Ort"}."
                else -> runSummary(status, zone)
            },
            style = MaterialTheme.typography.labelSmall,
            color = when {
                !covered -> StarWindowColors.Crosshair
                status?.isOverdue() == true -> StarWindowColors.AnchorPoint
                else -> StarWindowColors.WindowStroke
            },
        )
    }
}

/** „Lauf 18:00 UTC · aktualisiert vor 1 h 20 min · alle 3 h neu · Lauf bis Mi 27.08., 19" */
private fun runSummary(status: WeatherModelStatus?, zone: ZoneId): String {
    if (status?.runMillis == null) return "Lauf-Zeitpunkt nicht abrufbar"
    return buildString {
        append("Lauf ").append(utcClock(status.runMillis))
        status.ageMillis()?.let { append(" · aktualisiert ").append(formatAge(it)) }
        status.updateIntervalSeconds?.let { append(" · ").append(formatInterval(it)) }
        // Ausdrücklich „dieser Lauf": Der DWD schiebt zwischen die langen Läufe kurze auf 30
        // Stunden, und die Vorhersage reicht dann trotzdem weiter, weil sie mehrere zusammensetzt.
        status.dataEndMillis?.let { append(" · dieser Lauf bis ").append(localDay(it, zone)) }
        // Nur erwähnen, wenn das Modell gröber als stündlich rechnet: Die Kurve zeigt Stundenwerte,
        // und dass die bei ECMWF interpoliert sind, gehört dazugesagt.
        status.stepSeconds?.takeIf { it > 3600 }?.let {
            append(" · Rohdaten alle ").append(it / 3600).append(" h")
        }
    }
}

/** Modellläufe heißen nach ihrer UTC-Stunde („der 12z-Lauf"); so werden sie auch angeschrieben. */
private fun utcClock(millis: Long): String =
    utcFormatter.format(Instant.ofEpochMilli(millis)) + " UTC"

private fun localDay(millis: Long, zone: ZoneId): String =
    dayFormatter.withZone(zone).format(Instant.ofEpochMilli(millis))

/** „vor 12 min", „vor 3 h 05 min", „vor 2 Tagen" — grob wird es erst, wenn genau nicht mehr hilft. */
internal fun formatAge(millis: Long): String {
    val minutes = millis / 60_000
    return when {
        minutes < 1 -> "gerade eben"
        minutes < 60 -> "vor $minutes min"
        minutes < 24 * 60 -> "vor %d h %02d min".format(minutes / 60, minutes % 60)
        else -> "vor ${minutes / (24 * 60)} Tagen"
    }
}

private fun formatInterval(seconds: Int): String = when {
    seconds <= 3600 -> "stündlich neu"
    seconds % 3600 == 0 -> "alle ${seconds / 3600} h neu"
    else -> "alle ${seconds / 60} min neu"
}

private val utcFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC)

private val dayFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE dd.MM., HH", Locale.GERMAN)
