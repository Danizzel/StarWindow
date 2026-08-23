package com.starwindow.app.core.astro

/**
 * Precession of the equinoxes: the bridge from a catalogue's J2000 coordinates to where a star
 * actually stands today.
 *
 * The Earth's axis wobbles like a slowing top, one turn in about 25 800 years. That is slow, but
 * the app's reference frame is fixed at **J2000** while the sky is not: the whole equatorial grid
 * has rotated by roughly **50.3 arcseconds per year** since then, which by the mid-2020s adds up to
 * about **0.37°** — around two thirds of the width of the full Moon, and six or seven pixels on a
 * phone viewfinder.
 *
 * That was worth ignoring while the app's pointing error was several degrees. It is not worth
 * ignoring once the compass is calibrated, and it is actively harmful there: a star-pattern
 * calibration aims the crosshair at a *catalogue* position, so an uncorrected precession does not
 * merely offset the display — it gets absorbed into the measured compass correction and quietly
 * poisons every direction the app reports afterwards.
 *
 * The angles are the IAU 2006 (P03) equatorial precession series, the same lineage Stellarium
 * uses. Applied to a direction the composition is
 *
 * ```
 * v_date = Rz(z_A) · Ry(−θ_A) · Rz(ζ_A) · v_J2000
 * ```
 *
 * which is the classical three-angle form written as a rotation of the vector rather than of the
 * axes — kept as a matrix so it can be inverted, composed and checked as a rotation.
 *
 * **Not** included, because on a phone they are noise: nutation (≤ 9″ ≈ 0.0025°), annual aberration
 * (≤ 20.5″ ≈ 0.0057°) and stellar proper motion (below 1″ per year for all but a handful of stars,
 * none of them in this catalogue's bright end). Together they stay under a hundredth of a degree,
 * two orders of magnitude below what the sensor can resolve; refraction, which is *not* negligible,
 * is applied separately in [CoordinateTransforms.apparentHorizontalAtLst].
 */
class Precession private constructor(
    /** Julian centuries from J2000 to the moment this was built for. */
    val julianCenturies: Double,
    /** J2000 → mean equator and equinox of date, as a rotation of direction vectors. */
    val rotation: Rotation3,
) {

    /** How far the frame has turned since J2000, in degrees. Zero for [NONE]. */
    val angleDeg: Double get() = rotation.angleDeg()

    val yearsSinceJ2000: Double get() = julianCenturies * 100.0

    /** A catalogue (J2000) position, brought to the equator and equinox of this moment. */
    fun toDate(j2000: Equatorial): Equatorial =
        Equatorial.fromVector(rotation.apply(j2000.toVector()))

    /** The inverse: an of-date position expressed back in the catalogue's J2000 frame. */
    fun toJ2000(ofDate: Equatorial): Equatorial =
        Equatorial.fromVector(rotation.inverseApply(ofDate.toVector()))

    companion object {

        /** No precession at all — the J2000 frame taken at face value. */
        val NONE = Precession(0.0, Rotation3.IDENTITY)

        /**
         * Precession from J2000 to the given moment.
         *
         * Cheap enough to build per frame (a handful of multiplications and one matrix product),
         * but it changes by 0.0001″ a day, so building it once per search is the sensible pattern.
         */
        fun forEpoch(epochMillis: Long): Precession {
            val t = AstroTime.julianCenturies(AstroTime.julianDate(epochMillis))
            return forJulianCenturies(t)
        }

        /** Same, from Julian centuries since J2000 — the form the tests drive. */
        fun forJulianCenturies(t: Double): Precession {
            if (t == 0.0) return NONE

            // IAU 2006 (Capitaine et al. 2003), arcseconds.
            val zeta = arcsecToRad(
                2.650545 + t * (2306.083227 + t * (0.2988499 + t * (0.01801828 +
                    t * (-0.000005971 + t * -0.0000003173))))
            )
            val z = arcsecToRad(
                -2.650545 + t * (2306.077181 + t * (1.0927348 + t * (0.01826837 +
                    t * (-0.000028596 + t * -0.0000002904))))
            )
            val theta = arcsecToRad(
                t * (2004.191903 + t * (-0.4294934 + t * (-0.04182264 +
                    t * (-0.000007089 + t * -0.0000001274))))
            )

            // Rz(z) · Ry(−θ) · Rz(ζ), the rightmost applied first.
            val rotation = Rotation3.fromRotationVector(Vec3(0.0, 0.0, z)) *
                Rotation3.fromRotationVector(Vec3(0.0, -theta, 0.0)) *
                Rotation3.fromRotationVector(Vec3(0.0, 0.0, zeta))

            return Precession(t, rotation)
        }

        private fun arcsecToRad(arcsec: Double): Double = Math.toRadians(arcsec / 3600.0)
    }
}
