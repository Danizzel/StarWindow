package com.starwindow.app.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starwindow.app.AppContainer
import com.starwindow.app.core.astro.AstroTime
import com.starwindow.app.core.astro.CoordinateTransforms
import com.starwindow.app.core.astro.Horizontal
import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.core.astro.Precession
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
import com.starwindow.app.data.tracking.TrackTarget
import com.starwindow.app.data.tracking.TrackedObject
import com.starwindow.app.data.tracking.TrackedWindow
import com.starwindow.app.data.tracking.TrackingStore
import com.starwindow.app.data.windows.Settings
import com.starwindow.app.data.windows.SettingsStore
import com.starwindow.app.data.windows.SkyWindowRepository
import com.starwindow.app.domain.OverlaySelection
import com.starwindow.app.domain.SkyTrackBuilder
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    /** The object the overlay points at, picked in the search. */
    val tracked: TrackTarget? = null,
) {
    /** The window the current anchors describe, or null while there are not enough of them. */
    val shape: WindowShape? get() = buildShape(mode, anchors)

    val canSave: Boolean get() = shape != null && observer != null

    val missingAnchors: Int get() = (mode.requiredAnchors - anchors.size).coerceAtLeast(0)

    /**
     * True when the corners were tapped in an order that makes the outline cross itself.
     *
     * Saving is still allowed — it is the user's window — but the area and the transit list would
     * be quietly wrong, and on a dark screen a bow tie does not look obviously different from the
     * shape that was meant.
     */
    val outlineCrossesItself: Boolean
        get() = (shape as? PolygonWindow)?.isSelfIntersecting == true
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
    private val trackingStore: TrackingStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CaptureUiState())
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    /**
     * The tracked target resolved to a direction, refreshed once a second.
     *
     * A catalogue object is fixed to the sky and drifts across the viewfinder at fifteen arcseconds
     * a second; a saved window is fixed to the horizon and never moves at all. Both come out of here
     * as a plain direction, so neither the overlay nor the status bar has to know the difference —
     * and a second's worth of drift, four thousandths of a degree, is far below what the sensor can
     * resolve.
     */
    val trackedTarget: StateFlow<SkyTarget?> =
        combine(
            trackingStore.target,
            _uiState.map { it.observer }.distinctUntilChanged(),
            flow {
                while (true) {
                    emit(System.currentTimeMillis())
                    delay(1_000)
                }
            },
        ) { target, observer, now ->
            when (target) {
                null -> null

                // Horizon-fixed: the centre of the window is the direction, no astronomy needed.
                is TrackedWindow -> SkyTarget(
                    label = target.name,
                    direction = target.shape.center(),
                    shape = target.shape,
                )

                is TrackedObject -> observer?.let {
                    // The path, when the user asked for one, is built here rather than in the
                    // overlay: it needs sidereal time and precession for a few dozen samples, and
                    // the draw lambda runs fifty times a second.
                    val track = target.takeIf { t -> t.hasPath }?.let { t ->
                        SkyTrackBuilder.overSpan(
                            label = t.label,
                            equatorial = t.obj.positionAt(Precession.forEpoch(now)),
                            observer = it,
                            fromMillis = requireNotNull(t.pathFromMillis),
                            toMillis = requireNotNull(t.pathToMillis),
                        )
                    }
                    val livePosition = CoordinateTransforms.apparentHorizontalAtLst(
                        target.obj.positionAt(Precession.forEpoch(now)),
                        it.latitudeDeg,
                        AstroTime.lstDeg(now, it.longitudeDeg),
                    )
                    SkyTarget(
                        label = target.label,
                        // With a path, the arrow leads to the path — **not** to where the object
                        // happens to be right now. For a night three months out those are opposite
                        // corners of the sky, and pointing at the live position would walk the user
                        // away from the very arc they asked to see. The highest point of the arc is
                        // the natural place to aim: it is what the night is about, and it is the
                        // part most likely to clear a roofline.
                        direction = track?.points
                            ?.maxByOrNull { point -> point.position.altitudeDeg }
                            ?.position
                            ?: livePosition,
                        type = target.type,
                        path = track?.points.orEmpty().map { point -> point.position },
                        pathHourMarks = track?.hourMarks().orEmpty().map { point -> point.position },
                        pathLabel = target.pathLabel,
                    )
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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

    /** The whole catalogue; what the overlay draws is a small selection from it. */
    private var fullCatalog: List<SkyObject> = emptyList()
    private var overlayJob: Job? = null

    init {
        viewModelScope.launch {
            settingsStore.settings.collect { settings ->
                val limitChanged = _uiState.value.settings.magnitudeLimit != settings.magnitudeLimit
                _uiState.update { it.copy(settings = settings) }
                // The tracker stamps the calibration onto every attitude, so no consumer can end
                // up with an uncorrected direction by accident.
                orientationTracker.updateCalibration(settings.calibration)
                applyObserver(settings.manualLocation ?: lastFix)
                if (limitChanged) refreshOverlayCatalog()
            }
        }
        viewModelScope.launch {
            fullCatalog = catalogRepository.objects()
            refreshOverlayCatalog()
        }
        viewModelScope.launch {
            trackingStore.target.collect { target ->
                _uiState.update { it.copy(tracked = target) }
            }
        }
        viewModelScope.launch { windowRepository.load() }
        startLocationUpdates()
    }

    /** Stops pointing at the tracked object or window. */
    fun stopTracking() = trackingStore.clear()

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
        val latitudeChanged = _uiState.value.observer?.latitudeDeg != observer?.latitudeDeg
        _uiState.update { it.copy(observer = observer) }
        // Half the catalogue never rises here, and which half depends on the latitude.
        if (latitudeChanged) refreshOverlayCatalog()
    }

    /**
     * Rebuilds the list of objects the overlay draws.
     *
     * Off the main thread and only when something it depends on has actually changed: it walks
     * twenty-two thousand entries, which is nothing once but would be felt if it ran on every
     * location update — those arrive every few seconds.
     */
    private fun refreshOverlayCatalog() {
        val catalog = fullCatalog
        if (catalog.isEmpty()) return
        overlayJob?.cancel()
        overlayJob = viewModelScope.launch {
            val state = _uiState.value
            val selected = withContext(Dispatchers.Default) {
                OverlaySelection.select(
                    catalog = catalog,
                    latitudeDeg = state.observer?.latitudeDeg,
                    magnitudeLimit = state.settings.magnitudeLimit,
                )
            }
            _uiState.update { it.copy(catalog = selected) }
        }
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
            // How good the pointing was at this moment, recorded with the window: months later
            // nothing else can tell whether the outline is worth a tenth of a degree or ten.
            calibrationResidualDeg = state.settings.calibration
                .takeIf { it.hasAttitudeCorrection }
                ?.attitudeResidualDeg,
            headingHeld = attitude.value?.headingHeld ?: false,
            headingHeldSeconds = attitude.value?.headingHeldSeconds ?: 0.0,
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
                    trackingStore = container.trackingStore,
                )
            }
        }
    }
}
