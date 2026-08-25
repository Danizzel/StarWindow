package com.starwindow.app.ui.windows

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CenterFocusStrong
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.starwindow.app.domain.AccuracyBand
import com.starwindow.app.domain.ResultFilter
import com.starwindow.app.domain.WindowAccuracy
import com.starwindow.app.domain.accuracy
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
    onTrackWindow: () -> Unit,
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
                modifier = Modifier.weight(1f),
            )
            state.window?.let { window ->
                IconButton(
                    onClick = {
                        viewModel.track(window)
                        onTrackWindow()
                    }
                ) {
                    Icon(
                        Icons.Filled.CenterFocusStrong,
                        contentDescription = "Fenster im Sucher zeigen",
                        tint = StarWindowColors.TrackTarget,
                    )
                }
            }
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
                    // Coloured by entry rather than by path: a circumpolar object crossing the
                    // window three times has to read as one object making three passes, not as
                    // three unrelated things.
                    val trackLabels = state.tracks.map { it.label }.distinct()
                    WindowTrackChart(
                        window = window,
                        tracks = state.tracks.mapIndexed { index, track ->
                            ChartTrack(
                                track = track,
                                color = trackPalette[
                                    trackLabels.indexOf(track.label).coerceAtLeast(0) %
                                        trackPalette.size
                                ],
                                emphasised = index in state.emphasisedTrackIndices,
                            )
                        },
                        figureSegments = state.figureSegments,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (state.selectedIds.isEmpty()) {
                                "Das Fenster steht still, der Himmel zieht hindurch. Durchgezogen " +
                                    "ist die Zeit im Fenster, gepunktet der An- und Abflug. " +
                                    "Zeilen unten antippen, um nur deren Bahnen zu sehen."
                            } else {
                                "Nur die ausgewählten Bahnen. Durchgezogen ist die Zeit im " +
                                    "Fenster, gepunktet der An- und Abflug; Punkte markieren " +
                                    "volle Stunden."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = StarWindowColors.Muted,
                            modifier = Modifier.weight(1f),
                        )
                        if (state.selectedIds.isNotEmpty()) {
                            TextButton(onClick = viewModel::clearSelection) {
                                Text("Alle", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
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
                                selected = transit.constellation.id in state.selectedIds,
                                onClick = { viewModel.toggleSelection(transit.constellation.id) },
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
                                selected = transit.obj.id in state.selectedIds,
                                onClick = { viewModel.toggleSelection(transit.obj.id) },
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
 * How far the window might really be from where it was drawn.
 *
 * Drawn as a bar rather than written as a number alone because the useful comparison is against the
 * window itself: an error of half a degree in a five-degree gap is nothing, the same error in a
 * half-degree slot means the outline could be anywhere. The bar is scaled so that full width is
 * "as wide as the window", which makes that comparison the thing the eye does first.
 */
@Composable
private fun AccuracyBar(accuracy: WindowAccuracy, windowRadiusDeg: Double? = null) {
    val color = when (accuracy.band) {
        AccuracyBand.GOOD -> StarWindowColors.WindowStroke
        AccuracyBand.FAIR -> StarWindowColors.AnchorPoint
        AccuracyBand.POOR -> StarWindowColors.Crosshair
    }
    val reference = windowRadiusDeg?.takeIf { it > 0.1 } ?: 15.0
    val fraction = (accuracy.uncertaintyDeg / reference).coerceIn(0.02, 1.0).toFloat()

    Column(modifier = Modifier.padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = accuracy.headline,
                style = MaterialTheme.typography.titleSmall,
                color = color,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = accuracy.band.label,
                style = MaterialTheme.typography.labelMedium,
                color = StarWindowColors.Muted,
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(StarWindowColors.NightSurfaceHigh)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .background(color)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = accuracy.reason,
            style = MaterialTheme.typography.labelSmall,
            color = StarWindowColors.Muted,
        )
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

            Text("Zeigegenauigkeit", style = MaterialTheme.typography.titleSmall)
            AccuracyBar(window.accuracy(), window.shape.angularRadiusDeg())

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
                append("${transit.peakStarsInside} von ${transit.figureStarCount} Figursternen")
                if (transit.showsMostOfTheFigure) append(" · Figur gut zu erkennen")
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (transit.showsMostOfTheFigure) {
                StarWindowColors.WindowStroke
            } else {
                StarWindowColors.Muted
            },
        )
        Spacer(Modifier.height(6.dp))
        transit.intervals.forEach { interval ->
            IntervalRow(
                fromLabel = if (interval.clippedAtStart) "läuft" else formatClock(interval.enterMillis),
                toLabel = if (interval.clippedAtEnd) "läuft" else formatClock(interval.exitMillis),
                duration = formatDuration(interval.durationMillis),
                peak = "max ${formatClock(interval.peakMillis)}",
            )
        }
    }
}

/**
 * One pass through the window, in fixed columns.
 *
 * The three values answer three different questions — when, how long, and when it is best — and as
 * a run-on sentence they had to be read one at a time. In columns the same three numbers can be
 * compared straight down a list of passes, which is what the eye wants to do with them.
 */
@Composable
private fun IntervalRow(fromLabel: String, toLabel: String, duration: String, peak: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$fromLabel → $toLabel",
            style = MaterialTheme.typography.bodySmall,
            color = StarWindowColors.Starlight,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = duration,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = StarWindowColors.WindowStroke,
            maxLines = 1,
            modifier = Modifier.weight(0.7f),
        )
        Text(
            text = peak,
            style = MaterialTheme.typography.labelSmall,
            color = StarWindowColors.Muted,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.7f),
        )
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
            IntervalRow(
                fromLabel = if (interval.clippedAtStart) "läuft" else formatClock(interval.enterMillis),
                toLabel = if (interval.clippedAtEnd) "läuft" else formatClock(interval.exitMillis),
                duration = formatDuration(interval.durationMillis),
                peak = "max %.0f°".format(interval.bestAltitudeDeg),
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
