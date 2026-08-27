package com.starwindow.app.data.planning

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneId

/**
 * One night someone has set aside for one object.
 *
 * Stores what it takes to render the calendar **without** recomputing anything: the object's name
 * and type, the hours the night was expected to give, how high it would stand. The alternative —
 * keeping only the id and a date, then re-planning on every calendar draw — would mean a year of
 * astronomy for every scroll, and would quietly change the numbers under the user whenever the
 * catalogue or the location moved. A plan is a note about a decision, and a note does not rewrite
 * itself.
 *
 * The date is an epoch day rather than a `LocalDate` because the file has to survive being read on
 * a device in another time zone, and because kotlinx-serialization has no built-in date type.
 */
@Serializable
data class PlannedSession(
    val id: String,
    /** Catalogue designation, e.g. `M31`; what links the entry back to the object. */
    val objectId: String,
    /** How the object is shown in the calendar, resolved when the plan was made. */
    val objectLabel: String,
    /** The night this belongs to, named by the evening it starts on. */
    val dateEpochDay: Long,
    /** Expected exposure time in minutes, as computed when the plan was made. */
    val usableMinutes: Int = 0,
    /** Highest altitude the object was expected to reach during darkness. */
    val bestAltitudeDeg: Double = 0.0,
    /** Moon illumination in percent on that night. */
    val moonIlluminationPercent: Double = 0.0,
    /** Free text — "Filter mitnehmen", "mit Jan verabredet". */
    val note: String = "",
    /**
     * Which reminders are switched on for this night.
     *
     * A set rather than a single choice: a week out and the day itself answer different questions,
     * and wanting both is the normal case rather than an edge one.
     */
    val reminders: Set<ReminderLead> = emptySet(),
    val createdAtMillis: Long = 0L,
) {
    val date: LocalDate get() = LocalDate.ofEpochDay(dateEpochDay)

    val usableHours: Double get() = usableMinutes / 60.0

    val hasNote: Boolean get() = note.isNotBlank()

    /**
     * Reminders that are still ahead of [nowMillis].
     *
     * A lead that has already passed cannot be scheduled — planning a night for tomorrow and asking
     * to be told a week beforehand is a request about the past.
     */
    fun pendingReminders(zone: ZoneId, nowMillis: Long = System.currentTimeMillis()): List<ReminderLead> =
        reminders.filter { it.triggerMillis(date, zone) > nowMillis }
            .sortedByDescending { it.daysBefore }

    /** The next reminder that will actually fire, or null. */
    fun nextReminder(zone: ZoneId, nowMillis: Long = System.currentTimeMillis()): ReminderLead? =
        pendingReminders(zone, nowMillis).minByOrNull { it.triggerMillis(date, zone) }
}
