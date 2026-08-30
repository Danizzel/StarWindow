package com.starwindow.app.domain

import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.core.astro.Twilight
import com.starwindow.app.data.weather.WeatherForecast
import com.starwindow.app.data.weather.WeatherModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Was die Vorhersage über **eine bestimmte Nacht** sagt.
 *
 * [AstroWeather] rechnet die Nacht aus, hier wird sie auf die eine Zeile eingedampft, die in eine
 * Benachrichtigung und auf eine Kalenderkachel passt — und, genauso wichtig, auf die Angabe, wie
 * alt die Aussage ist. Eine Prognose ohne ihr Alter ist die gefährlichere Zahl: Wer „bedeckt" liest
 * und nicht sieht, dass sie von vorgestern stammt, bleibt zu Hause, während draußen der Himmel
 * aufreißt.
 */
data class NightOutlook(
    val date: LocalDate,
    val night: AstroNight,
    val model: WeatherModel,
    /** Wann der Lauf geholt wurde — nicht, wann er gerechnet wurde. */
    val fetchedAtMillis: Long,
    val nowMillis: Long = System.currentTimeMillis(),
) {
    val verdict: NightVerdict get() = night.verdict

    /** Stunden in astronomischer Dunkelheit mit wenig Bewölkung und ohne Niederschlag. */
    val clearHours: Double get() = night.clearDarkMillis / 3_600_000.0

    /** Stunden, die die Gesamtbewertung überstehen — Mond und Bodenbedingungen eingerechnet. */
    val usableHours: Double get() = night.usableDarkMillis / 3_600_000.0

    /** Mittlere Bedeckung während der Dunkelheit. */
    val cloudPercent: Double? get() = night.meanCloudDarkPercent

    val ageMillis: Long get() = (nowMillis - fetchedAtMillis).coerceAtLeast(0L)

    /** Ab hier ist die Aussage alt genug, dass ihr Alter mitgenannt gehört. */
    val isStale: Boolean get() = ageMillis > STALE_AFTER_MILLIS

    /** Eine Nacht, für die es sich lohnt aufzubauen. */
    val isPromising: Boolean get() = verdict == NightVerdict.GOOD

    /** Eine Nacht, vor der gewarnt gehört. */
    val isDiscouraging: Boolean
        get() = verdict == NightVerdict.POOR || verdict == NightVerdict.NO_DARKNESS

    /**
     * Die Kurzfassung: Urteil und die eine Zahl, die es trägt.
     *
     * Bei einer guten Nacht sind das die klaren Stunden — daran entscheidet sich, ob der Aufbau
     * lohnt. Bei einer schlechten die Bedeckung, denn „ungeeignet" allein klingt nach einer
     * Meinung, „ungeeignet, 92 % bedeckt" nach einem Grund.
     */
    val headline: String
        get() = when (verdict) {
            NightVerdict.NO_DARKNESS -> "In dieser Nacht wird es nicht richtig dunkel"
            NightVerdict.UNKNOWN -> "keine Vorhersagedaten für diese Nacht"
            NightVerdict.GOOD, NightVerdict.PARTLY -> buildString {
                append(verdict.label)
                append(" · ").append(hours(clearHours)).append(" klar")
                if (moonMatters) append(" · Mond ").append(moonPercentText)
            }
            NightVerdict.POOR -> buildString {
                append(verdict.label)
                val cloud = cloudPercent
                if (cloud != null) append(" · ").append(cloud.roundToInt()).append(" % bedeckt")
                if (night.clearDarkMillis > 0) {
                    append(", nur ").append(hours(clearHours)).append(" klar")
                }
            }
        }

    /**
     * Woher die Aussage kommt und wie alt sie ist.
     *
     * Steht immer dabei, nicht nur wenn sie alt ist: Das Modell ist Teil der Aussage — ICON-D2 und
     * ECMWF sind sich über denselben Abend regelmäßig uneinig, und wer weiß, welches gesprochen
     * hat, liest die Zeile anders.
     */
    val source: String get() = "${model.label}, abgerufen ${relativeTime(fetchedAtMillis)}"

    /** Die ganze Auskunft, wie sie in eine Benachrichtigung geht. */
    val fullLine: String get() = "Prognose: $headline ($source)"

    /** Der Mond stört genug, dass er in der Kurzfassung erwähnt gehört. */
    private val moonMatters: Boolean
        get() = night.moonSpoilsTheNight ||
            ((night.maxMoonAltitudeDeg ?: -1.0) > 10.0 &&
                (night.moonIllumination?.percent ?: 0.0) >= 60.0)

    private val moonPercentText: String
        get() = "${(night.moonIllumination?.percent ?: 0.0).roundToInt()} %"

    private fun relativeTime(millis: Long): String {
        val then = Instant.ofEpochMilli(millis).atZone(night.zone)
        val today = Instant.ofEpochMilli(nowMillis).atZone(night.zone).toLocalDate()
        val days = today.toEpochDay() - then.toLocalDate().toEpochDay()
        val clock = clockFormat.format(then)
        return when {
            // Negativ kann nur werden, wer die Uhr zurückstellt; „in -1 Tagen" wäre die
            // schlechteste Art, davon zu erzählen.
            days <= 0L -> "heute $clock"
            days == 1L -> "gestern $clock"
            else -> "vor $days Tagen"
        }
    }

    companion object {
        /** Sechs Stunden: die kurzfristigen Modelle rechnen in diesem Takt oder schneller. */
        const val STALE_AFTER_MILLIS = 6 * 3_600_000L

        private val clockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        /** `4,2 h` — mit Komma, wie es im Rest der Oberfläche steht. */
        fun hours(value: Double): String = "%.1f h".format(Locale.GERMAN, value)
    }
}

/**
 * Bringt eine Vorhersage und einen Kalendertag zusammen.
 *
 * Der Grund für eine eigene Stelle statt eines Aufrufs von [AstroWeather.nightOf] überall: Die
 * Antwort „diese Nacht liegt außerhalb des Laufs" muss von „diese Nacht wird schlecht" getrennt
 * bleiben. Beides sähe sonst gleich aus — eine Nacht ohne Stunden bewertet sich als
 * [NightVerdict.UNKNOWN] —, und eine Erinnerung, die einem Termin in drei Wochen „keine Daten"
 * anschreibt, hat nichts gesagt und sieht trotzdem so aus, als hätte sie nachgesehen.
 */
object NightOutlooks {

    /**
     * Die Nacht auf [date], oder null wenn der Lauf sie nicht mehr abdeckt.
     *
     * @param location der Beobachtungsort; er entscheidet über Dämmerung und Mondstand und ist
     *   nicht dasselbe wie der Gitterpunkt, für den das Modell gerechnet hat.
     */
    fun forDate(
        forecast: WeatherForecast,
        location: ObserverLocation,
        date: LocalDate,
        zone: ZoneId = forecast.zone,
        nowMillis: Long = System.currentTimeMillis(),
    ): NightOutlook? {
        if (forecast.isEmpty) return null
        val times = Twilight.nightOf(date, location, zone)
        if (!covers(forecast, times.fromMillis, times.toMillis)) return null

        val night = AstroWeather.nightOf(date, times, zone, forecast, location)
        if (!night.hasData) return null
        return NightOutlook(
            date = date,
            night = night,
            model = forecast.model,
            fetchedAtMillis = forecast.fetchedAtMillis,
            nowMillis = nowMillis,
        )
    }

    /** Dasselbe für mehrere Termine; Nächte außerhalb des Laufs fehlen in der Karte. */
    fun forDates(
        forecast: WeatherForecast,
        location: ObserverLocation,
        dates: Collection<LocalDate>,
        zone: ZoneId = forecast.zone,
        nowMillis: Long = System.currentTimeMillis(),
    ): Map<LocalDate, NightOutlook> = dates.distinct()
        .mapNotNull { date -> forDate(forecast, location, date, zone, nowMillis)?.let { date to it } }
        .toMap()

    /**
     * Trägt der Lauf über diesen Zeitraum?
     *
     * Verlangt wird nicht, dass die letzte Stunde des Laufs hinter dem Beginn der Nacht liegt,
     * sondern dass überhaupt Stunden **in** der Nacht stehen. Ein Lauf, der mittags endet, deckt
     * den Abend desselben Tages formal ab und weiß über ihn trotzdem nichts.
     */
    private fun covers(forecast: WeatherForecast, fromMillis: Long, toMillis: Long): Boolean =
        forecast.hours.any { it.millis in fromMillis..toMillis }
}
