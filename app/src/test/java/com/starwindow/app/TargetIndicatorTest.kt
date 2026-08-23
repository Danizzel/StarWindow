package com.starwindow.app

import com.starwindow.app.core.astro.Horizontal
import com.starwindow.app.core.astro.Vec3
import com.starwindow.app.core.camera.EdgeInsets
import com.starwindow.app.core.camera.SkyProjection
import com.starwindow.app.core.camera.TargetIndicator
import com.starwindow.app.core.sensors.DeviceAttitude
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val VIEW_WIDTH = 1080f
private const val VIEW_HEIGHT = 2160f

/** ~62° across the width, a plausible phone camera. */
private const val FOCAL_PX = 900.0

/**
 * Attitude with the rear camera pointing at the given direction, screen upright.
 *
 * Columns of the matrix are the world-frame images of the display axes:
 * x = screen right, y = screen up, z = out of the screen (so the camera looks along -z).
 */
private fun lookingAt(azimuthDeg: Double, altitudeDeg: Double): DeviceAttitude {
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

private fun projectionLookingAt(azimuthDeg: Double, altitudeDeg: Double) = SkyProjection(
    attitude = lookingAt(azimuthDeg, altitudeDeg),
    focalPx = FOCAL_PX,
    viewWidthPx = VIEW_WIDTH,
    viewHeightPx = VIEW_HEIGHT,
)

class TargetIndicatorTest {

    @Test
    fun `a target in the middle of the view is reported on screen at the centre`() {
        val projection = projectionLookingAt(120.0, 30.0)
        val marker = TargetIndicator.locate(projection, Horizontal(120.0, 30.0))!!

        assertTrue(marker.onScreen)
        assertEquals(VIEW_WIDTH / 2f, marker.x, 1f)
        assertEquals(VIEW_HEIGHT / 2f, marker.y, 1f)
        assertEquals(0.0, marker.separationDeg, 1e-6)
    }

    @Test
    fun `a target just off to the side is off screen and pinned inside the padded edge`() {
        val padding = 40f
        val projection = projectionLookingAt(0.0, 0.0)
        // Well outside the ~62° horizontal field of view.
        val marker = TargetIndicator.locate(projection, Horizontal(70.0, 0.0), EdgeInsets.uniform(padding))!!

        assertTrue(!marker.onScreen)
        assertTrue(
            marker.x <= VIEW_WIDTH - padding + 0.5f && marker.x >= padding - 0.5f,
            "the marker sits outside the padded view at x=${marker.x}",
        )
        assertTrue(marker.y in 0f..VIEW_HEIGHT)
    }

    @Test
    fun `the arrow stays clear of the bands the screen's own controls occupy`() {
        // What the capture screen reports: a status band at the top, a fat control panel plus the
        // tracking bar at the bottom. An arrow drawn into either of those points at nothing.
        val insets = EdgeInsets(left = 20f, top = 300f, right = 20f, bottom = 600f)
        val projection = projectionLookingAt(180.0, 0.0)

        for (azimuth in 0 until 360 step 5) {
            for (altitude in -85..85 step 5) {
                val marker = TargetIndicator
                    .locate(projection, Horizontal(azimuth.toDouble(), altitude.toDouble()), insets)!!
                if (marker.onScreen) continue
                assertTrue(
                    marker.y >= insets.top - 0.5f && marker.y <= VIEW_HEIGHT - insets.bottom + 0.5f,
                    "az=$azimuth alt=$altitude put the arrow at y=${marker.y}, inside the controls",
                )
                assertTrue(marker.x >= insets.left - 0.5f && marker.x <= VIEW_WIDTH - insets.right + 0.5f)
            }
        }
    }

    @Test
    fun `insets bigger than the view still leave the marker on the screen`() {
        val projection = projectionLookingAt(0.0, 0.0)
        val absurd = EdgeInsets(left = 2000f, top = 3000f, right = 2000f, bottom = 3000f)
        val marker = TargetIndicator.locate(projection, Horizontal(120.0, 40.0), absurd)!!

        assertTrue(marker.x in 0f..VIEW_WIDTH, "x=${marker.x}")
        assertTrue(marker.y in 0f..VIEW_HEIGHT, "y=${marker.y}")
    }

    @Test
    fun `the arrow points the way the phone has to turn`() {
        val projection = projectionLookingAt(0.0, 0.0)

        // The target is to the east, i.e. to the right of a north-facing camera.
        val east = TargetIndicator.locate(projection, Horizontal(70.0, 0.0))!!
        assertTrue(east.dirX > 0.9f, "east should point right, dirX=${east.dirX}")
        assertTrue(abs(east.dirY) < 0.2f)

        val west = TargetIndicator.locate(projection, Horizontal(290.0, 0.0))!!
        assertTrue(west.dirX < -0.9f, "west should point left, dirX=${west.dirX}")

        // Screen y grows downwards, so "up in the sky" is a negative dirY.
        val high = TargetIndicator.locate(projection, Horizontal(0.0, 70.0))!!
        assertTrue(high.dirY < -0.9f, "the zenith should point up, dirY=${high.dirY}")

        val low = TargetIndicator.locate(projection, Horizontal(0.0, -70.0))!!
        assertTrue(low.dirY > 0.9f, "below the horizon should point down, dirY=${low.dirY}")
    }

    @Test
    fun `a target behind the observer still gets a usable direction`() {
        // The projection has no answer here — this is exactly the case a naive implementation
        // gets backwards, sending the user further away from the target.
        val projection = projectionLookingAt(0.0, 0.0)
        val marker = TargetIndicator.locate(projection, Horizontal(180.0, 20.0))!!

        assertTrue(!marker.onScreen)
        assertTrue(marker.separationDeg > 90.0, "separation=${marker.separationDeg}")
        // Behind and above: turning around means tilting up, not down.
        assertTrue(marker.dirY < 0f, "dirY=${marker.dirY}")
    }

    @Test
    fun `the marker never leaves the view whatever direction is asked for`() {
        val projection = projectionLookingAt(35.0, 15.0)
        for (azimuth in 0 until 360 step 7) {
            for (altitude in -80..80 step 11) {
                val marker = TargetIndicator.locate(projection, Horizontal(azimuth.toDouble(), altitude.toDouble()))
                    ?: continue
                assertTrue(
                    marker.x in -1f..(VIEW_WIDTH + 1f) && marker.y in -1f..(VIEW_HEIGHT + 1f),
                    "marker for az=$azimuth alt=$altitude escaped the view at (${marker.x}, ${marker.y})",
                )
            }
        }
    }

    @Test
    fun `separation matches the angle between the view centre and the target`() {
        val projection = projectionLookingAt(90.0, 0.0)
        assertEquals(30.0, TargetIndicator.locate(projection, Horizontal(120.0, 0.0))!!.separationDeg, 1e-6)
        assertEquals(45.0, TargetIndicator.locate(projection, Horizontal(90.0, 45.0))!!.separationDeg, 1e-6)
    }

    @Test
    fun `the arrow rotation agrees with the direction it points in`() {
        val projection = projectionLookingAt(0.0, 0.0)
        val marker = TargetIndicator.locate(projection, Horizontal(70.0, 0.0))!!
        // Pointing right is zero rotation for an arrow drawn along +x.
        assertEquals(0f, marker.arrowRotationDeg, 12f)
    }

    @Test
    fun `an unusable projection yields no marker at all`() {
        val unusable = SkyProjection(lookingAt(0.0, 0.0), focalPx = 0.0, VIEW_WIDTH, VIEW_HEIGHT)
        assertTrue(TargetIndicator.locate(unusable, Horizontal(10.0, 10.0)) == null)
    }
}
