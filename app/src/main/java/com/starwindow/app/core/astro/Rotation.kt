package com.starwindow.app.core.astro

import kotlin.math.cos
import kotlin.math.sin

/**
 * A rotation in three dimensions, stored as a row-major 3x3 matrix.
 *
 * Used for the calibration correction, which is a small rotation applied in the world frame after
 * the magnetic declination. Kept separate from the sensor's `FloatArray` matrices on purpose: those
 * come from the platform in float precision and in the display frame, these are ours and in double.
 */
data class Rotation3(private val m: DoubleArray) {

    init {
        require(m.size == 9) { "A 3x3 rotation needs exactly nine components" }
    }

    operator fun get(row: Int, column: Int): Double = m[row * 3 + column]

    fun apply(v: Vec3): Vec3 = Vec3(
        m[0] * v.x + m[1] * v.y + m[2] * v.z,
        m[3] * v.x + m[4] * v.y + m[5] * v.z,
        m[6] * v.x + m[7] * v.y + m[8] * v.z,
    )

    /** Inverse rotation. Rotation matrices are orthonormal, so this is just the transpose. */
    fun inverseApply(v: Vec3): Vec3 = Vec3(
        m[0] * v.x + m[3] * v.y + m[6] * v.z,
        m[1] * v.x + m[4] * v.y + m[7] * v.z,
        m[2] * v.x + m[5] * v.y + m[8] * v.z,
    )

    fun inverse(): Rotation3 = Rotation3(
        doubleArrayOf(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8])
    )

    /** `this` applied after [other]: `(this * other) · v == this(other(v))`. */
    operator fun times(other: Rotation3): Rotation3 {
        val r = DoubleArray(9)
        for (row in 0..2) {
            for (col in 0..2) {
                var sum = 0.0
                for (k in 0..2) sum += m[row * 3 + k] * other.m[k * 3 + col]
                r[row * 3 + col] = sum
            }
        }
        return Rotation3(r)
    }

    /** Rotation angle in degrees; 0 means "does nothing". */
    fun angleDeg(): Double {
        val trace = (m[0] + m[4] + m[8]).coerceIn(-1.0, 3.0)
        return Math.toDegrees(kotlin.math.acos(((trace - 1.0) / 2.0).coerceIn(-1.0, 1.0)))
    }

    val isIdentity: Boolean get() = angleDeg() < 1e-9

    override fun equals(other: Any?): Boolean =
        this === other || (other is Rotation3 && m.contentEquals(other.m))

    override fun hashCode(): Int = m.contentHashCode()

    companion object {
        val IDENTITY = Rotation3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))

        /**
         * Rotation from an axis-angle vector (Rodrigues' formula). The vector's direction is the
         * axis, its length the angle in radians. This is the exponential map that turns the
         * calibration solver's incremental steps into an actual rotation.
         */
        fun fromRotationVector(omega: Vec3): Rotation3 {
            val theta = omega.length
            if (theta < 1e-12) return IDENTITY
            val k = omega * (1.0 / theta)
            val c = cos(theta)
            val s = sin(theta)
            val t = 1.0 - c
            return Rotation3(
                doubleArrayOf(
                    t * k.x * k.x + c, t * k.x * k.y - s * k.z, t * k.x * k.z + s * k.y,
                    t * k.x * k.y + s * k.z, t * k.y * k.y + c, t * k.y * k.z - s * k.x,
                    t * k.x * k.z - s * k.y, t * k.y * k.z + s * k.x, t * k.z * k.z + c,
                )
            )
        }

        /**
         * Rotation about the zenith by [deg], i.e. a pure heading change: every direction keeps its
         * altitude and gains [deg] of azimuth. This is what a compass offset — and the magnetic
         * declination — actually is.
         */
        fun aboutZenith(deg: Double): Rotation3 {
            val a = Math.toRadians(deg)
            val c = cos(a)
            val s = sin(a)
            // In the ENU frame azimuth is atan2(east, north), so a positive heading change mixes
            // the two horizontal components like this. Derived, not guessed — see the unit tests.
            return Rotation3(doubleArrayOf(c, s, 0.0, -s, c, 0.0, 0.0, 0.0, 1.0))
        }

        /** The shortest rotation taking [from] onto [to]. Both are treated as directions. */
        fun between(from: Vec3, to: Vec3): Rotation3 {
            val a = from.normalized()
            val b = to.normalized()
            val axis = a cross b
            val sinTheta = axis.length
            val cosTheta = a dot b
            if (sinTheta < 1e-12) {
                // Parallel, or antiparallel — for our small corrections the former is the only
                // realistic case and needs no rotation at all.
                return if (cosTheta > 0) IDENTITY else fromRotationVector(anyPerpendicular(a) * Math.PI)
            }
            val theta = kotlin.math.atan2(sinTheta, cosTheta)
            return fromRotationVector(axis * (theta / sinTheta))
        }

        private fun anyPerpendicular(v: Vec3): Vec3 {
            val candidate = if (kotlin.math.abs(v.x) < 0.9) Vec3(1.0, 0.0, 0.0) else Vec3(0.0, 1.0, 0.0)
            return (v cross candidate).normalized()
        }
    }
}
