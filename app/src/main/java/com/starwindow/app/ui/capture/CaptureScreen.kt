package com.starwindow.app.ui.capture

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.starwindow.app.core.camera.PreviewFit
import com.starwindow.app.core.camera.SkyProjection
import com.starwindow.app.core.sensors.compassAccuracyLabel
import com.starwindow.app.ui.theme.StarWindowColors

/**
 * The capture screen: viewfinder, sky overlay, and the controls to place points and save a window.
 */
@Composable
fun CaptureScreen(
    viewModel: CaptureViewModel,
    onOpenWindows: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val hudAttitude by viewModel.hudAttitude.collectAsStateWithLifecycle()

    // Deliberately NOT read during composition — the overlay reads it in the draw phase and the
    // tap handler in a callback, so sensor updates never trigger a recomposition.
    val attitudeState = viewModel.attitude.collectAsStateWithLifecycle()

    var hasCameraPermission by remember {
        mutableStateOf(context.isGranted(Manifest.permission.CAMERA))
    }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        hasCameraPermission = granted[Manifest.permission.CAMERA] == true ||
            context.isGranted(Manifest.permission.CAMERA)
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            viewModel.startLocationUpdates()
        }
    }

    // Ask once on first entry. Re-entering the screen with the permissions already granted must
    // not pop the system dialog again.
    LaunchedEffect(Unit) {
        if (!hasCameraPermission || !context.isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
        }
    }

    val focalPx = remember(state.streamInfo, viewSize, state.settings.fovScale) {
        val info = state.streamInfo
        if (info == null || viewSize == IntSize.Zero) {
            0.0
        } else {
            SkyProjection.focalLengthInViewPixels(
                intrinsics = info.intrinsics,
                streamWidth = info.streamWidth,
                streamHeight = info.streamHeight,
                rotationDegrees = info.rotationDegrees,
                viewWidthPx = viewSize.width.toFloat(),
                viewHeightPx = viewSize.height.toFloat(),
                fit = PreviewFit.FIT_CENTER,
                fovScale = state.settings.fovScale,
            )
        }
    }

    val visibleFovDeg = remember(focalPx, viewSize) {
        if (focalPx <= 0.0 || viewSize == IntSize.Zero) {
            null
        } else {
            2.0 * Math.toDegrees(kotlin.math.atan(viewSize.width / (2.0 * focalPx)))
        }
    }

    Box(modifier = modifier.fillMaxSize().background(StarWindowColors.Night)) {
        if (hasCameraPermission) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewSize = it }
                    .pointerInput(focalPx) {
                        detectTapGestures { offset ->
                            val attitude = attitudeState.value ?: return@detectTapGestures
                            val projection = SkyProjection(
                                attitude = attitude,
                                focalPx = focalPx,
                                viewWidthPx = size.width.toFloat(),
                                viewHeightPx = size.height.toFloat(),
                            )
                            if (projection.isUsable) {
                                viewModel.addAnchor(projection.screenToSky(offset.x, offset.y))
                            }
                        }
                    }
            ) {
                CameraPreview(
                    modifier = Modifier.fillMaxSize(),
                    onStreamInfo = viewModel::onStreamInfo,
                    onError = viewModel::onCameraError,
                )
                SkyOverlay(
                    attitudeState = attitudeState,
                    focalPx = focalPx,
                    anchors = state.anchors,
                    shape = state.shape,
                    catalog = state.catalog,
                    observer = state.observer,
                    showGraticule = state.settings.showGraticule,
                    showCatalog = state.settings.showCatalogOverlay,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            PermissionPlaceholder(
                onRequest = {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.CAMERA,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                        )
                    )
                },
                modifier = Modifier.align(Alignment.Center),
            )
        }

        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            CaptureHud(
                state = state,
                compassAccuracy = hudAttitude?.accuracy,
                declinationDeg = hudAttitude?.magneticDeclinationDeg,
                visibleFovDeg = visibleFovDeg,
                hasOrientationSensor = viewModel.hasOrientationSensor,
                onOpenWindows = onOpenWindows,
                onOpenSettings = { showSettings = true },
            )

            Spacer(Modifier.weight(1f))

            CaptureControls(
                state = state,
                onModeChange = viewModel::setMode,
                onUndo = viewModel::undoAnchor,
                onClear = viewModel::clearAnchors,
                onSave = { showSaveDialog = true },
            )
        }
    }

    if (showSaveDialog) {
        SaveWindowDialog(
            defaultName = "",
            onDismiss = { showSaveDialog = false },
            onConfirm = { name ->
                viewModel.saveWindow(name, visibleFovDeg)
                showSaveDialog = false
            },
        )
    }

    if (showSettings) {
        CaptureSettingsDialog(
            settings = state.settings,
            observer = state.observer,
            onDismiss = { showSettings = false },
            onFovScaleChange = viewModel::setFovScale,
            onToggleGraticule = viewModel::toggleGraticule,
            onToggleCatalog = viewModel::toggleCatalogOverlay,
        )
    }

    state.message?.let { message ->
        LaunchedEffect(message) {
            kotlinx.coroutines.delay(2500)
            viewModel.consumeMessage()
        }
        Box(modifier = Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.BottomCenter) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = StarWindowColors.NightSurfaceHigh,
                modifier = Modifier.padding(bottom = 120.dp),
            ) {
                Text(
                    text = message,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    color = StarWindowColors.Starlight,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** Status line: what the app knows about where it is, where it points and how much it can trust it. */
@Composable
private fun CaptureHud(
    state: CaptureUiState,
    compassAccuracy: Int?,
    declinationDeg: Double?,
    visibleFovDeg: Double?,
    hasOrientationSensor: Boolean,
    onOpenWindows: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Surface(color = Color.Black.copy(alpha = 0.55f)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val observer = state.observer
                Text(
                    text = if (observer == null) {
                        "Position unbekannt – ohne sie gibt es keine Himmelskoordinaten"
                    } else {
                        "%.4f°, %.4f°%s".format(
                            observer.latitudeDeg,
                            observer.longitudeDeg,
                            if (observer.manual) " (manuell)" else "",
                        )
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (state.observer == null) StarWindowColors.Crosshair else StarWindowColors.Starlight,
                )
                Text(
                    text = buildString {
                        append("Kompass: ")
                        append(if (hasOrientationSensor) compassAccuracyLabel(compassAccuracy ?: -1) else "kein Sensor")
                        declinationDeg?.let { append("  ·  Deklination %.1f°".format(it)) }
                        visibleFovDeg?.let { append("  ·  Bildfeld %.1f°".format(it)) }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = StarWindowColors.Muted,
                )
                state.cameraError?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = StarWindowColors.Crosshair,
                    )
                }
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Einstellungen", tint = StarWindowColors.Starlight)
            }
            IconButton(onClick = onOpenWindows) {
                Icon(Icons.Filled.ViewList, contentDescription = "Gespeicherte Fenster", tint = StarWindowColors.Starlight)
            }
        }
    }
}

@Composable
private fun CaptureControls(
    state: CaptureUiState,
    onModeChange: (DrawMode) -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onSave: () -> Unit,
) {
    Surface(color = Color.Black.copy(alpha = 0.6f)) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DrawMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.mode == mode,
                        onClick = { onModeChange(mode) },
                        label = { Text(mode.label) },
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            Text(
                text = if (state.missingAnchors > 0) {
                    "${state.mode.hint} · noch ${state.missingAnchors} Punkt(e)"
                } else {
                    state.mode.hint
                },
                style = MaterialTheme.typography.labelSmall,
                color = StarWindowColors.Muted,
            )

            state.shape?.let { shape ->
                val center = shape.center()
                Text(
                    text = "Fläche %.1f°²  ·  Mitte Az %.1f° / Alt %.1f°".format(
                        shape.areaSquareDeg(),
                        center.azimuthDeg,
                        center.altitudeDeg,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = StarWindowColors.WindowStroke,
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onUndo, enabled = state.anchors.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Letzten Punkt entfernen")
                }
                IconButton(onClick = onClear, enabled = state.anchors.isNotEmpty()) {
                    Icon(Icons.Filled.Delete, contentDescription = "Alle Punkte löschen")
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = onSave, enabled = state.canSave) {
                    Text("Fenster speichern")
                }
            }
        }
    }
}

@Composable
private fun PermissionPlaceholder(onRequest: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "StarWindow braucht die Kamera, um den Himmelsausschnitt zu zeichnen, " +
                "und den Standort, um ihn Himmelskoordinaten zuzuordnen.",
            textAlign = TextAlign.Center,
            color = StarWindowColors.Starlight,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRequest) { Text("Berechtigungen erteilen") }
    }
}

private fun android.content.Context.isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
