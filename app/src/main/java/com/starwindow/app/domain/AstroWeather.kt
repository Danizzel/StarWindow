package com.starwindow.app.domain

import com.starwindow.app.core.astro.CoordinateTransforms
import com.starwindow.app.core.astro.LunarEphemeris
import com.starwindow.app.core.astro.MoonIllumination
import com.starwindow.app.core.astro.NightTimes
import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.core.astro.SolarEphemeris
import com.starwindow.app.core.astro.TwilightPhase
import com.starwindow.app.core.astro.Twilight
import com.starwindow.app.data.weather.WeatherForecast
import com.starwindow.app.data.weather.WeatherHour
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Was eine Stunde am stärksten verdirbt. */
enum class NightLimiter(val label: String) {
    NONE("frei"),
    TWILIGHT("Dämmerung"),
    CLOUDS("Bewölkung"),
    MOON("Mondlicht"),
    PRECIPITATION("Niederschlag"),
    DEW("Taubeschlag"),
    WIND("Wind"),
}

/**
 * Eine Stunde, aus Wetter und Himmelsstand zusammengerechnet.
 *
 * Die Wetterwerte bleiben als [weather] unverändert daneben stehen: Die Bewertung ist eine Meinung,
 * die Bewölkung eine Messgröße, und wer die Zahl selbst lesen will, soll sie finden.
 */
data class AstroHour(
    val millis: Long,
    val weather: WeatherHour,
    val sunAltitudeDeg: Double,
    val moonAltitudeDeg: Double,
    val moonIllumination: MoonIllumination,
    /** 0 bis 100 — wie brauchbar diese Stunde für Astrofotografie ist. */
    val score: Int,
    val limiter: NightLimiter,
) {
    val phase: TwilightPhase get() = TwilightPhase.forSunAltitude(sunAltitudeDeg)

    /** Bedeckung in Prozent; fehlt die Gesamtangabe, tritt die dichteste Schicht an ihre Stelle. */
    val cloudPercent: Double? get() = weather.effectiveCloudPercent

    val isDark: Boolean get() = sunAltitudeDeg <= -18.0

    val moonUp: Boolean get() = moonAltitudeDeg > 0.0
}

/** Das Urteil über eine ganze Nacht — die Spalte mit dem Haken in der Liste. */
enum class NightVerdict(val label: String) {
    GOOD("geeignet"),
    PARTLY("teilweise"),
    POOR("ungeeignet"),
    NO_DARKNESS("keine Dunkelheit"),
    UNKNOWN("keine Daten"),
}

/**
 * Eine Nacht, von Mittag bis Mittag.
 *
 * [hours] deckt das ganze Fenster ab, damit die Kurve den Abend vor der Dämmerung und den Morgen
 * danach zeigen kann; [darkHours] ist der Teil, über den geurteilt wird.
 */
data class AstroNight(
    /** Das Datum des Abends, nicht des Morgens danach. */
    val date: LocalDate,
    val zone: ZoneId,
    val times: NightTimes,
    val hours: List<AstroHour>,
    /** Dauer in astronomischer Dunkelheit mit wenig Bewölkung und ohne Niederschlag. */
    val clearDarkMillis: Long,
    /** Dauer in astronomischer Dunkelheit, in der die Bewertung mindestens [USABLE_SCORE] erreicht. */
    val usableDarkMillis: Long,
    val bestScore: Int,
    val verdict: NightVerdict,
    /** Bewölkung während der Dunkelheit — Mittel und bestes Loch. */
    val meanCloudDarkPercent: Double?,
    val minCloudDarkPercent: Double?,
    /** Mondbeleuchtung um Mitternacht dieser Nacht. */
    val moonIllumination: MoonIllumination?,
    /** Höchster Mondstand während der Dunkelheit, oder null wenn der Mond unten bleibt. */
    val maxMoonAltitudeDeg: Double?,
) {
    val darkHours: List<AstroHour> get() = hours.filter { times.isDark(it.millis) }

    val hasData: Boolean get() = hours.isNotEmpty()

    /**
     * Die Stunde, auf die es ankommt — die beste innerhalb der Dunkelheit.
     *
     * Die Einzelwerte der Nacht (Temperatur, Wind, Wolkenschichten) werden für genau diese Stunde
     * gezeigt. Ein Mittel über die ganze Nacht wäre die falsche Zahl: Wer aufbaut, tut es für das
     * Fenster, das sich auftut, nicht für den Durchschnitt.
     */
    val bestHour: AstroHour?
        get() = darkHours.maxByOrNull { it.score } ?: hours.maxByOrNull { it.score }

    /**
     * True, wenn die Nacht am Mond scheitert und nicht am Wetter.
     *
     * Das Urteil oben zählt Wolken — so lautet die Frage, und das Wetter ist auch das Einzige, was
     * sich nicht vorher ausrechnen lässt. Der Mond dagegen steht seit Jahrhunderten fest, und eine
     * wolkenlose Vollmondnacht darf nicht kommentarlos als „geeignet" durchgehen.
     */
    val moonSpoilsTheNight: Boolean
        get() = clearDarkMillis > 0 &&
            usableDarkMillis < AstroWeather.PARTLY_NIGHT_MILLIS &&
            bestHour?.limiter == NightLimiter.MOON

    companion object {
        /** Ab hier ist eine Stunde brauchbar — genug für Belichtungsreihen, nicht nur für Schnappschüsse. */
        const val USABLE_SCORE = 50
    }
}

/**
 * Wetter plus Himmelsstand ergibt die Frage, die zählt: **lohnt sich diese Nacht?**
 *
 * Die Bewertung ist ein Produkt aus vier Faktoren — Dunkelheit, Bewölkung, Mond, Bedingungen am
 * Boden. Ein Produkt und keine gewichtete Summe, weil die Größen sich gegenseitig nicht ersetzen
 * können: Eine völlig klare Nacht bei Vollmond in der Dämmerung ist nicht „zu zwei Dritteln gut",
 * sie ist schlecht. Jeder Faktor kann für sich allein alles kippen, und genau das tut ein Produkt.
 *
 * Die Zahlen dahinter sind Erfahrungswerte aus der Praxis, keine Messgrößen. Deshalb wird neben der
 * Bewertung immer auch der Grund genannt ([NightLimiter]) und die Bewölkung roh angezeigt — wer
 * anderer Meinung ist als die Formel, soll das an den Rohdaten sehen können.
 */
object AstroWeather {

    /** Bis zu dieser Bedeckung heißt eine Stunde „klar". */
    const val CLEAR_CLOUD_PERCENT = 30.0

    /** So lange muss es am Stück reichen, damit eine Nacht den Haken bekommt. */
    const val GOOD_NIGHT_MILLIS = 90 * 60_000L

    /** Ab hier lohnt sich wenigstens der Aufbau. */
    const val PARTLY_NIGHT_MILLIS = 45 * 60_000L

    /**
     * Die Nächte, die die Vorhersage abdeckt.
     *
     * Die erste ist die laufende: vor sechs Uhr morgens ist das noch die Nacht von gestern, danach
     * der kommende Abend. Ohne diese Unterscheidung würde die Ansicht um zwei Uhr nachts — also
     * mitten in der Beobachtung — bereits auf den nächsten Abend zeigen.
     */
    fun nights(
        forecast: WeatherForecast,
        location: ObserverLocation,
        nowMillis: Long = System.currentTimeMillis(),
        maxNights: Int = 15,
    ): List<AstroNight> {
        if (forecast.isEmpty) return emptyList()

        val zone = forecast.zone
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val firstDate = if (now.hour < MORNING_CUTOFF_HOUR) {
            now.toLocalDate().minusDays(1)
        } else {
            now.toLocalDate()
        }
        val lastMillis = forecast.lastMillis ?: return emptyList()

        val nights = ArrayList<AstroNight>(maxNights)
        for (offset in 0 until maxNights) {
            val date = firstDate.plusDays(offset.toLong())
            val times = Twilight.nightOf(date, location, zone)
            // Eine Nacht kommt nur in die Liste, wenn die Vorhersage ihren Abend noch trägt; der
            // letzte Lauf endet mittags und würde sonst als leere Nacht auftauchen.
            val eveningMillis = times.sunsetMillis ?: ((times.fromMillis + times.toMillis) / 2)
            if (eveningMillis > lastMillis) break
            nights += nightOf(date, times, zone, forecast, location)
        }
        return nights
    }

    /** Eine einzelne Nacht zusammensetzen. */
    fun nightOf(
        date: LocalDate,
        times: NightTimes,
        zone: ZoneId,
        forecast: WeatherForecast,
        location: ObserverLocation,
    ): AstroNight {
        val hours = forecast.hours
            .filter { it.millis in times.fromMillis..times.toMillis }
            .map { evaluate(it, location) }

        val darkHours = hours.filter { times.isDark(it.millis) }
        val clouds = darkHours.mapNotNull { it.cloudPercent }

        val clearDark = durationWhere(hours, times) { hour ->
            val cloud = hour.cloudPercent
            cloud != null && cloud <= CLEAR_CLOUD_PERCENT && (hour.weather.precipitationMm ?: 0.0) < 0.05
        }
        val usableDark = durationWhere(hours, times) { it.score >= AstroNight.USABLE_SCORE }
        val bestScore = darkHours.maxOfOrNull { it.score } ?: 0

        val midnight = (times.fromMillis + times.toMillis) / 2

        return AstroNight(
            date = date,
            zone = zone,
            times = times,
            hours = hours,
            clearDarkMillis = clearDark,
            usableDarkMillis = usableDark,
            bestScore = bestScore,
            verdict = verdictOf(times, hours, clearDark, clouds),
            meanCloudDarkPercent = clouds.averageOrNull(),
            minCloudDarkPercent = clouds.minOrNull(),
            moonIllumination = if (hours.isEmpty()) null else LunarEphemeris.illuminationAt(midnight),
            maxMoonAltitudeDeg = darkHours.maxOfOrNull { it.moonAltitudeDeg }?.takeIf { it > 0.0 },
        )
    }

    /** Wetterstunde + Himmelsstand + Bewertung. */
    fun evaluate(hour: WeatherHour, location: ObserverLocation): AstroHour {
        val sun = SolarEphemeris.at(hour.millis)
        val moon = LunarEphemeris.at(hour.millis)
        val sunAltitude = CoordinateTransforms
            .equatorialToHorizontal(sun.equatorial, location, hour.millis).altitudeDeg
        val moonAltitude = LunarEphemeris.topocentricAltitudeDeg(moon, location, hour.millis)
        val illumination = LunarEphemeris.illumination(sun, moon)

        val rating = rate(hour, sunAltitude, moonAltitude, illumination.fraction)

        return AstroHour(
            millis = hour.millis,
            weather = hour,
            sunAltitudeDeg = sunAltitude,
            moonAltitudeDeg = moonAltitude,
            moonIllumination = illumination,
            score = rating.score,
            limiter = rating.limiter,
        )
    }

    /** Die vier Faktoren und was am Ende herauskommt. */
    data class Rating(
        val darkness: Double,
        val clouds: Double,
        val moon: Double,
        val conditions: Double,
        val limiter: NightLimiter,
    ) {
        val score: Int get() = (darkness * clouds * moon * conditions * 100).roundToInt().coerceIn(0, 100)
    }

    fun rate(
        hour: WeatherHour,
        sunAltitudeDeg: Double,
        moonAltitudeDeg: Double,
        moonFraction: Double,
    ): Rating {
        val darkness = darknessFactor(sunAltitudeDeg)
        val clouds = cloudFactor(hour.effectiveCloudPercent)
        val moon = moonFactor(moonAltitudeDeg, moonFraction)

        // Niederschlag ist kein Faktor, sondern ein Abbruch: bei Regen kommt die Ausrüstung rein.
        val wet = (hour.precipitationMm ?: 0.0) >= 0.05
        val dew = dewFactor(hour)
        val wind = windFactor(hour)
        val conditions = if (wet) 0.0 else dew * wind

        val limiter = when {
            wet -> NightLimiter.PRECIPITATION
            else -> listOf(
                NightLimiter.TWILIGHT to darkness,
                NightLimiter.CLOUDS to clouds,
                NightLimiter.MOON to moon,
                NightLimiter.DEW to dew,
                NightLimiter.WIND to wind,
            ).minBy { it.second }
                .let { (name, value) -> if (value > 0.92) NightLimiter.NONE else name }
        }

        return Rating(darkness, clouds, moon, conditions, limiter)
    }

    /**
     * Wie dunkel es ist, 0 bis 1.
     *
     * Voll zählt erst die astronomische Dunkelheit. Die nautische Dämmerung ist nicht „halb so
     * gut": Der Himmelshintergrund liegt dort noch deutlich über dem natürlichen Niveau, aber
     * helle Ziele lassen sich schon aufnehmen — daher 0,55 an ihrer dunklen Grenze.
     */
    fun darknessFactor(sunAltitudeDeg: Double): Double = when {
        sunAltitudeDeg <= -18.0 -> 1.0
        sunAltitudeDeg <= -12.0 -> 0.55 + 0.45 * (-12.0 - sunAltitudeDeg) / 6.0
        sunAltitudeDeg <= -6.0 -> 0.15 + 0.40 * (-6.0 - sunAltitudeDeg) / 6.0
        sunAltitudeDeg <= Twilight.HORIZON_DEG ->
            0.15 * (Twilight.HORIZON_DEG - sunAltitudeDeg) / (6.0 + Twilight.HORIZON_DEG)
        else -> 0.0
    }

    /**
     * Wie klar es ist, 0 bis 1.
     *
     * Der Exponent 1,5 ist Absicht: 50 % Bedeckung heißt nicht, dass die Hälfte der Aufnahmen
     * brauchbar ist. Das Ziel steht die halbe Zeit hinter Wolken, die Bilder an den Rändern tragen
     * Schleier, und die Nachführung verliert zwischendurch den Leitstern. Halbe Bedeckung kostet
     * also mehr als die Hälfte.
     */
    fun cloudFactor(cloudPercent: Double?): Double {
        val cloud = cloudPercent ?: return 1.0
        return (1.0 - cloud.coerceIn(0.0, 100.0) / 100.0).pow(1.5)
    }

    /**
     * Wie sehr der Mond stört, 0,15 bis 1.
     *
     * Es zählt nicht die Phase allein, sondern Phase **und** Höhe: Eine Vollmondsichel dicht über
     * dem Horizont ist halb so schlimm wie derselbe Mond im Zenit, weil das Streulicht mit dem
     * Weg durch die Atmosphäre und mit dem beleuchteten Himmelsanteil wächst.
     */
    fun moonFactor(moonAltitudeDeg: Double, illuminatedFraction: Double): Double {
        if (moonAltitudeDeg <= 0.0) return 1.0
        val height = sqrt(sin(Math.toRadians(moonAltitudeDeg.coerceIn(0.0, 90.0))))
        return (1.0 - 0.85 * illuminatedFraction.coerceIn(0.0, 1.0).pow(1.5) * height)
            .coerceIn(0.15, 1.0)
    }

    /** Taubeschlag: unter 2 K Taupunktdifferenz beschlägt die Frontlinse innerhalb einer Stunde. */
    fun dewFactor(hour: WeatherHour): Double {
        val spread = hour.dewSpreadK
        val humidity = hour.humidityPercent
        val bySpread = when {
            spread == null -> 1.0
            spread < 1.0 -> 0.75
            spread < 3.0 -> 0.90
            else -> 1.0
        }
        val byHumidity = if (humidity != null && humidity >= 95.0) 0.85 else 1.0
        return bySpread * byHumidity
    }

    /** Wind: Böen verwackeln die Nachführung, lange vor dem Punkt, an dem etwas umfällt. */
    fun windFactor(hour: WeatherHour): Double {
        val gusts = hour.windGustsKmh
        if (gusts != null) {
            return when {
                gusts > 40.0 -> 0.60
                gusts > 25.0 -> 0.85
                else -> 1.0
            }
        }
        val wind = hour.windSpeedKmh ?: return 1.0
        return when {
            wind > 30.0 -> 0.70
            wind > 20.0 -> 0.90
            else -> 1.0
        }
    }

    private fun verdictOf(
        times: NightTimes,
        hours: List<AstroHour>,
        clearDarkMillis: Long,
        cloudsWhileDark: List<Double>,
    ): NightVerdict = when {
        hours.isEmpty() -> NightVerdict.UNKNOWN
        times.darkDurationMillis == 0L -> NightVerdict.NO_DARKNESS
        clearDarkMillis >= GOOD_NIGHT_MILLIS -> NightVerdict.GOOD
        clearDarkMillis >= PARTLY_NIGHT_MILLIS -> NightVerdict.PARTLY
        (cloudsWhileDark.minOrNull() ?: 100.0) <= 60.0 -> NightVerdict.PARTLY
        else -> NightVerdict.POOR
    }

    /**
     * Wie lange die Bedingung während der astronomischen Dunkelheit gilt.
     *
     * Zwischen zwei Stützstellen wird der Mittelwert geprüft und das Intervall ganz gezählt oder
     * gar nicht. Das ist die einzige Auswertung, die auch dann noch stimmt, wenn das Modell alle
     * drei Stunden statt jede Stunde liefert — was ECMWF im hinteren Teil des Laufs tut.
     */
    private fun durationWhere(
        hours: List<AstroHour>,
        times: NightTimes,
        predicate: (AstroHour) -> Boolean,
    ): Long {
        val darkFrom = times.darkFromMillis ?: return 0L
        val darkTo = times.darkToMillis ?: return 0L
        var total = 0L
        for (i in 0 until hours.size - 1) {
            val from = maxOf(hours[i].millis, darkFrom)
            val to = minOf(hours[i + 1].millis, darkTo)
            if (to <= from) continue
            if (predicate(hours[i]) && predicate(hours[i + 1])) total += to - from
        }
        return total
    }

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()

    /** Vor dieser Stunde gehört der Abend von gestern noch zur laufenden Nacht. */
    private const val MORNING_CUTOFF_HOUR = 6
}
