package com.starwindow.app.ui.windows

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starwindow.app.AppContainer
import com.starwindow.app.core.astro.AstroTime
import com.starwindow.app.core.astro.CoordinateTransforms
import com.starwindow.app.core.astro.Horizontal
import com.starwindow.app.core.astro.Precession
import com.starwindow.app.core.geometry.SkyWindow
import com.starwindow.app.data.catalog.CatalogRepository
import com.starwindow.app.data.catalog.ConstellationRepository
import com.starwindow.app.data.windows.SkyWindowRepository
import com.starwindow.app.domain.ConstellationTransit
import com.starwindow.app.domain.ConstellationTransitCalculator
import com.starwindow.app.domain.ObjectTransit
import com.starwindow.app.domain.ResultFilter
import com.starwindow.app.domain.SkyTrack
import com.starwindow.app.domain.SkyTrackBuilder
import com.starwindow.app.domain.TransitCalculator
import com.starwindow.app.domain.TransitSearchResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WindowDetailUiState(
    val window: SkyWindow? = null,
    val result: TransitSearchResult? = null,
    val constellations: List<ConstellationTransit> = emptyList(),
    val hoursAhead: Int = 24,
    val magnitudeLimit: Double = 8.0,
    val filter: ResultFilter = ResultFilter.ALL,
    /** Catalogue id or constellation id of the entry whose path is highlighted. */
    val selectedId: String? = null,
    val tracks: List<SkyTrack> = emptyList(),
    val emphasisedTrackIndex: Int? = null,
    val figureSegments: List<Pair<Horizontal, Horizontal>> = emptyList(),
    /** The object whose info sheet is open, or null. */
    val info: ObjectInfo? = null,
    val isSearching: Boolean = false,
    val error: String? = null,
) {
    val visibleObjects: List<ObjectTransit>
        get() = if (!filter.showsObjects) {
            emptyList()
        } else {
            result?.transits.orEmpty().filter { filter.matches(it.obj) }
        }

    val visibleConstellations: List<ConstellationTransit>
        get() = if (filter.showsConstellations) constellations else emptyList()

    val isEmpty: Boolean get() = visibleObjects.isEmpty() && visibleConstellations.isEmpty()
}

class WindowDetailViewModel(
    private val windowId: String,
    private val windowRepository: SkyWindowRepository,
    private val catalogRepository: CatalogRepository,
    private val constellationRepository: ConstellationRepository,
    private val transitCalculator: TransitCalculator,
    private val constellationTransitCalculator: ConstellationTransitCalculator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WindowDetailUiState())
    val uiState: StateFlow<WindowDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            windowRepository.load()
            val window = windowRepository.find(windowId)
            _uiState.update { it.copy(window = window) }
            if (window == null) {
                _uiState.update { it.copy(error = "Fenster nicht gefunden") }
            } else {
                search()
            }
        }
    }

    fun setHoursAhead(hours: Int) {
        _uiState.update { it.copy(hoursAhead = hours) }
        search()
    }

    fun setMagnitudeLimit(limit: Double) {
        _uiState.update { it.copy(magnitudeLimit = limit) }
        search()
    }

    fun setFilter(filter: ResultFilter) {
        _uiState.update { it.copy(filter = filter) }
        rebuildTracks()
    }

    /** Highlights one entry's path, or clears the highlight when it is tapped again. */
    fun select(id: String?) {
        _uiState.update { it.copy(selectedId = if (it.selectedId == id) null else id) }
        rebuildTracks()
    }

    /**
     * Gathers everything the info sheet shows: the whole-span altitude curve, the stretches inside
     * the window, where the object stands right now, and how it would sit in the frame.
     */
    fun openInfo(objectId: String) {
        val state = _uiState.value
        val window = state.window ?: return
        val transit = state.result?.transits?.firstOrNull { it.obj.id == objectId } ?: return

        val now = System.currentTimeMillis()
        val until = now + state.hoursAhead * 3_600_000L
        val obj = transit.obj
        val precession = Precession.forEpoch(now)

        _uiState.update {
            it.copy(
                info = ObjectInfo(
                    obj = obj,
                    track = SkyTrackBuilder.overSpan(
                        label = obj.name.ifBlank { obj.id },
                        equatorial = obj.positionAt(precession),
                        observer = window.observer,
                        fromMillis = now,
                        toMillis = until,
                    ),
                    passes = transit.intervals.map { interval ->
                        WindowPass(interval.enterMillis, interval.exitMillis)
                    },
                    currentPosition = CoordinateTransforms.apparentHorizontalAtLst(
                        obj.positionAt(precession),
                        window.observer.latitudeDeg,
                        AstroTime.lstDeg(now, window.observer.longitudeDeg),
                    ),
                    fillFactor = obj.fillFactor(window.shape.angularRadiusDeg()),
                )
            )
        }
    }

    fun closeInfo() = _uiState.update { it.copy(info = null) }

    fun search() {
        val window = _uiState.value.window ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true, error = null) }
            val state = _uiState.value
            val now = System.currentTimeMillis()
            val until = now + state.hoursAhead * 3_600_000L

            val outcome = runCatching {
                val objects = catalogRepository.objectsVisibleFrom(
                    latitudeDeg = window.observer.latitudeDeg,
                    magnitudeLimit = state.magnitudeLimit,
                )
                val transits = transitCalculator.search(window, objects, now, until)
                val figures = constellationTransitCalculator.search(
                    window,
                    constellationRepository.constellations(),
                    now,
                    until,
                )
                transits to figures
            }

            outcome
                .onSuccess { (transits, figures) ->
                    _uiState.update {
                        it.copy(isSearching = false, result = transits, constellations = figures)
                    }
                    rebuildTracks()
                }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(isSearching = false, error = throwable.message ?: "Berechnung fehlgeschlagen")
                    }
                }
        }
    }

    /**
     * Rebuilds the paths shown in the chart.
     *
     * Only a handful are drawn at once: a chart with forty overlapping paths says nothing. The
     * selected entry is always among them, so tapping a row always shows its path.
     */
    private fun rebuildTracks() {
        val state = _uiState.value
        val window = state.window ?: return
        val precession = Precession.forEpoch(System.currentTimeMillis())

        val objectTransits = state.visibleObjects
        val selected = state.selectedId

        val chosen = buildList {
            objectTransits.firstOrNull { it.obj.id == selected }?.let { add(it) }
            addAll(objectTransits.filter { it.obj.id != selected }.take(MAX_TRACKS - size))
        }

        val tracks = chosen.map { transit ->
            val interval = transit.intervals.first()
            SkyTrackBuilder.forInterval(
                label = transit.obj.name.ifBlank { transit.obj.id },
                equatorial = transit.obj.positionAt(precession),
                observer = window.observer,
                enterMillis = interval.enterMillis,
                exitMillis = interval.exitMillis,
            )
        }.toMutableList()

        var emphasised = chosen.indexOfFirst { it.obj.id == selected }.takeIf { it >= 0 }
        var figure: List<Pair<Horizontal, Horizontal>> = emptyList()

        val selectedConstellation = state.constellations.firstOrNull { it.constellation.id == selected }
        if (selectedConstellation != null) {
            val interval = selectedConstellation.intervals.maxByOrNull { it.peakStarsInside }
                ?: selectedConstellation.intervals.first()
            figure = constellationTransitCalculator.figureAt(
                selectedConstellation.constellation,
                window,
                interval.peakMillis,
            )
            // The figure's brightest stars carry the path; the whole outline would be a thicket.
            val stars = selectedConstellation.constellation.stars.take(MAX_FIGURE_TRACKS)
            emphasised = tracks.size
            stars.forEach { star ->
                tracks += SkyTrackBuilder.forInterval(
                    label = star.name,
                    equatorial = star.positionAt(precession),
                    observer = window.observer,
                    enterMillis = interval.enterMillis,
                    exitMillis = interval.exitMillis,
                )
            }
        }

        _uiState.update {
            it.copy(tracks = tracks, emphasisedTrackIndex = emphasised, figureSegments = figure)
        }
    }

    companion object {
        private const val MAX_TRACKS = 6
        private const val MAX_FIGURE_TRACKS = 3

        fun factory(container: AppContainer, windowId: String) = viewModelFactory {
            initializer {
                WindowDetailViewModel(
                    windowId = windowId,
                    windowRepository = container.windowRepository,
                    catalogRepository = container.catalogRepository,
                    constellationRepository = container.constellationRepository,
                    transitCalculator = container.transitCalculator,
                    constellationTransitCalculator = container.constellationTransitCalculator,
                )
            }
        }
    }
}
