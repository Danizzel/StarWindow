package com.starwindow.app.ui.planning

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.starwindow.app.data.planning.WatchedObject
import com.starwindow.app.data.planning.WatchScheduler
import com.starwindow.app.data.planning.WeatherDemand
import com.starwindow.app.ui.theme.StarWindowColors
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Die Bedingung hinter einer Vormerkung, zum Nachschärfen.
 *
 * Drei Stellschrauben, und keine mehr. Sie decken die drei Fragen ab, die sich beim Vormerken
 * tatsächlich unterscheiden: **wie hoch** muss es stehen (ein Kugelsternhaufen verträgt den Dunst
 * über der Stadt, eine schwache Galaxie nicht), **wie lange** muss das Fenster sein (eine halbe
 * Stunde für ein Handy, zwei für eine Belichtungsreihe), und **wie sicher** muss das Wetter sein
 * (wer eine Stunde fährt, will keine Lücke). Alles Weitere — Mondphase, Bewölkungsgrad,
 * Taupunkt — steckt schon in der Nachtbewertung und noch einmal einzeln danach zu fragen hieße,
 * dieselbe Entscheidung zweimal zu stellen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchSheet(
    watch: WatchedObject,
    entry: WatchEntry?,
    notificationsAllowed: Boolean,
    onRequestPermission: () -> Unit,
    onChange: ((WatchedObject) -> WatchedObject) -> Unit,
    onOpenObject: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = StarWindowColors.NightSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                Text(
                    watch.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = StarWindowColors.Starlight,
                )
                Text(
                    text = "vorgemerkt – die App meldet sich, wenn es passt",
                    style = MaterialTheme.typography.labelMedium,
                    color = StarWindowColors.AnchorPoint,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(StarWindowColors.Night)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Geprüft wird jeden Nachmittag um " +
                        "${timeFormat.format(WatchScheduler.CHECK_TIME)} Uhr für die kommende Nacht.",
                    style = MaterialTheme.typography.labelSmall,
                    color = StarWindowColors.Muted,
                )
                val next = entry?.nextNight
                Text(
                    text = if (next == null) {
                        "In den nächsten Monaten kommt es hier nicht über " +
                            "${watch.minAltitudeDeg.toInt()}°."
                    } else {
                        "Astronomisch passt es ab ${dateFormat.format(next)} – dann " +
                            "%.1f h über %d°.".format(
                                Locale.GERMAN, entry.nextUsableHours, watch.minAltitudeDeg.toInt(),
                            )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = StarWindowColors.Starlight,
                )
                Text(
                    text = "Ob dann auch das Wetter mitspielt, steht erst am Tag selbst fest.",
                    style = MaterialTheme.typography.labelSmall,
                    color = StarWindowColors.Muted,
                )
            }

            if (!notificationsAllowed) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Benachrichtigungen sind für StarWindow abgeschaltet – diese " +
                            "Vormerkung bleibt bestehen, kann sich aber nicht melden.",
                        style = MaterialTheme.typography.labelSmall,
                        color = StarWindowColors.Crosshair,
                    )
                    OutlinedButton(onClick = onRequestPermission) { Text("Freigeben") }
                }
            }

            HorizontalDivider(color = StarWindowColors.NightSurfaceHigh)

            ChoiceRow(
                title = "Mindesthöhe",
                hint = "Darunter entscheiden Dunst und Lichtglocke über das Bild, nicht die Belichtung.",
                options = ALTITUDES.map { "${it.toInt()}°" },
                selectedIndex = ALTITUDES.indexOfFirst { it == watch.minAltitudeDeg }.takeIf { it >= 0 },
                onSelect = { index -> onChange { it.copy(minAltitudeDeg = ALTITUDES[index]) } },
            )

            ChoiceRow(
                title = "Mindestdauer",
                hint = "So lange muss das Objekt hoch stehen und der Himmel gleichzeitig brauchbar sein.",
                options = DURATIONS.map { "%.1f h".format(Locale.GERMAN, it) },
                selectedIndex = DURATIONS.indexOfFirst { it == watch.minUsableHours }.takeIf { it >= 0 },
                onSelect = { index -> onChange { it.copy(minUsableHours = DURATIONS[index]) } },
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Wetter",
                    style = MaterialTheme.typography.titleSmall,
                    color = StarWindowColors.Starlight,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WeatherDemand.entries.forEach { demand ->
                        FilterChip(
                            selected = watch.weatherDemand == demand,
                            onClick = { onChange { it.copy(weatherDemand = demand) } },
                            label = { Text(demand.label) },
                        )
                    }
                }
                Text(
                    text = watch.weatherDemand.hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = StarWindowColors.Muted,
                )
                Text(
                    text = "Ohne Vorhersage meldet sich die App gar nicht – lieber still als " +
                        "in den Regen geschickt.",
                    style = MaterialTheme.typography.labelSmall,
                    color = StarWindowColors.Muted,
                )
            }

            HorizontalDivider(color = StarWindowColors.NightSurfaceHigh)

            Text(
                text = "Nach einer Meldung ${watch.quietNights} Nächte Ruhe, damit eine stabile " +
                    "Hochdrucklage nicht jeden Abend dasselbe sagt.",
                style = MaterialTheme.typography.labelSmall,
                color = StarWindowColors.Muted,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = onOpenObject, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Objekt öffnen")
                }
                OutlinedButton(onClick = onRemove) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Löschen")
                }
            }
        }
    }
}

/**
 * Eine Reihe fester Auswahlmöglichkeiten.
 *
 * Chips statt eines Schiebereglers: Es sind drei sinnvolle Antworten, und ein Regler von 10 bis 60
 * würde eine Genauigkeit vorspiegeln, die die Sache nicht hat.
 */
@Composable
private fun ChoiceRow(
    title: String,
    hint: String,
    options: List<String>,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = StarWindowColors.Starlight)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { index, label ->
                FilterChip(
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    label = { Text(label) },
                )
            }
        }
        Text(hint, style = MaterialTheme.typography.labelSmall, color = StarWindowColors.Muted)
    }
}

/** 25° für robuste Ziele, 30° als Regelfall, 40° für alles, was den Dunst nicht verträgt. */
private val ALTITUDES = listOf(25.0, 30.0, 40.0)

private val DURATIONS = listOf(0.5, 1.0, 2.0)

private val dateFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE, d. MMM", Locale.GERMAN)
private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN)
