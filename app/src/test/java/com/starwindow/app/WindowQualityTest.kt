package com.starwindow.app

import com.starwindow.app.core.astro.Horizontal
import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.core.astro.Rotation3
import com.starwindow.app.core.calibration.Calibration
import com.starwindow.app.core.calibration.CalibrationSource
import com.starwindow.app.core.calibration.CalibrationTrust
import com.starwindow.app.core.calibration.trustAt
import com.starwindow.app.core.geometry.PolygonWindow
import com.starwindow.app.core.geometry.SkyWindow
import com.starwindow.app.domain.AccuracyBand
import com.starwindow.app.domain.accuracy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val NOW = 1_700_000_000_000L
private const val DAY = 24L * 3_600_000

private val MUNICH = ObserverLocation(48.14, 11.58)

private fun window(
    shape: PolygonWindow,
    compassAccuracy: Int = 3,
    calibrationResidualDeg: Double? = null,
    headingHeld: Boolean = false,
) = SkyWindow(
    id = "w",
    name = "Fenster",
    shape = shape,
    observer = MUNICH,
    capturedAtMillis = NOW,
    compassAccuracy = compassAccuracy,
    calibrationResidualDeg = calibrationResidualDeg,
    headingHeld = headingHeld,
)

/** A plain square about 10° across, corners in order. */
private val square = PolygonWindow(
    listOf(
        Horizontal(100.0, 30.0),
        Horizontal(110.0, 30.0),
        Horizontal(110.0, 40.0),
        Horizontal(100.0, 40.0),
    )
)

/** The same four corners, but tapped so that two edges cross: a bow tie. */
private val bowTie = PolygonWindow(
    listOf(
        Horizontal(100.0, 30.0),
        Horizontal(110.0, 30.0),
        Horizontal(100.0, 40.0),
        Horizontal(110.0, 40.0),
    )
)

class SelfIntersectionTest {

    @Test
    fun `a square tapped in order does not cross itself`() {
        assertTrue(!square.isSelfIntersecting)
    }

    @Test
    fun `corners tapped out of order are recognised as a bow tie`() {
        assertTrue(bowTie.isSelfIntersecting)
    }

    @Test
    fun `a triangle can never cross itself`() {
        val triangle = PolygonWindow(
            listOf(Horizontal(10.0, 20.0), Horizontal(20.0, 20.0), Horizontal(15.0, 30.0))
        )
        assertTrue(!triangle.isSelfIntersecting)
    }

    @Test
    fun `a concave outline is not mistaken for a crossing`() {
        // An L shape: legitimate, and exactly what a gap between two roofs looks like.
        val concave = PolygonWindow(
            listOf(
                Horizontal(100.0, 20.0),
                Horizontal(115.0, 20.0),
                Horizontal(115.0, 27.0),
                Horizontal(107.0, 27.0),
                Horizontal(107.0, 38.0),
                Horizontal(100.0, 38.0),
            )
        )
        assertTrue(!concave.isSelfIntersecting)
    }

    @Test
    fun `the bow tie is exactly the case where the area comes out wrong`() {
        // Why the warning exists: the two lobes cancel, so the reported area collapses.
        assertTrue(
            bowTie.areaSquareDeg() < square.areaSquareDeg() / 2.0,
            "bow tie %.1f vs square %.1f".format(bowTie.areaSquareDeg(), square.areaSquareDeg()),
        )
    }
}

class WindowAccuracyTest {

    @Test
    fun `a calibrated window quotes its measured residual`() {
        val accuracy = window(square, calibrationResidualDeg = 0.8).accuracy()
        assertTrue(accuracy.isMeasured)
        assertEquals(0.8, accuracy.uncertaintyDeg, 1e-9)
        assertEquals(AccuracyBand.GOOD, accuracy.band)
    }

    @Test
    fun `an impossibly small residual is floored rather than believed`() {
        // No hand holding a phone points to a hundredth of a degree, whatever the fit says.
        val accuracy = window(square, calibrationResidualDeg = 0.01).accuracy()
        assertTrue(accuracy.uncertaintyDeg >= 0.5, "uncertainty=${accuracy.uncertaintyDeg}")
    }

    @Test
    fun `an uncalibrated window is marked as an estimate, not a measurement`() {
        val accuracy = window(square, compassAccuracy = 3).accuracy()
        assertTrue(!accuracy.isMeasured)
        assertTrue(accuracy.headline.startsWith("etwa"), accuracy.headline)
    }

    @Test
    fun `a worse compass gives a wider error and a worse band`() {
        val high = window(square, compassAccuracy = 3).accuracy()
        val unreliable = window(square, compassAccuracy = 0).accuracy()

        assertTrue(unreliable.uncertaintyDeg > high.uncertaintyDeg)
        assertEquals(AccuracyBand.GOOD, high.band)
        assertEquals(AccuracyBand.POOR, unreliable.band)
    }

    @Test
    fun `a disturbed compass widens the error either way`() {
        val calm = window(square, calibrationResidualDeg = 0.8).accuracy()
        val disturbed = window(square, calibrationResidualDeg = 0.8, headingHeld = true).accuracy()
        assertTrue(disturbed.uncertaintyDeg > calm.uncertaintyDeg)
        assertTrue(disturbed.reason.contains("gestört"), disturbed.reason)
    }
}

class CalibrationTrustTest {

    private fun calibrated(
        atMillis: Long = NOW,
        measuredAt: ObserverLocation? = MUNICH,
    ) = Calibration.NONE.withAttitude(
        rotation = Rotation3.aboutZenith(3.0),
        source = CalibrationSource.STAR_PATTERN,
        residualDeg = 0.4,
        sampleCount = 2,
        atMillis = atMillis,
        measuredAt = measuredAt,
    )

    @Test
    fun `no correction means nothing to trust or distrust`() {
        assertEquals(CalibrationTrust.NONE, Calibration.NONE.trustAt(MUNICH, NOW))
    }

    @Test
    fun `measured here and just now is fresh`() {
        assertEquals(CalibrationTrust.FRESH, calibrated().trustAt(MUNICH, NOW))
    }

    @Test
    fun `a few days old is ageing, a fortnight old is stale`() {
        assertEquals(CalibrationTrust.AGING, calibrated().trustAt(MUNICH, NOW + 3 * DAY))
        assertEquals(CalibrationTrust.STALE, calibrated().trustAt(MUNICH, NOW + 20 * DAY))
    }

    @Test
    fun `moving away invalidates it even when it was measured minutes ago`() {
        // Distance beats age: the compass error is a property of the place, not of the phone.
        val hamburg = ObserverLocation(53.55, 9.99)
        assertEquals(CalibrationTrust.MOVED, calibrated().trustAt(hamburg, NOW))
    }

    @Test
    fun `walking across the garden does not raise a warning`() {
        val nextDoor = ObserverLocation(48.1405, 11.5805)
        assertEquals(CalibrationTrust.FRESH, calibrated().trustAt(nextDoor, NOW))
    }

    @Test
    fun `an older calibration without a recorded place is judged by age alone`() {
        val old = calibrated(measuredAt = null)
        assertEquals(CalibrationTrust.FRESH, old.trustAt(MUNICH, NOW))
        assertEquals(CalibrationTrust.STALE, old.trustAt(MUNICH, NOW + 20 * DAY))
    }

    @Test
    fun `stale and moved both call for a fresh measurement, ageing only warns`() {
        assertTrue(CalibrationTrust.STALE.needsRemeasuring)
        assertTrue(CalibrationTrust.MOVED.needsRemeasuring)
        assertTrue(CalibrationTrust.AGING.isQuestionable && !CalibrationTrust.AGING.needsRemeasuring)
        assertTrue(!CalibrationTrust.FRESH.isQuestionable)
    }
}

class ObserverDistanceTest {

    @Test
    fun `distance between two known cities is right to about a percent`() {
        // Munich to Hamburg is about 612 km great circle.
        val distance = MUNICH.distanceKmTo(ObserverLocation(53.55, 9.99))
        assertTrue(distance in 600.0..625.0, "distance=$distance")
    }

    @Test
    fun `one degree of latitude is about 111 km`() {
        val distance = ObserverLocation(0.0, 0.0).distanceKmTo(ObserverLocation(1.0, 0.0))
        assertEquals(111.2, distance, 0.5)
    }

    @Test
    fun `the date line is not a wall`() {
        val west = ObserverLocation(0.0, 179.5)
        val east = ObserverLocation(0.0, -179.5)
        assertEquals(111.2, west.distanceKmTo(east), 0.5)
    }

    @Test
    fun `a position is zero kilometres from itself`() {
        assertEquals(0.0, MUNICH.distanceKmTo(MUNICH), 1e-6)
    }
}
