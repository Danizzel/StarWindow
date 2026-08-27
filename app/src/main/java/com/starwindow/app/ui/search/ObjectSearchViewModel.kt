package com.starwindow.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starwindow.app.AppContainer
import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.core.geometry.SkyWindow
import com.starwindow.app.core.sensors.LocationTracker
import com.starwindow.app.data.catalog.CatalogRepository
import com.starwindow.app.data.catalog.SkyObject
import com.starwindow.app.data.tracking.TrackingStore
import com.starwindow.app.data.windows.SettingsStore
import com.starwindow.app.data.windows.SkyWindowRepository
import com.starwindow.app.domain.BortleScale
import com.starwindow.app.domain.CatalogFilter
import com.starwindow.app.domain.FilterField
import com.starwindow.app.domain.ObjectHit
import com.starwindow.app.domain.ObjectSearch
import com.starwindow.app.domain.ObjectSort
import com.starwindow.app.domain.PhotographicInterest
import com.starwindow.app.domain.SkyConditions
import com.starwindow.app.domain.TonightBoard
import com.starwindow.app.domain.TonightGroup
import com.starwindow.app.domain.TransitCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ObjectSearchUiState(
    val query: String = "",
    val sort: ObjectSort = ObjectSort.RELEVANCE,
    val filter: CatalogFilter = CatalogFilter.NONE,
    val hits: List<ObjectHit> = emptyList(),
    /** The suggestion board, shown instead of a list while nothing is typed or filtered. */
    val board: List<TonightGroup> = emptyList(),
    val conditions: SkyConditions = SkyConditions.UNKNOWN,
    val observer: ObserverLocation? = null,
    val catalogSize: Int = 0,
    val isLoading: Boolean = true,
    /** True while the window transit search runs; it takes long enough to be worth saying so. */
    val isComputingWindow: Boolean = false,
    val trackedId: String? = null,
    val windows: List<SkyWindow> = emptyList(),
    val filterSheetOpen: Boolean = false,
    /** Constellation abbreviations present in the catalogue, for the filter sheet. */
    val constellations: List<String> = emptyList(),
) {
    val hasQuery: Boolean get() = query.isNotBlank()

    /**
     * Nothing typed and nothing filtered: the screen shows the evening, not the catalogue.
     *
     * The filter matters as much as the query here — someone who switches on "durchs Fenster"
     * without typing anything is asking a question about the catalogue and must get a list.
     */
    val showsBoard: Boolean get() = !hasQuery && filter.isEmpty

    val selectedWindow: SkyWindow?
        get() = filter.windowId?.let { id -> windows.firstOrNull { it.id == id } }
            ?: windows.firstOrNull()

    val isEmpty: Boolean
        get() = !isLoading && !isComputingWindow &&
            if (showsBoard) board.isEmpty() else hits.isEmpty()
}

/**
 * Search and suggestion over the whole catalogue.
 *
 * The ranking lives in [ObjectSearch], the suggestion board in [TonightBoard] and the feasibility
 * verdict in `Feasibility` — this class owns only the timing and the wiring between them, which
 * with a twenty-two thousand entry catalogue is a job of its own:
 *
 * * keystrokes are debounced, because re-ranking on every letter makes the field feel sticky;
 * * every recomputation happens off the main thread, so a long result list cannot drop a frame;
 * * the window transit search runs on its own job and announces itself, because it is the one
 *   operation here that takes long enough to notice.
 */
class ObjectSearchViewModel(
    private val catalogRepository: CatalogRepository,
    private val locationTracker: LocationTracker,
    private val settingsStore: SettingsStore,
    private val trackingStore: TrackingStore,
    private val windowRepository: SkyWindowRepository,
    private val transitCalculator: TransitCalculator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ObjectSearchUiState())
    val uiState: StateFlow<ObjectSearchUiState> = _uiState.asStateFlow()

    private var catalog: List<SkyObject> = emptyList()
    private var searchJob: Job? = null

    /**
     * Which objects pass through the selected window, cached by window id.
     *
     * The transit search is the expensive thing on this screen — seconds, not milliseconds — and
     * toggling a filter chip must not pay for it twice. Cleared when the window selection changes,
     * which is the only thing that can invalidate it within a session.
     */
    private var windowTransits: Pair<String, Map<String, Int?>>? = null

    init {
        viewModelScope.launch {
            catalog = catalogRepository.objects()
            _uiState.update {
                it.copy(
                    catalogSize = catalog.size,
                    observer = resolveObserver(),
                    conditions = resolveConditions(),
                    constellations = catalog.mapNotNull { obj -> obj.constellation }
                        .distinct()
                        .sorted(),
                    isLoading = false,
                )
            }
            recompute(debounce = false)
        }
        viewModelScope.launch {
            windowRepository.load()
        }
        viewModelScope.launch {
            windowRepository.windows.collect { windows ->
                _uiState.update { it.copy(windows = windows) }
                // A window that no longer exists cannot filter anything.
                if (_uiState.value.filter.onlyThroughWindow && windows.isEmpty()) {
                    setFilter(_uiState.value.filter.copy(onlyThroughWindow = false))
                }
            }
        }
        viewModelScope.launch {
            trackingStore.target.collect { target ->
                _uiState.update { it.copy(trackedId = target?.id) }
            }
        }
        // The position may only arrive after the screen is already open; the altitude column, the
        // board and the feasibility verdicts all depend on it, so everything is rebuilt when it does.
        viewModelScope.launch {
            locationTracker.locations().collect {
                _uiState.update { state ->
                    state.copy(observer = resolveObserver(), conditions = resolveConditions())
                }
                recompute(debounce = false)
            }
        }
    }

    fun setQuery(query: String) {
        _uiState.update { it.copy(query = query) }
        recompute(debounce = true)
    }

    fun clearQuery() = setQuery("")

    fun setSort(sort: ObjectSort) {
        _uiState.update { it.copy(sort = sort) }
        recompute(debounce = false)
    }

    fun setFilter(filter: CatalogFilter) {
        val previous = _uiState.value.filter
        if (previous.windowId != filter.windowId) windowTransits = null
        _uiState.update { it.copy(filter = filter) }
        recompute(debounce = false)
    }

    /** Convenience for the chip row: drop one restriction. */
    fun clearFilterField(field: FilterField) = setFilter(_uiState.value.filter.without(field))

    fun clearAllFilters() = setFilter(CatalogFilter.NONE)


    fun openFilterSheet() = _uiState.update { it.copy(filterSheetOpen = true) }

    fun closeFilterSheet() = _uiState.update { it.copy(filterSheetOpen = false) }

    private fun recompute(debounce: Boolean) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (debounce) delay(DEBOUNCE_MILLIS)
            val state = _uiState.value

            if (state.showsBoard) {
                buildBoard(state)
                return@launch
            }

            // The window test needs a transit search, which is slow enough that the list has to
            // say it is working rather than appear to be empty.
            val throughWindow = if (state.filter.onlyThroughWindow) {
                _uiState.update { it.copy(isComputingWindow = true) }
                transitsForSelectedWindow(state)
            } else {
                null
            }

            val hits = withContext(Dispatchers.Default) {
                val candidates = catalog.asSequence()
                    .filter { throughWindow == null || throughWindow.containsKey(it.id) }
                    .toList()

                val found = ObjectSearch.search(
                    query = state.query,
                    objects = candidates,
                    observer = state.observer,
                    sort = state.sort,
                    conditions = state.conditions,
                )
                // The remaining filters are applied after ranking rather than before it, so the
                // altitude they test is the one the search already computed.
                found
                    .filter { state.filter.matches(it.obj, it.altitudeDeg, state.conditions) }
                    .map { hit ->
                        throughWindow?.get(hit.obj.id)
                            ?.let { minutes -> hit.copy(minutesLeftInWindow = minutes) }
                            ?: hit
                    }
            }
            _uiState.update { it.copy(hits = hits, isComputingWindow = false) }
        }
    }

    private suspend fun buildBoard(state: ObjectSearchUiState) {
        val inWindow = objectsInsideWindowNow(state)
        val board = withContext(Dispatchers.Default) {
            TonightBoard.build(
                objects = catalog,
                observer = state.observer,
                conditions = state.conditions,
                inWindowNow = inWindow,
            )
        }
        _uiState.update { it.copy(board = board, hits = emptyList(), isComputingWindow = false) }
    }

    /**
     * What currently sits inside the most recent window, with the minutes it has left.
     *
     * Two stages on purpose. Asking which objects are inside *right now* is one membership test per
     * object and costs nothing; asking how much longer each stays is a transit search and costs
     * seconds. Doing the cheap test first means the expensive one runs over the handful that
     * qualified rather than over the whole catalogue.
     */
    private suspend fun objectsInsideWindowNow(
        state: ObjectSearchUiState,
    ): List<Pair<SkyObject, Int?>> {
        val window = state.selectedWindow ?: return emptyList()
        val now = System.currentTimeMillis()

        val inside = withContext(Dispatchers.Default) {
            transitCalculator
                .objectsInsideAt(
                    window = window,
                    objects = catalog.filter { PhotographicInterest.isPhotoTarget(it) },
                    atMillis = now,
                )
                .map { (obj, _) -> obj }
        }
        if (inside.isEmpty()) return emptyList()

        val result = transitCalculator.search(
            window = window,
            objects = inside,
            fromMillis = now,
            toMillis = now + WINDOW_LOOKAHEAD_MILLIS,
        )
        val exits = result.transits.associate { transit ->
            transit.obj.id to transit.intervals
                .firstOrNull { it.clippedAtStart }
                ?.let { ((it.exitMillis - now) / 60_000L).toInt() }
        }
        return inside.map { it to exits[it.id] }
    }

    /**
     * Object ids that pass through the selected window tonight, mapped to minutes still inside.
     *
     * Restricted to photographic targets before the search runs. That is not a shortcut but the
     * right question: the window filter answers "what could I shoot through this gap tonight", and
     * running a twelve-hour transit sweep over nine thousand stars to answer it would take seconds
     * and produce a list nobody wants.
     */
    private suspend fun transitsForSelectedWindow(
        state: ObjectSearchUiState,
    ): Map<String, Int?> {
        val window = state.selectedWindow ?: return emptyMap()
        windowTransits?.let { (id, cached) -> if (id == window.id) return cached }

        val now = System.currentTimeMillis()
        val result = transitCalculator.search(
            window = window,
            objects = catalog.filter { PhotographicInterest.isPhotoTarget(it) },
            fromMillis = now,
            toMillis = now + WINDOW_LOOKAHEAD_MILLIS,
        )
        val transits = result.transits.associate { transit ->
            transit.obj.id to transit.intervals
                .firstOrNull { it.clippedAtStart }
                ?.let { ((it.exitMillis - now) / 60_000L).toInt() }
        }
        windowTransits = window.id to transits
        return transits
    }

    private fun resolveObserver(): ObserverLocation? =
        settingsStore.current.manualLocation ?: locationTracker.lastKnown()

    /**
     * The sky the feasibility verdicts are judged against.
     *
     * The Bortle level comes from the weather screen's place, which is usually an estimate from
     * settlement size — [SkyConditions.isEstimated] carries that through so the interface can say
     * so rather than presenting a guess as a measurement.
     */
    private fun resolveConditions(): SkyConditions {
        val place = settingsStore.current.weatherPlace
        return SkyConditions.at(
            observer = resolveObserver(),
            bortleLevel = place?.let { BortleScale.resolve(it) },
            userSetBortle = place?.bortleOverride != null,
        )
    }

    companion object {
        /** Long enough to swallow a fast typist's burst, short enough to feel immediate. */
        private const val DEBOUNCE_MILLIS = 180L

        /** How far the window search looks ahead: one night. */
        private const val WINDOW_LOOKAHEAD_MILLIS = 12 * 3_600_000L


        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                ObjectSearchViewModel(
                    catalogRepository = container.catalogRepository,
                    locationTracker = container.locationTracker,
                    settingsStore = container.settingsStore,
                    trackingStore = container.trackingStore,
                    windowRepository = container.windowRepository,
                    transitCalculator = container.transitCalculator,
                )
            }
        }
    }
}
