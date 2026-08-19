package com.starwindow.app.core.camera

import android.hardware.camera2.CameraCharacteristics
import kotlinx.serialization.Serializable

/** How the viewfinder is driven. */
@Serializable
enum class ExposureMode {
    /** The camera decides. Fine by day, shows almost nothing under a dark sky. */
    AUTO,

    /** Long exposure and high sensitivity, set by hand. What the app is actually for. */
    NIGHT;

    val label: String get() = if (this == AUTO) "Automatik" else "Nacht"
}

/**
 * Viewfinder exposure. The defaults are a starting point for a dark rural sky: a quarter of a
 * second brings the brighter stars out on most phones without turning the preview into a slideshow.
 */
@Serializable
data class ExposureSettings(
    val mode: ExposureMode = ExposureMode.AUTO,
    val exposureTimeNs: Long = 250_000_000L,
    val iso: Int = 1600,
    /**
     * Lock focus at infinity. Autofocus has nothing to lock onto in a dark sky and will hunt
     * forever, which also keeps the image soft exactly when sharpness matters most.
     */
    val focusAtInfinity: Boolean = true,
    /**
     * Exposure compensation in the camera's own steps, used in [ExposureMode.AUTO] and as the
     * fallback on devices without manual sensor control.
     */
    val exposureCompensationSteps: Int = 0,
) {
    val exposureTimeSeconds: Double get() = exposureTimeNs / 1_000_000_000.0

    /** `1/4 s` or `2,0 s`, whichever reads better at this length. */
    fun formatExposureTime(): String {
        val seconds = exposureTimeSeconds
        return if (seconds < 1.0) "1/%.0f s".format(1.0 / seconds) else "%.1f s".format(seconds)
    }

    companion object {
        val AUTO = ExposureSettings()

        /** A sensible starting point for the night mode, clamped to what the device can do. */
        fun nightDefault(capabilities: ExposureCapabilities): ExposureSettings = ExposureSettings(
            mode = ExposureMode.NIGHT,
            exposureTimeNs = capabilities.clampExposureTime(250_000_000L),
            iso = capabilities.clampIso(1600),
            focusAtInfinity = capabilities.supportsFocusDistance,
        )
    }
}

/**
 * What the camera is actually willing to do. Read once when the camera binds, because every slider
 * in the night mode has to be clamped to these limits — asking for a two second exposure on a
 * device that stops at a third of a second silently gets ignored otherwise.
 */
data class ExposureCapabilities(
    val supportsManualSensor: Boolean,
    val minExposureTimeNs: Long,
    val maxExposureTimeNs: Long,
    val minIso: Int,
    val maxIso: Int,
    val maxFrameDurationNs: Long,
    val supportsFocusDistance: Boolean,
    val exposureCompensationRange: IntRange,
    /** How many EV one compensation step is worth. */
    val exposureCompensationStepEv: Double,
) {

    /** True when the night mode can do anything useful at all on this device. */
    val supportsNightMode: Boolean get() = supportsManualSensor

    /** True when at least the brightness can be pushed, even without manual sensor control. */
    val supportsExposureCompensation: Boolean
        get() = exposureCompensationRange.last > exposureCompensationRange.first

    fun clampExposureTime(ns: Long): Long = ns.coerceIn(minExposureTimeNs, maxExposureTimeNs)

    fun clampIso(iso: Int): Int = iso.coerceIn(minIso, maxIso)

    fun clampCompensation(steps: Int): Int = steps.coerceIn(
        exposureCompensationRange.first,
        exposureCompensationRange.last,
    )

    /** Frame duration must never be shorter than the exposure, or the request is rejected. */
    fun frameDurationFor(exposureTimeNs: Long): Long =
        exposureTimeNs.coerceAtMost(maxFrameDurationNs)

    companion object {

        /** Everything switched off — used when the camera reports nothing usable. */
        val NONE = ExposureCapabilities(
            supportsManualSensor = false,
            minExposureTimeNs = 0L,
            maxExposureTimeNs = 0L,
            minIso = 0,
            maxIso = 0,
            maxFrameDurationNs = 0L,
            supportsFocusDistance = false,
            exposureCompensationRange = 0..0,
            exposureCompensationStepEv = 0.0,
        )

        fun fromCharacteristics(characteristics: CameraCharacteristics): ExposureCapabilities {
            val capabilities = characteristics.get(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
            )
            val manualSensor = capabilities?.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
            ) == true

            val exposureRange = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
            )
            val isoRange = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
            )
            val maxFrameDuration = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION
            ) ?: 0L

            // A minimum focus distance of zero means a fixed-focus lens: already at infinity, and
            // the focus distance cannot be set at all.
            val minFocusDistance = characteristics.get(
                CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
            ) ?: 0f

            val compensationRange = characteristics.get(
                CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE
            )
            val compensationStep = characteristics.get(
                CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP
            )

            return ExposureCapabilities(
                supportsManualSensor = manualSensor && exposureRange != null && isoRange != null,
                minExposureTimeNs = exposureRange?.lower ?: 0L,
                maxExposureTimeNs = exposureRange?.upper ?: 0L,
                minIso = isoRange?.lower ?: 0,
                maxIso = isoRange?.upper ?: 0,
                maxFrameDurationNs = maxFrameDuration,
                supportsFocusDistance = minFocusDistance > 0f,
                exposureCompensationRange = (compensationRange?.lower ?: 0)..(compensationRange?.upper ?: 0),
                exposureCompensationStepEv = compensationStep?.toDouble() ?: 0.0,
            )
        }
    }
}
