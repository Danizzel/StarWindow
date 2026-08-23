package com.starwindow.app.ui.windows

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.starwindow.app.appContainer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.starwindow.app.core.astro.Angles
import com.starwindow.app.core.astro.AstroTime
import com.starwindow.app.core.astro.CoordinateTransforms
import com.starwindow.app.core.geometry.SkyWindow
import com.starwindow.app.domain.ConstellationTransit
import com.starwindow.app.data.images.SkyImageLoader
import com.starwindow.app.domain.ObjectTransit
import com.starwindow.app.domain.ResultFilter
import com.starwindow.app.ui.components.ObjectSymbol
import com.starwindow.app.ui.components.objectSubtitle
import com.starwindow.app.ui.theme.ObjectPalette
import com.starwindow.app.ui.theme.StarWindowColors

/** Colours cycled through the paths so each one stays tellable apart from the others. */
private val trackPalette = listOf(
    Color(0xFF9FD8FF),
    Color(0xFFFFB74D),
    Color(0xFF8FE6A4),
    Color(0xFFE59BFF),
    Color(0xFFFF8A80),
    Color(0xFFFFF59D),
)

@Composable
fun WindowDetailScreen(
    viewModel: WindowDetailViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val imageLoader: SkyImageLoader = LocalContext.current.appContainer.skyImageLoader

    state.info?.let { info ->
        ObjectInfoSheet(
            info = info,
            imageLoader = imageLoader,
            onDismiss = viewModel::closeInfo,
        )
    }

    Column(modifier = modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
            }
            Text(
                text = state.window?.name ?: "Fenster",
                style = MaterialTheme.typography.titleLarge,
            )
        }

        val window = state.window
        if (window == null) {
            Text(
                text = state.error ?: "Wird geladen…",
                modifier = Modifier.padding(24.dp),
                color = StarWindowColors.Muted,
            )
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column {
                    Text("Laufbahnen durch das Fenster", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    WindowTrackChart(
                        window = window,
                        tracks = state.tracks.mapIndexed { index, track ->
                            ChartTrack(
                                track = track,
                                color = trackPalette[index % trackPalette.size],
                                emphasised = index == state.emphasisedTrackIndex,
                            )
                        },
                        figureSegments = state.figureSegments,
                    )
                    Text(
                        "Das Fenster steht still, der Himmel zieht hindurch. Durchgezogen ist die " +
                            "Zeit im Fenster, gepunktet der An- und Abflug; Punkte markieren volle " +
                            "Stunden. Eine Zeile antippen hebt ihre Bahn hervor.",
                        style = MaterialTheme.typography.labelSmall,
                        color = StarWindowColors.Muted,
                    )
                }
            }

            item { WindowSummaryCard(window) }

            item {
                Column {
                    Text("Zeitraum", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(6, 12, 24, 48).forEach { hours ->
                            FilterChip(
                                selected = state.hoursAhead == hours,
                                onClick = { viewModel.setHoursAhead(hours) },
                                label = { Text("$hours h") },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Grenzgröße", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(3.0, 6.0, 8.0, 12.0).forEach { limit ->
                            FilterChip(
                                selected = state.magnitudeLimit == limit,
                                onClick = { viewModel.setMagnitudeLimit(limit) },
                                label = { Text("%.0f mag".format(limit)) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Art", style = MaterialTheme.typography.titleSmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(ResultFilter.entries.toList(), key = { it.name }) { filter ->
                            FilterChip(
                                selected = state.filter == filter,
                                onClick = { viewModel.setFilter(filter) },
                                label = { Text(filter.label) },
                            )
                        }
                    }
                }
            }

            when {
                state.isSearching -> item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                state.error != null -> item {
                    Text(requireNotNull(state.error), color = StarWindowColors.Crosshair)
                }

                state.isEmpty -> item {
                    Text(
                        "In diesem Zeitraum kreuzt nichts das Fenster. Größeren Zeitraum, " +
                            "schwächere Grenzgröße oder eine andere Art probieren.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StarWindowColors.Muted,
                    )
                }

                else -> {
                    val constellations = state.visibleConstellations
                    val objects = state.visibleObjects

                    if (constellations.isNotEmpty()) {
                        item {
                            SectionHeader(
                                title = "Sternbilder",
                                count = constellations.size,
                                note = "ziehen durch das Fenster",
                            )
                        }
                        items(constellations, key = { "con-${it.constellation.id}" }) { transit ->
                            ConstellationCard(
                                transit = transit,
                                selected = state.selectedId == transit.constellation.id,
                                onClick = { viewModel.select(transit.constellation.id) },
                            )
                        }
                    }

                    if (objects.isNotEmpty()) {
                        item {
                            SectionHeader(
                                title = "Objekte",
                                count = objects.size,
                                note = "in zeitlicher Reihenfolge" +
                                    (state.result?.let { " · ${it.computeMillis} ms" } ?: ""),
                            )
                        }
                        items(objects, key = { "obj-${it.obj.id}" }) { transit ->
                            TransitCard(
                                transit = transit,
                                selected = state.selectedId == transit.obj.id,
                                onClick = { viewModel.select(transit.obj.id) },
                                onInfo = { viewModel.openInfo(transit.obj.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Divides the result list into its sections.
 *
 * The count sits in the heading rather than inside a sentence, because "how many" is the first
 * thing anyone wants from a list of results and a number is found faster at the start of a line
 * than in the middle of one.
 */
@Composable
private fun SectionHeader(title: String, count: Int, note: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = "$count",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = StarWindowColors.WindowStroke,
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = StarWindowColors.Starlight,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = note,
            style = MaterialTheme.typography.labelSmall,
            color = StarWindowColors.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun WindowSummaryCard(window: SkyWindow) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            val center = window.centerHorizontal
            Text("Geometrie", style = MaterialTheme.typography.titleSmall)
            Text(
                "Mitte  Az ${Angles.formatDeg(center.azimuthDeg)}  " +
                    "Alt ${Angles.formatDeg(center.altitudeDeg)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Fläche %.2f°²  ·  Radius bis Rand %.1f°".format(
                    window.shape.areaSquareDeg(),
                    window.shape.angularRadiusDeg(),
                ),
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            Text("Beobachtungsort", style = MaterialTheme.typography.titleSmall)
            Text(
                "Breite %.5f°  ·  Länge %.5f°  ·  %.0f m".format(
                    window.observer.latitudeDeg,
                    window.observer.longitudeDeg,
                    window.observer.elevationM,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Aufgenommen ${formatTimestamp(window.capturedAtMillis)}  ·  " +
                    "magnetische Deklination %.1f°".format(window.magneticDeclinationDeg),
                style = MaterialTheme.typography.bodySmall,
                color = StarWindowColors.Muted,
            )

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            Text("Himmelskoordinaten", style = MaterialTheme.typography.titleSmall)
            val atCapture = window.centerEquatorialAtCapture()
            Text(
                "Mitte bei Aufnahme:  RA ${Angles.formatRa(atCapture.raDeg)}  " +
                    "Dec ${Angles.formatDec(atCapture.decDeg)}",
                style = MaterialTheme.typography.bodySmall,
            )
            val now = System.currentTimeMillis()
            val atNow = CoordinateTransforms.horizontalToEquatorial(center, window.observer, now)
            Text(
                "Mitte jetzt:  RA ${Angles.formatRa(atNow.raDeg)}  " +
                    "Dec ${Angles.formatDec(atNow.decDeg)}",
                style = MaterialTheme.typography.bodySmall,
            )
            val siderealTime = Angles.formatRa(AstroTime.lstDeg(now, window.observer.longitudeDeg))
            Text(
                "Sternzeit $siderealTime · Die Deklination des Fensters bleibt konstant, " +
                    "die Rektaszension wandert mit der Erddrehung.",
                style = MaterialTheme.typography.labelSmall,
                color = StarWindowColors.Muted,
            )
        }
    }
}

@Composable
private fun ConstellationCard(
    transit: ConstellationTransit,
    selected: Boolean,
    onClick: () -> Unit,
) {
    SelectableCard(selected = selected, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = transit.constellation.name,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(transit.constellation.id, style = MaterialTheme.typography.labelMedium)
        }
        Text(
            text = buildString {
                append("Sternbild · höchstens ")
                append("${transit.peakStarsInside} von ${transit.figureStarCount} Figursternen")
                append(" gleichzeitig im Fenster")
                if (transit.showsMostOfTheFigure) append(" – die Figur ist gut zu erkennen")
            },
            style = MaterialTheme.typography.labelSmall,
            color = StarWindowColors.Muted,
        )
        Spacer(Modifier.height(6.dp))
        transit.intervals.forEach { interval ->
            Text(
                text = buildString {
                    append(if (interval.clippedAtStart) "bereits drin" else formatClock(interval.enterMillis))
                    append(" → ")
                    append(if (interval.clippedAtEnd) "noch drin" else formatClock(interval.exitMillis))
                    append("   ${formatDuration(interval.durationMillis)}")
                    append("   am meisten um ${formatClock(interval.peakMillis)}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = StarWindowColors.Starlight,
            )
        }
    }
}

/**
 * One object that crosses the window.
 *
 * Laid out in fixed columns rather than as sentences, so a list of forty can be skimmed: symbol,
 * then identity, then the one number that ranks them against each other — how long the object
 * spends inside the window — set apart on the right in its own colour. A window is a narrow slot,
 * and "how long do I have" is the question that decides what gets photographed tonight.
 *
 * Only the first pass is spelled out. Further passes are counted rather than listed; the ones after
 * the first are hours away and belong in the info sheet, not in a row that has to stay skimmable.
 */
@Composable
private fun TransitCard(
    transit: ObjectTransit,
    selected: Boolean,
    onClick: () -> Unit,
    onInfo: () -> Unit,
) {
    SelectableCard(selected = selected, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ObjectSymbol(transit.obj.type, size = 18.dp, modifier = Modifier.padding(end = 8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = transit.obj.id,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = ObjectPalette.colorFor(transit.obj.type),
                        maxLines = 1,
                    )
                    if (transit.obj.name.isNotBlank() && transit.obj.name != transit.obj.id) {
                        Text(
                            text = "  ${transit.obj.name}",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    text = objectSubtitle(transit.obj),
                    style = MaterialTheme.typography.labelSmall,
                    color = StarWindowColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 6.dp)) {
                Text(
                    text = formatDuration(transit.totalDurationMillis),
                    style = MaterialTheme.typography.titleSmall,
                    color = StarWindowColors.WindowStroke,
                )
                Text(
                    text = "im Fenster",
                    style = MaterialTheme.typography.labelSmall,
                    color = StarWindowColors.Muted,
                )
            }
            IconButton(onClick = onInfo, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = "Informationen zu ${transit.obj.displayName}",
                    tint = StarWindowColors.CatalogMarker,
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        transit.intervals.firstOrNull()?.let { interval ->
            Text(
                text = buildString {
                    append(if (interval.clippedAtStart) "bereits drin" else formatClock(interval.enterMillis))
                    append(" → ")
                    append(if (interval.clippedAtEnd) "noch drin" else formatClock(interval.exitMillis))
                    append("   ${formatDuration(interval.durationMillis)}")
                    append("   max %.1f°".format(interval.bestAltitudeDeg))
                },
                style = MaterialTheme.typography.bodySmall,
                color = StarWindowColors.Starlight,
            )
        }
        if (transit.intervals.size > 1) {
            Text(
                text = "+ ${transit.intervals.size - 1} weitere Durchgänge",
                style = MaterialTheme.typography.labelSmall,
                color = StarWindowColors.Muted,
            )
        }
    }
}

/** A result row. Selecting it lifts its card and highlights its path in the chart above. */
@Composable
private fun SelectableCard(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = StarWindowColors.NightSurfaceHigh)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            if (selected) {
                Surface(
                    shape = CircleShape,
                    color = StarWindowColors.WindowStroke,
                    modifier = Modifier.padding(top = 5.dp, end = 8.dp).size(8.dp),
                    content = {},
                )
            }
            Column(modifier = Modifier.weight(1f)) { content() }
        }
    }
}
