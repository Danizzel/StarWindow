package com.starwindow.app.ui.planning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starwindow.app.AppContainer
import com.starwindow.app.core.sensors.LocationTracker
import com.starwindow.app.data.planning.PlanRepository
import com.starwindow.app.data.planning.PlannedSession
import com.starwindow.app.data.planning.ReminderLead
import com.starwindow.app.data.planning.WatchScheduler
import com.starwindow.app.data.planning.WatchedObject
import com.starwindow.app.data.planning.WatchlistRepository
import com.starwindow.app.data.weather.WeatherRepository
import com.starwindow.app.data.windows.SettingsStore
import com.starwindow.app.domain.NightOutlook
import com.starwindow.app.domain.NightOutlooks
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
import java.time.YearMonth

/** One month of the calendar grid, with the planned nights in it. */
data class CalendarMonth(
    val yearMonth: YearMonth,
    /** Sessions on each day of the month, keyed by day-of-month. */
    val sessionsByDay: Map<Int, List<PlannedSession>>,
) {
    val total: Int get() = sessionsByDay.values.sumOf { it.size }
}

/**
 * Ein vorgemerktes Objekt, ergänzt um die nächste Nacht, in der es rein astronomisch passen würde.
 *
 * Die Zahl ist bewusst **ohne Wetter** gerechnet: Sie beantwortet „ab wann kann das überhaupt
 * etwas werden", und das ist eine Eigenschaft von Objekt, Ort und Jahreszeit, die drei Monate im
 * Voraus gilt. Ob es in dieser Nacht dann klar wird, entscheidet die Prüfung am selben Nachmittag —
 * eine Vorhersage für den 14. Januar gibt es heute nicht.
 */
data class WatchEntry(
    val watch: WatchedObject,
    val nextNight: LocalDate?,
    val nextUsableHours: Double,
) {
    val hasOpportunity: Boolean get() = nextNight != null
}

data class CalendarUiState(
    val months: List<CalendarMonth> = emptyList(),
    /** Everything still ahead, earliest first — the list under the grid. */
    val upcoming: List<PlannedSession> = emptyList(),
    /** Sessions whose night has passed, kept but folded away. */
    val past: List<PlannedSession> = emptyList(),
    val selectedDate: LocalDate? = null,
    /** The session whose sheet is open, or null. */
    val openSession: PlannedSession? = null,
    val today: LocalDate = LocalDate.now(),
    val zone: ZoneId = ZoneId.systemDefault(),
    val isLoading: Boolean = true,
    /**
     * Die Wetterlage der geplanten Nächte, soweit die Vorhersage reicht — nach Epochentag.
     *
     * Eine Karte und keine Angabe im [PlannedSession] selbst: Was beim Planen gespeichert wurde,
     * ist eine Aufzeichnung einer Entscheidung und wird nicht rückwirkend umgeschrieben. Das Wetter
     * legt sich daneben und verschwindet wieder, wenn es veraltet.
     */
    val outlooks: Map<Long, NightOutlook> = emptyMap(),
    val isWeatherLoading: Boolean = false,
    /** Warum keine Vorhersage dasteht, wenn keine dasteht. */
    val weatherNote: String? = null,
    val watchlist: List<WatchEntry> = emptyList(),
    val openWatch: WatchedObject? = null,
) {
    val isEmpty: Boolean
        get() = !isLoading && upcoming.isEmpty() && past.isEmpty() && watchlist.isEmpty()

    /** What is planned for the day the user tapped. */
    val selectedSessions: List<PlannedSession>
        get() {
            val date = selectedDate ?: return emptyList()
            return (upcoming + past).filter { it.dateEpochDay == date.toEpochDay() }
        }

    /**
     * The next night, for the banner at the top.
     *
     * The one thing a planning calendar is asked most often and answers worst: a grid shows a
     * season, not "what is next", and scrolling to find today's row is exactly the work the banner
     * removes.
     */
    val next: PlannedSession? get() = upcoming.firstOrNull()

    /** Whole days until [next]; 0 means tonight. */
    val daysUntilNext: Long?
        get() = next?.let { it.date.toEpochDay() - today.toEpochDay() }

    fun outlookFor(session: PlannedSession): NightOutlook? = outlooks[session.dateEpochDay]

    fun outlookFor(date: LocalDate): NightOutlook? = outlooks[date.toEpochDay()]
}

/**
 * Die geplanten Nächte, als Jahr zum Durchblättern — und die Merkliste daneben.
 *
 * Die gespeicherten Zahlen eines Termins werden weiterhin **nicht** neu gerechnet: Alles, was hier
 * steht, wurde einmal beim Planen ermittelt und mit dem Termin abgelegt. Ein Jahr Astronomie pro
 * Bildlauf wäre teuer, und schlimmer noch würden sich die Zahlen unter dem Nutzer ändern, sobald
 * der Ort wechselt. Ein Plan ist die Aufzeichnung einer Entscheidung.
 *
 * Neu dazu kommt das **Wetter**, und es gehorcht der umgekehrten Regel: Es wird bei jedem Öffnen
 * frisch geholt, denn eine Vorhersage von letzter Woche ist keine Vorhersage. Es legt sich neben
 * die gespeicherten Zahlen, statt sie zu ersetzen.
 */
class CalendarViewModel(
    private val planRepository: PlanRepository,
    private val watchlistRepository: WatchlistRepository,
    private val weatherRepository: WeatherRepository,
    private val settingsStore: SettingsStore,
    private val locationTracker: LocationTracker,
    private val watchScheduler: WatchScheduler,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CalendarUiState())
    val uiState: StateFlow<CalendarUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            planRepository.load()
            watchlistRepository.load()
            // Alarms are system state and the file is ours; a reboot or a cleared app can leave
            // the two out of step. Re-arming is idempotent, so doing it on open costs nothing.
            planRepository.rescheduleAll()
            // Dasselbe für die Kette der täglichen Prüfungen: Reißt sie einmal, knüpft das Öffnen
            // des Kalenders sie wieder an.
            if (watchlistRepository.watched.value.isNotEmpty()) watchScheduler.scheduleDailyCheck()
        }
        viewModelScope.launch {
            planRepository.sessions.collect { sessions ->
                rebuild(sessions)
                loadWeather(sessions)
            }
        }
        viewModelScope.launch {
            watchlistRepository.watched.collect { watched -> rebuildWatchlist(watched) }
        }
    }

    fun selectDate(date: LocalDate?) {
        _uiState.update { it.copy(selectedDate = if (it.selectedDate == date) null else date) }
    }

    fun openSession(session: PlannedSession) {
        // The channel is created the first time a sheet opens rather than at startup: an app nobody
        // has asked for reminders should not sit in the notification settings offering them.
        planRepository.ensureNotificationChannel()
        _uiState.update { it.copy(openSession = session) }
    }

    fun closeSession() = _uiState.update { it.copy(openSession = null) }

    fun toggleReminder(lead: ReminderLead) {
        val session = _uiState.value.openSession ?: return
        viewModelScope.launch { planRepository.toggleReminder(session.id, lead) }
    }

    fun setNote(note: String) {
        val session = _uiState.value.openSession ?: return
        viewModelScope.launch { planRepository.setNote(session.id, note) }
    }

    fun remove(session: PlannedSession) {
        viewModelScope.launch { planRepository.remove(session.id) }
        if (_uiState.value.openSession?.id == session.id) closeSession()
    }

    fun openWatch(watch: WatchedObject) {
        watchScheduler.ensureChannel()
        _uiState.update { it.copy(openWatch = watch) }
    }

    fun closeWatch() = _uiState.update { it.copy(openWatch = null) }

    fun updateWatch(transform: (WatchedObject) -> WatchedObject) {
        val watch = _uiState.value.openWatch ?: return
        viewModelScope.launch { watchlistRepository.update(watch.id, transform) }
    }

    fun removeWatch(watch: WatchedObject) {
        viewModelScope.launch { watchlistRepository.remove(watch.id) }
        if (_uiState.value.openWatch?.id == watch.id) closeWatch()
    }

    private fun rebuild(sessions: List<PlannedSession>) {
        val today = LocalDate.now()
        val byDate = sessions.groupBy { it.date }

        // Twelve months from this one, so the grid always covers a full planning year even when
        // the plan itself is empty — an empty calendar still has to show what it is.
        val start = YearMonth.from(today)
        val months = (0 until MONTHS_SHOWN).map { offset ->
            val month = start.plusMonths(offset.toLong())
            CalendarMonth(
                yearMonth = month,
                sessionsByDay = byDate
                    .filterKeys { YearMonth.from(it) == month }
                    .mapKeys { (date, _) -> date.dayOfMonth },
            )
        }

        _uiState.update { state ->
            state.copy(
                months = months,
                upcoming = sessions.filter { session -> !session.date.isBefore(today) },
                past = sessions.filter { session -> session.date.isBefore(today) }.reversed(),
                today = today,
                // Keep the open sheet pointing at the stored version, so a toggled reminder or a
                // saved note shows up in it instead of leaving a stale copy on screen.
                openSession = state.openSession?.let { open -> sessions.firstOrNull { it.id == open.id } },
                isLoading = false,
            )
        }
    }

    /**
     * Holt die Vorhersage und legt sie über die kommenden Termine.
     *
     * Ein einziger Abruf für alle Termine: Die Vorhersage ist ein Lauf über zwei Wochen, und jeden
     * Termin einzeln zu fragen hieße, denselben Lauf mehrfach zu holen. Termine jenseits der
     * Reichweite fallen einfach heraus — für den 14. Januar gibt es im Oktober keine Aussage, und
     * eine leere Stelle ist die richtige Darstellung dafür.
     */
    private fun loadWeather(sessions: List<PlannedSession>) {
        val today = LocalDate.now()
        val dates = sessions.map { it.date }.filter { !it.isBefore(today) }
        if (dates.isEmpty()) {
            _uiState.update { it.copy(outlooks = emptyMap(), isWeatherLoading = false, weatherNote = null) }
            return
        }

        val location = PlanningLocation.resolve(settingsStore, locationTracker)
        val place = location.place
        val observer = location.observer
        if (place == null || observer == null) {
            _uiState.update {
                it.copy(
                    outlooks = emptyMap(),
                    isWeatherLoading = false,
                    weatherNote = "Ohne Ort keine Vorhersage – in der Wetteransicht einen wählen.",
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isWeatherLoading = true) }
            val forecast = weatherRepository
                .forecast(place, settingsStore.current.weatherModel)
                .getOrNull()
            if (forecast == null) {
                _uiState.update {
                    it.copy(isWeatherLoading = false, weatherNote = "Vorhersage nicht erreichbar.")
                }
                return@launch
            }
            val outlooks = withContext(Dispatchers.Default) {
                NightOutlooks.forDates(forecast, observer, dates, location.zone)
            }
            _uiState.update { state ->
                state.copy(
                    outlooks = outlooks.mapKeys { (date, _) -> date.toEpochDay() },
                    isWeatherLoading = false,
                    weatherNote = null,
                )
            }
        }
    }

    /**
     * Rechnet für jeden Merklisteneintrag die nächste astronomisch passende Nacht aus.
     *
     * [WATCH_LOOKAHEAD_DAYS] Tage voraus statt eines ganzen Jahres: Die Frage der Merkliste ist
     * „wann wird das wieder etwas", und ein halbes Jahr im Voraus ist die Antwort „im Winter" —
     * dafür braucht es keine Datumsangabe. Vier Monate decken jede Saisonlücke ab, die einen
     * Interessieren kann, und kosten pro Eintrag ein paar hundert trigonometrische Auswertungen.
     */
    private fun rebuildWatchlist(watched: List<WatchedObject>) {
        if (watched.isEmpty()) {
            _uiState.update { it.copy(watchlist = emptyList(), openWatch = null) }
            return
        }
        val location = PlanningLocation.resolve(settingsStore, locationTracker)
        val observer = location.observer
        if (observer == null) {
            _uiState.update { state ->
                state.copy(
                    watchlist = watched.map { WatchEntry(it, null, 0.0) },
                    openWatch = state.openWatch?.let { open -> watched.firstOrNull { it.id == open.id } },
                )
            }
            return
        }

        viewModelScope.launch {
            val today = LocalDate.now(location.zone)
            val entries = withContext(Dispatchers.Default) {
                watched.map { watch ->
                    val night = (0 until WATCH_LOOKAHEAD_DAYS)
                        .asSequence()
                        .map { offset ->
                            ObservationPlanner.nightFor(
                                positionJ2000 = watch.position,
                                observer = observer,
                                zone = location.zone,
                                date = today.plusDays(offset.toLong()),
                                minAltitudeDeg = watch.minAltitudeDeg,
                            )
                        }
                        .firstOrNull { it.usableMillis >= watch.minUsableMillis }
                    WatchEntry(
                        watch = watch,
                        nextNight = night?.date,
                        nextUsableHours = night?.usableHours ?: 0.0,
                    )
                }
            }
            _uiState.update { state ->
                state.copy(
                    watchlist = entries,
                    openWatch = state.openWatch?.let { open -> watched.firstOrNull { it.id == open.id } },
                )
            }
        }
    }

    companion object {
        /** A full year ahead: that is the horizon the planner itself works on. */
        const val MONTHS_SHOWN = 12

        /** Vier Monate — weit genug, um jede Saisonlücke zu überbrücken. */
        const val WATCH_LOOKAHEAD_DAYS = 120

        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                CalendarViewModel(
                    planRepository = container.planRepository,
                    watchlistRepository = container.watchlistRepository,
                    weatherRepository = container.weatherRepository,
                    settingsStore = container.settingsStore,
                    locationTracker = container.locationTracker,
                    watchScheduler = container.watchScheduler,
                )
            }
        }
    }
}
