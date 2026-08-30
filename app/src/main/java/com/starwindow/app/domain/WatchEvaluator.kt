package com.starwindow.app.domain

import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.data.planning.WatchedObject
import com.starwindow.app.data.planning.WeatherDemand
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Eine Nacht, in der ein vorgemerktes Objekt tatsächlich zu fotografieren ist.
 *
 * Enthält beide Rechnungen, aus denen der Treffer entstanden ist — die astronomische [night] und
 * die meteorologische [outlook] —, damit die Meldung sagen kann, *warum* sie kommt. „M31 steht gut"
 * ist eine Behauptung; „4,2 h ab 22:10, bis 62° hoch, Vorhersage geeignet" ist eine Auskunft, die
 * sich prüfen lässt.
 */
data class WatchHit(
    val watch: WatchedObject,
    val date: LocalDate,
    val night: ObservationNight,
    val outlook: NightOutlook,
    /** Der Abschnitt, in dem das Objekt hoch steht **und** das Wetter mitspielt. */
    val goodFromMillis: Long,
    val goodToMillis: Long,
) {
    val goodMillis: Long get() = goodToMillis - goodFromMillis

    val goodHours: Double get() = goodMillis / 3_600_000.0

    /** Wie hoch das Objekt in diesem Abschnitt höchstens steht. */
    val bestAltitudeDeg: Double get() = night.bestAltitudeDeg

    /**
     * Die Zeile für die Benachrichtigung.
     *
     * Zeitpunkt zuerst, denn das ist die Handlung: Wer das liest, will wissen, wann er draußen sein
     * muss. Höhe und Wetterurteil folgen als Begründung.
     */
    fun headline(zone: ZoneId): String = buildString {
        append(NightOutlook.hours(goodHours))
        append(" ab ").append(clock(goodFromMillis, zone))
        append(" · bis ").append(bestAltitudeDeg.roundToInt()).append("° hoch")
    }

    fun detail(zone: ZoneId): String = buildString {
        append("Gutes Fenster: ")
        append(clock(goodFromMillis, zone)).append(" bis ").append(clock(goodToMillis, zone))
        append("\nHöchster Stand ").append(bestAltitudeDeg.roundToInt()).append("°")
        val moon = night.moonIlluminationPercent.roundToInt()
        if (night.moonInterferes) append(", Mond ").append(moon).append(" % und über dem Horizont")
        append("\nPrognose: ").append(outlook.headline)
        append("\n").append(outlook.source)
    }

    private fun clock(millis: Long, zone: ZoneId): String =
        clockFormat.format(Instant.ofEpochMilli(millis).atZone(zone))

    private companion object {
        val clockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN)
    }
}

/**
 * Prüft, ob eine vorgemerkte Bedingung eingetreten ist.
 *
 * **Zwei Rechnungen, die sich überlagern müssen.** [ObservationPlanner] sagt, wann das Objekt hoch
 * genug und der Himmel dunkel ist; [AstroWeather] sagt, wann die Nacht fotografisch etwas hergibt.
 * Beides einzeln zu prüfen wäre der naheliegende und falsche Weg: Ein Objekt, das von 20 bis 23 Uhr
 * hoch steht, und eine Wolkenlücke von 2 bis 5 Uhr ergeben zusammen eine Nacht, in der beide
 * Bedingungen erfüllt sind und trotzdem nichts zu holen ist. Gezählt wird deshalb die
 * **Überschneidung** — und genau ihre Länge ist die Zahl, die in der Meldung steht.
 *
 * Ohne Vorhersage gibt es keinen Treffer. Das ist Absicht und nicht Vorsicht: Eine Meldung „M31
 * steht heute gut" schickt jemanden mit fünfzehn Kilo Ausrüstung vor die Tür. Wenn die App nicht
 * weiß, ob es klar wird, sagt sie nichts und fragt später noch einmal.
 */
object WatchEvaluator {

    /**
     * @param outlook die Wetterlage derselben Nacht, oder null wenn keine vorliegt.
     */
    fun evaluate(
        watch: WatchedObject,
        observer: ObserverLocation,
        zone: ZoneId,
        date: LocalDate,
        outlook: NightOutlook?,
    ): WatchHit? {
        if (!watch.mayNotifyFor(date)) return null
        if (outlook == null || !accepts(watch.weatherDemand, outlook)) return null

        val night = ObservationPlanner.nightFor(
            positionJ2000 = watch.position,
            observer = observer,
            zone = zone,
            date = date,
            minAltitudeDeg = watch.minAltitudeDeg,
        )
        if (!night.isWorthwhile) return null
        val from = night.usableFromMillis ?: return null
        val to = night.usableToMillis ?: return null

        val window = bestOverlap(outlook.night.hours, from, to) ?: return null
        if (window.last - window.first < watch.minUsableMillis) return null

        return WatchHit(
            watch = watch,
            date = date,
            night = night,
            outlook = outlook,
            goodFromMillis = window.first,
            goodToMillis = window.last,
        )
    }

    /** Dieselbe Prüfung für die ganze Merkliste, die lohnendste Nacht zuerst. */
    fun evaluateAll(
        watched: List<WatchedObject>,
        observer: ObserverLocation,
        zone: ZoneId,
        date: LocalDate,
        outlook: NightOutlook?,
    ): List<WatchHit> = watched
        .mapNotNull { evaluate(it, observer, zone, date, outlook) }
        .sortedByDescending { it.goodMillis }

    private fun accepts(demand: WeatherDemand, outlook: NightOutlook): Boolean = when (demand) {
        WeatherDemand.GOOD_ONLY -> outlook.verdict == NightVerdict.GOOD
        WeatherDemand.ALSO_PARTLY ->
            outlook.verdict == NightVerdict.GOOD || outlook.verdict == NightVerdict.PARTLY
    }

    /**
     * Der längste Abschnitt zwischen [fromMillis] und [toMillis], in dem die Bewertung durchgehend
     * über [AstroNight.USABLE_SCORE] liegt.
     *
     * Zwischen zwei Stützstellen wird verlangt, dass **beide** brauchbar sind, wie in
     * [AstroWeather]: Das ist die einzige Auswertung, die auch dann noch stimmt, wenn ein Modell im
     * hinteren Teil des Laufs nur alle drei Stunden liefert.
     */
    private fun bestOverlap(
        hours: List<AstroHour>,
        fromMillis: Long,
        toMillis: Long,
    ): LongRange? {
        if (hours.size < 2 || toMillis <= fromMillis) return null
        val segments = ArrayList<LongRange>()
        for (i in 0 until hours.size - 1) {
            val a = hours[i]
            val b = hours[i + 1]
            if (a.score < AstroNight.USABLE_SCORE || b.score < AstroNight.USABLE_SCORE) continue
            val start = maxOf(a.millis, fromMillis)
            val end = minOf(b.millis, toMillis)
            if (end <= start) continue
            val last = segments.lastOrNull()
            if (last != null && start <= last.last) {
                segments[segments.lastIndex] = last.first..maxOf(last.last, end)
            } else {
                segments += start..end
            }
        }
        return segments.maxByOrNull { it.last - it.first }
    }
}
