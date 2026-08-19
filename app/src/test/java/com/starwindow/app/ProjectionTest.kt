package com.starwindow.app

import com.starwindow.app.core.astro.Angles
import com.starwindow.app.core.astro.Horizontal
import com.starwindow.app.core.astro.Vec3
import com.starwindow.app.core.camera.CameraIntrinsics
import com.starwindow.app.core.camera.PreviewFit
import com.starwindow.app.core.camera.SkyProjection
import com.starwindow.app.core.sensors.DeviceAttitude
import kotlin.math.abs
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Attitude with the rear camera pointing at the given direction, screen upright.
 *
 * Columns of the matrix are the world-frame images of the display axes:
 * x = screen right, y = screen up, z = out of the screen (so the camera looks along -z).
 */
private fun attitudeLookingAt(azimuthDeg: Double, altitudeDeg: Double): DeviceAttitude {
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
        magneticDeclinationDeg = 0.0,
        accuracy = 3,
        timestampMs = 0L,
    )
}

class DeviceAttitudeTest {

    @Test
    fun `a phone held level facing north reports north`() {
        val attitude = DeviceAttitude(
            rotationMatrix = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f),
            magneticDeclinationDeg = 0.0,
            accuracy = 3,
            timestampMs = 0L,
        )
        assertEquals(0.0, attitude.cameraDirection.azimuthDeg, 1e-5)
        assertEquals(0.0, attitude.cameraDirection.altitudeDeg, 1e-5)
        assertEquals(0.0, attitude.rollDeg, 1e-5)
    }

    @Test
    fun `the helper attitude points where it is told`() {
        for (az in 0 until 360 step 23) {
            for (alt in -70..70 step 17) {
                val attitude = attitudeLookingAt(az.toDouble(), alt.toDouble())
                val direction = attitude.cameraDirection
                assertEquals(az.toDouble(), direction.azimuthDeg, 1e-4)
                assertEquals(alt.toDouble(), direction.altitudeDeg, 1e-4)
            }
        }
    }

    @Test
    fun `declination shifts true north without moving altitude`() {
        val attitude = attitudeLookingAt(10.0, 25.0).copy(magneticDeclinationDeg = 3.5)
        assertEquals(13.5, attitude.cameraDirection.azimuthDeg, 1e-4)
        assertEquals(25.0, attitude.cameraDirection.altitudeDeg, 1e-4)
    }

    @Test
    fun `world and display rotations are inverses`() {
        val attitude = attitudeLookingAt(210.0, 33.0)
        val v = Vec3(0.3, -0.5, 0.81).normalized()
        val back = attitude.worldToDisplay(attitude.displayToWorld(v))
        assertTrue(abs(back.x - v.x) < 1e-6 && abs(back.y - v.y) < 1e-6 && abs(back.z - v.z) < 1e-6)
    }
}

class SkyProjectionTest {

    private val viewWidth = 1080f
    private val viewHeight = 2160f

    /** A 60 degree horizontal field of view across the 1080 px width. */
    private val focalPx = (viewWidth / 2.0) / tan(Math.toRadians(30.0))

    @Test
    fun `the centre of the screen is the camera direction`() {
        val projection = SkyProjection(attitudeLookingAt(137.0, 22.0), focalPx, viewWidth, viewHeight)
        val direction = projection.screenToSky(viewWidth / 2f, viewHeight / 2f)
        assertEquals(137.0, direction.azimuthDeg, 1e-4)
        assertEquals(22.0, direction.altitudeDeg, 1e-4)
    }

    @Test
    fun `the reported field of view matches the focal length`() {
        val projection = SkyProjection(attitudeLookingAt(0.0, 0.0), focalPx, viewWidth, viewHeight)
        assertEquals(60.0, projection.visibleHorizontalFovDeg, 1e-6)
    }

    @Test
    fun `the screen edge sits at half the field of view`() {
        val projection = SkyProjection(attitudeLookingAt(0.0, 0.0), focalPx, viewWidth, viewHeight)
        val edge = projection.screenToSky(viewWidth, viewHeight / 2f)
        assertEquals(30.0, Angles.wrapDeg180(edge.azimuthDeg), 1e-4)
        assertEquals(0.0, edge.altitudeDeg, 1e-4)
    }

    @Test
    fun `screen to sky and back is an identity`() {
        for (az in listOf(0.0, 47.0, 180.0, 305.0)) {
            for (alt in listOf(-40.0, 0.0, 35.0, 78.0)) {
                val projection = SkyProjection(attitudeLookingAt(az, alt), focalPx, viewWidth, viewHeight)
                for (x in listOf(0f, 270f, 540f, 810f, 1080f)) {
                    for (y in listOf(0f, 540f, 1080f, 1620f, 2160f)) {
                        val sky = projection.screenToSky(x, y)
                        val back = projection.skyToScreen(sky)
                        assertTrue(back != null, "lost the point at ($x, $y) looking at $az/$alt")
                        assertEquals(x, back!!.x, 0.01f)
                        assertEquals(y, back.y, 0.01f)
                    }
                }
            }
        }
    }

    @Test
    fun `panning by the field of view moves a marker across the screen`() {
        // Pin a direction that sits at the centre, then turn the phone by ten degrees: the marker
        // must move by exactly the number of pixels ten degrees is worth.
        val target = Horizontal(100.0, 0.0)
        val turned = SkyProjection(attitudeLookingAt(90.0, 0.0), focalPx, viewWidth, viewHeight)
        val point = turned.skyToScreen(target)
        assertTrue(point != null)
        val expectedOffset = focalPx * tan(Math.toRadians(10.0))
        assertEquals((viewWidth / 2.0 + expectedOffset).toFloat(), point!!.x, 0.05f)
        assertEquals(viewHeight / 2f, point.y, 0.05f)
    }

    @Test
    fun `directions behind the camera have no screen position`() {
        val projection = SkyProjection(attitudeLookingAt(0.0, 0.0), focalPx, viewWidth, viewHeight)
        assertNull(projection.skyToScreen(Horizontal(180.0, 0.0)))
        assertNull(projection.skyToScreen(Horizontal(270.0, 0.0)))
    }

    @Test
    fun `roll tilts the sky in the viewfinder`() {
        // Roll the phone 90 degrees clockwise: screen right now points at the zenith.
        val attitude = DeviceAttitude(
            rotationMatrix = floatArrayOf(0f, -1f, 0f, 0f, 0f, -1f, 1f, 0f, 0f),
            magneticDeclinationDeg = 0.0,
            accuracy = 3,
            timestampMs = 0L,
        )
        assertEquals(0.0, attitude.cameraDirection.azimuthDeg, 1e-4)
        assertEquals(90.0, attitude.rollDeg, 1e-4)

        val projection = SkyProjection(attitude, focalPx, viewWidth, viewHeight)
        val right = projection.screenToSky(viewWidth, viewHeight / 2f)
        assertEquals(30.0, right.altitudeDeg, 1e-4)
    }
}

class CameraIntrinsicsTest {

    private val intrinsics = CameraIntrinsics(
        cameraId = "0",
        horizontalFovDeg = 67.06,
        verticalFovDeg = 53.13,
        focalLengthMm = 4.3f,
        sensorWidthMm = 5.7f,
        sensorHeightMm = 4.3f,
    )

    @Test
    fun `a full width stream keeps the horizontal field of view`() {
        // A 4:3 stream matches the sensor, so the full horizontal field of view is used.
        val focal = intrinsics.focalLengthInStreamPixels(4000, 3000)
        val fov = 2.0 * Math.toDegrees(kotlin.math.atan(4000 / (2.0 * focal)))
        assertEquals(67.06, fov, 0.05)
    }

    @Test
    fun `a wider stream is cropped vertically not horizontally`() {
        val fourThree = intrinsics.focalLengthInStreamPixels(4000, 3000)
        val sixteenNine = intrinsics.focalLengthInStreamPixels(4000, 2250)
        // Same width, same crop of the sensor's width: the pixel scale must be identical.
        assertEquals(fourThree, sixteenNine, 1e-6)
    }

    @Test
    fun `portrait display rotation maps the sensor's short side across the screen`() {
        val focalView = SkyProjection.focalLengthInViewPixels(
            intrinsics = intrinsics,
            streamWidth = 1920,
            streamHeight = 1440,
            rotationDegrees = 90,
            viewWidthPx = 1080f,
            viewHeightPx = 2160f,
            fit = PreviewFit.FIT_CENTER,
        )
        val projection = SkyProjection(attitudeLookingAt(0.0, 0.0), focalView, 1080f, 2160f)

        // Rotated by 90 degrees the buffer is 1440x1920; fitted into a 1080x2160 view it is
        // limited by width, so what spans the screen horizontally is the stream's *vertical*
        // field of view.
        val focalStream = intrinsics.focalLengthInStreamPixels(1920, 1440)
        val streamVerticalFov = 2.0 * Math.toDegrees(kotlin.math.atan(1440 / (2.0 * focalStream)))
        assertEquals(streamVerticalFov, projection.visibleHorizontalFovDeg, 1e-6)

        // The 4:3 stream is a hair wider than this 5.7x4.3 mm sensor, so it loses a fraction of a
        // degree off the top and bottom compared with the sensor's own 53.13 degrees.
        assertEquals(53.13, streamVerticalFov, 0.4)
        assertTrue(streamVerticalFov < 53.13)
    }

    @Test
    fun `the calibration factor widens the field of view`() {
        val base = SkyProjection.focalLengthInViewPixels(
            intrinsics, 1920, 1440, 0, 1440f, 1080f, PreviewFit.FIT_CENTER, 1.0,
        )
        val wider = SkyProjection.focalLengthInViewPixels(
            intrinsics, 1920, 1440, 0, 1440f, 1080f, PreviewFit.FIT_CENTER, 1.1,
        )
        assertTrue(wider < base)
        val baseFov = SkyProjection(attitudeLookingAt(0.0, 0.0), base, 1440f, 1080f).visibleHorizontalFovDeg
        val widerFov = SkyProjection(attitudeLookingAt(0.0, 0.0), wider, 1440f, 1080f).visibleHorizontalFovDeg
        assertTrue(widerFov > baseFov)
    }
}
