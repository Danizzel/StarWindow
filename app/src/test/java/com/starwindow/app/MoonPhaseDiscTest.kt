package com.starwindow.app

import com.starwindow.app.ui.weather.litSpan
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Die Form der Mondscheibe.
 *
 * Der Fehler, den man hier macht, ist der seitenverkehrte Mond — und der fällt auf dem Bildschirm
 * niemandem auf, weil kaum jemand im Kopf hat, auf welcher Seite ein abnehmender Mond hell ist.
 * Auf der Nordhalbkugel gilt: **zunehmend ist rechts hell.**
 */
class MoonPhaseDiscTest {

    private fun assertNear(expected: Float, actual: Float, what: String) {
        assertTrue(abs(expected - actual) < 0.001f, "$what: erwartet $expected, war $actual")
    }

    @Test
    fun `a waxing crescent is lit on the right`() {
        val span = litSpan(fraction = 0.25, waxing = true)
        assertNear(0.5f, span.start, "innere Kante")
        assertNear(1f, span.endInclusive, "Rand")
    }

    @Test
    fun `a waning crescent is lit on the left`() {
        val span = litSpan(fraction = 0.25, waxing = false)
        assertNear(-1f, span.start, "Rand")
        assertNear(-0.5f, span.endInclusive, "innere Kante")
    }

    /** Halbmond: Der Terminator steht in der Mitte, genau eine Hälfte leuchtet. */
    @Test
    fun `a half moon is split down the middle`() {
        val waxing = litSpan(fraction = 0.5, waxing = true)
        assertNear(0f, waxing.start, "Terminator")
        assertNear(1f, waxing.endInclusive, "Rand")

        val waning = litSpan(fraction = 0.5, waxing = false)
        assertNear(-1f, waning.start, "Rand")
        assertNear(0f, waning.endInclusive, "Terminator")
    }

    /** Mehr als halb: Der Terminator wandert über die Mitte hinaus auf die dunkle Seite. */
    @Test
    fun `a gibbous moon reaches past the middle`() {
        val span = litSpan(fraction = 0.9, waxing = true)
        assertNear(-0.8f, span.start, "Terminator")
        assertNear(1f, span.endInclusive, "Rand")
    }

    /** Voll und neu sind die Randfälle, bei denen die Formel nicht kippen darf. */
    @Test
    fun `full covers the disc and new covers nothing`() {
        val full = litSpan(fraction = 1.0, waxing = true)
        assertNear(-1f, full.start, "linker Rand")
        assertNear(1f, full.endInclusive, "rechter Rand")

        val new = litSpan(fraction = 0.0, waxing = true)
        assertNear(1f, new.start, "Terminator am Rand")
        assertNear(1f, new.endInclusive, "Rand")
    }

    /** Die beiden Richtungen sind exakt gespiegelt — sonst stimmt eine von beiden nicht. */
    @Test
    fun `waxing and waning mirror each other`() {
        for (fraction in listOf(0.05, 0.3, 0.5, 0.75, 0.98)) {
            val waxing = litSpan(fraction, waxing = true)
            val waning = litSpan(fraction, waxing = false)
            assertNear(-waxing.endInclusive, waning.start, "gespiegelter Start bei $fraction")
            assertNear(-waxing.start, waning.endInclusive, "gespiegeltes Ende bei $fraction")
        }
    }
}
