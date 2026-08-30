package com.starwindow.app.domain

import com.starwindow.app.core.geometry.SkyWindow
import com.starwindow.app.core.sensors.AttitudeFusion

/**
 * How well a saved window can be trusted.
 *
 * A window drawn with an unreliable compass can sit several degrees away from where it was really
 * pointed, and nothing about the stored outline says so. Months later that is invisible: the transit
 * list looks exactly as confident either way. The point of this type is to keep the *quality of the
 * measurement* attached to the measurement, so a result can be read with the right amount of doubt.
 */
enum class AccuracyBand(val label: String) {
    /** Calibrated, or a compass the sensor itself called good. */
    GOOD("gut"),
    FAIR("brauchbar"),
    POOR("fraglich"),
}

data class WindowAccuracy(
    /** Half-width of the plausible pointing error, in degrees. */
    val uncertaintyDeg: Double,
    /**
     * True when [uncertaintyDeg] comes from an actual measurement — the residual of the calibration
     * that was in force. False when it is a conventional band derived from the compass accuracy
     * flag, which Android reports only as high/medium/low without ever quoting an angle.
     */
    val isMeasured: Boolean,
    val band: AccuracyBand,
    /** Short plain-language reason, for the line under the bar. */
    val reason: String,
) {
    /** How the number should be introduced: measured values deserve a firmer word than guesses. */
    val headline: String
        get() = if (isMeasured) {
            "± %.1f°".format(uncertaintyDeg)
        } else {
            "etwa ± %.0f°".format(uncertaintyDeg)
        }
}

/**
 * Estimates how far a saved window might really be from where it was drawn.
 *
 * Two very different sources, and the difference is worth keeping visible:
 *
 *  * a **calibration residual** is a measurement — the app aimed at known references and recorded
 *    how far it missed. That number means something.
 *  * a **compass accuracy flag** is not. Android reports high/medium/low/unreliable and never says
 *    what they are worth in degrees. The values below are the conventional rough bands for a phone
 *    magnetometer, and they are labelled as estimates in the interface for exactly that reason —
 *    quoting "±5,0°" from a flag would be inventing precision.
 */
fun SkyWindow.accuracy(): WindowAccuracy {
    val residual = calibrationResidualDeg
    if (residual != null) {
        // Even a perfect fit cannot beat the hand that held the phone and the smoothing lag.
        val value = maxOf(residual, MEASUREMENT_FLOOR_DEG) + heldPenalty()
        return WindowAccuracy(
            uncertaintyDeg = value,
            isMeasured = true,
            band = if (value <= 2.0) AccuracyBand.GOOD else AccuracyBand.FAIR,
            reason = buildString {
                append("Beim Aufnehmen kalibriert, gemessene Restabweichung %.1f°".format(residual))
                if (headingHeld) append("; der Kompass war dabei gestört")
                append(".")
            },
        )
    }

    val fromFlag = when (compassAccuracy) {
        3 -> 5.0
        2 -> 10.0
        1 -> 20.0
        else -> 30.0
    } + heldPenalty()

    return WindowAccuracy(
        uncertaintyDeg = fromFlag,
        isMeasured = false,
        band = when {
            fromFlag <= 6.0 -> AccuracyBand.GOOD
            fromFlag <= 12.0 -> AccuracyBand.FAIR
            else -> AccuracyBand.POOR
        },
        reason = buildString {
            append("Nicht kalibriert; geschätzt aus der Kompassgüte ")
            append(
                when (compassAccuracy) {
                    3 -> "„hoch“"
                    2 -> "„mittel“"
                    1 -> "„niedrig“"
                    else -> "„unzuverlässig“"
                }
            )
            if (headingHeld) append(", der Kompass war zudem gestört")
            append(".")
        },
    )
}

/**
 * The gyroscope was carrying north on its own, so that direction had been ageing.
 *
 * Charged by the *duration* rather than as a flat penalty: the drift is a rate, and a window saved
 * three seconds into a disturbance is in a completely different position from one saved twenty
 * minutes in. Older windows recorded only the fact and not the duration; those fall back to a flat
 * charge rather than pretending the drift was nothing.
 */
private fun SkyWindow.heldPenalty(): Double = when {
    headingHeldSeconds > 0.0 ->
        maxOf(AttitudeFusion.heldHeadingDriftDeg(headingHeldSeconds), MINIMUM_HELD_PENALTY_DEG)
    headingHeld -> MINIMUM_HELD_PENALTY_DEG
    else -> 0.0
}

private const val MEASUREMENT_FLOOR_DEG = 0.5
private const val MINIMUM_HELD_PENALTY_DEG = 3.0
