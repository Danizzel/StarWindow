package com.starwindow.app.ui.guide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starwindow.app.AppContainer
import com.starwindow.app.core.astro.LunarEphemeris
import com.starwindow.app.core.astro.Precession
import com.starwindow.app.core.geometry.SphericalGeometry
import com.starwindow.app.core.sensors.LocationTracker
import com.starwindow.app.data.catalog.CatalogRepository
import com.starwindow.app.data.catalog.SkyObject
import com.starwindow.app.data.favorites.FavoritesRepository
import com.starwindow.app.data.gear.SmartTelescope
import com.starwindow.app.data.gear.SmartTelescopes
import com.starwindow.app.data.weather.WeatherRepository
import com.starwindow.app.data.windows.SettingsStore
import com.starwindow.app.domain.BortleScale
import com.starwindow.app.domain.ObservationPlanner
import com.starwindow.app.domain.ObjectSearch
import com.starwindow.app.domain.PhotoGuide
import com.starwindow.app.domain.PhotoPlan
import com.starwindow.app.domain.PhotographicInterest
import com.starwindow.app.domain.Season
import com.starwindow.app.ui.planning.PlanningLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.abs

data class PhotoGuideUiState(
    val query: String = "",
    val suggestions: List<SkyObject> = emptyList(),
    val target: SkyObject? = null,
    val telescope: SmartTelescope = SmartTelescopes.DEFAULT,
    /** Die Stufe, mit der gerechnet wird. */
    val bortle: Int = 5,
    /** Was der Ort selbst hergibt, oder null, wenn er nichts hergibt. */
    val suggestedBortle: Int? = null,
    /** True, wenn [bortle] von Hand gesetzt wurde statt aus dem Ort zu kommen. */
    val bortleIsManual: Boolean = false,
    val placeLabel: String = "",
    val hasLocation: Boolean = true,
    val plan: PhotoPlan? = null,
    val moonIlluminationPercent: Double = 0.0,
    val moonAltitudeDeg: Double = -90.0,
    /** Woher der Taupunkt kommt, oder null, wenn keine Vorhersage vorliegt. */
    val dewSource: String? = null,
    val isLoading: Boolean = false,
) {
    val favoritesShown: Boolean get() = query.isBlank()
}

/**
 * Der Fotoguide.
 *
 * Sammelt die drei Angaben, die der Nutzer machen muss — Ziel, Gerät, Bortle-Stufe — und alles
 * Übrige, das die App ohnehin weiß: wo geplant wird, wie lange das Objekt heute Nacht steht, wo der
 * Mond dabei ist und wie nah die Luft am Taupunkt liegt. [PhotoGuide] macht daraus den Plan.
 *
 * **Zur Bortle-Stufe:** Sie gehört zum Beobachtungsort und nicht zum Nutzer, deshalb wird ein von
 * Hand gesetzter Wert dort abgelegt, wo der Ort liegt — im gewählten Wetterort, denselben Wert, den
 * die Wetteransicht anzeigt und schreibt. Nur wenn gar kein Ort gewählt ist und das Gerät auf sein
 * GPS angewiesen ist, landet er in den Einstellungen. Zwei Ansichten, die dieselbe Stufe
 * verschieden anzeigen, wären schlimmer als eine Stufe, die an zwei Stellen stehen kann.
 */
class PhotoGuideViewModel(
    private val catalogRepository: CatalogRepository,
    private val favoritesRepository: FavoritesRepository,
    private val settingsStore: SettingsStore,
    private val locationTracker: LocationTracker,
    private val weatherRepository: WeatherRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PhotoGuideUiState())
    val uiState: StateFlow<PhotoGuideUiState> = _uiState.asStateFlow()

    private var catalog: List<SkyObject> = emptyList()
    private var planJob: Job? = null

    init {
        viewModelScope.launch {
            catalog = catalogRepository.objects()
            favoritesRepository.load()

            val settings = settingsStore.current
            val location = PlanningLocation.resolve(settingsStore, locationTracker)
            val suggested = location.place?.let { BortleScale.estimate(it) }
            val manual = location.place?.bortleOverride ?: settings.bortleOverride

            _uiState.update {
                it.copy(
                    telescope = SmartTelescopes.byId(settings.telescopeId),
                    suggestedBortle = suggested,
                    bortle = manual ?: suggested ?: DEFAULT_BORTLE,
                    bortleIsManual = manual != null,
                    placeLabel = location.label,
                    hasLocation = location.hasLocation,
                    suggestions = defaultSuggestions(),
                )
            }

            settings.guideObjectId
                ?.let { id -> catalog.firstOrNull { it.id == id } }
                ?.let { selectTarget(it) }
        }

        // Ein Herz, das anderswo gesetzt wird, gehört sofort in die Vorschlagsliste: Der Guide
        // fragt nach einem Ziel, und die eigenen Favoriten sind die kürzeste Antwort darauf.
        viewModelScope.launch {
            favoritesRepository.favorites.collect {
                if (_uiState.value.query.isBlank()) {
                    _uiState.update { state -> state.copy(suggestions = defaultSuggestions()) }
                }
            }
        }
    }

    fun setQuery(query: String) {
        _uiState.update { it.copy(query = query) }
        viewModelScope.launch {
            val found = if (query.isBlank()) {
                defaultSuggestions()
            } else {
                withContext(Dispatchers.Default) {
                    ObjectSearch.search(query, catalog, observer = null, limit = 30)
                        .map { it.obj }
                        .filter { PhotographicInterest.isPhotoTarget(it) }
                        .take(12)
                }
            }
            _uiState.update { it.copy(suggestions = found) }
        }
    }

    fun selectTarget(obj: SkyObject) {
        settingsStore.setGuideObject(obj.id)
        _uiState.update { it.copy(target = obj, query = "", suggestions = defaultSuggestions()) }
        recompute()
    }

    fun clearTarget() {
        settingsStore.setGuideObject(null)
        _uiState.update { it.copy(target = null, plan = null) }
    }

    fun selectTelescope(telescope: SmartTelescope) {
        settingsStore.setTelescope(telescope.id)
        _uiState.update { it.copy(telescope = telescope) }
        recompute()
    }

    /** Setzt die Stufe von Hand; null nimmt wieder den Vorschlag des Orts. */
    fun setBortle(level: Int?) {
        val place = settingsStore.current.weatherPlace
        if (place != null) {
            settingsStore.setWeatherPlace(place.copy(bortleOverride = level))
        } else {
            settingsStore.setBortleOverride(level)
        }
        _uiState.update {
            it.copy(
                bortle = level ?: it.suggestedBortle ?: DEFAULT_BORTLE,
                bortleIsManual = level != null,
            )
        }
        recompute()
    }

    /**
     * Rechnet den Plan.
     *
     * Die Nacht kommt aus [ObservationPlanner] und ist dieselbe Rechnung, mit der auch Kalender und
     * Hub arbeiten — ein zweiter Weg zu „wie lange steht das heute Nacht" wäre ein zweites
     * Ergebnis.
     */
    private fun recompute() {
        val target = _uiState.value.target ?: return
        planJob?.cancel()
        planJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val location = PlanningLocation.resolve(settingsStore, locationTracker)
            val observer = location.observer
            val today = LocalDate.now(location.zone)
            val state = _uiState.value

            val night = observer?.let {
                withContext(Dispatchers.Default) {
                    ObservationPlanner.nightFor(
                        positionJ2000 = target.equatorialJ2000,
                        observer = it,
                        zone = location.zone,
                        date = today,
                        minAltitudeDeg = MIN_ALTITUDE_DEG,
                    )
                }
            }

            // Der Mondabstand entscheidet mit über die Belichtungszeit und ist billig zu haben,
            // sobald der beste Zeitpunkt der Nacht feststeht: dann steht auch fest, wo der Mond
            // in diesem Moment steht.
            val moment = night?.bestMillis ?: System.currentTimeMillis()
            val moon = LunarEphemeris.at(moment)
            val separation = SphericalGeometry.separationDeg(
                moon.equatorial.toVector(),
                Precession.forEpoch(moment).toDate(target.equatorialJ2000).toVector(),
            )
            val moonAltitude = observer
                ?.let { LunarEphemeris.topocentricAltitudeDeg(moon, it, moment) }
                ?: -90.0
            val illumination = LunarEphemeris.illuminationAt(moment).percent

            // Nur der zwischengespeicherte Lauf, kein Abruf: Der Guide soll sofort antworten und
            // auch am dunklen Feld ohne Empfang etwas sagen. Liegt nichts vor, entscheidet die
            // Jahreszeit über den Tau — und der Text sagt dann auch, dass er das tut.
            val forecast = location.place?.let { place ->
                runCatching {
                    weatherRepository.lastKnown(
                        place = place,
                        model = settingsStore.current.weatherModel,
                        fallbackToAnyModel = true,
                    )
                }.getOrNull()
            }
            val hour = forecast?.hours
                ?.minByOrNull { abs(it.millis - moment) }
                ?.takeIf { abs(it.millis - moment) <= MAX_FORECAST_GAP_MILLIS }

            val plan = PhotoGuide.plan(
                obj = target,
                telescope = state.telescope,
                bortle = state.bortle,
                moonIlluminationPercent = illumination,
                moonAltitudeDeg = moonAltitude,
                moonSeparationDeg = separation,
                dewSpreadK = hour?.dewSpreadK,
                humidityPercent = hour?.humidityPercent,
                season = observer?.let { Season.of(today, it.latitudeDeg) },
                availableHours = (night?.usableMillis ?: 0L) / 3_600_000.0,
            )

            _uiState.update {
                it.copy(
                    plan = plan,
                    moonIlluminationPercent = illumination,
                    moonAltitudeDeg = moonAltitude,
                    placeLabel = location.label,
                    hasLocation = observer != null,
                    dewSource = when {
                        hour?.dewSpreadK != null -> "Taupunkt aus der Vorhersage"
                        hour?.humidityPercent != null -> "Luftfeuchte aus der Vorhersage"
                        else -> null
                    },
                    isLoading = false,
                )
            }
        }
    }

    /**
     * Was ohne Eingabe vorgeschlagen wird.
     *
     * Erst die Favoriten, dann die lohnendsten Katalogeinträge. Wer den Guide öffnet, hat meistens
     * schon ein Motiv im Kopf — und wenn es eines ist, das er mag, steht es dann in der ersten
     * Zeile statt hinter acht Buchstaben Tipparbeit.
     */
    private fun defaultSuggestions(): List<SkyObject> {
        val favorites = favoritesRepository.favorites.value
        val liked = favorites.mapNotNull { id -> catalog.firstOrNull { it.id == id } }
        val rest = catalog.asSequence()
            .filter { PhotographicInterest.isPhotoTarget(it) && it.id !in favorites }
            .sortedByDescending { PhotographicInterest.score(it) }
            .take(12)
        return (liked + rest).take(12)
    }

    companion object {
        /** Ohne Ort und ohne Vorschlag: Vorstadthimmel, wo die meisten Menschen wohnen. */
        const val DEFAULT_BORTLE = 5

        /** Dieselbe Untergrenze wie im Hub — was tiefer steht, ist mit diesen Geräten nichts. */
        private const val MIN_ALTITUDE_DEG = 20.0

        /** Weiter als drei Stunden vom gemeinten Zeitpunkt sagt eine Vorhersagestunde nichts mehr. */
        private const val MAX_FORECAST_GAP_MILLIS = 3 * 3_600_000L

        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                PhotoGuideViewModel(
                    catalogRepository = container.catalogRepository,
                    favoritesRepository = container.favoritesRepository,
                    settingsStore = container.settingsStore,
                    locationTracker = container.locationTracker,
                    weatherRepository = container.weatherRepository,
                )
            }
        }
    }
}
