package com.starwindow.app.core.sensors

import com.starwindow.app.core.astro.Horizontal
import com.starwindow.app.core.astro.Rotation3
import com.starwindow.app.core.astro.Vec3

/** Which sensors an attitude came from. */
enum class AttitudeSource(val label: String) {
    /**
     * Gyroscope for the motion, compass only for north — the steady one. The overlay stops
     * twitching, and a magnetic disturbance can no longer swing the sky.
     */
    GYRO_WITH_COMPASS_HEADING("Kreisel + Kompass"),

    /** The platform's fused rotation vector on its own; used when there is no game rotation vector. */
    FUSED_COMPASS("Kompass"),

    /** Accelerometer and magnetometer only, no gyroscope. Noticeably shakier. */
    GEOMAGNETIC("Kompass ohne Kreisel"),
}

/**
 * The phone's orientation, already remapped to the *display* frame:
 * x = screen right, y = screen up, z = out of the screen towards the user.
 * The rear camera therefore looks along -z.
 *
 * [rotationMatrix] is row-major and maps display-frame vectors into the world frame
 * (x = east, y = **magnetic** north, z = up). Getting from there to true north takes two steps,
 * both of which the helpers below apply: the magnetic declination for the observer's position, and
 * the calibration [correction] the user measured against stars or a known bearing.
 */
data class DeviceAttitude(
    val rotationMatrix: FloatArray,
    val magneticDeclinationDeg: Double,
    val accuracy: Int,
    val timestampMs: Long,
    /** Learned correction, applied in the world frame after the declination. */
    val correction: Rotation3 = Rotation3.IDENTITY,
    /** Which sensors produced this attitude — the two differ a lot in how steady they are. */
    val source: AttitudeSource = AttitudeSource.FUSED_COMPASS,
    /** Field strength the magnetometer measures, in µT. Null when there is no magnetometer. */
    val fieldMicroTesla: Float? = null,
    /** What the geomagnetic model says the field should be here, in µT. */
    val expectedFieldMicroTesla: Float? = null,
    /**
     * True while north is being carried by the gyroscope alone because the compass is not currently
     * worth believing. The direction stays usable; it just slowly ages instead of jumping about.
     */
    val headingHeld: Boolean = false,
) {

    /** How far the measured field is from the model, in µT, or null without a magnetometer. */
    val fieldDeviationMicroTesla: Float?
        get() {
            val measured = fieldMicroTesla ?: return null
            val expected = expectedFieldMicroTesla ?: return null
            return measured - expected
        }

    /** True when something ferrous or magnetic nearby is bending the compass. */
    val isMagneticallyDisturbed: Boolean
        get() {
            val measured = fieldMicroTesla ?: return false
            val expected = expectedFieldMicroTesla ?: return false
            return !AttitudeFusion.isFieldPlausible(measured, expected)
        }

    /**
     * Magnetic world frame → true, calibrated world frame. Built once per attitude because the
     * overlay runs it over hundreds of directions per frame.
     */
    private val trueFromMagnetic: Rotation3 by lazy {
        correction * Rotation3.aboutZenith(magneticDeclinationDeg)
    }

    /** The sensor's rotation as a double-precision matrix, for the calibration solvers. */
    val worldFromDisplay: Rotation3 by lazy {
        Rotation3(DoubleArray(9) { rotationMatrix[it].toDouble() })
    }

    /** Where the rear camera points, in true-north horizontal coordinates. */
    val cameraDirection: Horizontal
        get() = toTrueNorth(displayToWorld(Vec3(0.0, 0.0, -1.0)))

    /**
     * Tilt of the horizon in the viewfinder, in degrees: 0 means the screen's up axis points at
     * the zenith, positive means the phone is rolled clockwise.
     */
    val rollDeg: Double
        get() = Math.toDegrees(kotlin.math.atan2(rotationMatrix[6].toDouble(), rotationMatrix[7].toDouble()))

    /** Rotates a display-frame vector into the magnetic-north world frame. */
    fun displayToWorld(v: Vec3): Vec3 {
        val r = rotationMatrix
        return Vec3(
            r[0] * v.x + r[1] * v.y + r[2] * v.z,
            r[3] * v.x + r[4] * v.y + r[5] * v.z,
            r[6] * v.x + r[7] * v.y + r[8] * v.z,
        )
    }

    /** Rotates a world-frame vector back into the display frame (the matrix is orthonormal). */
    fun worldToDisplay(v: Vec3): Vec3 {
        val r = rotationMatrix
        return Vec3(
            r[0] * v.x + r[3] * v.y + r[6] * v.z,
            r[1] * v.x + r[4] * v.y + r[7] * v.z,
            r[2] * v.x + r[5] * v.y + r[8] * v.z,
        )
    }

    /** Magnetic-frame vector → true-north, calibrated horizontal coordinates. */
    fun toTrueNorth(worldVector: Vec3): Horizontal =
        Horizontal.fromVector(trueFromMagnetic.apply(worldVector))

    /** True-north horizontal coordinates → magnetic-frame unit vector. Inverse of [toTrueNorth]. */
    fun toMagneticVector(horizontal: Horizontal): Vec3 =
        trueFromMagnetic.inverseApply(horizontal.toVector())

    /**
     * Where the camera points with the calibration deliberately ignored. The calibration solvers
     * need this: fitting a correction against directions that already carry one would just fit it
     * against itself.
     */
    val uncalibratedCameraDirection: Horizontal
        get() = Horizontal.fromVector(
            Rotation3.aboutZenith(magneticDeclinationDeg)
                .apply(displayToWorld(Vec3(0.0, 0.0, -1.0)))
        )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeviceAttitude) return false
        return rotationMatrix.contentEquals(other.rotationMatrix) &&
            magneticDeclinationDeg == other.magneticDeclinationDeg &&
            accuracy == other.accuracy &&
            timestampMs == other.timestampMs &&
            correction == other.correction &&
            source == other.source &&
            fieldMicroTesla == other.fieldMicroTesla &&
            expectedFieldMicroTesla == other.expectedFieldMicroTesla &&
            headingHeld == other.headingHeld
    }

    override fun hashCode(): Int {
        var result = rotationMatrix.contentHashCode()
        result = 31 * result + magneticDeclinationDeg.hashCode()
        result = 31 * result + accuracy
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + correction.hashCode()
        result = 31 * result + source.hashCode()
        result = 31 * result + (fieldMicroTesla?.hashCode() ?: 0)
        result = 31 * result + (expectedFieldMicroTesla?.hashCode() ?: 0)
        result = 31 * result + headingHeld.hashCode()
        return result
    }

    companion object {
        /** Identity attitude: phone flat on its back, screen up, top pointing magnetic north. */
        val IDENTITY = DeviceAttitude(
            rotationMatrix = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            magneticDeclinationDeg = 0.0,
            accuracy = android.hardware.SensorManager.SENSOR_STATUS_UNRELIABLE,
            timestampMs = 0L,
        )
    }
}
