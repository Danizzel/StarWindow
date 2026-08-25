package com.starwindow.app.core.calibration

import com.starwindow.app.core.astro.ObserverLocation

/**
 * How much a stored attitude calibration is still worth.
 *
 * A compass correction is not a property of the phone, it is a property of the *place* the phone
 * was standing in. It absorbs the iron in the balcony railing, the car on the drive and the rebar
 * in the wall, and none of that travels with the user. Carrying an old correction to a new place
 * silently is worse than having none: the app would report a wrong direction with full confidence,
 * which is exactly the failure a calibration was supposed to prevent.
 *
 * Time matters for the same reason at a slower rate — the correction was fitted against one
 * sighting, and nothing re-checks it afterwards.
 *
 * This is deliberately separate from the live magnetic-disturbance detection in the sensor layer:
 * that one catches "there is iron next to you *right now*", this one catches "what you measured no
 * longer describes where you are". Neither subsumes the other.
 */
enum class CalibrationTrust(val label: String, val explanation: String) {

    NONE(
        "nicht kalibriert",
        "Die App arbeitet mit der rohen Kompassrichtung.",
    ),

    FRESH(
        "frisch",
        "Hier und vor Kurzem gemessen.",
    ),

    AGING(
        "älter",
        "Die Messung liegt einige Tage zurück. Sie gilt weiter, ist aber seither von nichts " +
            "gegengeprüft worden.",
    ),

    STALE(
        "veraltet",
        "Die Messung ist über zwei Wochen alt. Vor einer Aufnahme, auf die es ankommt, lieber neu " +
            "kalibrieren.",
    ),

    MOVED(
        "anderer Ort",
        "Gemessen wurde woanders. Der Kompassfehler kommt zum großen Teil aus der Umgebung – " +
            "Eisen, Fahrzeuge, Gebäude – und die ist hier eine andere.",
    );

    /** True when the correction is still being applied but the user should be told about it. */
    val isQuestionable: Boolean get() = this == AGING || this == STALE || this == MOVED

    /** True when the user should be nudged to measure again before it matters. */
    val needsRemeasuring: Boolean get() = this == STALE || this == MOVED
}

/**
 * Judges a stored calibration against where and when it is about to be used.
 *
 * Distance beats age: a correction measured this morning three towns away is worth less than one
 * measured a week ago on this balcony.
 */
fun Calibration.trustAt(observer: ObserverLocation?, nowMillis: Long): CalibrationTrust {
    if (!hasAttitudeCorrection) return CalibrationTrust.NONE

    val measuredAt = attitudeLocation()
    if (observer != null && measuredAt != null &&
        observer.distanceKmTo(measuredAt) > MOVED_KM
    ) {
        return CalibrationTrust.MOVED
    }

    val ageMillis = nowMillis - attitudeUpdatedAtMillis
    return when {
        attitudeUpdatedAtMillis <= 0L -> CalibrationTrust.AGING
        ageMillis > STALE_MILLIS -> CalibrationTrust.STALE
        ageMillis > AGING_MILLIS -> CalibrationTrust.AGING
        else -> CalibrationTrust.FRESH
    }
}

/** Where the attitude correction was measured, if that was recorded. */
fun Calibration.attitudeLocation(): ObserverLocation? {
    val latitude = attitudeLatitudeDeg ?: return null
    val longitude = attitudeLongitudeDeg ?: return null
    return ObserverLocation(latitude, longitude)
}

/**
 * Far enough that the geomagnetic model itself differs measurably, and far enough that "this is a
 * different spot" is certainly true. Local iron invalidates a correction over metres rather than
 * kilometres — but warning every time the user steps off the balcony would train them to ignore the
 * warning, and the live disturbance detection already covers that case.
 */
private const val MOVED_KM = 5.0

private const val AGING_MILLIS = 2L * 24 * 3_600_000
private const val STALE_MILLIS = 14L * 24 * 3_600_000
