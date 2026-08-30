package com.starwindow.app.ui.weather

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starwindow.app.AppContainer
import com.starwindow.app.core.sensors.LocationTracker
import com.starwindow.app.data.weather.PlaceLookup
import com.starwindow.app.data.weather.WeatherModel
import com.starwindow.app.data.weather.WeatherModelStatus
import com.starwindow.app.data.weather.WeatherPlace
import com.starwindow.app.data.weather.WeatherRepository
import com.starwindow.app.data.windows.SettingsStore
import com.starwindow.app.domain.AstroNight
import com.starwindow.app.domain.AstroWeather
import com.starwindow.app.domain.BortleScale
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WeatherUiState(
    val place: WeatherPlace? = null,
    val query: String = "",
    val suggestions: List<WeatherPlace> = emptyList(),
    val isSearching: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val nights: List<AstroNight> = emptyList(),
    /** Die Nacht, die in der Liste aufgeklappt ist. */
    val expandedDate: LocalDate? = null,
    val fetchedAtMillis: Long? = null,
    val zone: ZoneId = ZoneId.systemDefault(),
    /** Das gewählte Wettermodell. */
    val model: WeatherModel = WeatherModel.DEFAULT,
    /** Was die Modelle über ihren letzten Lauf sagen; leer, solange noch nichts geladen ist. */
    val modelStatuses: Map<WeatherModel, WeatherModelStatus> = emptyMap(),
    /** Bis wohin die geladene Vorhersage trägt — nicht dasselbe wie das Ende des letzten Laufs. */
    val forecastEndMillis: Long? = null,
) {
    /** Die laufende beziehungsweise kommende Nacht — die, die oben steht. */
    val tonight: AstroNight? get() = nights.firstOrNull()

    /** Alle weiteren, für die Liste darunter. */
    val upcoming: List<AstroNight> get() = nights.drop(1)

    val bortleLevel: Int? get() = place?.let { BortleScale.resolve(it) }

    val bortleIsManual: Boolean get() = place?.bortleOverride != null

    val hasSuggestions: Boolean get() = suggestions.isNotEmpty()

    val selectedStatus: WeatherModelStatus? get() = modelStatuses[model]

    /**
     * Ob ein Modell den gewählten Ort überhaupt abdeckt.
     *
     * Ohne bekannten Zustand wird nichts behauptet — lieber ein Chip, der beim Antippen ehrlich
     * scheitert, als einer, der grundlos gesperrt ist.
     */
    fun covers(candidate: WeatherModel): Boolean {
        val here = place ?: return true
        val status = modelStatuses[candidate] ?: return true
        return status.covers(here.latitudeDeg, here.longitudeDeg)
    }
}

/**
 * Die Wetteransicht.
 *
 * Der Ablauf ist bewusst schlicht: ein Ort, ein Modelllauf, daraus alle Nächte auf einmal. Die
 * Alternative — pro aufgeklapptem Tag nachladen — sähe sparsamer aus, wäre es aber nicht: Der Lauf
 * kommt ohnehin als ein Block über die Leitung, und ihn im Speicher zu behalten kostet für zwei
 * Wochen Stundenwerte weniger als ein einziges Vorschaubild.
 *
 * Die Auswertung läuft auf [Dispatchers.Default]: Für fünfzehn Nächte werden Sonne und Mond je
 * einige hundert Mal gerechnet, und das gehört nicht in den Haupt-Thread.
 */
class WeatherViewModel(
    private val weatherRepository: WeatherRepository,
    private val placeLookup: PlaceLookup,
    private val locationTracker: LocationTracker,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WeatherUiState())
    val uiState: StateFlow<WeatherUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var loadJob: Job? = null
    private var statusJob: Job? = null

    init {
        _uiState.update { it.copy(model = settingsStore.current.weatherModel) }
        val saved = settingsStore.current.weatherPlace
        if (saved != null) {
            selectPlace(saved, persist = false)
        } else {
            useCurrentLocation()
        }
        refreshModelStatuses()
    }

    /**
     * Holt den Zustand aller Modelle.
     *
     * Gleich beim Öffnen und nicht erst beim Aufklappen der Auswahl: Die Zeile unter der Chipreihe
     * sagt, wie alt der Lauf ist, den man gerade ansieht — und diese Zeile soll nicht erst
     * erscheinen, wenn jemand danach sucht.
     */
    fun refreshModelStatuses(forceReload: Boolean = false) {
        statusJob?.cancel()
        statusJob = viewModelScope.launch {
            if (forceReload) {
                WeatherModel.ORDERED.forEach { weatherRepository.modelStatus(it, forceReload = true) }
            }
            val statuses = weatherRepository.modelStatuses()
            if (statuses.isNotEmpty()) {
                _uiState.update { it.copy(modelStatuses = statuses) }
            }
        }
    }

    /**
     * Modell wechseln.
     *
     * Der Ort bleibt, die Vorhersage wird neu geholt — und mit ihr ändert sich in aller Regel auch
     * die Länge der Nächteliste: ICON-D2 reicht zwei Tage weit, ECMWF fünfzehn.
     */
    fun selectModel(model: WeatherModel) {
        if (model == _uiState.value.model) return
        settingsStore.setWeatherModel(model)
        _uiState.update { it.copy(model = model) }
        _uiState.value.place?.let { load(it, model, forceReload = false) }
    }

    fun setQuery(query: String) {
        _uiState.update { it.copy(query = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update { it.copy(suggestions = emptyList(), isSearching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS)
            _uiState.update { it.copy(isSearching = true) }
            val hits = weatherRepository.searchPlaces(query).getOrNull().orEmpty()
            _uiState.update { it.copy(suggestions = hits, isSearching = false) }
        }
    }

    fun clearQuery() = setQuery("")

    /** Einen Treffer der Ortssuche übernehmen. */
    fun selectPlace(place: WeatherPlace, persist: Boolean = true) {
        searchJob?.cancel()
        if (persist) settingsStore.setWeatherPlace(place)
        _uiState.update {
            it.copy(
                place = place,
                query = "",
                suggestions = emptyList(),
                isSearching = false,
                zone = place.zone ?: it.zone,
            )
        }
        load(place, _uiState.value.model, forceReload = false)
    }

    /**
     * Zurück auf den eigenen Standort.
     *
     * Ohne Ortungsfreigabe bleibt nur die Ortssuche, und die Meldung sagt genau das — eine leere
     * Ansicht ohne Grund wäre die schlechteste der möglichen Antworten.
     */
    fun useCurrentLocation() {
        val fix = locationTracker.lastKnown()
        if (fix == null) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    error = if (locationTracker.hasPermission) {
                        "Noch keine Position – bitte den Ort oben eingeben."
                    } else {
                        "Ohne Ortungsfreigabe: bitte den Ort oben eingeben."
                    },
                )
            }
            return
        }
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val place = placeLookup.describe(fix)
            settingsStore.setWeatherPlace(place)
            _uiState.update { it.copy(place = place, zone = place.zone ?: it.zone) }
            load(place, _uiState.value.model, forceReload = false)
        }
    }

    fun refresh() {
        refreshModelStatuses(forceReload = true)
        val place = _uiState.value.place ?: return useCurrentLocation()
        load(place, _uiState.value.model, forceReload = true)
    }

    /** Einen Tag auf- oder zuklappen. Ein zweiter Tipp auf denselben schließt ihn wieder. */
    fun toggleDay(date: LocalDate) {
        _uiState.update { it.copy(expandedDate = if (it.expandedDate == date) null else date) }
    }

    /** Bortle-Stufe von Hand setzen; null stellt die Schätzung wieder her. */
    fun setBortleOverride(level: Int?) {
        val place = _uiState.value.place ?: return
        val updated = place.copy(bortleOverride = level)
        settingsStore.setWeatherPlace(updated)
        _uiState.update { it.copy(place = updated) }
    }

    private fun load(place: WeatherPlace, model: WeatherModel, forceReload: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val result = weatherRepository.forecast(place, model, forceReload)
            val forecast = result.getOrNull()
            if (forecast == null) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        nights = emptyList(),
                        error = result.exceptionOrNull()?.message
                            ?: "Die Vorhersage ist gerade nicht erreichbar.",
                    )
                }
                return@launch
            }

            val location = place.toObserverLocation()
            val nights = withContext(Dispatchers.Default) {
                AstroWeather.nights(forecast, location)
            }

            _uiState.update {
                it.copy(
                    // Der Lauf meldet die Höhe seines Gitterpunkts und die Zeitzone; beides gehört
                    // in den gespeicherten Ort, ohne die vom Nutzer gesetzte Bortle-Stufe zu verlieren.
                    place = forecast.place.copy(bortleOverride = place.bortleOverride),
                    zone = forecast.zone,
                    model = forecast.model,
                    nights = nights,
                    fetchedAtMillis = forecast.fetchedAtMillis,
                    forecastEndMillis = forecast.lastMillis,
                    isLoading = false,
                    error = if (nights.isEmpty()) {
                        "${forecast.model.label} liefert für diesen Ort keine ganze Nacht."
                    } else {
                        null
                    },
                )
            }
        }
    }

    companion object {
        /** Der Geocoder ist ein Netzaufruf, also länger als bei der Katalogsuche. */
        private const val SEARCH_DEBOUNCE_MILLIS = 350L

        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                WeatherViewModel(
                    weatherRepository = container.weatherRepository,
                    placeLookup = container.placeLookup,
                    locationTracker = container.locationTracker,
                    settingsStore = container.settingsStore,
                )
            }
        }
    }
}
