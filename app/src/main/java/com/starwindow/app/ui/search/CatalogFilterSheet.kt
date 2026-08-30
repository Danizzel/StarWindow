package com.starwindow.app.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.starwindow.app.core.geometry.SkyWindow
import com.starwindow.app.domain.CatalogFilter
import com.starwindow.app.domain.ResultFilter
import com.starwindow.app.domain.SkyConditions
import com.starwindow.app.ui.theme.StarWindowColors

/**
 * Everything the result list can be narrowed by, in one sheet.
 *
 * A sheet rather than a permanent control area, because these are decisions made once and then
 * lived with: two rows of chips standing there all evening cost a fifth of a phone screen to
 * display seven choices, and with a catalogue this size the choices that matter are not the seven
 * that fit. Here there is room for the ones that actually cut twenty-two thousand entries down —
 * brightness, size, constellation, altitude — and the list keeps the whole screen once it closes.
 *
 * Every control writes straight through to the [CatalogFilter]; there is no "apply" button. The
 * list behind the sheet updates as the sliders move, which is both the fastest way to find the
 * right cut and the only way to see what a filter actually does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogFilterSheet(
    filter: CatalogFilter,
    constellations: List<String>,
    windows: List<SkyWindow>,
    conditions: SkyConditions,
    favoriteCount: Int,
    onChange: (CatalogFilter) -> Unit,
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
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Filter",
                    style = MaterialTheme.typography.titleMedium,
                    color = StarWindowColors.Starlight,
                    modifier = Modifier.weight(1f),
                )
                if (!filter.isEmpty) {
                    TextButton(onClick = { onChange(CatalogFilter.NONE) }) {
                        Text("Zurücksetzen")
                    }
                }
            }

            // Ganz oben, weil er als Einziger nicht sucht, sondern abkürzt: Wer ihn einschaltet,
            // ist mit dem Suchen fertig und will zu seiner eigenen Liste.
            SwitchRow(
                title = "Nur Favoriten",
                subtitle = when (favoriteCount) {
                    0 -> "Noch keine – das Herz sitzt oben auf dem Objektblatt."
                    1 -> "1 Objekt mit Herz"
                    else -> "$favoriteCount Objekte mit Herz"
                },
                checked = filter.onlyFavorites,
                onChange = { onChange(filter.copy(onlyFavorites = it)) },
            )

            HorizontalDivider(color = StarWindowColors.NightSurfaceHigh)

            FilterSection("Art") {
                ChipRow(
                    options = ResultFilter.entries.filter { it != ResultFilter.CONSTELLATIONS },
                    selected = filter.kind,
                    label = { it.label },
                    onSelect = { onChange(filter.copy(kind = it)) },
                )
            }

            // Deliberately the *upper* bound on the number, which is the lower bound on brightness:
            // magnitudes run backwards, and a slider that gets shorter as things get brighter would
            // be read wrong every time.
            SliderSection(
                title = "Helligkeit",
                valueLabel = filter.magnitudeLimit?.let { "heller als %.1f mag".format(it) }
                    ?: "keine Grenze",
                value = filter.magnitudeLimit ?: MAGNITUDE_MAX,
                range = MAGNITUDE_MIN..MAGNITUDE_MAX,
                steps = 27,
                onChange = {
                    onChange(filter.copy(magnitudeLimit = it.takeIf { v -> v < MAGNITUDE_MAX }))
                },
            )

            SliderSection(
                title = "Mindestgröße",
                valueLabel = filter.minSizeArcmin
                    ?.let { "größer als " + CatalogFilter.formatSize(it) }
                    ?: "beliebig",
                value = filter.minSizeArcmin ?: 0.0,
                range = 0.0..60.0,
                steps = 23,
                onChange = { onChange(filter.copy(minSizeArcmin = it.takeIf { v -> v >= 1.0 })) },
            )

            SliderSection(
                title = "Mindesthöhe jetzt",
                valueLabel = filter.minAltitudeDeg?.let { "über %.0f°".format(it) } ?: "beliebig",
                value = filter.minAltitudeDeg ?: 0.0,
                range = 0.0..70.0,
                steps = 13,
                onChange = { onChange(filter.copy(minAltitudeDeg = it.takeIf { v -> v >= 5.0 })) },
            )

            FilterSection("Sternbild") {
                ChipRow(
                    options = listOf<String?>(null) + constellations,
                    selected = filter.constellation,
                    label = { it ?: "alle" },
                    onSelect = { onChange(filter.copy(constellation = it)) },
                )
            }

            HorizontalDivider(color = StarWindowColors.NightSurfaceHigh)

            SwitchRow(
                title = "Nur was heute Nacht machbar ist",
                subtitle = "Blendet aus, was zu tief steht oder im Himmelshintergrund untergeht – " +
                    "beurteilt für Bortle ${conditions.bortleLevel}" +
                    if (conditions.isEstimated) " (geschätzt)" else "",
                checked = filter.hideImpossible,
                onChange = { onChange(filter.copy(hideImpossible = it)) },
            )

            if (windows.isNotEmpty()) {
                SwitchRow(
                    title = "Nur was durchs Fenster zieht",
                    subtitle = "Rechnet die Durchgänge für die nächsten zwölf Stunden. " +
                        "Dauert ein paar Sekunden.",
                    checked = filter.onlyThroughWindow,
                    onChange = { onChange(filter.copy(onlyThroughWindow = it)) },
                )
                if (filter.onlyThroughWindow && windows.size > 1) {
                    ChipRow(
                        options = windows,
                        selected = windows.firstOrNull { it.id == filter.windowId } ?: windows.first(),
                        label = { it.name },
                        onSelect = { onChange(filter.copy(windowId = it.id)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = StarWindowColors.Muted)
        content()
    }
}

@Composable
private fun <T> ChipRow(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(options.size) { index ->
            val option = options[index]
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
            )
        }
    }
}

/**
 * A slider whose value is spelled out in words above it.
 *
 * The number alone would not do: "9.0" is ambiguous between a limit and a target, and a slider at
 * its far end has to be able to say "no limit at all" rather than "9.0 mag" when that is what it
 * means.
 */
@Composable
private fun SliderSection(
    title: String,
    valueLabel: String,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    steps: Int,
    onChange: (Double) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = StarWindowColors.Muted,
                modifier = Modifier.weight(1f),
            )
            Text(valueLabel, style = MaterialTheme.typography.labelMedium, color = StarWindowColors.WindowStroke)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            steps = steps,
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = StarWindowColors.Starlight)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = StarWindowColors.Muted)
        }
        Spacer(Modifier.height(0.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Brighter than this and the slider would only be selecting the handful of naked-eye showpieces. */
private const val MAGNITUDE_MIN = 0.0

/** The catalogue's own faint end; at this setting nothing is cut. */
private const val MAGNITUDE_MAX = 14.0
