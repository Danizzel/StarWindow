package com.starwindow.app.core.camera

import com.starwindow.app.core.astro.Horizontal
import com.starwindow.app.core.astro.Vec3
import com.starwindow.app.core.sensors.DeviceAttitude
import kotlin.math.atan
import kotlin.math.min

/** A point in view pixels, origin top-left, y growing downwards (Android screen convention). */
data class ScreenPoint(val x: Float, val y: Float)

/** How the preview stream is fitted into the view. Must match what the PreviewView is told to do. */
enum class PreviewFit { FIT_CENTER, FILL_CENTER }

/**
 * The pinhole model that connects the viewfinder to the sky.
 *
 * A tap at view pixel (x, y) is a ray in the display frame; the attitude matrix rotates it into
 * the world; the result is an azimuth/altitude. Running it backwards pins a sky direction to a
 * screen position, which is what keeps the placed points glued to the sky while the phone moves.
 */
class SkyProjection(
    val attitude: DeviceAttitude,
    /** Focal length of the displayed image, in view pixels. */
    val focalPx: Double,
    val viewWidthPx: Float,
    val viewHeightPx: Float,
) {

    private val centerX = viewWidthPx / 2f
    private val centerY = viewHeightPx / 2f

    val isUsable: Boolean get() = focalPx > 1.0 && viewWidthPx > 0f && viewHeightPx > 0f

    /** Field of view actually visible in the viewfinder, across its width. */
    val visibleHorizontalFovDeg: Double
        get() = 2.0 * Math.toDegrees(atan(viewWidthPx / (2.0 * focalPx)))

    /** Field of view actually visible in the viewfinder, across its height. */
    val visibleVerticalFovDeg: Double
        get() = 2.0 * Math.toDegrees(atan(viewHeightPx / (2.0 * focalPx)))

    /** Degrees of sky per view pixel at the centre of the image. */
    val degreesPerPixel: Double get() = Math.toDegrees(1.0 / focalPx)

    /** Where the centre of the viewfinder points. */
    val centerDirection: Horizontal get() = attitude.cameraDirection

    /** View pixel → true-north sky direction. */
    fun screenToSky(x: Float, y: Float): Horizontal {
        val ray = Vec3(
            (x - centerX).toDouble(),
            // Screen y grows downwards, the display frame's y axis grows upwards.
            (centerY - y).toDouble(),
            -focalPx,
        )
        return attitude.toTrueNorth(attitude.displayToWorld(ray))
    }

    fun screenToSky(point: ScreenPoint): Horizontal = screenToSky(point.x, point.y)

    /**
     * True-north sky direction → view pixel. Returns null when the direction is behind the camera,
     * where a pinhole projection has no meaningful answer.
     */
    fun skyToScreen(direction: Horizontal): ScreenPoint? {
        val display = attitude.worldToDisplay(attitude.toMagneticVector(direction))
        // The camera looks along -z, so anything in front of it has a negative z component.
        if (display.z > -1e-4) return null
        val scale = focalPx / -display.z
        return ScreenPoint(
            x = centerX + (display.x * scale).toFloat(),
            y = centerY - (display.y * scale).toFloat(),
        )
    }

    /** Whether a direction currently falls inside the viewfinder (with a small margin). */
    fun isOnScreen(direction: Horizontal, marginPx: Float = 0f): Boolean {
        val p = skyToScreen(direction) ?: return false
        return p.x >= -marginPx && p.x <= viewWidthPx + marginPx &&
            p.y >= -marginPx && p.y <= viewHeightPx + marginPx
    }

    companion object {

        /**
         * Works out the focal length in *view* pixels for a preview stream displayed inside a view.
         *
         * @param streamWidth/[streamHeight] resolution of the preview buffer, in sensor orientation.
         * @param rotationDegrees rotation CameraX applies to make the buffer upright on screen.
         * @param fovScale user calibration factor; > 1 means "the real field of view is wider than
         *   the camera claims", which stretches the sky relative to the image.
         */
        fun focalLengthInViewPixels(
            intrinsics: CameraIntrinsics,
            streamWidth: Int,
            streamHeight: Int,
            rotationDegrees: Int,
            viewWidthPx: Float,
            viewHeightPx: Float,
            fit: PreviewFit = PreviewFit.FIT_CENTER,
            fovScale: Double = 1.0,
        ): Double {
            if (streamWidth <= 0 || streamHeight <= 0 || viewWidthPx <= 0f || viewHeightPx <= 0f) {
                return 0.0
            }
            val focalStreamPx = intrinsics.focalLengthInStreamPixels(streamWidth, streamHeight)
            if (focalStreamPx <= 0.0) return 0.0

            val quarterTurn = ((rotationDegrees % 360) + 360) % 360 == 90 ||
                ((rotationDegrees % 360) + 360) % 360 == 270
            val displayedWidth = if (quarterTurn) streamHeight else streamWidth
            val displayedHeight = if (quarterTurn) streamWidth else streamHeight

            val scaleX = viewWidthPx / displayedWidth
            val scaleY = viewHeightPx / displayedHeight
            val scale = when (fit) {
                PreviewFit.FIT_CENTER -> min(scaleX, scaleY)
                PreviewFit.FILL_CENTER -> kotlin.math.max(scaleX, scaleY)
            }

            // A wider real field of view means a shorter effective focal length.
            return focalStreamPx * scale / fovScale.coerceIn(0.5, 2.0)
        }
    }
}
