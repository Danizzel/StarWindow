package com.starwindow.app.ui.capture

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.data.windows.Settings

@Composable
fun SaveWindowDialog(
    defaultName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(defaultName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fenster speichern") },
        text = {
            Column {
                Text(
                    "Der Ausschnitt wird in Azimut/Höhe gespeichert – also fest gegenüber dem " +
                        "Horizont. Genau dadurch lässt sich später berechnen, welche Objekte " +
                        "hindurchziehen.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (z. B. \"Lücke über der Garage\")") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text("Speichern") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
fun CaptureSettingsDialog(
    settings: Settings,
    observer: ObserverLocation?,
    onDismiss: () -> Unit,
    onFovScaleChange: (Double) -> Unit,
    onToggleGraticule: () -> Unit,
    onToggleCatalog: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Anzeige & Kalibrierung") },
        text = {
            Column {
                SettingSwitch("Gradnetz einblenden", settings.showGraticule, onToggleGraticule)
                SettingSwitch("Katalogobjekte einblenden", settings.showCatalogOverlay, onToggleCatalog)

                Spacer(Modifier.height(16.dp))
                Text("Bildfeld-Feinjustierung", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Ein Objekt anpeilen und die Kamera schwenken: wandert die Markierung " +
                        "schneller als das Bild, den Wert erhöhen.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Slider(
                    value = settings.fovScale.toFloat(),
                    onValueChange = { onFovScaleChange(it.toDouble()) },
                    valueRange = 0.7f..1.4f,
                    steps = 69,
                )
                Text(
                    "Faktor %.3f".format(settings.fovScale),
                    style = MaterialTheme.typography.labelMedium,
                )

                observer?.let {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Standort: %.4f°, %.4f°, %.0f m%s".format(
                            it.latitudeDeg,
                            it.longitudeDeg,
                            it.elevationM,
                            it.accuracyM?.let { acc -> " (±%.0f m)".format(acc) } ?: "",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fertig") } },
    )
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}
