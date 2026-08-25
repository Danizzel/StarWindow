package com.starwindow.app.ui.calibration

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.starwindow.app.core.calibration.CalibrationSource
import com.starwindow.app.core.calibration.trustAt
import com.starwindow.app.core.sensors.DeviceAttitude
import com.starwindow.app.core.sensors.compassAccuracyLabel
import com.starwindow.app.data.windows.Settings
import com.starwindow.app.ui.capture.CameraPreview
import com.starwindow.app.ui.capture.PreviewStreamInfo
import com.starwindow.app.ui.capture.SkyOverlay
import com.starwindow.app.ui.components.SkyViewport
import com.starwindow.app.ui.components.rememberSkyViewport
import com.starwindow.app.ui.theme.StarWindowColors

/**
 * Calibration.
 *
 * Four methods, none of them required. The app works uncalibrated; each method only narrows one of
 * the two error sources further. Three of the four need no view of the stars at all, which matters
 * because the situations this app is built for — a slot between two buildings — are exactly the
 * ones where most of the sky is hidden.
 */
@Composable
fun CalibrationScreen(
    viewModel: CalibrationViewModel,
    settings: Settings,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val attitudeState = viewModel.attitude.collectAsStateWithLifecycle()
    val hudAttitude by viewModel.hudAttitude.collectAsStateWithLifecycle()
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var streamInfo by remember { mutableStateOf<PreviewStreamInfo?>(null) }

    val viewport = rememberSkyViewport(streamInfo, viewSize, state.calibration.fovScale)

    Box(modifier = modifier.fillMaxSize().background(StarWindowColors.Night)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { viewSize = it }
                .pointerInput(state.method, viewport) {
                    detectTapGestures { offset ->
                        // Only the pan sweep reads taps; the other methods aim with the crosshair,
                        // where the field of view does not enter the measurement at all.
                        if (state.method == CalibrationMethod.PAN_SWEEP && viewport.isReady) {
                            viewModel.capturePanSighting(
                                offset.x,
                                offset.y,
                                size.width.toFloat(),
                                size.height.toFloat(),
                            )
                        }
                    }
                }
        ) {
            CameraPreview(
                exposure = settings.exposure,
                onStreamInfo = { streamInfo = it },
                modifier = Modifier.fillMaxSize(),
            )
            SkyOverlay(
                attitudeState = attitudeState,
                focalPx = viewport.focalPx,
                anchors = emptyList(),
                shape = null,
                catalog = state.candidateStars,
                observer = state.observer,
                showGraticule = settings.showGraticule,
                showCatalog = state.method == CalibrationMethod.STARS,
                highlightDirection = state.selectedStar
                    ?.takeIf { state.method == CalibrationMethod.STARS }
                    ?.let { viewModel.currentPositionOf(it) },
                highlightLabel = state.selectedStar?.let { it.name.ifBlank { it.id } }.orEmpty(),
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            CalibrationHeader(state, hudAttitude, onBack)
            Spacer(Modifier.weight(1f))
            CalibrationPanel(
                state = state,
                viewport = viewport,
                viewModel = viewModel,
            )
        }
    }
}

@Composable
private fun CalibrationHeader(
    state: CalibrationUiState,
    attitude: DeviceAttitude?,
    onBack: () -> Unit,
) {
    Surface(color = Color.Black.copy(alpha = 0.6f)) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Zurück",
                        tint = StarWindowColors.Starlight,
                    )
                }
                Text(
                    "Kalibrierung",
                    style = MaterialTheme.typography.titleMedium,
                    color = StarWindowColors.Starlight,
                )
            }

            val calibration = state.calibration
            Text(
                text = if (calibration.hasAttitudeCorrection) {
                    "Ausrichtung: %.1f° korrigiert (%s%s)".format(
                        calibration.correctionAngleDeg,
                        calibration.attitudeSource.label,
                        calibration.attitudeResidualDeg
                            ?.let { ", Rest %.2f°".format(it) } ?: "",
                    )
                } else {
                    "Ausrichtung: nicht kalibriert – der Kompass wird ungeprüft übernommen"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (calibration.hasAttitudeCorrection) {
                    StarWindowColors.WindowStroke
                } else {
                    StarWindowColors.Muted
                },
                modifier = Modifier.padding(horizontal = 8.dp),
            )

            // A correction measured elsewhere, or long ago, is still applied — but the user should
            // know it is being applied, because it may no longer describe this place at all.
            val trust = calibration.trustAt(state.observer, System.currentTimeMillis())
            if (trust.isQuestionable) {
                Text(
                    text = "Diese Messung ist ${trust.label}. ${trust.explanation}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (trust.needsRemeasuring) {
                        StarWindowColors.Crosshair
                    } else {
                        StarWindowColors.AnchorPoint
                    },
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }

            Text(
                text = if (calibration.hasFovCorrection) {
                    "Bildfeld: Faktor %.3f (%s)".format(
                        calibration.fovScale,
                        calibration.fovSource.label,
                    )
                } else {
                    "Bildfeld: Herstellerangabe der Kamera"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (calibration.hasFovCorrection) {
                    StarWindowColors.WindowStroke
                } else {
                    StarWindowColors.Muted
                },
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )

            SensorHealthLine(attitude)
        }
    }
}

/**
 * What the sensors are currently doing, on the screen where it decides whether calibrating is even
 * worth starting.
 *
 * Measuring a compass correction while a radiator is bending the field does not produce a
 * correction — it produces a number that describes the radiator, and it then gets applied to the
 * whole night sky. Saying so here costs one line and saves the user a calibration they would have
 * had to undo.
 */
@Composable
private fun SensorHealthLine(attitude: DeviceAttitude?) {
    if (attitude == null) return

    Text(
        text = buildString {
            append("Sensor: ").append(attitude.source.label)
            append(" · Kompassgüte ").append(compassAccuracyLabel(attitude.accuracy))
            attitude.fieldMicroTesla?.let { append(" · Feld %.0f µT".format(it)) }
            attitude.expectedFieldMicroTesla?.let { append(" (erwartet %.0f)".format(it)) }
        },
        style = MaterialTheme.typography.labelSmall,
        color = StarWindowColors.Muted,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
    )

    // The dip angle is the sensitive half of the check and worth showing next to the strength:
    // iron nearby often leaves the strength alone and bends the direction instead.
    val dip = attitude.inclinationDeg
    val expectedDip = attitude.expectedInclinationDeg
    if (dip != null && expectedDip != null) {
        Text(
            text = buildString {
                append("Feldneigung %.0f° (erwartet %.0f°)".format(dip, expectedDip))
                attitude.hardIronMicroTesla?.let {
                    append(" · Eigenmagnetismus %.0f µT".format(it))
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (attitude.hasFieldDirectionDistortion) {
                StarWindowColors.Crosshair
            } else {
                StarWindowColors.Muted
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }

    when {
        // The one the user can actually fix, so it goes first.
        attitude.needsCompassCalibration -> Text(
            text = "Der Magnetsensor ist noch nicht eingemessen – das Handy ein paar Mal in einer " +
                "liegenden Acht schwenken, dann meldet Android ihn neu kalibriert. Jetzt zu " +
                "kalibrieren hieße, den Fehler des Sensors mitzumessen.",
            style = MaterialTheme.typography.labelSmall,
            color = StarWindowColors.Crosshair,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )

        attitude.hasFieldDirectionDistortion -> Text(
            text = "Die Feldrichtung stimmt nicht – etwas Eisenhaltiges in der Nähe verbiegt das " +
                "Erdmagnetfeld, ohne seine Stärke zu ändern. Ein paar Schritte weggehen; " +
                "Nordrichtung wird solange vom Kreisel gehalten.",
            style = MaterialTheme.typography.labelSmall,
            color = StarWindowColors.Crosshair,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )

        attitude.hasFieldStrengthDistortion -> Text(
            text = "Magnetstörung – das Feld passt nicht zum Erdmagnetfeld. Nordrichtung wird " +
                "vom Kreisel gehalten; jetzt zu kalibrieren würde die Störung mit einmessen.",
            style = MaterialTheme.typography.labelSmall,
            color = StarWindowColors.Crosshair,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )

        attitude.accuracy < android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> Text(
            text = "Kompassgüte niedrig – das Handy einmal in einer Acht bewegen, dann meldet " +
                "Android den Magnetsensor neu kalibriert.",
            style = MaterialTheme.typography.labelSmall,
            color = StarWindowColors.AnchorPoint,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }

    if (attitude.headingDriftDeg >= 0.5) {
        Text(
            text = "Nord wird seit %.0f s vom Kreisel getragen – bis zu ±%.0f° Drift.".format(
                attitude.headingHeldSeconds,
                attitude.headingDriftDeg,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = StarWindowColors.AnchorPoint,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun CalibrationPanel(
    state: CalibrationUiState,
    viewport: SkyViewport,
    viewModel: CalibrationViewModel,
) {
    Surface(color = Color.Black.copy(alpha = 0.72f)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 380.dp)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CalibrationMethod.entries.forEach { method ->
                    FilterChip(
                        selected = state.method == method,
                        onClick = { viewModel.setMethod(method) },
                        label = { Text(method.label) },
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = state.method.instructions,
                style = MaterialTheme.typography.bodySmall,
                color = StarWindowColors.Muted,
            )
            if (state.method.needsClearSky) {
                Text(
                    "Braucht freie Sicht auf einen hellen Stern – bei bedecktem Himmel eine der " +
                        "anderen Methoden benutzen.",
                    style = MaterialTheme.typography.labelSmall,
                    color = StarWindowColors.AnchorPoint,
                )
            }

            Spacer(Modifier.height(10.dp))

            when (state.method) {
                CalibrationMethod.STARS -> StarPanel(state, viewModel)
                CalibrationMethod.LANDMARK -> LandmarkPanel(state, viewModel)
                CalibrationMethod.PAN_SWEEP -> PanSweepPanel(state, viewport, viewModel)
                CalibrationMethod.MANUAL -> ManualPanel(state, viewModel)
            }

            state.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = StarWindowColors.Crosshair)
            }
            state.message?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = StarWindowColors.WindowStroke)
            }
        }
    }
}

@Composable
private fun StarPanel(state: CalibrationUiState, viewModel: CalibrationViewModel) {
    if (state.observer == null) {
        Text(
            "Ohne Standort lässt sich kein Stern zuordnen. Standort freigeben oder in den " +
                "Einstellungen von Hand eintragen.",
            style = MaterialTheme.typography.bodySmall,
            color = StarWindowColors.Crosshair,
        )
        return
    }
    if (state.candidateStars.isEmpty()) {
        Text(
            "Gerade steht kein heller Stern hoch genug. Aktualisieren oder später erneut versuchen.",
            style = MaterialTheme.typography.bodySmall,
            color = StarWindowColors.Muted,
        )
        TextButton(onClick = viewModel::refreshCandidates) { Text("Aktualisieren") }
        return
    }

    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(state.candidateStars, key = { it.id }) { star ->
            val position = viewModel.currentPositionOf(star)
            FilterChip(
                selected = state.selectedStarId == star.id,
                onClick = { viewModel.selectStar(star.id) },
                label = {
                    Text(
                        star.name.ifBlank { star.id } +
                            (position?.let { " %.0f°".format(it.altitudeDeg) } ?: "")
                    )
                },
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = viewModel::captureStarSighting) { Text("Fadenkreuz sitzt") }
        OutlinedButton(
            onClick = viewModel::applyStarCalibration,
            enabled = state.canApplyStars,
        ) { Text("Übernehmen (${state.starSamples.size})") }
    }

    if (state.starSamples.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        state.starSamples.forEach { sample ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "%s: %.1f° daneben".format(sample.name, sample.errorDeg),
                    style = MaterialTheme.typography.labelMedium,
                    color = StarWindowColors.Starlight,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { viewModel.removeStarSample(sample.objectId) }) {
                    Text("Entfernen")
                }
            }
        }
    }

    if (state.calibration.attitudeSource != CalibrationSource.NONE) {
        TextButton(onClick = viewModel::resetAttitude) { Text("Ausrichtung zurücksetzen") }
    }
}

@Composable
private fun LandmarkPanel(state: CalibrationUiState, viewModel: CalibrationViewModel) {
    OutlinedTextField(
        value = state.landmarkBearingText,
        onValueChange = viewModel::setLandmarkBearingText,
        label = { Text("Wahre Peilung in Grad (0 = Nord)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = viewModel::applyLandmarkBearing) { Text("Peilung übernehmen") }
        if (state.calibration.attitudeSource != CalibrationSource.NONE) {
            OutlinedButton(onClick = viewModel::resetAttitude) { Text("Zurücksetzen") }
        }
    }
    Text(
        "Die Peilung ist geografisch, nicht magnetisch – aus einer Karte abgelesen ist sie genau das.",
        style = MaterialTheme.typography.labelSmall,
        color = StarWindowColors.Muted,
    )
}

@Composable
private fun PanSweepPanel(
    state: CalibrationUiState,
    viewport: SkyViewport,
    viewModel: CalibrationViewModel,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        AssistChip(
            onClick = {},
            label = { Text("${state.panSamples.size} Antippungen") },
        )
        viewport.visibleFovDeg?.let {
            AssistChip(onClick = {}, label = { Text("aktuell %.1f°".format(it)) })
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                viewModel.applyPanSweep(viewport.baselineFocalPx, viewport.viewSize.width.toFloat())
            },
            enabled = state.canApplyPan && viewport.isReady,
        ) { Text("Auswerten") }
        OutlinedButton(
            onClick = viewModel::clearPanSamples,
            enabled = state.panSamples.isNotEmpty(),
        ) { Text("Verwerfen") }
        if (state.calibration.fovSource != CalibrationSource.NONE) {
            TextButton(onClick = viewModel::resetFov) { Text("Zurücksetzen") }
        }
    }
}

@Composable
private fun ManualPanel(state: CalibrationUiState, viewModel: CalibrationViewModel) {
    Slider(
        value = state.calibration.fovScale.toFloat(),
        onValueChange = { viewModel.setManualFovScale(it.toDouble()) },
        valueRange = 0.7f..1.4f,
        steps = 69,
    )
    Text(
        "Faktor %.3f".format(state.calibration.fovScale),
        style = MaterialTheme.typography.labelMedium,
        color = StarWindowColors.Starlight,
        fontFamily = FontFamily.Monospace,
    )
    if (state.calibration.fovSource != CalibrationSource.NONE) {
        TextButton(onClick = viewModel::resetFov) { Text("Zurücksetzen") }
    }
}
