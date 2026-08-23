package com.starwindow.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starwindow.app.AppContainer
import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.core.sensors.LocationTracker
import com.starwindow.app.data.catalog.CatalogRepository
import com.starwindow.app.data.catalog.SkyObject
import com.starwindow.app.data.tracking.TrackingStore
import com.starwindow.app.data.windows.SettingsStore
import com.starwindow.app.domain.ObjectHit
import com.starwindow.app.domain.ObjectSearch
import com.starwindow.app.domain.ObjectSort
import com.starwindow.app.domain.ResultFilter
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
    val filter: ResultFilter = ResultFilter.ALL,
    val hits: List<ObjectHit> = emptyList(),
    val observer: ObserverLocation? = null,
    val catalogSize: Int = 0,
    val isLoading: Boolean = true,
    /** The object the viewfinder is currently pointing at, so the list can mark it. */
    val trackedId: String? = null,
) {
    val hasQuery: Boolean get() = query.isNotBlank()

    /** Nothing typed yet: the list is the "worth looking at right now" suggestion instead. */
    val isSuggestion: Boolean get() = !hasQuery

    val isEmpty: Boolean get() = hits.isEmpty() && !isLoading
}

/**
 * Search over the whole catalogue.
 *
 * The ranking itself lives in [ObjectSearch]; this class only owns the timing. Keystrokes are
 * debounced because the catalogue runs to thousands of entries and re-ranking on every single
 * letter would make the field feel sticky, and the work happens off the main thread so a long
 * result list can never drop a frame in the text field.
 */
class ObjectSearchViewModel(
    private val catalogRepository: CatalogRepository,
    private val locationTracker: LocationTracker,
    private val settingsStore: SettingsStore,
    private val trackingStore: TrackingStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ObjectSearchUiState())
    val uiState: StateFlow<ObjectSearchUiState> = _uiState.asStateFlow()

    private var catalog: List<SkyObject> = emptyList()
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            catalog = catalogRepository.objects()
            _uiState.update {
                it.copy(
                    catalogSize = catalog.size,
                    observer = resolveObserver(),
                    isLoading = false,
                )
            }
            recompute(debounce = false)
        }
        viewModelScope.launch {
            trackingStore.target.collect { target ->
                _uiState.update { it.copy(trackedId = target?.id) }
            }
        }
        // The position may only arrive after the screen is already open; the altitude column and
        // the suggestions both depend on it, so the list is rebuilt when it does.
        viewModelScope.launch {
            locationTracker.locations().collect {
                _uiState.update { state -> state.copy(observer = resolveObserver()) }
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

    fun setFilter(filter: ResultFilter) {
        _uiState.update { it.copy(filter = filter) }
        recompute(debounce = false)
    }

    private fun recompute(debounce: Boolean) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (debounce) delay(DEBOUNCE_MILLIS)
            val state = _uiState.value

            val hits = withContext(Dispatchers.Default) {
                val candidates = catalog.filter { state.filter.matches(it) }
                if (state.hasQuery) {
                    ObjectSearch.search(
                        query = state.query,
                        objects = candidates,
                        observer = state.observer,
                        sort = state.sort,
                    )
                } else if (state.sort == ObjectSort.RELEVANCE) {
                    ObjectSearch.visibleNow(candidates, state.observer)
                } else {
                    // An explicit sort with nothing typed means "show me the catalogue like this".
                    ObjectSearch.search(
                        query = "",
                        objects = candidates,
                        observer = state.observer,
                        sort = state.sort,
                    )
                }
            }
            _uiState.update { it.copy(hits = hits) }
        }
    }

    private fun resolveObserver(): ObserverLocation? =
        settingsStore.current.manualLocation ?: locationTracker.lastKnown()

    companion object {
        /** Long enough to swallow a fast typist's burst, short enough to feel immediate. */
        private const val DEBOUNCE_MILLIS = 180L

        /** Types worth offering as filters here; constellations are not catalogue objects. */
        val FILTERS: List<ResultFilter> = ResultFilter.entries.filter { it != ResultFilter.CONSTELLATIONS }

        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                ObjectSearchViewModel(
                    catalogRepository = container.catalogRepository,
                    locationTracker = container.locationTracker,
                    settingsStore = container.settingsStore,
                    trackingStore = container.trackingStore,
                )
            }
        }
    }
}
