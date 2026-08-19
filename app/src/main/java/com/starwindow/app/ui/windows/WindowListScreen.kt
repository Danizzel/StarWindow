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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.starwindow.app.core.astro.Angles
import com.starwindow.app.core.geometry.SkyWindow
import com.starwindow.app.ui.theme.StarWindowColors

@Composable
fun WindowListScreen(
    viewModel: WindowListViewModel,
    onOpenWindow: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val windows by viewModel.windows.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
            }
            Text("Gespeicherte Fenster", style = MaterialTheme.typography.titleLarge)
        }

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
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(windows, key = { it.id }) { window ->
                    WindowCard(
                        window = window,
                        onClick = { onOpenWindow(window.id) },
                        onDelete = { viewModel.delete(window.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun WindowCard(window: SkyWindow, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(14.dp),
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
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Fenster löschen")
            }
        }
    }
}
