package com.starwindow.app.ui.windows

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.starwindow.app.core.astro.Angles
import com.starwindow.app.core.astro.AstroTime
import com.starwindow.app.core.astro.CoordinateTransforms
import com.starwindow.app.core.geometry.SkyWindow
import com.starwindow.app.domain.ObjectTransit
import com.starwindow.app.ui.theme.StarWindowColors

@Composable
fun WindowDetailScreen(
    viewModel: WindowDetailViewModel,
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
            item { WindowSummaryCard(window) }

            item {
                Column {
                    Text("Zeitraum", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(6, 12, 24, 48).forEach { hours ->
                            FilterChip(
                                selected = state.hoursAhead == hours,
                                onClick = { viewModel.setHoursAhead(hours) },
                                label = { Text("${hours} h") },
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

                state.result == null -> item {
                    Text(state.error ?: "Keine Berechnung", color = StarWindowColors.Muted)
                }

                else -> {
                    val result = requireNotNull(state.result)
                    item {
                        Text(
                            text = "${result.transits.size} Objekte ziehen durch das Fenster " +
                                "(von ${result.objectsConsidered} möglichen, " +
                                "${result.computeMillis} ms)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = StarWindowColors.WindowStroke,
                        )
                    }
                    if (result.transits.isEmpty()) {
                        item {
                            Text(
                                "In diesem Zeitraum kreuzt kein Katalogobjekt das Fenster. " +
                                    "Größeren Zeitraum oder schwächere Grenzgröße probieren.",
                                style = MaterialTheme.typography.bodySmall,
                                color = StarWindowColors.Muted,
                            )
                        }
                    }
                    items(result.transits, key = { it.obj.id }) { transit ->
                        TransitCard(transit)
                    }
                }
            }
        }
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
private fun TransitCard(transit: ObjectTransit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = transit.obj.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                transit.obj.magnitude?.let {
                    Text("%.1f mag".format(it), style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(
                text = buildString {
                    append(transit.obj.type.label)
                    transit.obj.constellation?.let { append(" · $it") }
                    append(" · gesamt ${formatDuration(transit.totalDurationMillis)}")
                },
                style = MaterialTheme.typography.labelSmall,
                color = StarWindowColors.Muted,
            )
            Spacer(Modifier.height(6.dp))
            transit.intervals.forEach { interval ->
                Text(
                    text = buildString {
                        if (interval.clippedAtStart) append("bereits drin") else {
                            append(formatClock(interval.enterMillis))
                        }
                        append(" → ")
                        if (interval.clippedAtEnd) append("noch drin") else {
                            append(formatClock(interval.exitMillis))
                        }
                        append("   ${formatDuration(interval.durationMillis)}")
                        append("   max %.1f°".format(interval.bestAltitudeDeg))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = StarWindowColors.Starlight,
                )
            }
        }
    }
}
