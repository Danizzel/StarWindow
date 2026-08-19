package com.starwindow.app.core.camera

import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera

/**
 * Drives the viewfinder's exposure.
 *
 * Under a genuinely dark sky the automatic exposure gives up: it meters a nearly black scene, hits
 * its own ceiling well short of what is needed, and leaves the user drawing a window against an
 * empty rectangle. The fix is to take the sensor off automatic and set a long exposure and a high
 * sensitivity by hand.
 *
 * Applied through [Camera2CameraControl] rather than through the use case builder, because that
 * works on the running camera — the sliders take effect while the user watches, with no rebind and
 * no black frame in between.
 *
 * Devices without `MANUAL_SENSOR` fall back to pushing the automatic exposure as far as its own
 * compensation range allows. That is much weaker, but it is what those devices have.
 */
@OptIn(ExperimentalCamera2Interop::class)
class NightVisionController(
    private val camera: Camera,
    private val capabilities: ExposureCapabilities,
) {

    /** Sends [settings] to the camera. Returns what was actually applied after clamping. */
    fun apply(settings: ExposureSettings): ExposureSettings {
        val effective = clamp(settings)
        val options = CaptureRequestOptions.Builder()

        if (effective.mode == ExposureMode.NIGHT && capabilities.supportsManualSensor) {
            options.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CameraMetadata.CONTROL_AE_MODE_OFF,
            )
            options.setCaptureRequestOption(
                CaptureRequest.SENSOR_EXPOSURE_TIME,
                effective.exposureTimeNs,
            )
            options.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, effective.iso)
            // The frame duration caps the frame rate. It must be at least the exposure time, or the
            // request is silently dropped.
            options.setCaptureRequestOption(
                CaptureRequest.SENSOR_FRAME_DURATION,
                capabilities.frameDurationFor(effective.exposureTimeNs),
            )
        } else {
            options.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CameraMetadata.CONTROL_AE_MODE_ON,
            )
        }

        if (effective.focusAtInfinity && capabilities.supportsFocusDistance) {
            options.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CameraMetadata.CONTROL_AF_MODE_OFF,
            )
            // Focus distance is in diopters, so zero is infinity — which is where the sky is.
            options.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
        } else if (capabilities.supportsFocusDistance) {
            options.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            )
        }

        runCatching {
            Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(options.build())
        }.onFailure { Log.w(TAG, "Belichtungseinstellungen wurden abgelehnt", it) }

        // Exposure compensation is a separate CameraX control, and it is the only lever left on
        // devices without manual sensor support.
        if (capabilities.supportsExposureCompensation) {
            val steps = if (effective.mode == ExposureMode.NIGHT && !capabilities.supportsManualSensor) {
                capabilities.exposureCompensationRange.last
            } else {
                effective.exposureCompensationSteps
            }
            runCatching { camera.cameraControl.setExposureCompensationIndex(steps) }
                .onFailure { Log.w(TAG, "Belichtungskorrektur wurde abgelehnt", it) }
        }

        return effective
    }

    private fun clamp(settings: ExposureSettings) = settings.copy(
        exposureTimeNs = capabilities.clampExposureTime(settings.exposureTimeNs),
        iso = capabilities.clampIso(settings.iso),
        focusAtInfinity = settings.focusAtInfinity && capabilities.supportsFocusDistance,
        exposureCompensationSteps = capabilities.clampCompensation(settings.exposureCompensationSteps),
    )

    companion object {
        private const val TAG = "NightVisionController"
    }
}
