package com.starwindow.app.ui.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.core.camera.ExposureCapabilities
import com.starwindow.app.core.camera.ExposureMode
import com.starwindow.app.core.camera.ExposureSettings
import com.starwindow.app.data.windows.Settings
import kotlin.math.ln
import kotlin.math.pow

/**
 * Asked before a mode switch throws away points that have already been placed.
 *
 * The points mean something different in each mode — corners, centre and rim, opposite corners —
 * so they genuinely cannot be carried over. Only shown when there is something to lose: a dialog
 * that appears every time turns into a reflex and stops being read.
 */
@Composable
fun DiscardAnchorsDialog(
    mode: DrawMode,
    anchorCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zu „${mode.label}“ wechseln?") },
        text = {
            Text(
                "Dabei gehen die schon gesetzten Punkte ($anchorCount) verloren – in jedem Modus " +
                    "bedeuten sie etwas anderes.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Wechseln") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

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
                    label = { Text("Name (z. B. „Lücke über der Garage“)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text("Speichern") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/**
 * The night viewfinder.
 *
 * Exposure time runs on a logarithmic slider because the useful range spans three orders of
 * magnitude — a linear slider would spend most of its travel in the part nobody needs.
 */
@Composable
fun NightVisionDialog(
    exposure: ExposureSettings,
    capabilities: ExposureCapabilities?,
    onExposureChange: (ExposureSettings) -> Unit,
    onModeChange: (ExposureMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sucher") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExposureMode.entries.forEach { mode ->
                        FilterChip(
                            selected = exposure.mode == mode,
                            onClick = { onModeChange(mode) },
                            label = { Text(mode.label) },
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                when {
                    capabilities == null -> Text(
                        "Die Kamera läuft noch nicht.",
                        style = MaterialTheme.typography.bodySmall,
                    )

                    exposure.mode == ExposureMode.AUTO -> Text(
                        "Die Automatik reicht bei Tageslicht und in der Dämmerung. Wird es richtig " +
                            "dunkel, zeigt sie fast nichts mehr – dann auf Nacht umschalten.",
                        style = MaterialTheme.typography.bodySmall,
                    )

                    !capabilities.supportsManualSensor -> Text(
                        "Dieses Gerät gibt Belichtungszeit und Empfindlichkeit nicht frei. " +
                            "Stattdessen wird die Belichtungskorrektur voll aufgedreht – das hilft " +
                            "etwas, reicht für Sterne aber meist nicht.",
                        style = MaterialTheme.typography.bodySmall,
                    )

                    else -> {
                        Text(
                            "Belichtungszeit ${exposure.formatExposureTime()}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        LogSlider(
                            value = exposure.exposureTimeNs.toDouble(),
                            min = capabilities.minExposureTimeNs.toDouble().coerceAtLeast(1e6),
                            max = capabilities.maxExposureTimeNs.toDouble().coerceAtLeast(2e6),
                            onValueChange = {
                                onExposureChange(exposure.copy(exposureTimeNs = it.toLong()))
                            },
                        )
                        Text(
                            "Längere Zeiten holen mehr Sterne heraus, machen die Vorschau aber " +
                                "träge. Das Gradnetz und die Markierungen bleiben flüssig.",
                            style = MaterialTheme.typography.labelSmall,
                        )

                        Spacer(Modifier.height(12.dp))
                        Text("ISO ${exposure.iso}", style = MaterialTheme.typography.titleSmall)
                        Slider(
                            value = exposure.iso.toFloat(),
                            onValueChange = { onExposureChange(exposure.copy(iso = it.toInt())) },
                            valueRange = capabilities.minIso.toFloat()..capabilities.maxIso.toFloat(),
                        )

                        if (capabilities.supportsFocusDistance) {
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Fokus auf unendlich",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Switch(
                                    checked = exposure.focusAtInfinity,
                                    onCheckedChange = {
                                        onExposureChange(exposure.copy(focusAtInfinity = it))
                                    },
                                )
                            }
                            Text(
                                "Der Autofokus findet am dunklen Himmel nichts und sucht endlos.",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fertig") } },
    )
}

@Composable
private fun LogSlider(
    value: Double,
    min: Double,
    max: Double,
    onValueChange: (Double) -> Unit,
) {
    val logMin = ln(min)
    val logMax = ln(max)
    Slider(
        value = ((ln(value.coerceIn(min, max)) - logMin) / (logMax - logMin)).toFloat(),
        onValueChange = { fraction ->
            onValueChange(Math.E.pow(logMin + (logMax - logMin) * fraction))
        },
        valueRange = 0f..1f,
    )
}

@Composable
fun CaptureSettingsDialog(
    settings: Settings,
    observer: ObserverLocation?,
    onDismiss: () -> Unit,
    onToggleGraticule: () -> Unit,
    onToggleCatalog: () -> Unit,
    onManualLocation: (ObserverLocation?) -> Unit,
    onOpenCalibration: () -> Unit,
) {
    var latitudeText by remember(observer) {
        mutableStateOf(observer?.latitudeDeg?.let { "%.5f".format(it) } ?: "")
    }
    var longitudeText by remember(observer) {
        mutableStateOf(observer?.longitudeDeg?.let { "%.5f".format(it) } ?: "")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Einstellungen") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SettingSwitch("Gradnetz einblenden", settings.showGraticule, onToggleGraticule)
                SettingSwitch("Katalogobjekte einblenden", settings.showCatalogOverlay, onToggleCatalog)

                Spacer(Modifier.height(16.dp))
                Text("Kalibrierung", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = buildString {
                        val calibration = settings.calibration
                        append("Ausrichtung: ")
                        append(
                            if (calibration.hasAttitudeCorrection) {
                                "%.1f° (%s)".format(
                                    calibration.correctionAngleDeg,
                                    calibration.attitudeSource.label,
                                )
                            } else {
                                "nicht kalibriert"
                            }
                        )
                        append("\nBildfeld: ")
                        append(
                            if (calibration.hasFovCorrection) {
                                "Faktor %.3f (%s)".format(
                                    calibration.fovScale,
                                    calibration.fovSource.label,
                                )
                            } else {
                                "Herstellerangabe"
                            }
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onOpenCalibration) { Text("Kalibrierung öffnen") }

                Spacer(Modifier.height(16.dp))
                Text("Standort", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = observer?.let {
                        "%.5f°, %.5f°%s".format(
                            it.latitudeDeg,
                            it.longitudeDeg,
                            if (it.manual) " (manuell)" else " (GPS)",
                        )
                    } ?: "unbekannt",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Ohne GPS – drinnen oder beim Ausprobieren – die Koordinaten hier von Hand " +
                        "eintragen.",
                    style = MaterialTheme.typography.labelSmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = latitudeText,
                        onValueChange = { latitudeText = it },
                        label = { Text("Breite") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = longitudeText,
                        onValueChange = { longitudeText = it },
                        label = { Text("Länge") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            val latitude = latitudeText.replace(',', '.').toDoubleOrNull()
                            val longitude = longitudeText.replace(',', '.').toDoubleOrNull()
                            if (latitude != null && longitude != null &&
                                latitude in -90.0..90.0 && longitude in -180.0..180.0
                            ) {
                                onManualLocation(
                                    ObserverLocation(latitude, longitude, manual = true)
                                )
                            }
                        }
                    ) { Text("Übernehmen") }
                    if (settings.manualLocation != null) {
                        TextButton(onClick = { onManualLocation(null) }) { Text("Wieder GPS") }
                    }
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
