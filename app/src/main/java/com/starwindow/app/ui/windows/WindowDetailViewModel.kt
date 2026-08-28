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
import com.starwindow.app.data.catalog.ObjectNotesRepository
import com.starwindow.app.data.tracking.TrackedWindow
import com.starwindow.app.data.tracking.TrackingStore
import com.starwindow.app.data.windows.SkyWindowRepository
import com.starwindow.app.domain.ConstellationTransit
import com.starwindow.app.domain.ConstellationTransitCalculator
import com.starwindow.app.domain.ObjectDescription
import com.starwindow.app.domain.ObjectTransit
import com.starwindow.app.domain.ResultFilter
import com.starwindow.app.domain.SkyTrack
import com.starwindow.app.domain.SkyTrackBuilder
import com.starwindow.app.domain.TransitCalculator
import com.starwindow.app.domain.TransitListView
import com.starwindow.app.domain.TransitSort
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
    /** Free text over the result list; the whole catalogue is not searched here, only the hits. */
    val query: String = "",
    val sort: TransitSort = TransitSort.INTEREST,
    /** Nur Durchgänge zeigen, die wenigstens teilweise in der Dunkelheit liegen. */
    val onlyDark: Boolean = false,
    /**
     * Catalogue and constellation ids whose paths the chart shows.
     *
     * Empty means "nothing chosen yet", and then the chart falls back to a handful of the results
     * so it is never blank. As soon as one entry is picked the chart shows **only** the picked ones:
     * with a dozen paths crossing each other, isolating the one being considered is the whole point
     * of the view.
     */
    val selectedIds: Set<String> = emptySet(),
    val tracks: List<SkyTrack> = emptyList(),
    /** Indices of the paths belonging to the selected entry; all of its passes are lifted. */
    val emphasisedTrackIndices: Set<Int> = emptySet(),
    val figureSegments: List<Pair<Horizontal, Horizontal>> = emptyList(),
    /** The object whose info sheet is open, or null. */
    val info: ObjectInfo? = null,
    val isSearching: Boolean = false,
    val error: String? = null,
) {
    val visibleObjects: List<ObjectTransit>
        get() = TransitListView.build(result?.transits.orEmpty(), filter, query, sort, onlyDark)

    /**
     * Wie viele Treffer in der Dunkelheit liegen.
     *
     * Steht am Schalter, damit man vorher sieht, was er wegnimmt: „nur nachts (48 von 212)" ist
     * eine Auskunft, ein nackter Schalter ist ein Sprung ins Ungewisse.
     */
    val darkObjectCount: Int get() = result?.transits?.count { it.hasDarkTime } ?: 0

    val visibleConstellations: List<ConstellationTransit>
        get() = if (filter.showsConstellations && query.isBlank()) constellations else emptyList()

    val isEmpty: Boolean get() = visibleObjects.isEmpty() && visibleConstellations.isEmpty()

    val hasQuery: Boolean get() = query.isNotBlank()

    /** How many the filter and the query took out, for the header line. */
    val totalObjects: Int get() = result?.transits?.size ?: 0

    /** The best few of each kind, shown above the list while nothing is filtered or typed. */
    val highlights: Map<ResultFilter, List<ObjectTransit>>
        get() = if (filter == ResultFilter.ALL && !hasQuery) {
            TransitListView.highlightsByKind(result?.transits.orEmpty())
        } else {
            emptyMap()
        }
}

class WindowDetailViewModel(
    private val windowId: String,
    private val windowRepository: SkyWindowRepository,
    private val catalogRepository: CatalogRepository,
    private val constellationRepository: ConstellationRepository,
    private val transitCalculator: TransitCalculator,
    private val constellationTransitCalculator: ConstellationTransitCalculator,
    private val trackingStore: TrackingStore,
    private val objectNotesRepository: ObjectNotesRepository,
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

    /** Blendet Durchgänge aus, die ganz in der Helligkeit liegen. */
    fun setOnlyDark(onlyDark: Boolean) {
        _uiState.update { it.copy(onlyDark = onlyDark) }
        rebuildTracks()
    }

    /**
     * Filters the result list by name or designation.
     *
     * No debounce and no coroutine: this searches the few hundred results already in hand, not the
     * catalogue, so it is a list filter and can run straight through on the keystroke.
     */
    fun setQuery(query: String) {
        _uiState.update { it.copy(query = query) }
        rebuildTracks()
    }

    fun clearQuery() = setQuery("")

    fun setSort(sort: TransitSort) {
        _uiState.update { it.copy(sort = sort) }
    }

    /** Adds an entry to the chart, or takes it out again when it is tapped a second time. */
    fun toggleSelection(id: String) {
        _uiState.update {
            val next = if (id in it.selectedIds) it.selectedIds - id else it.selectedIds + id
            it.copy(selectedIds = next)
        }
        rebuildTracks()
    }

    /** Back to the overview: no choice made, so the chart shows a sample of the results again. */
    fun clearSelection() {
        _uiState.update { it.copy(selectedIds = emptySet()) }
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

        // Built in one go rather than filled in afterwards: the notes come off disk the first time,
        // and a sheet that pops open and then grows a paragraph reads as a glitch.
        viewModelScope.launch {
            val description = ObjectDescription.describe(obj, objectNotesRepository.noteFor(obj))
            _uiState.update {
                it.copy(
                    info = ObjectInfo(
                        obj = obj,
                        description = description,
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
    }

    fun closeInfo() = _uiState.update { it.copy(info = null) }

    /** Points the viewfinder back at this window; the overlay draws its outline once in view. */
    fun track(window: SkyWindow) = trackingStore.track(TrackedWindow.of(window))

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
     * With nothing chosen the chart shows a sample, because a blank chart under a full result list
     * looks broken. Once anything is chosen it shows **only** what was chosen: a dozen paths
     * crossing each other tell you nothing about any single one of them, and isolating one is the
     * reason to tap a row at all.
     */
    private fun rebuildTracks() {
        val state = _uiState.value
        val window = state.window ?: return
        val precession = Precession.forEpoch(System.currentTimeMillis())

        val objectTransits = state.visibleObjects
        val selected = state.selectedIds
        val hasSelection = selected.isNotEmpty()

        val chosen = if (hasSelection) {
            objectTransits.filter { it.obj.id in selected }
        } else {
            objectTransits.take(MAX_TRACKS)
        }

        // Every pass of a chosen object, only the first of the sample. A circumpolar object can
        // cross the same window three or four times in a night, and seeing them together is the
        // point of picking it — drawing every pass of every object would be a thicket.
        val tracks = chosen.flatMap { transit ->
            val intervals = if (hasSelection) {
                transit.intervals.take(MAX_PASSES_PER_OBJECT)
            } else {
                transit.intervals.take(1)
            }
            intervals.map { interval ->
                SkyTrackBuilder.forInterval(
                    label = transit.obj.name.ifBlank { transit.obj.id },
                    equatorial = transit.obj.positionAt(precession),
                    observer = window.observer,
                    enterMillis = interval.enterMillis,
                    exitMillis = interval.exitMillis,
                )
            }
        }.toMutableList()

        // Everything drawn on an explicit choice is in the foreground; the fallback sample is not.
        var emphasised = if (hasSelection) tracks.indices.toSet() else emptySet()
        var figure: List<Pair<Horizontal, Horizontal>> = emptyList()

        val selectedConstellation = state.constellations.firstOrNull { it.constellation.id in selected }
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
            val firstFigureTrack = tracks.size
            stars.forEach { star ->
                tracks += SkyTrackBuilder.forInterval(
                    label = star.name,
                    equatorial = star.positionAt(precession),
                    observer = window.observer,
                    enterMillis = interval.enterMillis,
                    exitMillis = interval.exitMillis,
                )
            }
            emphasised = (firstFigureTrack until tracks.size).toSet()
        }

        _uiState.update {
            it.copy(tracks = tracks, emphasisedTrackIndices = emphasised, figureSegments = figure)
        }
    }

    companion object {
        private const val MAX_TRACKS = 6
        private const val MAX_FIGURE_TRACKS = 3

        /** A circumpolar object can cross the same window several times in one night. */
        private const val MAX_PASSES_PER_OBJECT = 4

        fun factory(container: AppContainer, windowId: String) = viewModelFactory {
            initializer {
                WindowDetailViewModel(
                    windowId = windowId,
                    windowRepository = container.windowRepository,
                    catalogRepository = container.catalogRepository,
                    constellationRepository = container.constellationRepository,
                    transitCalculator = container.transitCalculator,
                    constellationTransitCalculator = container.constellationTransitCalculator,
                    trackingStore = container.trackingStore,
                    objectNotesRepository = container.objectNotesRepository,
                )
            }
        }
    }
}
