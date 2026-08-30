package com.starwindow.app.ui.calibration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starwindow.app.AppContainer
import com.starwindow.app.core.astro.AstroTime
import com.starwindow.app.core.astro.CoordinateTransforms
import com.starwindow.app.core.astro.Precession
import com.starwindow.app.core.astro.Horizontal
import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.core.calibration.AttitudeFit
import com.starwindow.app.core.calibration.Calibration
import com.starwindow.app.core.calibration.CalibrationSource
import com.starwindow.app.core.calibration.DirectionPair
import com.starwindow.app.core.calibration.FieldOfViewSolver
import com.starwindow.app.core.calibration.FovFitException
import com.starwindow.app.core.calibration.PanSighting
import com.starwindow.app.core.geometry.SphericalGeometry
import com.starwindow.app.core.sensors.DeviceAttitude
import com.starwindow.app.core.sensors.LocationTracker
import com.starwindow.app.core.sensors.OrientationTracker
import com.starwindow.app.data.catalog.CatalogRepository
import com.starwindow.app.data.catalog.SkyObject
import com.starwindow.app.data.windows.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The four ways to calibrate. None of them is mandatory, and the app is usable with none of them
 * done — which is the point: under a partly obstructed or overcast sky the star method simply is
 * not available, and the other three still are.
 */
enum class CalibrationMethod(
    val label: String,
    val corrects: String,
    val needsClearSky: Boolean,
    val instructions: String,
) {
    STARS(
        label = "Sternmuster",
        corrects = "Ausrichtung",
        needsClearSky = true,
        instructions = "Fadenkreuz auf einen der vorgeschlagenen Sterne halten und bestätigen. " +
            "Ein Stern korrigiert die Nordrichtung, ab zwei weit auseinanderliegenden Sternen " +
            "auch die Neigung.",
    ),
    LANDMARK(
        label = "Peilung",
        corrects = "Nordrichtung",
        needsClearSky = false,
        instructions = "Fadenkreuz auf einen Punkt halten, dessen wahre Peilung bekannt ist " +
            "(Karte, Kirchturm, Mast), und diese Peilung eintragen. Funktioniert bei jedem Wetter " +
            "und am Tag.",
    ),
    PAN_SWEEP(
        label = "Schwenk",
        corrects = "Bildfeld",
        needsClearSky = false,
        instructions = "Ein beliebiges markantes Merkmal antippen, die Kamera schwenken, bis es am " +
            "anderen Bildrand steht, und dasselbe Merkmal erneut antippen. Braucht weder Sterne " +
            "noch Kompass – nur die gemessene Drehung dazwischen.",
    ),
    MANUAL(
        label = "Manuell",
        corrects = "Bildfeld",
        needsClearSky = false,
        instructions = "Bildfeld-Faktor direkt einstellen. Wandert eine Markierung beim Schwenken " +
            "schneller als das Bild, den Wert erhöhen.",
    ),
}

/** A star the user has already sighted in this session. */
data class StarSample(
    val objectId: String,
    val name: String,
    val measured: Horizontal,
    val reference: Horizontal,
) {
    val errorDeg: Double get() = SphericalGeometry.separationDeg(measured, reference)
}

/** A tap recorded during a pan sweep. */
data class PanSample(
    val sighting: PanSighting,
    val screenX: Float,
    val screenY: Float,
)

data class CalibrationUiState(
    val method: CalibrationMethod = CalibrationMethod.PAN_SWEEP,
    val calibration: Calibration = Calibration.NONE,
    val observer: ObserverLocation? = null,
    val candidateStars: List<SkyObject> = emptyList(),
    val selectedStarId: String? = null,
    val starSamples: List<StarSample> = emptyList(),
    val panSamples: List<PanSample> = emptyList(),
    val landmarkBearingText: String = "",
    val message: String? = null,
    val error: String? = null,
) {
    val selectedStar: SkyObject?
        get() = candidateStars.firstOrNull { it.id == selectedStarId }

    val canApplyStars: Boolean get() = starSamples.isNotEmpty()
    val canApplyPan: Boolean get() = panSamples.size >= 2
}

class CalibrationViewModel(
    private val orientationTracker: OrientationTracker,
    private val locationTracker: LocationTracker,
    private val catalogRepository: CatalogRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CalibrationUiState())
    val uiState: StateFlow<CalibrationUiState> = _uiState.asStateFlow()

    val attitude: StateFlow<DeviceAttitude?> = orientationTracker.attitudes()
        .catch { }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val hudAttitude: StateFlow<DeviceAttitude?> = attitude
        .sample(250)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            settingsStore.settings.collect { settings ->
                orientationTracker.updateCalibration(settings.calibration)
                _uiState.update {
                    it.copy(
                        calibration = settings.calibration,
                        observer = settings.manualLocation ?: it.observer,
                    )
                }
            }
        }
        viewModelScope.launch {
            locationTracker.locations().collect { fix ->
                val manual = settingsStore.current.manualLocation
                _uiState.update { it.copy(observer = manual ?: fix) }
                refreshCandidates()
            }
        }
        viewModelScope.launch {
            _uiState.update { it.copy(observer = it.observer ?: locationTracker.lastKnown()) }
            refreshCandidates()
        }
    }

    fun setMethod(method: CalibrationMethod) = _uiState.update {
        it.copy(method = method, message = null, error = null)
    }

    fun selectStar(id: String) = _uiState.update { it.copy(selectedStarId = id, error = null) }

    fun setLandmarkBearingText(text: String) = _uiState.update {
        it.copy(landmarkBearingText = text, error = null)
    }

    fun consumeMessages() = _uiState.update { it.copy(message = null, error = null) }

    /** Recomputes which catalogue stars are currently high enough to be worth aiming at. */
    fun refreshCandidates() {
        val observer = _uiState.value.observer ?: return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val lst = AstroTime.lstDeg(now, observer.longitudeDeg)
            val precession = Precession.forEpoch(now)
            val stars = catalogRepository.objects()
                .filter { (it.magnitude ?: 99.0) <= 2.5 }
                .map {
                    it to CoordinateTransforms.apparentHorizontalAtLst(
                        it.positionAt(precession),
                        observer.latitudeDeg,
                        lst,
                    )
                }
                // Below about fifteen degrees refraction and haze make a star a poor reference.
                .filter { (_, position) -> position.altitudeDeg > 15.0 }
                .sortedBy { (star, _) -> star.magnitude ?: 99.0 }
                .map { it.first }
            _uiState.update { state ->
                state.copy(
                    candidateStars = stars,
                    selectedStarId = state.selectedStarId?.takeIf { id -> stars.any { it.id == id } }
                        ?: stars.firstOrNull()?.id,
                )
            }
        }
    }

    /** Where a catalogue object stands right now, for aiming aids. */
    fun currentPositionOf(star: SkyObject): Horizontal? {
        val observer = _uiState.value.observer ?: return null
        val now = System.currentTimeMillis()
        return CoordinateTransforms.apparentHorizontalAtLst(
            star.positionAt(Precession.forEpoch(now)),
            observer.latitudeDeg,
            AstroTime.lstDeg(now, observer.longitudeDeg),
        )
    }

    // --- Stars --------------------------------------------------------------------------------

    /**
     * Records that the crosshair is on the selected star right now.
     *
     * Deliberately uses the *uncalibrated* direction: fitting a correction against directions that
     * already carry one would just be fitting it against itself.
     */
    fun captureStarSighting() {
        val state = _uiState.value
        val star = state.selectedStar
        val observer = state.observer
        val currentAttitude = attitude.value

        if (star == null) return failWith("Zuerst einen Stern auswählen")
        if (observer == null) return failWith("Ohne Standort lässt sich kein Stern zuordnen")
        if (currentAttitude == null) return failWith("Noch keine Lagedaten vom Sensor")

        // The reference has to be where the star *is*, not where the J2000 catalogue puts it.
        // Otherwise a third of a degree of precession is measured as a compass error and then
        // applied to every direction the app reports afterwards.
        val now = System.currentTimeMillis()
        val reference = CoordinateTransforms.apparentHorizontalAtLst(
            star.positionAt(Precession.forEpoch(now)),
            observer.latitudeDeg,
            AstroTime.lstDeg(now, observer.longitudeDeg),
        )
        val sample = StarSample(
            objectId = star.id,
            name = star.name.ifBlank { star.id },
            measured = currentAttitude.uncalibratedCameraDirection,
            reference = reference,
        )

        if (sample.errorDeg > MAX_PLAUSIBLE_ERROR_DEG) {
            return failWith(
                "Das liegt %.0f° neben %s – vermutlich der falsche Stern.".format(
                    sample.errorDeg, sample.name,
                )
            )
        }

        _uiState.update { current ->
            current.copy(
                starSamples = current.starSamples.filterNot { it.objectId == sample.objectId } + sample,
                message = "%s aufgenommen (%.1f° Abweichung)".format(sample.name, sample.errorDeg),
                error = null,
            )
        }
    }

    fun removeStarSample(objectId: String) = _uiState.update {
        it.copy(starSamples = it.starSamples.filterNot { sample -> sample.objectId == objectId })
    }

    fun applyStarCalibration() {
        val samples = _uiState.value.starSamples
        if (samples.isEmpty()) return failWith("Noch kein Stern aufgenommen")

        val fit = AttitudeFit.solve(samples.map { DirectionPair(it.measured, it.reference, it.name) })
            ?: return failWith("Die Messung ließ sich nicht ausgleichen")

        storeCalibration(
            settingsStore.current.calibration.withAttitude(
                rotation = fit.rotation,
                source = CalibrationSource.STAR_PATTERN,
                residualDeg = fit.residualDeg,
                sampleCount = fit.sampleCount,
                atMillis = System.currentTimeMillis(),
                // A compass error belongs to the place it was measured in, so the place is
                // recorded with it — see CalibrationTrust.
                measuredAt = _uiState.value.observer,
            )
        )
        _uiState.update {
            it.copy(
                starSamples = emptyList(),
                message = buildString {
                    append("Ausrichtung korrigiert: %.1f°".format(fit.rotation.angleDeg()))
                    append(", Restfehler %.2f°".format(fit.residualDeg))
                    if (!fit.correctsTiltToo) {
                        append(". Für die Neigung einen zweiten, weit entfernten Stern aufnehmen.")
                    }
                },
            )
        }
    }

    // --- Landmark bearing ---------------------------------------------------------------------

    fun applyLandmarkBearing() {
        val state = _uiState.value
        val currentAttitude = attitude.value
            ?: return failWith("Noch keine Lagedaten vom Sensor")
        val bearing = state.landmarkBearingText.replace(',', '.').trim().toDoubleOrNull()
            ?: return failWith("Peilung als Zahl in Grad eingeben, z. B. 237,5")
        if (bearing < 0.0 || bearing > 360.0) return failWith("Peilung muss zwischen 0° und 360° liegen")

        val measured = currentAttitude.uncalibratedCameraDirection
        val fit = AttitudeFit.solveHeadingOnly(
            listOf(DirectionPair(measured, measured.copy(azimuthDeg = bearing), "Peilung"))
        ) ?: return failWith("Die Peilung ließ sich nicht auswerten")

        storeCalibration(
            settingsStore.current.calibration.withAttitude(
                rotation = fit.rotation,
                source = CalibrationSource.LANDMARK_BEARING,
                residualDeg = fit.residualDeg,
                sampleCount = 1,
                atMillis = System.currentTimeMillis(),
                measuredAt = _uiState.value.observer,
            )
        )
        _uiState.update {
            it.copy(
                landmarkBearingText = "",
                message = "Nordrichtung um %.1f° korrigiert".format(fit.rotation.angleDeg()),
            )
        }
    }

    // --- Pan sweep ----------------------------------------------------------------------------

    /** Records a tap on the feature being swept. Needs no sky and no compass. */
    fun capturePanSighting(screenX: Float, screenY: Float, viewWidthPx: Float, viewHeightPx: Float) {
        val currentAttitude = attitude.value
            ?: return failWith("Noch keine Lagedaten vom Sensor")
        val sample = PanSample(
            sighting = PanSighting(
                worldFromDisplay = currentAttitude.worldFromDisplay,
                offsetXPx = (screenX - viewWidthPx / 2f).toDouble(),
                offsetYPx = (viewHeightPx / 2f - screenY).toDouble(),
            ),
            screenX = screenX,
            screenY = screenY,
        )
        _uiState.update {
            it.copy(
                panSamples = it.panSamples + sample,
                message = if (it.panSamples.isEmpty()) {
                    "Merkmal aufgenommen. Jetzt schwenken und dasselbe Merkmal erneut antippen."
                } else {
                    // Ab hier immer mindestens zwei: Der erste Antipper hat den Zweig darüber.
                    "${it.panSamples.size + 1} Antippungen aufgenommen"
                },
                error = null,
            )
        }
    }

    fun clearPanSamples() = _uiState.update { it.copy(panSamples = emptyList(), message = null) }

    fun applyPanSweep(baselineFocalPx: Double, viewWidthPx: Float) {
        val samples = _uiState.value.panSamples
        if (samples.size < 2) return failWith("Mindestens zwei Antippungen nötig")
        if (baselineFocalPx <= 0.0) return failWith("Die Kamera liefert noch keine Geometrie")

        val result = FieldOfViewSolver.solve(samples.map { it.sighting }, baselineFocalPx)
        val fit = result.getOrElse { throwable ->
            return failWith((throwable as? FovFitException)?.message ?: "Schwenk nicht auswertbar")
        }

        // focalLengthInViewPixels divides by the factor, so a shorter measured focal length — a
        // wider real field of view — means a factor above one.
        val scale = baselineFocalPx / fit.focalPx
        storeCalibration(
            settingsStore.current.calibration.withFov(
                scale = scale,
                source = CalibrationSource.PAN_SWEEP,
                residualDeg = fit.residualDeg,
                sampleCount = fit.sampleCount,
                atMillis = System.currentTimeMillis(),
            )
        )
        _uiState.update {
            it.copy(
                panSamples = emptyList(),
                message = "Bildfeld gemessen: %.1f° breit (Faktor %.3f, Restfehler %.2f°)".format(
                    FieldOfViewSolver.fieldOfViewDeg(fit.focalPx, viewWidthPx.toDouble()),
                    scale,
                    fit.residualDeg,
                ),
            )
        }
    }

    // --- Manual and resets ---------------------------------------------------------------------

    fun setManualFovScale(scale: Double) {
        storeCalibration(
            settingsStore.current.calibration.withFov(
                scale = scale,
                source = CalibrationSource.MANUAL,
                residualDeg = null,
                sampleCount = 0,
                atMillis = System.currentTimeMillis(),
            )
        )
    }

    fun resetAttitude() {
        storeCalibration(settingsStore.current.calibration.clearAttitude())
        _uiState.update { it.copy(starSamples = emptyList(), message = "Ausrichtung zurückgesetzt") }
    }

    fun resetFov() {
        storeCalibration(settingsStore.current.calibration.clearFov())
        _uiState.update { it.copy(panSamples = emptyList(), message = "Bildfeld zurückgesetzt") }
    }

    private fun storeCalibration(calibration: Calibration) {
        settingsStore.setCalibration(calibration)
        orientationTracker.updateCalibration(calibration)
        _uiState.update { it.copy(calibration = calibration) }
    }

    private fun failWith(message: String) {
        _uiState.update { it.copy(error = message) }
    }

    companion object {
        /** Beyond this a "sighting" is far more likely to be the wrong star than a real error. */
        private const val MAX_PLAUSIBLE_ERROR_DEG = 25.0

        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                CalibrationViewModel(
                    orientationTracker = container.orientationTracker,
                    locationTracker = container.locationTracker,
                    catalogRepository = container.catalogRepository,
                    settingsStore = container.settingsStore,
                )
            }
        }
    }
}
