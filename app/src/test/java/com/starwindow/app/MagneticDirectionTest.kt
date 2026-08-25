package com.starwindow.app

import com.starwindow.app.core.sensors.AttitudeFusion
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Munich: field about 48 µT, dip about 64° below the horizon. */
private const val MUNICH_FIELD = 48.5f
private const val MUNICH_DIP = 64.0

private val LEVEL = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

/**
 * Device→world matrix for a phone tilted about the world's north axis, so that the tilt genuinely
 * differs while the field vector stays the same physical thing.
 */
private fun tiltedBy(degrees: Double): FloatArray {
    val a = Math.toRadians(degrees)
    val c = cos(a).toFloat()
    val s = sin(a).toFloat()
    return floatArrayOf(c, 0f, s, 0f, 1f, 0f, -s, 0f, c)
}

/** The Earth's field as a device-frame vector, for a device whose attitude is [deviceToWorld]. */
private fun fieldInDevice(dipDeg: Double, strength: Float, deviceToWorld: FloatArray): FloatArray {
    val dip = Math.toRadians(dipDeg)
    // World frame: x = east, y = north, z = up. The field points north and downwards.
    val world = floatArrayOf(0f, (strength * cos(dip)).toFloat(), (-strength * sin(dip)).toFloat())
    // device = Rᵀ · world, because the matrix maps device → world.
    return FloatArray(3) { row ->
        var sum = 0f
        for (k in 0..2) sum += deviceToWorld[k * 3 + row] * world[k]
        sum
    }
}

class InclinationTest {

    @Test
    fun `the dip of an undisturbed field comes back whatever way the phone is held`() {
        // This is what makes the dip usable as a check: it depends on gravity and the field, never
        // on the heading, so it can judge the compass without being derived from it.
        for (tilt in -80..80 step 20) {
            val attitude = tiltedBy(tilt.toDouble())
            val measured = AttitudeFusion.inclinationDeg(
                fieldInDevice(MUNICH_DIP, MUNICH_FIELD, attitude),
                attitude,
            )
            assertTrue(measured != null, "no dip at tilt $tilt")
            assertEquals(MUNICH_DIP, measured, 1e-3, "tilt $tilt")
        }
    }

    @Test
    fun `a field pointing straight down reads as ninety degrees, straight up as minus ninety`() {
        assertEquals(90.0, AttitudeFusion.inclinationDeg(floatArrayOf(0f, 0f, -48f), LEVEL)!!, 1e-6)
        assertEquals(-90.0, AttitudeFusion.inclinationDeg(floatArrayOf(0f, 0f, 48f), LEVEL)!!, 1e-6)
    }

    @Test
    fun `a purely horizontal field reads as zero, like at the magnetic equator`() {
        assertEquals(0.0, AttitudeFusion.inclinationDeg(floatArrayOf(0f, 48f, 0f), LEVEL)!!, 1e-6)
    }

    @Test
    fun `a field of nothing has no direction to report`() {
        assertTrue(AttitudeFusion.inclinationDeg(floatArrayOf(0f, 0f, 0f), LEVEL) == null)
    }
}

class FieldDirectionDisturbanceTest {

    @Test
    fun `the undisturbed field passes the dip test`() {
        assertTrue(AttitudeFusion.isInclinationPlausible(MUNICH_DIP, MUNICH_DIP))
    }

    @Test
    fun `iron that bends the field without changing its length is caught only by the dip test`() {
        // The whole reason the dip check exists. A disturbance at right angles to the Earth's field
        // rotates the sum while leaving its length almost untouched: the strength test waves it
        // through, and the error lands squarely in the heading.
        val clean = fieldInDevice(MUNICH_DIP, MUNICH_FIELD, LEVEL)
        val dip = Math.toRadians(MUNICH_DIP)
        val magnitude = 18f
        val disturbed = floatArrayOf(
            clean[0],
            clean[1] + (sin(dip) * magnitude).toFloat(),
            clean[2] + (cos(dip) * magnitude).toFloat(),
        )

        val strength = sqrt(
            disturbed[0] * disturbed[0] + disturbed[1] * disturbed[1] + disturbed[2] * disturbed[2]
        )
        val measuredDip = AttitudeFusion.inclinationDeg(disturbed, LEVEL)!!

        assertTrue(
            AttitudeFusion.isFieldPlausible(strength, MUNICH_FIELD),
            "the strength test should not fire here: %.1f vs %.1f".format(strength, MUNICH_FIELD),
        )
        assertTrue(
            !AttitudeFusion.isInclinationPlausible(measuredDip, MUNICH_DIP),
            "the dip test missed a %.0f° bend".format(measuredDip - MUNICH_DIP),
        )
    }

    @Test
    fun `a few degrees of dip error is tolerated rather than treated as a disturbance`() {
        // Residual hard iron and a hand that is not quite level must not raise the alarm, or the
        // warning becomes permanent and stops meaning anything.
        assertTrue(AttitudeFusion.isInclinationPlausible(MUNICH_DIP + 8.0, MUNICH_DIP))
        assertTrue(AttitudeFusion.isInclinationPlausible(MUNICH_DIP - 8.0, MUNICH_DIP))
        assertTrue(!AttitudeFusion.isInclinationPlausible(MUNICH_DIP + 25.0, MUNICH_DIP))
    }

    @Test
    fun `the dip test works in the southern hemisphere, where the field points upwards`() {
        val southern = -60.0
        assertTrue(AttitudeFusion.isInclinationPlausible(southern, southern))
        assertTrue(!AttitudeFusion.isInclinationPlausible(southern + 30.0, southern))
    }
}

class HeldHeadingDriftTest {

    @Test
    fun `a heading that is not held has not drifted`() {
        assertEquals(0.0, AttitudeFusion.heldHeadingDriftDeg(0.0), 1e-9)
    }

    @Test
    fun `the drift budget grows with the time it has been held`() {
        assertTrue(AttitudeFusion.heldHeadingDriftDeg(600.0) > AttitudeFusion.heldHeadingDriftDeg(30.0))
        assertEquals(
            AttitudeFusion.GYRO_DRIFT_DEG_PER_MINUTE,
            AttitudeFusion.heldHeadingDriftDeg(60.0),
            1e-9,
        )
    }

    @Test
    fun `beyond a point the honest answer stops being a number`() {
        // Held for a day the heading is not "±720°", it is simply unknown; the cap keeps the
        // interface from quoting an absurdity.
        assertEquals(
            AttitudeFusion.MAX_HELD_DRIFT_DEG,
            AttitudeFusion.heldHeadingDriftDeg(86_400.0),
            1e-9,
        )
    }
}
