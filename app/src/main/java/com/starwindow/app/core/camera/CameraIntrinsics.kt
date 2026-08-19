package com.starwindow.app.core.camera

import android.hardware.camera2.CameraCharacteristics
import android.util.Size
import kotlin.math.atan
import kotlin.math.tan

/**
 * What the lens actually sees. Everything the app does hangs off these two angles: without a
 * correct field of view a tap in the middle of the screen still lands on the right star, but a tap
 * near the edge can be several degrees off.
 */
data class CameraIntrinsics(
    val cameraId: String,
    /** Field of view across the full active sensor width (the long side), in degrees. */
    val horizontalFovDeg: Double,
    /** Field of view across the full active sensor height (the short side), in degrees. */
    val verticalFovDeg: Double,
    val focalLengthMm: Float,
    val sensorWidthMm: Float,
    val sensorHeightMm: Float,
    /** True when the values are a generic fallback because the camera did not report its optics. */
    val isEstimate: Boolean = false,
) {

    /**
     * Focal length expressed in pixels of a stream of the given size.
     *
     * A preview stream is a centre crop of the active array to the stream's aspect ratio, so the
     * field of view of the stream is narrower than the sensor's in exactly one direction.
     */
    fun focalLengthInStreamPixels(streamWidth: Int, streamHeight: Int): Double {
        if (streamWidth <= 0 || streamHeight <= 0) return 0.0
        val sensorAspect = horizontalFovTan() / verticalFovTan()
        val streamAspect = streamWidth.toDouble() / streamHeight.toDouble()
        return if (streamAspect >= sensorAspect) {
            // Full sensor width is used, the top and bottom get cropped away.
            (streamWidth / 2.0) / horizontalFovTan()
        } else {
            // Full sensor height is used, the sides get cropped away.
            (streamHeight / 2.0) / verticalFovTan()
        }
    }

    private fun horizontalFovTan() = tan(Math.toRadians(horizontalFovDeg / 2.0))
    private fun verticalFovTan() = tan(Math.toRadians(verticalFovDeg / 2.0))

    companion object {
        /**
         * A plausible main-camera field of view, used when the camera does not report usable
         * optics. Roughly a 26 mm equivalent lens: a 5.7 x 4.3 mm sensor behind 4.3 mm of focal
         * length. The two angles are kept consistent with those millimetres — an inconsistent
         * pair would quietly skew every pixel-to-angle conversion.
         */
        val FALLBACK = CameraIntrinsics(
            cameraId = "unknown",
            horizontalFovDeg = 67.06,
            verticalFovDeg = 53.13,
            focalLengthMm = 4.3f,
            sensorWidthMm = 5.7f,
            sensorHeightMm = 4.3f,
            isEstimate = true,
        )

        /** Reads the optics out of the Camera2 characteristics of the bound camera. */
        fun fromCharacteristics(
            cameraId: String,
            characteristics: CameraCharacteristics,
        ): CameraIntrinsics {
            val focalLengths = characteristics.get(
                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
            )
            val physicalSize = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE
            )
            val focal = focalLengths?.firstOrNull()
            if (focal == null || focal <= 0f || physicalSize == null) {
                return FALLBACK.copy(cameraId = cameraId)
            }

            // The physical size describes the whole pixel array; only the active array is read out.
            val pixelArray: Size? = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE
            )
            val activeArray = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE
            )
            var widthMm = physicalSize.width
            var heightMm = physicalSize.height
            if (pixelArray != null && activeArray != null &&
                pixelArray.width > 0 && pixelArray.height > 0
            ) {
                widthMm *= activeArray.width().toFloat() / pixelArray.width
                heightMm *= activeArray.height().toFloat() / pixelArray.height
            }

            return CameraIntrinsics(
                cameraId = cameraId,
                horizontalFovDeg = fovDeg(widthMm, focal),
                verticalFovDeg = fovDeg(heightMm, focal),
                focalLengthMm = focal,
                sensorWidthMm = widthMm,
                sensorHeightMm = heightMm,
                isEstimate = false,
            )
        }

        private fun fovDeg(sensorExtentMm: Float, focalMm: Float): Double =
            2.0 * Math.toDegrees(atan((sensorExtentMm / (2.0 * focalMm))))
    }
}
