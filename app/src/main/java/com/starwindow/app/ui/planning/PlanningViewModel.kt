package com.starwindow.app.ui.planning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starwindow.app.AppContainer
import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.core.sensors.LocationTracker
import com.starwindow.app.data.catalog.CatalogRepository
import com.starwindow.app.data.catalog.SkyObject
import com.starwindow.app.data.planning.PlanRepository
import com.starwindow.app.data.planning.PlannedSession
import com.starwindow.app.data.weather.WeatherPlace
import com.starwindow.app.data.windows.SettingsStore
import com.starwindow.app.domain.ObservationNight
import com.starwindow.app.domain.ObservationPlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

data class PlanningUiState(
    val obj: SkyObject? = null,
    val nights: List<ObservationNight> = emptyList(),
    val bestNights: List<ObservationNight> = emptyList(),
    val season: ClosedRange<LocalDate>? = null,
    /** True when there is no dead stretch to speak of; "Saison" would then mislead. */
    val isYearRound: Boolean = false,
    /** Where the plan is computed for, spelled out so the numbers can be trusted. */
    val placeLabel: String = "",
    val observer: ObserverLocation? = null,
    val zone: ZoneId = ZoneId.systemDefault(),
    /** Epoch days this object is already planned for. */
    val plannedDates: Set<Long> = emptySet(),
    val isLoading: Boolean = true,
    val error: String? = null,
) {
    val hasUsableNights: Boolean get() = nights.any { it.isWorthwhile }

    /** The single best night of the year, for the headline. */
    val peak: ObservationNight? get() = nights.maxByOrNull { it.score }?.takeIf { it.isWorthwhile }
}

/**
 * The year ahead for one object.
 *
 * All the astronomy is in [ObservationPlanner] and runs in one shot off the main thread — a full
 * year is a few hundred trigonometric evaluations, so there is no progressive loading to manage and
 * no reason to recompute on scroll.
 *
 * The place deserves a note. Planning happens for the **weather place** when one is set, not for
 * wherever the phone currently is: someone planning a session in October is usually sitting at home
 * and thinking about the dark field they drive to, and `Settings.weatherPlace` already carries
 * exactly that distinction for the forecast. Falling back to the current position keeps it working
 * before any place has been chosen.
 */
class PlanningViewModel(
    private val objectId: String,
    private val catalogRepository: CatalogRepository,
    private val planRepository: PlanRepository,
    private val locationTracker: LocationTracker,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlanningUiState())
    val uiState: StateFlow<PlanningUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            planRepository.load()
            val obj = catalogRepository.objects().firstOrNull { it.id == objectId }
            if (obj == null) {
                _uiState.update { it.copy(isLoading = false, error = "Objekt nicht gefunden") }
                return@launch
            }

            val location = PlanningLocation.resolve(settingsStore, locationTracker)
            val observer = location.observer
            if (observer == null) {
                _uiState.update {
                    it.copy(
                        obj = obj,
                        isLoading = false,
                        error = "Ohne Standort lässt sich nicht planen. Ort in der Wetteransicht " +
                            "wählen oder Standortfreigabe erteilen.",
                    )
                }
                return@launch
            }

            val zone = location.zone
            val from = LocalDate.now(zone)
            val nights = withContext(Dispatchers.Default) {
                ObservationPlanner.plan(obj, observer, zone, from)
            }

            _uiState.update {
                it.copy(
                    obj = obj,
                    nights = nights,
                    bestNights = ObservationPlanner.bestNights(nights),
                    season = ObservationPlanner.season(nights),
                    isYearRound = ObservationPlanner.isYearRound(nights),
                    placeLabel = location.label,
                    observer = observer,
                    zone = zone,
                    isLoading = false,
                )
            }
        }

        viewModelScope.launch {
            planRepository.sessions.collect {
                _uiState.update { state -> state.copy(plannedDates = planRepository.datesFor(objectId)) }
            }
        }
    }

    /** Adds this night to the calendar, or takes it out again when it is already there. */
    fun togglePlan(night: ObservationNight) {
        val obj = _uiState.value.obj ?: return
        viewModelScope.launch {
            if (planRepository.isPlanned(obj.id, night.date)) {
                planRepository.removeFor(obj.id, night.date)
            } else {
                planRepository.add(
                    PlannedSession(
                        id = PlanRepository.newId(),
                        objectId = obj.id,
                        objectLabel = obj.displayName,
                        dateEpochDay = night.date.toEpochDay(),
                        usableMinutes = (night.usableMillis / 60_000L).toInt(),
                        bestAltitudeDeg = night.bestAltitudeDeg,
                        moonIlluminationPercent = night.moonIlluminationPercent,
                        createdAtMillis = System.currentTimeMillis(),
                    )
                )
            }
        }
    }

    companion object {
        fun factory(container: AppContainer, objectId: String) = viewModelFactory {
            initializer {
                PlanningViewModel(
                    objectId = objectId,
                    catalogRepository = container.catalogRepository,
                    planRepository = container.planRepository,
                    locationTracker = container.locationTracker,
                    settingsStore = container.settingsStore,
                )
            }
        }
    }
}
