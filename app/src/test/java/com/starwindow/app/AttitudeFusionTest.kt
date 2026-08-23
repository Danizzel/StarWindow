package com.starwindow.app

import com.starwindow.app.core.sensors.AttitudeFusion
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Row-major 3x3 product. */
private fun multiply(a: FloatArray, b: FloatArray): FloatArray {
    val out = FloatArray(9)
    for (row in 0..2) {
        for (col in 0..2) {
            var sum = 0f
            for (k in 0..2) sum += a[row * 3 + k] * b[k * 3 + col]
            out[row * 3 + col] = sum
        }
    }
    return out
}

private fun rotationAboutZ(deg: Double): FloatArray {
    val a = Math.toRadians(deg)
    val c = cos(a).toFloat()
    val s = sin(a).toFloat()
    return floatArrayOf(c, -s, 0f, s, c, 0f, 0f, 0f, 1f)
}

/** An arbitrary but valid device→world orientation, built from a yaw and a pitch. */
private fun deviceFrame(yawDeg: Double, pitchDeg: Double): FloatArray {
    val p = Math.toRadians(pitchDeg)
    val cp = cos(p).toFloat()
    val sp = sin(p).toFloat()
    val aboutX = floatArrayOf(1f, 0f, 0f, 0f, cp, -sp, 0f, sp, cp)
    return multiply(rotationAboutZ(yawDeg), aboutX)
}

/** Quaternion (x, y, z, w) for a rotation about the z axis. */
private fun quaternionAboutZ(deg: Double): FloatArray {
    val half = Math.toRadians(deg) / 2.0
    return floatArrayOf(0f, 0f, sin(half).toFloat(), cos(half).toFloat())
}

class SmoothingTest {

    @Test
    fun `one time constant of elapsed time removes the usual fraction of the error`() {
        val alpha = AttitudeFusion.alphaFor(dtSeconds = 0.2, timeConstantSeconds = 0.2)
        assertEquals(1.0 - exp(-1.0), alpha.toDouble(), 1e-6)
    }

    @Test
    fun `no elapsed time means no movement, no time constant means follow exactly`() {
        assertEquals(0f, AttitudeFusion.alphaFor(0.0, 0.2))
        assertEquals(1f, AttitudeFusion.alphaFor(0.02, 0.0))
    }

    @Test
    fun `smoothing behaves the same however fast the device delivers samples`() {
        // The whole point of a time constant: a phone reporting at 200 Hz and one at 30 Hz must
        // end up in the same place after the same amount of wall-clock time. With a fixed weight
        // per sample — the obvious implementation — they differ by a factor of six.
        val timeConstant = 0.25
        val totalSeconds = 0.5

        fun remainingErrorAfter(sampleRateHz: Int): Double {
            val dt = 1.0 / sampleRateHz
            var value = 0.0
            repeat((totalSeconds * sampleRateHz).toInt()) {
                value += (1.0 - value) * AttitudeFusion.alphaFor(dt, timeConstant)
            }
            return 1.0 - value
        }

        val fast = remainingErrorAfter(200)
        val slow = remainingErrorAfter(30)
        assertEquals(fast, slow, 0.01, "200 Hz left $fast, 30 Hz left $slow")
        // And both are close to the analytic answer for an exponential filter.
        assertEquals(exp(-totalSeconds / timeConstant), fast, 0.01)
    }

    @Test
    fun `a still phone is smoothed hard and a panning one is barely smoothed at all`() {
        val still = AttitudeFusion.timeConstantFor(0.0)
        val slow = AttitudeFusion.timeConstantFor(5.0)
        val fast = AttitudeFusion.timeConstantFor(60.0)

        assertEquals(AttitudeFusion.STILL_TIME_CONSTANT_S, still, 1e-9)
        assertEquals(AttitudeFusion.FAST_TIME_CONSTANT_S, fast, 1e-9)
        assertTrue(slow in fast..still, "an intermediate rate gave $slow")
    }

    @Test
    fun `the time constant falls monotonically as the phone turns faster`() {
        var previous = Double.MAX_VALUE
        for (rate in 0..80 step 2) {
            val current = AttitudeFusion.timeConstantFor(rate.toDouble())
            assertTrue(current <= previous + 1e-12, "rate $rate broke the ordering")
            previous = current
        }
    }

    @Test
    fun `the angle between quaternions is the angle actually turned`() {
        assertEquals(0.0, AttitudeFusion.angleBetweenDeg(quaternionAboutZ(0.0), quaternionAboutZ(0.0)), 1e-4)
        assertEquals(30.0, AttitudeFusion.angleBetweenDeg(quaternionAboutZ(10.0), quaternionAboutZ(40.0)), 1e-3)
        assertEquals(90.0, AttitudeFusion.angleBetweenDeg(quaternionAboutZ(-45.0), quaternionAboutZ(45.0)), 1e-3)
    }
}

class HeadingFusionTest {

    @Test
    fun `the heading offset between two frames is recovered exactly`() {
        val gyro = deviceFrame(yawDeg = 37.0, pitchDeg = -22.0)
        for (offset in listOf(-170.0, -90.0, -12.5, 0.0, 12.5, 90.0, 179.0)) {
            val magnetic = multiply(rotationAboutZ(offset), gyro)
            assertEquals(
                offset,
                AttitudeFusion.headingOffsetDeg(magnetic, gyro),
                1e-3,
                "offset $offset was not recovered",
            )
        }
    }

    @Test
    fun `applying the recovered heading reproduces the compass frame`() {
        val random = Random(4711)
        repeat(50) {
            val gyro = deviceFrame(random.nextDouble(-180.0, 180.0), random.nextDouble(-80.0, 80.0))
            val offset = random.nextDouble(-180.0, 180.0)
            val magnetic = multiply(rotationAboutZ(offset), gyro)

            val recovered = AttitudeFusion.headingOffsetDeg(magnetic, gyro)
            val rebuilt = FloatArray(9)
            AttitudeFusion.applyHeading(gyro, recovered, rebuilt)

            for (i in 0..8) {
                assertEquals(magnetic[i], rebuilt[i], 1e-4f, "component $i after offset $offset")
            }
        }
    }

    @Test
    fun `a heading offset of zero leaves the frame untouched`() {
        val frame = deviceFrame(120.0, 15.0)
        val out = FloatArray(9)
        AttitudeFusion.applyHeading(frame, 0.0, out)
        for (i in 0..8) assertEquals(frame[i], out[i], 1e-6f)
    }

    @Test
    fun `blending headings takes the short way round the compass`() {
        // 350° and 10° are twenty degrees apart, not three hundred and forty.
        val blended = AttitudeFusion.blendHeadingDeg(currentDeg = -10.0, targetDeg = 10.0, alpha = 0.5f)
        assertEquals(0.0, blended, 1e-9)

        val acrossTheSeam = AttitudeFusion.blendHeadingDeg(179.0, -179.0, 0.5f)
        assertTrue(abs(abs(acrossTheSeam) - 180.0) < 1e-6, "blending across the seam gave $acrossTheSeam")
    }

    @Test
    fun `alpha zero holds the heading and alpha one adopts it`() {
        assertEquals(30.0, AttitudeFusion.blendHeadingDeg(30.0, 120.0, 0f), 1e-9)
        assertEquals(120.0, AttitudeFusion.blendHeadingDeg(30.0, 120.0, 1f), 1e-9)
    }

    @Test
    fun `a jittering compass moves the fused heading by a fraction of its own noise`() {
        // The reason the fusion exists. A compass wobbling by two degrees at 5 Hz is fed into the
        // ten second filter; the heading that comes out has to stay far steadier than the input.
        val random = Random(90210)
        val truth = 12.0
        var heading = truth
        var worst = 0.0

        repeat(200) {
            val noisy = truth + random.nextDouble(-2.0, 2.0)
            heading = AttitudeFusion.blendHeadingDeg(
                heading,
                noisy,
                AttitudeFusion.alphaFor(0.2, AttitudeFusion.HEADING_TIME_CONSTANT_S),
            )
            worst = maxOf(worst, abs(heading - truth))
        }

        assertTrue(worst < 0.5, "the fused heading wandered by $worst° on ±2° of noise")
    }

    @Test
    fun `a single wild compass reading barely moves the heading at all`() {
        // Walking past a car: one sample forty degrees out. Even without the plausibility gate the
        // filter must not let it drag the sky along.
        val moved = AttitudeFusion.blendHeadingDeg(
            currentDeg = 0.0,
            targetDeg = 40.0,
            alpha = AttitudeFusion.alphaFor(0.2, AttitudeFusion.HEADING_TIME_CONSTANT_S),
        )
        assertTrue(moved < 1.0, "one bad sample moved the heading by $moved°")
    }
}

class MagneticHealthTest {

    @Test
    fun `a field that looks like the Earth's is accepted`() {
        // Central Europe is around 49 µT; the tolerance has to cover the phone's own hard iron.
        assertTrue(AttitudeFusion.isFieldPlausible(measuredMicroTesla = 49f, expectedMicroTesla = 49f))
        assertTrue(AttitudeFusion.isFieldPlausible(44f, 49f))
        assertTrue(AttitudeFusion.isFieldPlausible(56f, 49f))
    }

    @Test
    fun `iron and magnets are rejected`() {
        assertTrue(!AttitudeFusion.isFieldPlausible(120f, 49f), "a magnet was accepted")
        assertTrue(!AttitudeFusion.isFieldPlausible(8f, 49f), "a shielded reading was accepted")
    }

    @Test
    fun `without a model value nothing can be judged, so nothing is rejected`() {
        assertTrue(AttitudeFusion.isFieldPlausible(300f, 0f))
    }
}
