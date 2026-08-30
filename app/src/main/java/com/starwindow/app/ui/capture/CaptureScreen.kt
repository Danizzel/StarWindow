package com.starwindow.app.ui.capture

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NightlightRound
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.starwindow.app.core.astro.Horizontal
import com.starwindow.app.core.calibration.trustAt
import com.starwindow.app.core.camera.EdgeInsets
import com.starwindow.app.core.camera.ExposureMode
import com.starwindow.app.core.camera.SkyProjection
import com.starwindow.app.core.sensors.DeviceAttitude
import com.starwindow.app.core.sensors.compassAccuracyLabel
import com.starwindow.app.data.tracking.TrackTarget
import com.starwindow.app.data.tracking.TrackedObject
import com.starwindow.app.data.tracking.TrackedWindow
import com.starwindow.app.ui.components.ObjectSymbol
import com.starwindow.app.ui.components.rememberSkyViewport
import com.starwindow.app.ui.theme.StarWindowColors

/**
 * The capture screen: viewfinder, sky overlay, and the controls to place points and save a window.
 *
 * The chrome is kept to two bands, one at each edge, because everything between them is the sky.
 * The top band answers "can the app trust what it is showing" and offers the search; the bottom
 * band is what the hands do. Nothing floats in the middle.
 *
 * Wetter, Kalender und Fensterliste standen früher als Symbole in der oberen Leiste und stehen
 * jetzt in der Leiste am unteren Rand ([com.starwindow.app.ui.nav.StarWindowBottomBar]): Es waren
 * nie Werkzeuge des Suchers, sondern andere Orte in der App, und oben, außer Reichweite des
 * Daumens, sahen sie aus wie Knöpfe für den Bildausschnitt. Oben bleibt, was zum Sucher gehört —
 * die Suche, die Nachtsicht und die Einstellungen samt Kalibrierung.
 */
@Composable
fun CaptureScreen(
    viewModel: CaptureViewModel,
    onOpenCalibration: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenTrackedObject: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val hudAttitude by viewModel.hudAttitude.collectAsStateWithLifecycle()
    val trackedTarget by viewModel.trackedTarget.collectAsStateWithLifecycle()

    // Deliberately NOT read during composition — the overlay reads it in the draw phase and the
    // tap handler in a callback, so sensor updates never trigger a recomposition.
    val attitudeState = viewModel.attitude.collectAsStateWithLifecycle()

    var hasCameraPermission by remember {
        mutableStateOf(context.isGranted(Manifest.permission.CAMERA))
    }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showNightVision by remember { mutableStateOf(false) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    /** The mode the user asked for while points were already placed; confirmed before it applies. */
    var pendingMode by remember { mutableStateOf<DrawMode?>(null) }

    // The two control bands are measured rather than guessed, so the arrow pointing at the tracked
    // object is never parked behind them — their height changes with the safe-area insets, with
    // the tracking bar appearing, and with the text the status pills happen to carry.
    var hudHeightPx by remember { mutableStateOf(0) }
    var controlsHeightPx by remember { mutableStateOf(0) }

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

    val viewport = rememberSkyViewport(
        streamInfo = state.streamInfo,
        viewSize = viewSize,
        fovScale = state.settings.calibration.fovScale,
    )

    Box(modifier = modifier.fillMaxSize().background(StarWindowColors.Night)) {
        if (hasCameraPermission) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewSize = it }
                    .pointerInput(viewport) {
                        detectTapGestures { offset ->
                            val attitude = attitudeState.value ?: return@detectTapGestures
                            val projection = SkyProjection(
                                attitude = attitude,
                                focalPx = viewport.focalPx,
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
                    exposure = state.settings.exposure,
                    onStreamInfo = viewModel::onStreamInfo,
                    modifier = Modifier.fillMaxSize(),
                    onError = viewModel::onCameraError,
                )
                SkyOverlay(
                    attitudeState = attitudeState,
                    focalPx = viewport.focalPx,
                    anchors = state.anchors,
                    shape = state.shape,
                    catalog = state.catalog,
                    observer = state.observer,
                    showGraticule = state.settings.showGraticule,
                    showCatalog = state.settings.showCatalogOverlay,
                    trackedTarget = trackedTarget,
                    chromeInsets = EdgeInsets(
                        top = hudHeightPx.toFloat(),
                        bottom = controlsHeightPx.toFloat(),
                    ),
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
            Box(modifier = Modifier.onSizeChanged { hudHeightPx = it.height }) {
                CaptureHud(
                    state = state,
                    attitude = hudAttitude,
                    visibleFovDeg = viewport.visibleFovDeg,
                    hasOrientationSensor = viewModel.hasOrientationSensor,
                    nightMode = state.settings.exposure.mode == ExposureMode.NIGHT,
                    onOpenSearch = onOpenSearch,
                    onOpenSettings = { showSettings = true },
                    onOpenNightVision = { showNightVision = true },
                )
            }

            Spacer(Modifier.weight(1f))

            Column(modifier = Modifier.onSizeChanged { controlsHeightPx = it.height }) {
                state.tracked?.let { tracked ->
                    TrackedTargetBar(
                        tracked = tracked,
                        target = trackedTarget,
                        // Only a catalogue entry has an info sheet to open; a window has its own
                        // detail screen, reachable from the list where it was picked.
                        onOpenInfo = (tracked as? TrackedObject)
                            ?.let { { onOpenTrackedObject(it.obj.id) } },
                        onStop = viewModel::stopTracking,
                    )
                }

                CaptureControls(
                    state = state,
                    // Switching mode throws the placed points away, because they mean something
                    // different in each mode. Ask first — but only when there is something to lose,
                    // so the dialog stays a warning instead of becoming a reflex to tap through.
                    onModeChange = { mode ->
                        if (state.anchors.isEmpty() || mode == state.mode) {
                            viewModel.setMode(mode)
                        } else {
                            pendingMode = mode
                        }
                    },
                    onUndo = viewModel::undoAnchor,
                    onClear = viewModel::clearAnchors,
                    onSave = { showSaveDialog = true },
                )
            }
        }
    }

    pendingMode?.let { mode ->
        DiscardAnchorsDialog(
            mode = mode,
            anchorCount = state.anchors.size,
            onConfirm = {
                viewModel.setMode(mode)
                pendingMode = null
            },
            onDismiss = { pendingMode = null },
        )
    }

    if (showSaveDialog) {
        SaveWindowDialog(
            defaultName = "",
            onDismiss = { showSaveDialog = false },
            onConfirm = { name ->
                viewModel.saveWindow(name, viewport.visibleFovDeg)
                showSaveDialog = false
            },
        )
    }

    if (showSettings) {
        CaptureSettingsDialog(
            settings = state.settings,
            observer = state.observer,
            onDismiss = { showSettings = false },
            onToggleGraticule = viewModel::toggleGraticule,
            onToggleCatalog = viewModel::toggleCatalogOverlay,
            onManualLocation = viewModel::setManualLocation,
            onOpenCalibration = {
                showSettings = false
                onOpenCalibration()
            },
        )
    }

    if (showNightVision) {
        NightVisionDialog(
            exposure = state.settings.exposure,
            capabilities = state.streamInfo?.exposureCapabilities,
            onExposureChange = viewModel::setExposure,
            onModeChange = viewModel::setExposureMode,
            onDismiss = { showNightVision = false },
        )
    }

    state.message?.let { message ->
        LaunchedEffect(message) {
            kotlinx.coroutines.delay(2500)
            viewModel.consumeMessage()
        }
        Box(modifier = Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.BottomCenter) {
            Surface(
                shape = RoundedCornerShape(50),
                color = StarWindowColors.NightSurfaceTop,
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

/**
 * The top band: the way in to the search, and what the app knows about where it is and where it
 * points.
 *
 * The status used to be two lines of prose. It is now a row of pills, one fact each, coloured by
 * whether that fact is good enough to rely on — red position, amber compass, green field of view
 * can all be read at a glance in the dark, which two sentences of grey text never could.
 */
@Composable
private fun CaptureHud(
    state: CaptureUiState,
    attitude: DeviceAttitude?,
    visibleFovDeg: Double?,
    hasOrientationSensor: Boolean,
    nightMode: Boolean,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNightVision: () -> Unit,
) {
    Surface(color = StarWindowColors.Night.copy(alpha = 0.82f)) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SearchPill(onClick = onOpenSearch, modifier = Modifier.weight(1f))
                IconButton(onClick = onOpenNightVision) {
                    Icon(
                        Icons.Filled.NightlightRound,
                        contentDescription = "Sucher / Nachtsicht",
                        tint = if (nightMode) StarWindowColors.WindowStroke else StarWindowColors.Starlight,
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = "Einstellungen und Kalibrierung",
                        tint = StarWindowColors.Starlight,
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val observer = state.observer
                StatusPill(
                    icon = Icons.Filled.MyLocation,
                    text = if (observer == null) {
                        "keine Position"
                    } else {
                        "%.3f°, %.3f°%s".format(
                            observer.latitudeDeg,
                            observer.longitudeDeg,
                            if (observer.manual) " (manuell)" else "",
                        )
                    },
                    tint = if (observer == null) StarWindowColors.Crosshair else StarWindowColors.Starlight,
                )
                StatusPill(
                    icon = Icons.Filled.Explore,
                    text = if (hasOrientationSensor) {
                        buildString {
                            append(attitude?.source?.label ?: "Lage")
                            append("  ")
                            append(compassAccuracyLabel(attitude?.accuracy ?: -1))
                        }
                    } else {
                        "kein Sensor"
                    },
                    tint = when {
                        !hasOrientationSensor -> StarWindowColors.Crosshair
                        (attitude?.accuracy ?: -1) < 2 -> StarWindowColors.AnchorPoint
                        else -> StarWindowColors.Starlight
                    },
                )

                // Only shown while something is actually wrong: a warning that is always there is
                // one nobody reads when it finally matters. The three cases are kept apart because
                // the user can do something different about each — wave a figure eight, walk away
                // from the iron, or simply wait.
                if (attitude?.needsCompassCalibration == true) {
                    StatusPill(
                        icon = Icons.Filled.Warning,
                        text = "Kompass kalibrieren: liegende Acht schwenken",
                        tint = StarWindowColors.Crosshair,
                    )
                } else if (attitude?.isMagneticallyDisturbed == true || attitude?.headingHeld == true) {
                    StatusPill(
                        icon = Icons.Filled.Warning,
                        text = buildString {
                            when {
                                attitude.hasFieldDirectionDistortion -> {
                                    append("Feldrichtung gestört")
                                    val dip = attitude.inclinationDeg
                                    val expected = attitude.expectedInclinationDeg
                                    if (dip != null && expected != null) {
                                        append(" %+.0f°".format(dip - expected))
                                    }
                                }

                                attitude.hasFieldStrengthDistortion -> {
                                    append("Magnetstörung")
                                    attitude.fieldDeviationMicroTesla
                                        ?.let { append(" %+.0f µT".format(it)) }
                                }

                                else -> append("Kompass unsicher")
                            }
                            append(" – Nord gehalten")
                            // A held heading ages. Saying for how long, and how far it may have
                            // wandered, is the difference between a warning and a fact.
                            if (attitude.headingDriftDeg >= 0.5) {
                                append(" (±%.0f°)".format(attitude.headingDriftDeg))
                            }
                        },
                        tint = StarWindowColors.Crosshair,
                    )
                }

                // A stored calibration that no longer describes this place is worse than none: it
                // would be applied with full confidence to a direction it cannot correct.
                val trust = state.settings.calibration.trustAt(state.observer, System.currentTimeMillis())
                if (trust.isQuestionable) {
                    StatusPill(
                        icon = Icons.Filled.Warning,
                        text = "Kalibrierung ${trust.label}",
                        tint = if (trust.needsRemeasuring) {
                            StarWindowColors.Crosshair
                        } else {
                            StarWindowColors.AnchorPoint
                        },
                    )
                }

                visibleFovDeg?.let {
                    StatusPill(
                        icon = Icons.Filled.CropFree,
                        text = "Bildfeld %.1f°".format(it),
                        tint = StarWindowColors.Starlight,
                    )
                }
                attitude?.magneticDeclinationDeg?.let {
                    StatusPill(
                        icon = Icons.Filled.Explore,
                        text = "Missweisung %+.1f°".format(it),
                        tint = StarWindowColors.Muted,
                    )
                }
            }

            state.cameraError?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = StarWindowColors.Crosshair,
                )
            }
        }
    }
}

/** Looks like a search field, is a button: the real field lives on the search screen. */
@Composable
private fun SearchPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = StarWindowColors.NightSurfaceHigh,
        shape = RoundedCornerShape(50),
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = StarWindowColors.Muted,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = "Nebel, Stern oder Galaxie suchen",
                style = MaterialTheme.typography.bodySmall,
                color = StarWindowColors.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StatusPill(icon: ImageVector, text: String, tint: Color) {
    Surface(color = StarWindowColors.tint(tint, 0.14f), shape = RoundedCornerShape(50)) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
            Spacer(Modifier.size(5.dp))
            Text(text, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
        }
    }
}

/**
 * The bar for the object being tracked.
 *
 * It repeats what the arrow already shows, in numbers: the arrow says *which way*, this says *how
 * far* and whether the thing is even above the horizon — which the arrow cannot, and which is the
 * difference between "keep turning" and "come back in four hours".
 */
@Composable
private fun TrackedTargetBar(
    tracked: TrackTarget,
    target: SkyTarget?,
    onOpenInfo: (() -> Unit)?,
    onStop: () -> Unit,
) {
    val position = target?.direction
    val pathLabel = target?.pathLabel.orEmpty()
    Surface(color = StarWindowColors.NightSurface.copy(alpha = 0.92f)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (tracked) {
                is TrackedObject ->
                    ObjectSymbol(tracked.type, size = 20.dp, color = StarWindowColors.TrackTarget)

                is TrackedWindow -> Icon(
                    Icons.Filled.CropFree,
                    contentDescription = null,
                    tint = StarWindowColors.TrackTarget,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tracked.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = StarWindowColors.TrackTarget,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        position == null -> "Ohne Position lässt sich die Richtung nicht berechnen"
                        // With a path shown, the arrow points at the arc's high point rather than
                        // at the object's live position — say so, or the bearing looks wrong to
                        // anyone who knows where the object actually is tonight.
                        pathLabel.isNotEmpty() -> "Bahn $pathLabel  ·  Pfeil zeigt zum höchsten Punkt"
                        position.altitudeDeg < 0.0 ->
                            "steht %.0f° unter dem Horizont – jetzt nicht zu sehen"
                                .format(-position.altitudeDeg)
                        else -> "Az %.0f°  ·  Höhe %.0f°  ·  dem Pfeil folgen".format(
                            position.azimuthDeg,
                            position.altitudeDeg,
                        )
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (position != null && position.altitudeDeg < 0.0) {
                        StarWindowColors.AnchorPoint
                    } else {
                        StarWindowColors.Muted
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            onOpenInfo?.let {
                IconButton(onClick = it) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = "Infos zu ${tracked.label}",
                        tint = StarWindowColors.CatalogMarker,
                    )
                }
            }
            IconButton(onClick = onStop) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Verfolgung beenden",
                    tint = StarWindowColors.Muted,
                )
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
    Surface(color = StarWindowColors.Night.copy(alpha = 0.88f)) {
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
                    // „Punkt(e)" spart eine Zeile Code und kostet die Zeile, die der Nutzer liest.
                    if (state.missingAnchors == 1) {
                        "${state.mode.hint} · noch ein Punkt"
                    } else {
                        "${state.mode.hint} · noch ${state.missingAnchors} Punkte"
                    }
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

            if (state.outlineCrossesItself) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = StarWindowColors.AnchorPoint,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.size(5.dp))
                    Text(
                        text = "Die Kontur überschneidet sich – Fläche und Durchgänge stimmen so " +
                            "nicht. Ecken der Reihe nach antippen.",
                        style = MaterialTheme.typography.labelSmall,
                        color = StarWindowColors.AnchorPoint,
                    )
                }
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
