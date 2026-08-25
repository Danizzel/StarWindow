package com.starwindow.app.core.calibration

import com.starwindow.app.core.astro.Angles
import com.starwindow.app.core.astro.Horizontal
import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.core.astro.Rotation3
import com.starwindow.app.core.astro.Vec3
import kotlinx.serialization.Serializable

/** How a calibration value was obtained. Kept with the value so its trustworthiness stays visible. */
@Serializable
enum class CalibrationSource {
    NONE,
    MANUAL,
    STAR_PATTERN,
    LANDMARK_BEARING,
    PAN_SWEEP;

    val label: String
        get() = when (this) {
            NONE -> "nicht kalibriert"
            MANUAL -> "manuell"
            STAR_PATTERN -> "Sternmuster"
            LANDMARK_BEARING -> "Peilung"
            PAN_SWEEP -> "Schwenk"
        }

    /** True for the methods that work with an obstructed or overcast sky. */
    val worksWithoutSky: Boolean
        get() = this == MANUAL || this == LANDMARK_BEARING || this == PAN_SWEEP || this == NONE
}

/**
 * The two corrections the app can learn, deliberately kept apart:
 *
 *  * [correction] — a small rotation in the true-north world frame. It absorbs what the compass
 *    gets wrong, which on a phone is most of the error budget.
 *  * [fovScale] — purely optical. Corrects a field of view the camera reported wrongly.
 *
 * They are measured separately and never fitted against each other. The attitude methods aim the
 * **crosshair** at a reference, and in the middle of the image the field of view does not enter the
 * calculation at all; the field of view methods only look at *relative* rotations and never need to
 * know where north is. That separation is what keeps each measurement interpretable — and it is why
 * neither method is mandatory: whichever one the sky allows can be used on its own.
 */
@Serializable
data class Calibration(
    /** Axis-angle correction in radians, applied in the true-north world frame. */
    val correctionXRad: Double = 0.0,
    val correctionYRad: Double = 0.0,
    val correctionZRad: Double = 0.0,
    /** > 1 means the real field of view is wider than the camera claims. */
    val fovScale: Double = 1.0,

    val attitudeSource: CalibrationSource = CalibrationSource.NONE,
    val attitudeResidualDeg: Double? = null,
    val attitudeSampleCount: Int = 0,
    val attitudeUpdatedAtMillis: Long = 0L,
    /**
     * Where the attitude correction was measured. A compass error belongs to the place rather than
     * to the phone, so a correction carried far enough away is marked questionable instead of being
     * applied in silence — see [trustAt].
     */
    val attitudeLatitudeDeg: Double? = null,
    val attitudeLongitudeDeg: Double? = null,

    val fovSource: CalibrationSource = CalibrationSource.NONE,
    val fovResidualDeg: Double? = null,
    val fovSampleCount: Int = 0,
    val fovUpdatedAtMillis: Long = 0L,
) {

    val correction: Rotation3
        get() = Rotation3.fromRotationVector(Vec3(correctionXRad, correctionYRad, correctionZRad))

    val hasAttitudeCorrection: Boolean
        get() = attitudeSource != CalibrationSource.NONE && !correction.isIdentity

    val hasFovCorrection: Boolean
        get() = fovSource != CalibrationSource.NONE

    /** Total angular size of the attitude correction, for display. */
    val correctionAngleDeg: Double get() = correction.angleDeg()

    /**
     * The azimuth shift the correction produces at the horizon — the number a user recognises as
     * "my compass was off by this much".
     */
    val headingOffsetDeg: Double
        get() {
            val north = Horizontal(0.0, 0.0).toVector()
            return Angles.wrapDeg180(Horizontal.fromVector(correction.apply(north)).azimuthDeg)
        }

    fun withAttitude(
        rotation: Rotation3,
        source: CalibrationSource,
        residualDeg: Double?,
        sampleCount: Int,
        atMillis: Long,
        /** Where it was measured, so [trustAt] can tell later whether it still applies. */
        measuredAt: ObserverLocation? = null,
    ): Calibration {
        val v = rotationVectorOf(rotation)
        return copy(
            correctionXRad = v.x,
            correctionYRad = v.y,
            correctionZRad = v.z,
            attitudeSource = source,
            attitudeResidualDeg = residualDeg,
            attitudeSampleCount = sampleCount,
            attitudeUpdatedAtMillis = atMillis,
            attitudeLatitudeDeg = measuredAt?.latitudeDeg,
            attitudeLongitudeDeg = measuredAt?.longitudeDeg,
        )
    }

    fun withFov(
        scale: Double,
        source: CalibrationSource,
        residualDeg: Double?,
        sampleCount: Int,
        atMillis: Long,
    ): Calibration = copy(
        fovScale = scale.coerceIn(MIN_FOV_SCALE, MAX_FOV_SCALE),
        fovSource = source,
        fovResidualDeg = residualDeg,
        fovSampleCount = sampleCount,
        fovUpdatedAtMillis = atMillis,
    )

    fun clearAttitude(): Calibration = copy(
        correctionXRad = 0.0,
        correctionYRad = 0.0,
        correctionZRad = 0.0,
        attitudeSource = CalibrationSource.NONE,
        attitudeResidualDeg = null,
        attitudeSampleCount = 0,
        attitudeUpdatedAtMillis = 0L,
        attitudeLatitudeDeg = null,
        attitudeLongitudeDeg = null,
    )

    fun clearFov(): Calibration = copy(
        fovScale = 1.0,
        fovSource = CalibrationSource.NONE,
        fovResidualDeg = null,
        fovSampleCount = 0,
        fovUpdatedAtMillis = 0L,
    )

    companion object {
        const val MIN_FOV_SCALE = 0.5
        const val MAX_FOV_SCALE = 2.0

        val NONE = Calibration()

        /** Axis-angle vector of a rotation — the inverse of [Rotation3.fromRotationVector]. */
        fun rotationVectorOf(rotation: Rotation3): Vec3 {
            val angle = Math.toRadians(rotation.angleDeg())
            if (angle < 1e-12) return Vec3(0.0, 0.0, 0.0)
            val sin = kotlin.math.sin(angle)
            if (kotlin.math.abs(sin) < 1e-9) {
                // A 180 degree rotation; far outside anything a calibration should produce, but it
                // must not come back as NaN.
                return Vec3(0.0, 0.0, angle)
            }
            val axis = Vec3(
                rotation[2, 1] - rotation[1, 2],
                rotation[0, 2] - rotation[2, 0],
                rotation[1, 0] - rotation[0, 1],
            ) * (1.0 / (2.0 * sin))
            return axis.normalized() * angle
        }
    }
}
