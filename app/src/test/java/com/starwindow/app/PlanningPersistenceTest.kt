package com.starwindow.app

import com.starwindow.app.data.planning.PlannedSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate

private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

private fun session(
    id: String = "a",
    objectId: String = "M31",
    date: LocalDate = LocalDate.of(2026, 10, 14),
    usableMinutes: Int = 260,
) = PlannedSession(
    id = id,
    objectId = objectId,
    objectLabel = "M31 · Andromedagalaxie",
    dateEpochDay = date.toEpochDay(),
    usableMinutes = usableMinutes,
    bestAltitudeDeg = 74.0,
    moonIlluminationPercent = 12.0,
    note = "Filter mitnehmen",
    createdAtMillis = 1_700_000_000_000L,
)

class PlannedSessionTest {

    @Test
    fun `a planned session survives a JSON round trip`() {
        val original = session()
        val restored = json.decodeFromString<PlannedSession>(json.encodeToString(original))
        assertEquals(original, restored)
    }

    @Test
    fun `a list round trips like the repository stores it`() {
        val sessions = listOf(session(), session(id = "b", objectId = "M42"))
        assertEquals(sessions, json.decodeFromString<List<PlannedSession>>(json.encodeToString(sessions)))
    }

    /**
     * The date is stored as an epoch day, not as a formatted string, so that a plan made in one
     * time zone still names the same night when the phone travels.
     */
    @Test
    fun `the stored date is an epoch day and comes back as the same date`() {
        val date = LocalDate.of(2027, 2, 3)
        val restored = json.decodeFromString<PlannedSession>(json.encodeToString(session(date = date)))

        assertEquals(date, restored.date)
        assertTrue(json.encodeToString(session(date = date)).contains(date.toEpochDay().toString()))
    }

    /**
     * A file written by an older version must still load. The numbers a session carries were added
     * for the calendar to render without recomputing, and none of them may be required.
     */
    @Test
    fun `an entry from an older version still loads`() {
        val minimal = """
            {"id":"x","objectId":"M13","objectLabel":"M13","dateEpochDay":20000}
        """.trimIndent()

        val restored = json.decodeFromString<PlannedSession>(minimal)

        assertEquals("M13", restored.objectId)
        assertEquals(LocalDate.ofEpochDay(20000), restored.date)
        assertEquals(0, restored.usableMinutes)
        assertEquals("", restored.note)
    }

    /** An unknown field from a *newer* version must not make the whole plan unreadable. */
    @Test
    fun `an unknown field is ignored rather than fatal`() {
        val future = """
            {"id":"x","objectId":"M13","objectLabel":"M13","dateEpochDay":20000,"filterSet":"Ha"}
        """.trimIndent()

        assertEquals("M13", json.decodeFromString<PlannedSession>(future).objectId)
    }

    @Test
    fun `usable hours are derived from the stored minutes`() {
        assertEquals(4.5, session(usableMinutes = 270).usableHours, 1e-9)
        assertEquals(0.0, session(usableMinutes = 0).usableHours, 1e-9)
    }
}
