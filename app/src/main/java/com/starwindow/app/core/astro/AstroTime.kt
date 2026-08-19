package com.starwindow.app.core.astro

/**
 * Time conversions needed to tie a direction on the local sky to a direction on the celestial
 * sphere. UT1 and UTC are treated as identical, which costs at most ~0.9 s (≈ 0.004° of sky
 * rotation) and is irrelevant next to the pointing error of a phone.
 */
object AstroTime {

    const val J2000_JD = 2451545.0
    private const val UNIX_EPOCH_JD = 2440587.5
    private const val MILLIS_PER_DAY = 86_400_000.0

    /** Julian Date for a Unix timestamp in milliseconds. */
    fun julianDate(epochMillis: Long): Double = epochMillis / MILLIS_PER_DAY + UNIX_EPOCH_JD

    /** Inverse of [julianDate]. */
    fun epochMillis(julianDate: Double): Long =
        ((julianDate - UNIX_EPOCH_JD) * MILLIS_PER_DAY).toLong()

    /** Julian centuries since J2000.0. */
    fun julianCenturies(julianDate: Double): Double = (julianDate - J2000_JD) / 36525.0

    /**
     * Greenwich Mean Sidereal Time in degrees (IAU 1982 series, good to well below an arcsecond
     * for the coming decades).
     */
    fun gmstDeg(epochMillis: Long): Double {
        val jd = julianDate(epochMillis)
        val d = jd - J2000_JD
        val t = d / 36525.0
        val gmst = 280.46061837 +
            360.98564736629 * d +
            0.000387933 * t * t -
            t * t * t / 38_710_000.0
        return Angles.normalizeDeg(gmst)
    }

    /** Local Mean Sidereal Time in degrees for an east-positive longitude. */
    fun lstDeg(epochMillis: Long, longitudeDeg: Double): Double =
        Angles.normalizeDeg(gmstDeg(epochMillis) + longitudeDeg)

    /** Hour angle in degrees, wrapped to (-180, 180]; negative means "still rising". */
    fun hourAngleDeg(lstDeg: Double, raDeg: Double): Double = Angles.wrapDeg180(lstDeg - raDeg)

    /**
     * How long one degree of hour angle takes in sidereal-corrected wall-clock seconds.
     * The sky turns 360.98…° per solar day, so a degree costs slightly less than 4 minutes.
     */
    const val SECONDS_PER_DEGREE_OF_HOUR_ANGLE = 86_400.0 / 360.98564736629
}
