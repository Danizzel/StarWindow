package com.starwindow.app.ui.hub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starwindow.app.AppContainer
import com.starwindow.app.core.sensors.LocationTracker
import com.starwindow.app.data.catalog.CatalogRepository
import com.starwindow.app.data.catalog.ObjectNotesRepository
import com.starwindow.app.data.windows.SettingsStore
import com.starwindow.app.domain.Season
import com.starwindow.app.domain.SeasonalHighlights
import com.starwindow.app.domain.SeasonalTarget
import com.starwindow.app.domain.TargetKind
import com.starwindow.app.ui.planning.PlanningLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

data class StargazingHubUiState(
    val season: Season = Season.WINTER,
    /** Die Jahreszeit, die gerade tatsächlich läuft — die Auswahl kann davon abweichen. */
    val currentSeason: Season = Season.WINTER,
    /** Die Nacht, für die gerechnet wurde. */
    val date: LocalDate = LocalDate.now(),
    val placeLabel: String = "",
    val targets: List<SeasonalTarget> = emptyList(),
    val rows: List<Pair<TargetKind, List<SeasonalTarget>>> = emptyList(),
    /** Kurzbeschreibungen zu den Motiven, nach Objekt-Kennung. */
    val notes: Map<String, String> = emptyMap(),
    val isLoading: Boolean = true,
    val hasLocation: Boolean = true,
) {
    /** Das Motiv, mit dem der Hub aufmacht. */
    val hero: SeasonalTarget? get() = targets.firstOrNull()

    /**
     * Die Reihe unter dem Hauptmotiv.
     *
     * Kurz gehalten, obwohl mehr da wäre: Darunter kommen dieselben Motive noch einmal nach Art
     * sortiert, und eine erste Reihe, die schon alles enthält, macht die folgenden zu einer
     * Wiederholung. Acht sind eine Bestenliste, vierundzwanzig sind der Katalog.
     */
    val topPicks: List<SeasonalTarget> get() = targets.drop(1).take(8)

    val isEmpty: Boolean get() = !isLoading && targets.isEmpty()

    /** Wird gerade die laufende Jahreszeit gezeigt, oder ein Blick nach vorn? */
    val isCurrent: Boolean get() = season == currentSeason

    /** Der Monat, aus dem die Zahlen stammen — der Grund, warum sich die Liste laufend ändert. */
    val monthLabel: String
        get() = date.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.GERMAN)
}

/**
 * Der Stargazing-Hub.
 *
 * Rechnet [SeasonalHighlights] für eine Jahreszeit und einen Ort. Beides ist wechselbar, und beides
 * kostet dieselbe knappe Sekunde, weshalb jede Auswahl den vorigen Lauf abbricht statt sich hinter
 * ihn zu stellen — wer sich durch die vier Jahreszeiten tippt, wartet sonst am Ende auf vier
 * Rechnungen, von denen ihn drei nicht interessieren.
 *
 * Der Ort kommt aus [PlanningLocation], also aus demselben „wohin fahre ich" wie Kalender und
 * Wetter — nicht daraus, wo das Telefon gerade liegt. Wer im Wohnzimmer sitzt und den Herbst plant,
 * meint das dunkle Feld.
 */
class StargazingHubViewModel(
    private val catalogRepository: CatalogRepository,
    private val notesRepository: ObjectNotesRepository,
    private val settingsStore: SettingsStore,
    private val locationTracker: LocationTracker,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StargazingHubUiState())
    val uiState: StateFlow<StargazingHubUiState> = _uiState.asStateFlow()

    private var job: Job? = null

    init {
        val location = PlanningLocation.resolve(settingsStore, locationTracker)
        val latitude = location.observer?.latitudeDeg ?: 0.0
        val current = Season.of(LocalDate.now(location.zone), latitude)
        _uiState.update { it.copy(season = current, currentSeason = current) }
        load(current)

        // Der Hub überlebt einen Wechsel in die Wetteransicht — die Leiste legt seinen Zustand
        // beiseite statt ihn wegzuwerfen. Wird dort ein anderer Ort gewählt, stünde hier sonst
        // beim Zurückkommen weiter der Himmel des alten Orts, und zwar wortlos.
        viewModelScope.launch {
            settingsStore.settings
                .map { it.weatherPlace }
                .distinctUntilChanged()
                .drop(1)
                .collect { load(_uiState.value.season) }
        }
    }

    fun selectSeason(season: Season) {
        if (_uiState.value.season == season) return
        _uiState.update { it.copy(season = season) }
        load(season)
    }

    private fun load(season: Season) {
        job?.cancel()
        job = viewModelScope.launch {
            val location = PlanningLocation.resolve(settingsStore, locationTracker)
            val observer = location.observer
            val today = LocalDate.now(location.zone)
            val currentSeason = Season.of(today, observer?.latitudeDeg ?: 0.0)

            if (observer == null) {
                _uiState.update {
                    it.copy(
                        season = season,
                        currentSeason = currentSeason,
                        placeLabel = location.label,
                        targets = emptyList(),
                        rows = emptyList(),
                        isLoading = false,
                        hasLocation = false,
                    )
                }
                return@launch
            }

            _uiState.update { it.copy(isLoading = true, hasLocation = true) }

            val date = SeasonalHighlights.referenceDate(season, today, observer.latitudeDeg)
            val objects = catalogRepository.objects()
            val targets = withContext(Dispatchers.Default) {
                SeasonalHighlights.build(
                    objects = objects,
                    observer = observer,
                    zone = location.zone,
                    date = date,
                )
            }

            // Erst nachschlagen, wenn feststeht, welche zwei Dutzend Objekte es überhaupt sind —
            // die Notizen liegen für ein paar hundert Einträge vor, und gesucht wird über alle
            // Bezeichnungen eines jeden.
            val notes = targets.associate { it.obj.id to (notesRepository.noteFor(it.obj) ?: "") }
                .filterValues { it.isNotBlank() }

            _uiState.update {
                it.copy(
                    season = season,
                    currentSeason = currentSeason,
                    date = date,
                    placeLabel = location.label,
                    targets = targets,
                    rows = SeasonalHighlights.rows(targets),
                    notes = notes,
                    isLoading = false,
                    hasLocation = true,
                )
            }
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                StargazingHubViewModel(
                    catalogRepository = container.catalogRepository,
                    notesRepository = container.objectNotesRepository,
                    settingsStore = container.settingsStore,
                    locationTracker = container.locationTracker,
                )
            }
        }
    }
}
