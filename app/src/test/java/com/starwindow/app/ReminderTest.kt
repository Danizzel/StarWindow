package com.starwindow.app

import com.starwindow.app.data.planning.PlannedSession
import com.starwindow.app.data.planning.ReminderLead
import com.starwindow.app.data.planning.ReminderScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

private val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private fun session(
    date: LocalDate = LocalDate.of(2026, 10, 16),
    reminders: Set<ReminderLead> = emptySet(),
    id: String = "s1",
) = PlannedSession(
    id = id,
    objectId = "M31",
    objectLabel = "M31 · Andromedagalaxie",
    dateEpochDay = date.toEpochDay(),
    usableMinutes = 260,
    reminders = reminders,
)

class ReminderLeadTest {

    /**
     * A reminder fires in the late afternoon of its own day, not at the time the object culminates.
     * An alert at three in the morning because that is when M31 is highest would be worse than none.
     */
    @Test
    fun `a reminder fires in the late afternoon of the right day`() {
        val night = LocalDate.of(2026, 10, 16)

        val week = ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(ReminderLead.ONE_WEEK.triggerMillis(night, BERLIN)),
            BERLIN,
        )

        assertEquals(LocalDate.of(2026, 10, 9), week.toLocalDate())
        assertEquals(ReminderLead.NOTIFY_TIME, week.toLocalTime())
    }

    @Test
    fun `each lead lands the stated number of days before the night`() {
        val night = LocalDate.of(2026, 3, 5)
        for (lead in ReminderLead.entries) {
            val fired = ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(lead.triggerMillis(night, BERLIN)),
                BERLIN,
            ).toLocalDate()
            assertEquals(night.minusDays(lead.daysBefore), fired, "${lead.name} landed on $fired")
        }
    }

    /** The zone is the *place's*, not the phone's, so the same night can differ by hours. */
    @Test
    fun `the trigger follows the time zone it is asked for`() {
        val night = LocalDate.of(2026, 10, 16)
        val berlin = ReminderLead.ONE_DAY.triggerMillis(night, BERLIN)
        val auckland = ReminderLead.ONE_DAY.triggerMillis(night, ZoneId.of("Pacific/Auckland"))

        assertTrue(berlin != auckland, "17:00 in Berlin is not 17:00 in Auckland")
    }

    /** Ordered as a countdown, which is how the sheet lists them. */
    @Test
    fun `the offered leads run from furthest ahead to nearest`() {
        assertEquals(
            listOf(7L, 3L, 1L, 0L),
            ReminderLead.ordered.map { it.daysBefore },
        )
        assertEquals(ReminderLead.entries.size, ReminderLead.ordered.size, "a lead is not offered")
    }
}

class PlannedSessionReminderTest {

    /**
     * Planning a night for tomorrow and asking to be told a week beforehand is a request about the
     * past. It stays stored — the user may move the night — but it must not be counted as pending.
     */
    @Test
    fun `a lead whose moment has passed is not pending`() {
        val tomorrow = LocalDate.now(BERLIN).plusDays(1)
        val entry = session(date = tomorrow, reminders = ReminderLead.entries.toSet())

        val pending = entry.pendingReminders(BERLIN)

        assertTrue(ReminderLead.ONE_WEEK !in pending, "a week before tomorrow is last week")
        assertTrue(ReminderLead.THREE_DAYS !in pending)
        assertTrue(entry.reminders.size > pending.size, "the lead is dropped, not deleted")
    }

    @Test
    fun `the next reminder is the soonest one still ahead`() {
        val night = LocalDate.now(BERLIN).plusDays(30)
        val entry = session(
            date = night,
            reminders = setOf(ReminderLead.ONE_WEEK, ReminderLead.ONE_DAY, ReminderLead.SAME_DAY),
        )

        assertEquals(ReminderLead.ONE_WEEK, entry.nextReminder(BERLIN))
    }

    @Test
    fun `a session without reminders has none pending`() {
        val entry = session(date = LocalDate.now(BERLIN).plusDays(30))
        assertTrue(entry.pendingReminders(BERLIN).isEmpty())
        assertNull(entry.nextReminder(BERLIN))
    }

    @Test
    fun `reminders and notes survive a JSON round trip`() {
        val original = session(reminders = setOf(ReminderLead.ONE_WEEK, ReminderLead.SAME_DAY))
            .copy(note = "Ha-Filter, hinten parken")

        val restored = json.decodeFromString<PlannedSession>(json.encodeToString(original))

        assertEquals(original, restored)
        assertEquals(setOf(ReminderLead.ONE_WEEK, ReminderLead.SAME_DAY), restored.reminders)
        assertTrue(restored.hasNote)
    }

    /** A plan written before reminders existed has to keep loading. */
    @Test
    fun `an entry without a reminders field still loads`() {
        val old = """{"id":"x","objectId":"M13","objectLabel":"M13","dateEpochDay":20000}"""

        val restored = json.decodeFromString<PlannedSession>(old)

        assertTrue(restored.reminders.isEmpty())
        assertTrue(!restored.hasNote)
    }

    /** The stored names are stable, so a future rename of an enum constant cannot orphan a plan. */
    @Test
    fun `the reminder leads serialise under stable names`() {
        val text = json.encodeToString(session(reminders = setOf(ReminderLead.THREE_DAYS)))
        assertTrue(text.contains("threeDays"), "unexpected encoding: $text")
    }
}

class ReminderSchedulerKeyTest {

    /**
     * Each (session, lead) pair needs its own alarm slot. Colliding request codes would mean one
     * reminder silently replacing another — the kind of bug that only shows up as a notification
     * that never arrived.
     */
    @Test
    fun `every lead of a session gets a distinct request code`() {
        val id = "0f5c1d3e-1111-2222-3333-444455556666"
        val codes = ReminderLead.entries.map { ReminderScheduler.requestCode(id, it) }

        assertEquals(codes.size, codes.toSet().size, "two leads share a request code")
    }

    @Test
    fun `different sessions do not share request codes for the same lead`() {
        val codes = (1..500)
            .map { ReminderScheduler.requestCode("session-$it", ReminderLead.ONE_DAY) }

        assertEquals(codes.size, codes.toSet().size, "two sessions collide on the same lead")
    }

    @Test
    fun `the request code is stable across calls`() {
        val id = "abc"
        assertEquals(
            ReminderScheduler.requestCode(id, ReminderLead.ONE_WEEK),
            ReminderScheduler.requestCode(id, ReminderLead.ONE_WEEK),
        )
    }
}
