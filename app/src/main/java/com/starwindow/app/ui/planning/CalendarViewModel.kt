package com.starwindow.app.ui.planning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starwindow.app.AppContainer
import com.starwindow.app.data.planning.PlanRepository
import com.starwindow.app.data.planning.PlannedSession
import com.starwindow.app.data.planning.ReminderLead
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
) {
    val isEmpty: Boolean get() = !isLoading && upcoming.isEmpty() && past.isEmpty()

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
}

/**
 * The planned nights, as a year to scroll through.
 *
 * Deliberately a plain reader over [PlanRepository]: everything shown here was computed once, when
 * the plan was made, and stored with it. Re-deriving the hours and altitudes on every calendar draw
 * would mean a year of astronomy per scroll — and worse, the numbers would quietly change under the
 * user whenever the location moved. A plan is a record of a decision.
 */
class CalendarViewModel(
    private val planRepository: PlanRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CalendarUiState())
    val uiState: StateFlow<CalendarUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            planRepository.load()
            // Alarms are system state and the file is ours; a reboot or a cleared app can leave
            // the two out of step. Re-arming is idempotent, so doing it on open costs nothing.
            planRepository.rescheduleAll()
        }
        viewModelScope.launch {
            planRepository.sessions.collect { sessions -> rebuild(sessions) }
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

    companion object {
        /** A full year ahead: that is the horizon the planner itself works on. */
        const val MONTHS_SHOWN = 12

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { CalendarViewModel(planRepository = container.planRepository) }
        }
    }
}
