package com.starwindow.app.domain

import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.core.astro.SolarEphemeris
import com.starwindow.app.core.astro.Twilight

/**
 * Die dunklen Abschnitte eines Zeitraums, einmal gerechnet.
 *
 * Die Durchgangsrechnung sagte bisher, **wann** etwas durch ein Fenster zieht, und schwieg darüber,
 * ob es zu dieser Zeit überhaupt dunkel ist. Für einen Katalog von 22.528 Einträgen über zwölf
 * Stunden ist das die halbe Antwort: Ein Durchgang um 14 Uhr steht in derselben Liste wie einer um
 * zwei, sieht dort genauso aus und nützt nichts.
 *
 * Einmal für den ganzen Suchzeitraum statt je Durchgang, und das ist der Grund für eine eigene
 * Klasse. Eine Suche liefert mehrere hundert Objekte mit je bis zu vier Durchgängen; jeden davon
 * einzeln gegen die Sonne zu rechnen wäre tausendfach dieselbe Rechnung. Die Sonne kennt den
 * Katalog nicht — ihre Bahn hängt nur am Zeitraum und am Ort.
 *
 * Abgetastet und nicht gelöst: Die Dämmerungsgrenzen ließen sich schließen (so macht es
 * [com.starwindow.app.domain.ObservationPlanner] für ein ganzes Jahr), aber hier geht es um einen
 * Zeitraum von Stunden, in dem es höchstens zwei Übergänge gibt, und die Abtastung ist zehn Zeilen
 * statt hundert.
 */
class DarkSpans private constructor(
    /** Die dunklen Abschnitte, aufsteigend und ohne Überlappung. */
    val spans: List<LongRange>,
) {

    val isEmpty: Boolean get() = spans.isEmpty()

    /** Wie viel von [fromMillis] bis [toMillis] in der Dunkelheit liegt. */
    fun overlap(fromMillis: Long, toMillis: Long): Long {
        if (toMillis <= fromMillis) return 0L
        var total = 0L
        for (span in spans) {
            val start = maxOf(span.first, fromMillis)
            val end = minOf(span.last, toMillis)
            if (end > start) total += end - start
        }
        return total
    }

    /** True, wenn dieser Zeitpunkt in der Dunkelheit liegt. */
    fun contains(millis: Long): Boolean = spans.any { millis in it }

    companion object {
        /** Astronomische Dunkelheit: die Sonne mehr als 18° unter dem Horizont. */
        const val DARK_SUN_ALTITUDE_DEG = ObservationPlanner.ASTRONOMICAL_TWILIGHT_DEG

        /**
         * Fünf Minuten — dieselbe Schrittweite, mit der [Twilight] arbeitet.
         *
         * Die Sonne läuft in fünf Minuten gut ein Achtelgrad; der Fehler an der Dämmerungsgrenze
         * bleibt damit unter zwei Minuten, und bei einer Grenze, die selbst eine Konvention ist,
         * wäre alles Genauere Scheingenauigkeit.
         */
        const val STEP_MILLIS = 5 * 60_000L

        fun over(
            fromMillis: Long,
            toMillis: Long,
            observer: ObserverLocation,
            thresholdDeg: Double = DARK_SUN_ALTITUDE_DEG,
        ): DarkSpans {
            if (toMillis <= fromMillis) return DarkSpans(emptyList())

            val spans = ArrayList<LongRange>(2)
            var spanStart: Long? = null
            var time = fromMillis
            while (true) {
                val sampled = minOf(time, toMillis)
                val dark = SolarEphemeris.altitudeDeg(sampled, observer) <= thresholdDeg
                if (dark && spanStart == null) {
                    spanStart = sampled
                } else if (!dark && spanStart != null) {
                    spans += spanStart..sampled
                    spanStart = null
                }
                if (sampled >= toMillis) break
                time += STEP_MILLIS
            }
            spanStart?.let { spans += it..toMillis }
            return DarkSpans(spans)
        }

        /** Für Aufrufer ohne Ort — dann wird über die Dunkelheit nichts behauptet. */
        val NONE = DarkSpans(emptyList())
    }
}
