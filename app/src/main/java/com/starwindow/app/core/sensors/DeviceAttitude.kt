package com.starwindow.app.core.sensors

import com.starwindow.app.core.astro.Angles
import com.starwindow.app.core.astro.Horizontal
import com.starwindow.app.core.astro.Vec3

/**
 * The phone's orientation, already remapped to the *display* frame:
 * x = screen right, y = screen up, z = out of the screen towards the user.
 * The rear camera therefore looks along -z.
 *
 * [rotationMatrix] is row-major and maps display-frame vectors into the world frame
 * (x = east, y = **magnetic** north, z = up). Use [magneticDeclinationDeg] to get to true north —
 * the helpers below already do.
 */
data class DeviceAttitude(
    val rotationMatrix: FloatArray,
    val magneticDeclinationDeg: Double,
    val accuracy: Int,
    val timestampMs: Long,
) {
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

    /** Magnetic-frame vector → true-north horizontal coordinates. */
    fun toTrueNorth(worldVector: Vec3): Horizontal {
        val magnetic = Horizontal.fromVector(worldVector)
        return Horizontal(
            azimuthDeg = Angles.normalizeDeg(magnetic.azimuthDeg + magneticDeclinationDeg),
            altitudeDeg = magnetic.altitudeDeg,
        )
    }

    /** True-north horizontal coordinates → magnetic-frame unit vector. */
    fun toMagneticVector(horizontal: Horizontal): Vec3 =
        Horizontal(
            azimuthDeg = horizontal.azimuthDeg - magneticDeclinationDeg,
            altitudeDeg = horizontal.altitudeDeg,
        ).toVector()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeviceAttitude) return false
        return rotationMatrix.contentEquals(other.rotationMatrix) &&
            magneticDeclinationDeg == other.magneticDeclinationDeg &&
            accuracy == other.accuracy &&
            timestampMs == other.timestampMs
    }

    override fun hashCode(): Int {
        var result = rotationMatrix.contentHashCode()
        result = 31 * result + magneticDeclinationDeg.hashCode()
        result = 31 * result + accuracy
        result = 31 * result + timestampMs.hashCode()
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
