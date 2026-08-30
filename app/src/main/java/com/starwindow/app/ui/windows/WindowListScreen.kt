package com.starwindow.app.ui.windows

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.starwindow.app.core.astro.Angles
import com.starwindow.app.core.geometry.SkyWindow
import com.starwindow.app.ui.components.ScreenHeader
import com.starwindow.app.ui.theme.StarWindowColors
import com.starwindow.app.ui.theme.StarWindowSpacing

@Composable
fun WindowListScreen(
    viewModel: WindowListViewModel,
    onOpenWindow: (String) -> Unit,
    onTrackWindow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val windows by viewModel.windows.collectAsStateWithLifecycle()
    val trackedWindowId by viewModel.trackedWindowId.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader(
            title = "Gespeicherte Fenster",
            subtitle = if (windows.isEmpty()) null else "${windows.size} Ausschnitte am Himmel",
        )

        if (windows.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Noch keine Fenster gespeichert.\nIn der Kameraansicht Punkte setzen und speichern.",
                    textAlign = TextAlign.Center,
                    color = StarWindowColors.Muted,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = StarWindowSpacing.screen,
                    end = StarWindowSpacing.screen,
                    bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(StarWindowSpacing.between),
            ) {
                items(windows, key = { it.id }) { window ->
                    WindowCard(
                        window = window,
                        isTracked = window.id == trackedWindowId,
                        onClick = { onOpenWindow(window.id) },
                        onTrack = {
                            viewModel.track(window)
                            onTrackWindow()
                        },
                        onDelete = { viewModel.delete(window.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun WindowCard(
    window: SkyWindow,
    isTracked: Boolean,
    onClick: () -> Unit,
    onTrack: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = StarWindowColors.NightSurface,
        border = BorderStroke(1.dp, StarWindowColors.Outline),
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(window.name, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                val center = window.centerHorizontal
                Text(
                    "Az %.1f°  Alt %.1f°  ·  %.1f°²".format(
                        center.azimuthDeg,
                        center.altitudeDeg,
                        window.shape.areaSquareDeg(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = StarWindowColors.Muted,
                )
                val equatorial = window.centerEquatorialAtCapture()
                Text(
                    "bei Aufnahme: RA ${Angles.formatRa(equatorial.raDeg)}  " +
                        "Dec ${Angles.formatDec(equatorial.decDeg)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = StarWindowColors.Muted,
                )
                Text(
                    formatTimestamp(window.capturedAtMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = StarWindowColors.Muted,
                )
            }
            IconButton(onClick = onTrack) {
                Icon(
                    Icons.Filled.CenterFocusStrong,
                    contentDescription = "„${window.name}“ im Sucher zeigen",
                    tint = if (isTracked) {
                        StarWindowColors.TrackTarget
                    } else {
                        StarWindowColors.Starlight
                    },
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Fenster löschen")
            }
        }
    }
}
