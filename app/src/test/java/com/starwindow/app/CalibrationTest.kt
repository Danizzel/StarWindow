package com.starwindow.app

import com.starwindow.app.core.astro.Angles
import com.starwindow.app.core.astro.Horizontal
import com.starwindow.app.core.astro.Rotation3
import com.starwindow.app.core.astro.Vec3
import com.starwindow.app.core.calibration.AttitudeFit
import com.starwindow.app.core.calibration.Calibration
import com.starwindow.app.core.calibration.CalibrationSource
import com.starwindow.app.core.calibration.DirectionPair
import com.starwindow.app.core.calibration.FieldOfViewSolver
import com.starwindow.app.core.calibration.FovFitException
import com.starwindow.app.core.calibration.FovFitProblem
import com.starwindow.app.core.calibration.PanSighting
import com.starwindow.app.core.camera.SkyProjection
import com.starwindow.app.core.geometry.SphericalGeometry
import com.starwindow.app.core.sensors.DeviceAttitude
import kotlin.math.abs
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun attitudeLookingAt(
    azimuthDeg: Double,
    altitudeDeg: Double,
    declinationDeg: Double = 0.0,
    correction: Rotation3 = Rotation3.IDENTITY,
): DeviceAttitude {
    val forward = Horizontal(azimuthDeg, altitudeDeg).toVector().normalized()
    val worldUp = Vec3(0.0, 0.0, 1.0)
    val right = (forward cross worldUp).normalized()
    val up = (right cross forward).normalized()
    val back = forward * -1.0
    return DeviceAttitude(
        rotationMatrix = floatArrayOf(
            right.x.toFloat(), up.x.toFloat(), back.x.toFloat(),
            right.y.toFloat(), up.y.toFloat(), back.y.toFloat(),
            right.z.toFloat(), up.z.toFloat(), back.z.toFloat(),
        ),
        magneticDeclinationDeg = declinationDeg,
        accuracy = 3,
        timestampMs = 0L,
        correction = correction,
    )
}

class Rotation3Test {

    @Test
    fun `rotating about the zenith changes azimuth and leaves altitude alone`() {
        for (az in 0 until 360 step 31) {
            for (alt in -80..80 step 23) {
                val start = Horizontal(az.toDouble(), alt.toDouble())
                val turned = Horizontal.fromVector(Rotation3.aboutZenith(17.0).apply(start.toVector()))
                assertEquals(Angles.normalizeDeg(az + 17.0), turned.azimuthDeg, 1e-9)
                assertEquals(alt.toDouble(), turned.altitudeDeg, 1e-9)
            }
        }
    }

    @Test
    fun `inverse undoes the rotation`() {
        val r = Rotation3.fromRotationVector(Vec3(0.13, -0.07, 0.21))
        val v = Vec3(0.3, -0.5, 0.81).normalized()
        val back = r.inverseApply(r.apply(v))
        assertTrue(abs(back.x - v.x) < 1e-12 && abs(back.y - v.y) < 1e-12 && abs(back.z - v.z) < 1e-12)
    }

    @Test
    fun `composition applies right to left`() {
        val a = Rotation3.aboutZenith(30.0)
        val b = Rotation3.aboutZenith(20.0)
        val v = Horizontal(0.0, 10.0).toVector()
        val composed = (a * b).apply(v)
        val stepwise = a.apply(b.apply(v))
        assertTrue(SphericalGeometry.separationDeg(Horizontal.fromVector(composed), Horizontal.fromVector(stepwise)) < 1e-12)
        assertEquals(50.0, Horizontal.fromVector(composed).azimuthDeg, 1e-9)
    }

    @Test
    fun `rotation vector survives the round trip through a matrix`() {
        for (v in listOf(Vec3(0.0, 0.0, 0.4), Vec3(0.05, -0.12, 0.3), Vec3(-0.5, 0.2, 0.1))) {
            val restored = Calibration.rotationVectorOf(Rotation3.fromRotationVector(v))
            assertTrue(abs(restored.x - v.x) < 1e-9, "x: $restored vs $v")
            assertTrue(abs(restored.y - v.y) < 1e-9, "y: $restored vs $v")
            assertTrue(abs(restored.z - v.z) < 1e-9, "z: $restored vs $v")
        }
    }

    @Test
    fun `angle reports the size of the rotation`() {
        assertEquals(0.0, Rotation3.IDENTITY.angleDeg(), 1e-12)
        assertEquals(23.0, Rotation3.aboutZenith(23.0).angleDeg(), 1e-9)
    }

    @Test
    fun `between builds the shortest rotation from one direction to another`() {
        val from = Horizontal(10.0, 20.0)
        val to = Horizontal(14.0, 23.0)
        val r = Rotation3.between(from.toVector(), to.toVector())
        val moved = Horizontal.fromVector(r.apply(from.toVector()))
        assertTrue(SphericalGeometry.separationDeg(moved, to) < 1e-9)
        assertEquals(SphericalGeometry.separationDeg(from, to), r.angleDeg(), 1e-9)
    }
}

class AttitudeCorrectionTest {

    @Test
    fun `without a correction the attitude behaves as before`() {
        val attitude = attitudeLookingAt(137.0, 22.0, declinationDeg = 3.5)
        assertEquals(140.5, attitude.cameraDirection.azimuthDeg, 1e-4)
        assertEquals(22.0, attitude.cameraDirection.altitudeDeg, 1e-4)
    }

    @Test
    fun `a heading correction shifts the reported azimuth`() {
        val attitude = attitudeLookingAt(
            137.0, 22.0,
            declinationDeg = 3.5,
            correction = Rotation3.aboutZenith(-2.0),
        )
        assertEquals(138.5, attitude.cameraDirection.azimuthDeg, 1e-4)
        assertEquals(22.0, attitude.cameraDirection.altitudeDeg, 1e-4)
    }

    @Test
    fun `the uncalibrated direction ignores the correction but keeps the declination`() {
        val attitude = attitudeLookingAt(
            80.0, 10.0,
            declinationDeg = 4.0,
            correction = Rotation3.aboutZenith(-6.0),
        )
        assertEquals(84.0, attitude.uncalibratedCameraDirection.azimuthDeg, 1e-4)
        assertEquals(78.0, attitude.cameraDirection.azimuthDeg, 1e-4)
    }

    @Test
    fun `screen to sky stays an exact inverse with a correction applied`() {
        val correction = Rotation3.fromRotationVector(Vec3(0.02, -0.03, 0.09))
        val attitude = attitudeLookingAt(212.0, 31.0, declinationDeg = -2.5, correction = correction)
        val focalPx = (1080f / 2.0) / tan(Math.toRadians(30.0))
        val projection = SkyProjection(attitude, focalPx, 1080f, 2160f)

        for (x in listOf(0f, 540f, 1080f)) {
            for (y in listOf(0f, 1080f, 2160f)) {
                val back = projection.skyToScreen(projection.screenToSky(x, y))
                assertTrue(back != null)
                assertEquals(x, back!!.x, 0.01f)
                assertEquals(y, back.y, 0.01f)
            }
        }
    }
}

class AttitudeFitTest {

    /** Builds observations by taking true directions and applying a known sensor error to them. */
    private fun pairsWithError(
        references: List<Horizontal>,
        sensorError: Rotation3,
        noiseDeg: Double = 0.0,
    ): List<DirectionPair> = references.mapIndexed { index, reference ->
        val measuredVector = sensorError.apply(reference.toVector())
        val measured = Horizontal.fromVector(measuredVector)
        val jittered = if (noiseDeg == 0.0) {
            measured
        } else {
            // Deterministic pseudo-noise so the test cannot flake.
            val sign = if (index % 2 == 0) 1.0 else -1.0
            measured.copy(azimuthDeg = measured.azimuthDeg + sign * noiseDeg)
        }
        DirectionPair(jittered, reference, "ref$index")
    }

    @Test
    fun `one sighting removes the error for that direction exactly`() {
        val error = Rotation3.aboutZenith(7.5)
        val references = listOf(Horizontal(120.0, 35.0))
        val fit = AttitudeFit.solve(pairsWithError(references, error))
        assertTrue(fit != null)
        assertTrue(fit!!.residualDeg < 1e-6, "residual ${fit.residualDeg}")
        assertEquals(1, fit.sampleCount)
        assertTrue(!fit.correctsTiltToo, "a single sighting cannot pin down the tilt")
    }

    @Test
    fun `several sightings recover a pure heading error`() {
        val error = Rotation3.aboutZenith(-12.0)
        val references = listOf(
            Horizontal(30.0, 20.0),
            Horizontal(150.0, 45.0),
            Horizontal(280.0, 15.0),
        )
        val fit = AttitudeFit.solve(pairsWithError(references, error))!!
        assertTrue(fit.residualDeg < 1e-6, "residual ${fit.residualDeg}")
        assertEquals(12.0, fit.rotation.angleDeg(), 1e-4)
        assertTrue(fit.correctsTiltToo)

        // The fit must be the inverse of the error that was applied.
        for (reference in references) {
            val measured = Horizontal.fromVector(error.apply(reference.toVector()))
            val corrected = Horizontal.fromVector(fit.rotation.apply(measured.toVector()))
            assertTrue(SphericalGeometry.separationDeg(corrected, reference) < 1e-6)
        }
    }

    @Test
    fun `several sightings also recover a tilt error`() {
        // A tilt is what a badly levelled phone produces; it is not a heading error at all.
        val error = Rotation3.fromRotationVector(Vec3(0.06, -0.04, 0.15))
        val references = listOf(
            Horizontal(10.0, 25.0),
            Horizontal(95.0, 55.0),
            Horizontal(200.0, 12.0),
            Horizontal(310.0, 40.0),
        )
        val fit = AttitudeFit.solve(pairsWithError(references, error))!!
        assertTrue(fit.residualDeg < 1e-6, "residual ${fit.residualDeg}")
        assertEquals(error.angleDeg(), fit.rotation.angleDeg(), 1e-4)
    }

    @Test
    fun `a large error still converges`() {
        val error = Rotation3.aboutZenith(45.0)
        val references = listOf(Horizontal(0.0, 30.0), Horizontal(120.0, 20.0), Horizontal(240.0, 50.0))
        val fit = AttitudeFit.solve(pairsWithError(references, error))!!
        assertTrue(fit.residualDeg < 1e-5, "residual ${fit.residualDeg}")
    }

    @Test
    fun `noise is averaged instead of followed`() {
        val error = Rotation3.aboutZenith(5.0)
        val references = listOf(
            Horizontal(20.0, 30.0),
            Horizontal(140.0, 30.0),
            Horizontal(260.0, 30.0),
            Horizontal(60.0, 60.0),
        )
        val fit = AttitudeFit.solve(pairsWithError(references, error, noiseDeg = 1.0))!!
        // Each observation is a degree off, so the fit cannot be perfect — but it must land near
        // the truth rather than chase any single sighting.
        assertTrue(fit.residualDeg < 1.2, "residual ${fit.residualDeg}")
        assertTrue(abs(fit.rotation.angleDeg() - 5.0) < 1.2, "angle ${fit.rotation.angleDeg()}")
    }

    @Test
    fun `heading only fit leaves altitudes untouched`() {
        val error = Rotation3.fromRotationVector(Vec3(0.05, 0.02, 0.12))
        val references = listOf(Horizontal(45.0, 5.0), Horizontal(230.0, 8.0))
        val fit = AttitudeFit.solveHeadingOnly(pairsWithError(references, error))!!

        for (alt in listOf(-20.0, 0.0, 35.0, 70.0)) {
            val before = Horizontal(123.0, alt)
            val after = Horizontal.fromVector(fit.rotation.apply(before.toVector()))
            assertEquals(alt, after.altitudeDeg, 1e-9)
        }
    }

    @Test
    fun `heading only fit averages across the zero degree seam`() {
        val pairs = listOf(
            DirectionPair(Horizontal(359.0, 0.0), Horizontal(1.0, 0.0)),
            DirectionPair(Horizontal(358.0, 0.0), Horizontal(2.0, 0.0)),
        )
        val fit = AttitudeFit.solveHeadingOnly(pairs)!!
        // Both say "add three degrees"; a naive mean of 2 and 4 in raw degrees would be fine here,
        // but a naive mean of the azimuths themselves would not be.
        assertEquals(3.0, fit.rotation.headingOffsetDegForTest(), 1e-6)
    }

    @Test
    fun `no pairs means no fit`() {
        assertTrue(AttitudeFit.solve(emptyList()) == null)
        assertTrue(AttitudeFit.solveHeadingOnly(emptyList()) == null)
    }
}

private fun Rotation3.headingOffsetDegForTest(): Double =
    Angles.wrapDeg180(Horizontal.fromVector(apply(Horizontal(0.0, 0.0).toVector())).azimuthDeg)

class FieldOfViewSolverTest {

    private val viewWidth = 1080.0
    private val viewHeight = 2160.0

    /** The focal length a 60 degree horizontal field of view corresponds to. */
    private val trueFocalPx = (viewWidth / 2.0) / tan(Math.toRadians(30.0))

    /**
     * Simulates the user tapping a fixed world direction while the phone is at a given attitude:
     * projects the direction through the *true* optics to get the screen offset.
     */
    private fun sighting(attitude: DeviceAttitude, feature: Horizontal, focalPx: Double): PanSighting {
        val projection = SkyProjection(attitude, focalPx, viewWidth.toFloat(), viewHeight.toFloat())
        val point = projection.skyToScreen(feature)!!
        return PanSighting(
            worldFromDisplay = attitude.worldFromDisplay,
            offsetXPx = point.x - viewWidth / 2.0,
            offsetYPx = viewHeight / 2.0 - point.y,
        )
    }

    @Test
    fun `a clean pan recovers the true focal length`() {
        val feature = Horizontal(100.0, 0.0)
        val sightings = listOf(
            sighting(attitudeLookingAt(88.0, 0.0), feature, trueFocalPx),
            sighting(attitudeLookingAt(112.0, 0.0), feature, trueFocalPx),
        )

        // The camera claims a 45 degree field of view; the truth is 60.
        val claimed = (viewWidth / 2.0) / tan(Math.toRadians(22.5))
        val fit = FieldOfViewSolver.solve(sightings, claimed).getOrThrow()

        assertEquals(trueFocalPx, fit.focalPx, trueFocalPx * 0.002)
        assertEquals(60.0, FieldOfViewSolver.fieldOfViewDeg(fit.focalPx, viewWidth), 0.15)
        assertTrue(fit.residualDeg < 0.05, "residual ${fit.residualDeg}")
        assertEquals(24.0, fit.panAngleDeg, 0.01)
    }

    @Test
    fun `the answer does not depend on the compass heading`() {
        // The whole point of the method: it uses relative rotation only. Shifting every attitude by
        // the same amount, as a wrong compass would, must not change the result.
        val feature = Horizontal(100.0, 5.0)
        val clean = listOf(
            sighting(attitudeLookingAt(88.0, 0.0), feature, trueFocalPx),
            sighting(attitudeLookingAt(112.0, 10.0), feature, trueFocalPx),
        )
        val shiftedFeature = Horizontal(140.0, 5.0)
        val shifted = listOf(
            sighting(attitudeLookingAt(128.0, 0.0), shiftedFeature, trueFocalPx),
            sighting(attitudeLookingAt(152.0, 10.0), shiftedFeature, trueFocalPx),
        )

        val a = FieldOfViewSolver.solve(clean, trueFocalPx).getOrThrow()
        val b = FieldOfViewSolver.solve(shifted, trueFocalPx).getOrThrow()
        assertEquals(a.focalPx, b.focalPx, trueFocalPx * 0.005)
    }

    @Test
    fun `a diagonal pan works too`() {
        val feature = Horizontal(200.0, 20.0)
        val sightings = listOf(
            sighting(attitudeLookingAt(190.0, 12.0), feature, trueFocalPx),
            sighting(attitudeLookingAt(210.0, 30.0), feature, trueFocalPx),
        )
        val fit = FieldOfViewSolver.solve(sightings, trueFocalPx * 1.4).getOrThrow()
        assertEquals(trueFocalPx, fit.focalPx, trueFocalPx * 0.005)
    }

    @Test
    fun `more than two sightings are used together`() {
        val feature = Horizontal(45.0, 15.0)
        val sightings = listOf(
            sighting(attitudeLookingAt(33.0, 10.0), feature, trueFocalPx),
            sighting(attitudeLookingAt(41.0, 14.0), feature, trueFocalPx),
            sighting(attitudeLookingAt(52.0, 20.0), feature, trueFocalPx),
            sighting(attitudeLookingAt(57.0, 12.0), feature, trueFocalPx),
        )
        val fit = FieldOfViewSolver.solve(sightings, trueFocalPx * 0.6).getOrThrow()
        assertEquals(trueFocalPx, fit.focalPx, trueFocalPx * 0.005)
        assertEquals(4, fit.sampleCount)
    }

    @Test
    fun `too small a pan is rejected instead of guessed`() {
        val feature = Horizontal(100.0, 0.0)
        val sightings = listOf(
            sighting(attitudeLookingAt(99.0, 0.0), feature, trueFocalPx),
            sighting(attitudeLookingAt(101.0, 0.0), feature, trueFocalPx),
        )
        val problem = FieldOfViewSolver.solve(sightings, trueFocalPx).exceptionOrNull()
        assertTrue(problem is FovFitException)
        assertEquals(FovFitProblem.PAN_TOO_SMALL, (problem as FovFitException).problem)
    }

    @Test
    fun `tapping two different features is rejected`() {
        val sightings = listOf(
            sighting(attitudeLookingAt(88.0, 0.0), Horizontal(100.0, 0.0), trueFocalPx),
            sighting(attitudeLookingAt(112.0, 0.0), Horizontal(104.0, 6.0), trueFocalPx),
        )
        val problem = FieldOfViewSolver.solve(sightings, trueFocalPx).exceptionOrNull()
        assertTrue(problem is FovFitException, "expected a rejection, got $problem")
        assertEquals(FovFitProblem.NO_AGREEMENT, (problem as FovFitException).problem)
    }

    @Test
    fun `a long pan with taps at the same screen spot is rejected`() {
        // Panning far but tapping the same pixel means it cannot have been the same feature.
        val sightings = listOf(
            PanSighting(attitudeLookingAt(80.0, 0.0).worldFromDisplay, 120.0, 40.0),
            PanSighting(attitudeLookingAt(110.0, 0.0).worldFromDisplay, 130.0, 45.0),
        )
        val problem = FieldOfViewSolver.solve(sightings, trueFocalPx).exceptionOrNull()
        assertTrue(problem is FovFitException, "expected a rejection, got $problem")
        assertEquals(FovFitProblem.POINTS_TOO_CLOSE, (problem as FovFitException).problem)
    }

    @Test
    fun `a single sighting is rejected`() {
        val sightings = listOf(sighting(attitudeLookingAt(88.0, 0.0), Horizontal(100.0, 0.0), trueFocalPx))
        assertEquals(
            FovFitProblem.TOO_FEW_SIGHTINGS,
            (FieldOfViewSolver.solve(sightings, trueFocalPx).exceptionOrNull() as FovFitException).problem,
        )
    }
}

class CalibrationModelTest {

    @Test
    fun `a fresh calibration changes nothing`() {
        val calibration = Calibration.NONE
        assertTrue(calibration.correction.isIdentity)
        assertEquals(1.0, calibration.fovScale, 0.0)
        assertTrue(!calibration.hasAttitudeCorrection)
        assertTrue(!calibration.hasFovCorrection)
    }

    @Test
    fun `storing an attitude fit keeps the rotation intact`() {
        val rotation = Rotation3.fromRotationVector(Vec3(0.01, -0.02, 0.13))
        val calibration = Calibration.NONE.withAttitude(
            rotation = rotation,
            source = CalibrationSource.STAR_PATTERN,
            residualDeg = 0.4,
            sampleCount = 3,
            atMillis = 42L,
        )
        assertTrue(calibration.hasAttitudeCorrection)
        assertEquals(rotation.angleDeg(), calibration.correction.angleDeg(), 1e-9)

        val direction = Horizontal(70.0, 25.0).toVector()
        val expected = Horizontal.fromVector(rotation.apply(direction))
        val actual = Horizontal.fromVector(calibration.correction.apply(direction))
        assertTrue(SphericalGeometry.separationDeg(expected, actual) < 1e-9)
    }

    @Test
    fun `the heading offset is the azimuth shift at the horizon`() {
        val calibration = Calibration.NONE.withAttitude(
            Rotation3.aboutZenith(-4.5), CalibrationSource.LANDMARK_BEARING, 0.0, 1, 0L,
        )
        assertEquals(-4.5, calibration.headingOffsetDeg, 1e-6)
    }

    @Test
    fun `the field of view factor is clamped to something physical`() {
        val tooBig = Calibration.NONE.withFov(9.0, CalibrationSource.PAN_SWEEP, 0.1, 2, 0L)
        assertEquals(Calibration.MAX_FOV_SCALE, tooBig.fovScale, 0.0)
        val tooSmall = Calibration.NONE.withFov(0.01, CalibrationSource.PAN_SWEEP, 0.1, 2, 0L)
        assertEquals(Calibration.MIN_FOV_SCALE, tooSmall.fovScale, 0.0)
    }

    @Test
    fun `clearing one half leaves the other alone`() {
        val both = Calibration.NONE
            .withAttitude(Rotation3.aboutZenith(3.0), CalibrationSource.STAR_PATTERN, 0.2, 2, 1L)
            .withFov(1.08, CalibrationSource.PAN_SWEEP, 0.1, 2, 1L)

        val withoutSky = both.clearAttitude()
        assertTrue(!withoutSky.hasAttitudeCorrection)
        assertEquals(1.08, withoutSky.fovScale, 1e-9)
        assertEquals(CalibrationSource.PAN_SWEEP, withoutSky.fovSource)

        val withoutFov = both.clearFov()
        assertTrue(withoutFov.hasAttitudeCorrection)
        assertEquals(1.0, withoutFov.fovScale, 0.0)
    }

    @Test
    fun `the methods that work without a clear sky are marked as such`() {
        assertTrue(CalibrationSource.PAN_SWEEP.worksWithoutSky)
        assertTrue(CalibrationSource.LANDMARK_BEARING.worksWithoutSky)
        assertTrue(CalibrationSource.MANUAL.worksWithoutSky)
        assertTrue(!CalibrationSource.STAR_PATTERN.worksWithoutSky)
    }
}
