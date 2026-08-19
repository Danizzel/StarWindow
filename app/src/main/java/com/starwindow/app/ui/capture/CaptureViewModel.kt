package com.starwindow.app.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starwindow.app.AppContainer
import com.starwindow.app.core.astro.Horizontal
import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.core.camera.ExposureMode
import com.starwindow.app.core.camera.ExposureSettings
import com.starwindow.app.core.geometry.AltAzBoxWindow
import com.starwindow.app.core.geometry.CircleWindow
import com.starwindow.app.core.geometry.PolygonWindow
import com.starwindow.app.core.geometry.SkyWindow
import com.starwindow.app.core.geometry.WindowShape
import com.starwindow.app.core.sensors.DeviceAttitude
import com.starwindow.app.core.sensors.LocationTracker
import com.starwindow.app.core.sensors.OrientationTracker
import com.starwindow.app.data.catalog.CatalogRepository
import com.starwindow.app.data.catalog.SkyObject
import com.starwindow.app.data.windows.Settings
import com.starwindow.app.data.windows.SettingsStore
import com.starwindow.app.data.windows.SkyWindowRepository
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which kind of window the next taps build. */
enum class DrawMode(val label: String, val hint: String, val requiredAnchors: Int) {
    POLYGON("Polygon", "Ecken antippen – ab 3 Punkten entsteht die Fläche", 3),
    CIRCLE("Kreis", "1. Tipp = Mittelpunkt, 2. Tipp = Rand", 2),
    BOX("Rechteck", "Zwei gegenüberliegende Ecken antippen", 2),
}

data class CaptureUiState(
    val mode: DrawMode = DrawMode.POLYGON,
    val anchors: List<Horizontal> = emptyList(),
    val observer: ObserverLocation? = null,
    val settings: Settings = Settings(),
    val catalog: List<SkyObject> = emptyList(),
    val streamInfo: PreviewStreamInfo? = null,
    val cameraError: String? = null,
    val message: String? = null,
) {
    /** The window the current anchors describe, or null while there are not enough of them. */
    val shape: WindowShape? get() = buildShape(mode, anchors)

    val canSave: Boolean get() = shape != null && observer != null

    val missingAnchors: Int get() = (mode.requiredAnchors - anchors.size).coerceAtLeast(0)
}

/** Builds the shape for a draw mode from the points the user placed. */
fun buildShape(mode: DrawMode, anchors: List<Horizontal>): WindowShape? = when (mode) {
    DrawMode.POLYGON -> if (anchors.size >= 3) PolygonWindow(anchors) else null
    DrawMode.CIRCLE -> if (anchors.size >= 2) {
        CircleWindow.fromCenterAndEdge(anchors[0], anchors[1])
    } else {
        null
    }
    DrawMode.BOX -> if (anchors.size >= 2) {
        AltAzBoxWindow.fromCorners(anchors[0], anchors[1])
    } else {
        null
    }
}

class CaptureViewModel(
    private val orientationTracker: OrientationTracker,
    private val locationTracker: LocationTracker,
    private val windowRepository: SkyWindowRepository,
    private val catalogRepository: CatalogRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CaptureUiState())
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    /**
     * Kept separate from [uiState] on purpose: this updates ~50 times a second and only the
     * overlay cares. Folding it into the screen state would recompose the whole screen at sensor
     * rate.
     */
    val attitude: StateFlow<DeviceAttitude?> = orientationTracker.attitudes()
        .catch { /* No rotation vector on this device; the overlay shows the warning. */ }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The same stream, thinned out for the text HUD. Reading the full-rate flow from a composable
     * would recompose the header at sensor rate for numbers nobody can read that fast.
     */
    val hudAttitude: StateFlow<DeviceAttitude?> = attitude
        .sample(250)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val hasOrientationSensor: Boolean = orientationTracker.isAvailable

    private var lastFix: ObserverLocation? = null
    private var locationJob: Job? = null

    init {
        viewModelScope.launch {
            settingsStore.settings.collect { settings ->
                _uiState.update { it.copy(settings = settings) }
                // The tracker stamps the calibration onto every attitude, so no consumer can end
                // up with an uncorrected direction by accident.
                orientationTracker.updateCalibration(settings.calibration)
                applyObserver(settings.manualLocation ?: lastFix)
            }
        }
        viewModelScope.launch {
            _uiState.update { it.copy(catalog = catalogRepository.objects()) }
        }
        viewModelScope.launch { windowRepository.load() }
        startLocationUpdates()
    }

    /**
     * (Re)starts the location stream. Call it again once the location permission is granted —
     * the tracker refuses to emit before that.
     */
    fun startLocationUpdates() {
        locationJob?.cancel()
        locationJob = viewModelScope.launch {
            locationTracker.locations().collect { fix ->
                lastFix = fix
                applyObserver(settingsStore.settings.value.manualLocation ?: fix)
            }
        }
    }

    private fun applyObserver(observer: ObserverLocation?) {
        orientationTracker.updateLocation(observer)
        _uiState.update { it.copy(observer = observer) }
    }

    fun setMode(mode: DrawMode) = _uiState.update {
        // Switching the mode always starts a fresh outline: the anchors mean different things.
        it.copy(mode = mode, anchors = emptyList())
    }

    fun addAnchor(direction: Horizontal) = _uiState.update { state ->
        val limit = when (state.mode) {
            DrawMode.POLYGON -> MAX_POLYGON_ANCHORS
            DrawMode.CIRCLE, DrawMode.BOX -> 2
        }
        val anchors = if (state.anchors.size >= limit) {
            // Circle and box only ever have two points; a further tap replaces the second one.
            state.anchors.dropLast(1) + direction
        } else {
            state.anchors + direction
        }
        state.copy(anchors = anchors, message = null)
    }

    fun undoAnchor() = _uiState.update { it.copy(anchors = it.anchors.dropLast(1)) }

    fun clearAnchors() = _uiState.update { it.copy(anchors = emptyList()) }

    fun onStreamInfo(info: PreviewStreamInfo) = _uiState.update {
        it.copy(streamInfo = info, cameraError = null)
    }

    fun onCameraError(message: String) = _uiState.update { it.copy(cameraError = message) }

    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    fun setExposure(exposure: ExposureSettings) = settingsStore.setExposure(exposure)

    /**
     * Switches the viewfinder between automatic and night mode. Entering night mode seeds sensible
     * values for this particular camera rather than whatever was left over from another device.
     */
    fun setExposureMode(mode: ExposureMode) {
        val capabilities = _uiState.value.streamInfo?.exposureCapabilities
        val current = _uiState.value.settings.exposure
        val next = when {
            mode == ExposureMode.AUTO -> current.copy(mode = ExposureMode.AUTO)
            capabilities == null -> current.copy(mode = ExposureMode.NIGHT)
            current.mode == ExposureMode.NIGHT -> current
            else -> ExposureSettings.nightDefault(capabilities)
        }
        settingsStore.setExposure(next)
    }

    fun setManualLocation(location: ObserverLocation?) {
        settingsStore.setManualLocation(location)
        applyObserver(location ?: lastFix)
    }

    fun toggleGraticule() =
        settingsStore.setShowGraticule(!_uiState.value.settings.showGraticule)

    fun toggleCatalogOverlay() =
        settingsStore.setShowCatalogOverlay(!_uiState.value.settings.showCatalogOverlay)

    /** Persists the current outline. Returns immediately; the result arrives via [uiState]. */
    fun saveWindow(name: String, visibleFovDeg: Double?) {
        val state = _uiState.value
        val shape = state.shape
        val observer = state.observer
        if (shape == null || observer == null) {
            _uiState.update { it.copy(message = "Zum Speichern fehlen noch Punkte oder die Position") }
            return
        }

        val window = SkyWindow(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { defaultName(state) },
            shape = shape,
            observer = observer,
            capturedAtMillis = System.currentTimeMillis(),
            magneticDeclinationDeg = attitude.value?.magneticDeclinationDeg ?: 0.0,
            compassAccuracy = attitude.value?.accuracy ?: 0,
            cameraFovDeg = visibleFovDeg,
        )

        viewModelScope.launch {
            windowRepository.save(window)
            _uiState.update {
                it.copy(anchors = emptyList(), message = "\"${window.name}\" gespeichert")
            }
        }
    }

    private fun defaultName(state: CaptureUiState): String {
        val center = state.shape?.center()
        return if (center == null) {
            "Fenster"
        } else {
            "Fenster %.0f°/%.0f°".format(center.azimuthDeg, center.altitudeDeg)
        }
    }

    companion object {
        private const val MAX_POLYGON_ANCHORS = 32

        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                CaptureViewModel(
                    orientationTracker = container.orientationTracker,
                    locationTracker = container.locationTracker,
                    windowRepository = container.windowRepository,
                    catalogRepository = container.catalogRepository,
                    settingsStore = container.settingsStore,
                )
            }
        }
    }
}
