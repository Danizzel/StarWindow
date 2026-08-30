package com.starwindow.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starwindow.app.AppContainer
import com.starwindow.app.core.astro.AstroTime
import com.starwindow.app.core.astro.CoordinateTransforms
import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.core.astro.Precession
import com.starwindow.app.core.sensors.LocationTracker
import com.starwindow.app.data.catalog.CatalogRepository
import com.starwindow.app.data.catalog.ObjectNotesRepository
import com.starwindow.app.data.favorites.FavoritesRepository
import com.starwindow.app.data.catalog.SkyObject
import com.starwindow.app.data.planning.WatchScheduler
import com.starwindow.app.data.planning.WatchlistRepository
import com.starwindow.app.data.tracking.TrackedObject
import com.starwindow.app.data.tracking.TrackingStore
import com.starwindow.app.data.windows.SettingsStore
import com.starwindow.app.domain.ObjectDescription
import com.starwindow.app.domain.SkyTrackBuilder
import com.starwindow.app.ui.windows.ObjectInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ObjectDetailUiState(
    val obj: SkyObject? = null,
    val info: ObjectInfo? = null,
    val observer: ObserverLocation? = null,
    val isTracked: Boolean = false,
    /** True, wenn dieses Objekt auf der Merkliste steht. */
    val isWatched: Boolean = false,
    /** True, wenn ein Herz daran hängt. */
    val isFavorite: Boolean = false,
    val description: ObjectDescription? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
)

/**
 * One object, everything known about it, and the decision to follow it.
 *
 * The altitude curve here spans the next day rather than a window pass: away from a saved window
 * the question is simply "when does it stand high tonight", and a curve that started at the edge of
 * a window nobody selected would answer nothing.
 */
class ObjectDetailViewModel(
    private val objectId: String,
    private val catalogRepository: CatalogRepository,
    private val locationTracker: LocationTracker,
    private val settingsStore: SettingsStore,
    private val trackingStore: TrackingStore,
    private val objectNotesRepository: ObjectNotesRepository,
    private val watchlistRepository: WatchlistRepository,
    private val watchScheduler: WatchScheduler,
    private val favoritesRepository: FavoritesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ObjectDetailUiState())
    val uiState: StateFlow<ObjectDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val obj = catalogRepository.objects().firstOrNull { it.id == objectId }
            if (obj == null) {
                _uiState.update {
                    it.copy(isLoading = false, error = "Objekt „$objectId“ ist nicht im Katalog")
                }
                return@launch
            }

            val observer = settingsStore.current.manualLocation ?: locationTracker.lastKnown()
            val description = ObjectDescription.describe(obj, objectNotesRepository.noteFor(obj))
            _uiState.update {
                it.copy(
                    obj = obj,
                    observer = observer,
                    info = buildInfo(obj, observer, description),
                    description = description,
                    isLoading = false,
                )
            }
        }
        viewModelScope.launch {
            trackingStore.target.collect { target ->
                _uiState.update { it.copy(isTracked = target?.id == objectId) }
            }
        }
        viewModelScope.launch {
            watchlistRepository.load()
            watchlistRepository.watched.collect { watched ->
                _uiState.update { state ->
                    state.copy(isWatched = watched.any { it.objectId == objectId })
                }
            }
        }
        viewModelScope.launch {
            favoritesRepository.load()
            favoritesRepository.favorites.collect { favorites ->
                _uiState.update { it.copy(isFavorite = objectId in favorites) }
            }
        }
    }

    /**
     * Setzt oder entfernt das Herz.
     *
     * Ohne Rückfrage und ohne Nebenwirkung — das ist der Unterschied zum Vormerken: Ein Favorit
     * legt keinen Alarm an, verlangt keine Berechtigung und kann nichts kaputt machen, weshalb er
     * auch keinen Dialog verdient. Der Zustand kommt über den Fluss zurück, nicht aus dem Klick,
     * damit zwei Bildschirme auf dasselbe Objekt nie Verschiedenes anzeigen.
     */
    fun toggleFavorite() {
        viewModelScope.launch { favoritesRepository.toggle(objectId) }
    }

    /** Starts following the object; the viewfinder picks it up from the store. */
    fun track() {
        _uiState.value.obj?.let { trackingStore.track(TrackedObject(it)) }
    }

    fun untrack() = trackingStore.clear()

    /**
     * Merkt das Objekt vor, oder nimmt es wieder von der Liste.
     *
     * Der Benachrichtigungskanal wird hier angelegt und nicht beim Start: Eine App, von der niemand
     * eine Meldung verlangt hat, soll nicht in den Systemeinstellungen stehen und eine anbieten.
     */
    fun toggleWatch() {
        val obj = _uiState.value.obj ?: return
        viewModelScope.launch {
            if (watchlistRepository.isWatched(obj.id)) {
                watchlistRepository.removeObject(obj.id)
            } else {
                watchScheduler.ensureChannel()
                watchlistRepository.add(obj)
            }
        }
    }

    /**
     * Builds the curve and the current position.
     *
     * Without a known position there is no horizon to be above, so there is no curve either — the
     * screen then shows the catalogue facts alone rather than a plausible looking curve computed
     * for Greenwich.
     */
    private fun buildInfo(
        obj: SkyObject,
        observer: ObserverLocation?,
        description: ObjectDescription,
    ): ObjectInfo? {
        if (observer == null) return null
        val now = System.currentTimeMillis()
        val equatorial = obj.positionAt(Precession.forEpoch(now))
        return ObjectInfo(
            obj = obj,
            track = SkyTrackBuilder.overSpan(
                label = obj.name.ifBlank { obj.id },
                equatorial = equatorial,
                observer = observer,
                fromMillis = now,
                toMillis = now + CURVE_SPAN_MILLIS,
            ),
            description = description,
            currentPosition = CoordinateTransforms.apparentHorizontalAtLst(
                equatorial,
                observer.latitudeDeg,
                AstroTime.lstDeg(now, observer.longitudeDeg),
            ),
        )
    }

    companion object {
        /** A full day, so an object that is down right now still shows when it comes back up. */
        private const val CURVE_SPAN_MILLIS = 24 * 3_600_000L

        fun factory(container: AppContainer, objectId: String) = viewModelFactory {
            initializer {
                ObjectDetailViewModel(
                    objectId = objectId,
                    catalogRepository = container.catalogRepository,
                    locationTracker = container.locationTracker,
                    settingsStore = container.settingsStore,
                    trackingStore = container.trackingStore,
                    objectNotesRepository = container.objectNotesRepository,
                    watchlistRepository = container.watchlistRepository,
                    watchScheduler = container.watchScheduler,
                    favoritesRepository = container.favoritesRepository,
                )
            }
        }
    }
}
