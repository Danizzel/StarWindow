package com.starwindow.app.data.planning

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.util.UUID

/**
 * The nights the user has set aside, kept on disk.
 *
 * Same shape and the same failure handling as `SkyWindowRepository`: one JSON file, written through
 * a temporary file so a crash mid-write cannot leave a half-file, and a corrupt file is renamed
 * rather than deleted so it can still be rescued by hand. A plan someone built over a season is
 * not something to lose to a parse error.
 */
class PlanRepository(context: Context) {

    /**
     * Alarms are kept in step here rather than by the callers.
     *
     * Every path that changes a session — adding, removing, editing the note or the reminders —
     * has to end with the alarm queue matching the file, and spreading that duty across view
     * models is how one of them eventually forgets. A stale alarm is a notification for a night
     * the user already deleted.
     */
    private val scheduler = ReminderScheduler(context)

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val mutex = Mutex()
    private val state = MutableStateFlow<List<PlannedSession>>(emptyList())

    /** Every planned session, earliest night first. */
    val sessions: StateFlow<List<PlannedSession>> = state.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock { state.value = readFile().sortedBy { it.dateEpochDay } }
    }

    /** Adds a night, or replaces the existing plan for the same object on the same night. */
    suspend fun add(session: PlannedSession): PlannedSession = withContext(Dispatchers.IO) {
        mutex.withLock {
            val others = state.value.filterNot {
                it.objectId == session.objectId && it.dateEpochDay == session.dateEpochDay
            }
            val updated = (others + session).sortedBy { it.dateEpochDay }
            writeFile(updated)
            state.value = updated
            scheduler.reschedule(session)
            session
        }
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            state.value.firstOrNull { it.id == id }?.let(scheduler::cancel)
            val updated = state.value.filterNot { it.id == id }
            writeFile(updated)
            state.value = updated
        }
    }

    /** Removes the plan for this object on this night, if there is one. */
    suspend fun removeFor(objectId: String, date: LocalDate) = withContext(Dispatchers.IO) {
        mutex.withLock {
            state.value
                .filter { it.objectId == objectId && it.dateEpochDay == date.toEpochDay() }
                .forEach(scheduler::cancel)
            val updated = state.value.filterNot {
                it.objectId == objectId && it.dateEpochDay == date.toEpochDay()
            }
            writeFile(updated)
            state.value = updated
        }
    }

    suspend fun setNote(id: String, note: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = state.value.map { if (it.id == id) it.copy(note = note) else it }
            writeFile(updated)
            state.value = updated
            // The note travels inside the notification, so a changed note means changed alarms.
            updated.firstOrNull { it.id == id }?.let(scheduler::reschedule)
        }
    }

    /** Switches one reminder lead on or off for a session. */
    suspend fun toggleReminder(id: String, lead: ReminderLead) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = state.value.map { session ->
                if (session.id != id) return@map session
                val next = if (lead in session.reminders) {
                    session.reminders - lead
                } else {
                    session.reminders + lead
                }
                session.copy(reminders = next)
            }
            writeFile(updated)
            state.value = updated
            updated.firstOrNull { it.id == id }?.let(scheduler::reschedule)
        }
    }

    /**
     * Re-arms every alarm from the stored plan.
     *
     * Called when the calendar opens: alarms are system state and the file is ours, and the two can
     * drift apart — a reboot clears them, and so does the user clearing app data on one but not the
     * other. Re-arming is idempotent, so doing it too often costs nothing.
     */
    fun rescheduleAll() {
        scheduler.rescheduleAll(state.value)
    }

    /** Creates the notification channel, so a first-time switch has somewhere to post. */
    fun ensureNotificationChannel() = scheduler.ensureChannel()

    /** Everything planned for one night. */
    fun forDate(date: LocalDate): List<PlannedSession> =
        state.value.filter { it.dateEpochDay == date.toEpochDay() }

    fun isPlanned(objectId: String, date: LocalDate): Boolean =
        state.value.any { it.objectId == objectId && it.dateEpochDay == date.toEpochDay() }

    /** Which nights this object is already planned for, so the outlook can mark them. */
    fun datesFor(objectId: String): Set<Long> =
        state.value.filter { it.objectId == objectId }.map { it.dateEpochDay }.toSet()

    private fun readFile(): List<PlannedSession> {
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString<List<PlannedSession>>(file.readText())
        } catch (e: Exception) {
            Log.w(TAG, "Konnte gespeicherte Planung nicht lesen", e)
            runCatching { file.renameTo(File(file.parentFile, "$FILE_NAME.corrupt")) }
            emptyList()
        }
    }

    private fun writeFile(sessions: List<PlannedSession>) {
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        temp.writeText(json.encodeToString(sessions))
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }

    companion object {
        private const val TAG = "PlanRepository"
        private const val FILE_NAME = "observation_plan.json"
        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun newId(): String = UUID.randomUUID().toString()
    }
}
