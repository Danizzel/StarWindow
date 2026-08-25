package com.starwindow.app

import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.data.weather.OpenMeteo
import com.starwindow.app.data.weather.WeatherForecast
import com.starwindow.app.data.weather.WeatherModel
import com.starwindow.app.data.weather.WeatherHour
import com.starwindow.app.data.weather.WeatherPlace
import com.starwindow.app.domain.AstroNight
import com.starwindow.app.domain.AstroWeather
import com.starwindow.app.domain.BortleScale
import com.starwindow.app.domain.NightLimiter
import com.starwindow.app.domain.NightVerdict
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val zurich = WeatherPlace(
    name = "Zürich",
    region = "Zürich",
    country = "Schweiz",
    latitudeDeg = 47.3769,
    longitudeDeg = 8.5417,
    elevationM = 429.0,
    timezoneId = "Europe/Zurich",
    population = 341_730,
)

/** Eine Stunde mit lauter harmlosen Werten; die Tests setzen jeweils nur das Interessante. */
private fun hour(
    millis: Long = 0L,
    cloud: Double? = 0.0,
    temperature: Double? = 5.0,
    dewPoint: Double? = -2.0,
    humidity: Double? = 60.0,
    wind: Double? = 5.0,
    gusts: Double? = 10.0,
    precipitation: Double? = 0.0,
) = WeatherHour(
    millis = millis,
    cloudTotalPercent = cloud,
    cloudLowPercent = null,
    cloudMidPercent = null,
    cloudHighPercent = null,
    temperatureC = temperature,
    dewPointC = dewPoint,
    humidityPercent = humidity,
    windSpeedKmh = wind,
    windGustsKmh = gusts,
    precipitationMm = precipitation,
    pressureHpa = 1013.0,
    jetStreamKmh = 40.0,
)

class WeatherHourTest {

    @Test
    fun `the dew spread is the difference between temperature and dew point`() {
        assertEquals(7.0, hour(temperature = 5.0, dewPoint = -2.0).dewSpreadK!!, 1e-9)
        assertNull(hour(temperature = null).dewSpreadK)
    }

    @Test
    fun `a missing total falls back to the densest layer`() {
        val layered = hour(cloud = null).copy(
            cloudLowPercent = 10.0,
            cloudMidPercent = 70.0,
            cloudHighPercent = 40.0,
        )
        assertEquals(70.0, layered.effectiveCloudPercent!!, 1e-9)
    }

    @Test
    fun `without any cloud figure there is none`() {
        assertNull(hour(cloud = null).effectiveCloudPercent)
    }
}

class AstroWeatherRatingTest {

    @Test
    fun `darkness counts fully only below minus eighteen degrees`() {
        assertEquals(1.0, AstroWeather.darknessFactor(-20.0), 1e-9)
        assertEquals(1.0, AstroWeather.darknessFactor(-18.0), 1e-9)
        assertEquals(0.55, AstroWeather.darknessFactor(-12.0), 1e-9)
        assertEquals(0.15, AstroWeather.darknessFactor(-6.0), 1e-9)
        assertEquals(0.0, AstroWeather.darknessFactor(5.0), 1e-9)
    }

    @Test
    fun `darkness never grows as the sun rises`() {
        var previous = 1.0
        var altitude = -20.0
        while (altitude <= 10.0) {
            val value = AstroWeather.darknessFactor(altitude)
            assertTrue(value <= previous + 1e-9, "bei $altitude° ging es wieder aufwärts")
            previous = value
            altitude += 0.25
        }
    }

    @Test
    fun `half cover costs more than half`() {
        assertEquals(1.0, AstroWeather.cloudFactor(0.0), 1e-9)
        assertEquals(0.0, AstroWeather.cloudFactor(100.0), 1e-9)
        assertTrue(AstroWeather.cloudFactor(50.0) < 0.5)
        // Ohne Angabe wird nicht gestraft — die Zahl fehlt, sie ist nicht schlecht.
        assertEquals(1.0, AstroWeather.cloudFactor(null), 1e-9)
    }

    @Test
    fun `the moon only matters above the horizon`() {
        assertEquals(1.0, AstroWeather.moonFactor(-5.0, 1.0), 1e-9)
        assertEquals(1.0, AstroWeather.moonFactor(60.0, 0.0), 1e-9)

        val fullHigh = AstroWeather.moonFactor(60.0, 1.0)
        val fullLow = AstroWeather.moonFactor(5.0, 1.0)
        val halfHigh = AstroWeather.moonFactor(60.0, 0.5)

        assertTrue(fullHigh < fullLow, "hoch stehender Vollmond muss mehr stören")
        assertTrue(fullHigh < halfHigh, "Vollmond muss mehr stören als Halbmond")
        assertTrue(fullHigh >= 0.15)
    }

    @Test
    fun `rain ends the night regardless of everything else`() {
        val rating = AstroWeather.rate(
            hour = hour(cloud = 0.0, precipitation = 0.4),
            sunAltitudeDeg = -30.0,
            moonAltitudeDeg = -20.0,
            moonFraction = 0.0,
        )
        assertEquals(0, rating.score)
        assertEquals(NightLimiter.PRECIPITATION, rating.limiter)
    }

    @Test
    fun `a perfect hour scores a hundred and names no limit`() {
        val rating = AstroWeather.rate(
            hour = hour(cloud = 0.0),
            sunAltitudeDeg = -30.0,
            moonAltitudeDeg = -20.0,
            moonFraction = 0.0,
        )
        assertEquals(100, rating.score)
        assertEquals(NightLimiter.NONE, rating.limiter)
    }

    @Test
    fun `the limiter names the worst of the factors`() {
        val cloudy = AstroWeather.rate(hour(cloud = 80.0), -30.0, -20.0, 0.0)
        assertEquals(NightLimiter.CLOUDS, cloudy.limiter)

        val moonlit = AstroWeather.rate(hour(cloud = 0.0), -30.0, 70.0, 1.0)
        assertEquals(NightLimiter.MOON, moonlit.limiter)

        val twilight = AstroWeather.rate(hour(cloud = 0.0), -8.0, -20.0, 0.0)
        assertEquals(NightLimiter.TWILIGHT, twilight.limiter)

        val damp = AstroWeather.rate(
            hour(cloud = 0.0, temperature = 4.0, dewPoint = 3.5, humidity = 98.0),
            -30.0,
            -20.0,
            0.0,
        )
        assertEquals(NightLimiter.DEW, damp.limiter)

        val stormy = AstroWeather.rate(hour(cloud = 0.0, gusts = 55.0), -30.0, -20.0, 0.0)
        assertEquals(NightLimiter.WIND, stormy.limiter)
    }

    @Test
    fun `gusts count, and the plain wind stands in when they are missing`() {
        assertEquals(0.6, AstroWeather.windFactor(hour(gusts = 55.0)), 1e-9)
        assertEquals(1.0, AstroWeather.windFactor(hour(gusts = null, wind = 12.0)), 1e-9)
        assertEquals(0.7, AstroWeather.windFactor(hour(gusts = null, wind = 40.0)), 1e-9)
    }
}

class AstroNightTest {

    private val zone: ZoneId = ZoneId.of("Europe/Zurich")
    private val location: ObserverLocation = zurich.toObserverLocation()

    /**
     * Eine Vorhersage über [days] Tage, jede Stunde derselbe Bedeckungsgrad.
     *
     * Der Startpunkt liegt bewusst auf Mitternacht des Vortages, damit die erste Nacht vollständig
     * abgedeckt ist — genau wie bei einem echten Lauf, der am Modelltag null Uhr beginnt.
     */
    private fun forecast(
        cloudPercent: Double,
        days: Int = 4,
        from: LocalDate = LocalDate.of(2024, 11, 10),
        place: WeatherPlace = zurich,
    ): WeatherForecast {
        val start = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val hours = (0 until days * 24).map { index ->
            hour(millis = start + index * 3_600_000L, cloud = cloudPercent)
        }
        return WeatherForecast(
            place = place,
            zone = zone,
            model = WeatherModel.ECMWF_IFS,
            hours = hours,
            fetchedAtMillis = start,
        )
    }

    private fun nowAt(date: LocalDate, hour: Int): Long =
        date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `a clear November night is fit for photography`() {
        val nights = AstroWeather.nights(
            forecast = forecast(cloudPercent = 5.0),
            location = location,
            nowMillis = nowAt(LocalDate.of(2024, 11, 10), 20),
        )

        val tonight = nights.first()
        assertEquals(LocalDate.of(2024, 11, 10), tonight.date)
        assertEquals(NightVerdict.GOOD, tonight.verdict)
        assertTrue(tonight.clearDarkMillis > 8 * 3_600_000L)
        // Nicht 100: fünf Prozent Bewölkung kosten schon etwas, und der Mond steht Mitte November
        // einen Teil der Nacht über dem Horizont.
        assertTrue(tonight.bestScore >= 85, "beste Stunde erreichte nur ${tonight.bestScore} %")
    }

    @Test
    fun `an overcast night is not`() {
        val nights = AstroWeather.nights(
            forecast = forecast(cloudPercent = 95.0),
            location = location,
            nowMillis = nowAt(LocalDate.of(2024, 11, 10), 20),
        )

        val tonight = nights.first()
        assertEquals(NightVerdict.POOR, tonight.verdict)
        assertEquals(0L, tonight.clearDarkMillis)
        assertTrue(tonight.bestScore < 10)
    }

    @Test
    fun `before dawn the current night is still yesterday's`() {
        val nights = AstroWeather.nights(
            forecast = forecast(cloudPercent = 5.0),
            location = location,
            // Zwei Uhr nachts — wer da hinschaut, steht draußen und meint diese Nacht.
            nowMillis = nowAt(LocalDate.of(2024, 11, 11), 2),
        )
        assertEquals(LocalDate.of(2024, 11, 10), nights.first().date)
    }

    @Test
    fun `after breakfast it is the coming evening`() {
        val nights = AstroWeather.nights(
            forecast = forecast(cloudPercent = 5.0),
            location = location,
            nowMillis = nowAt(LocalDate.of(2024, 11, 11), 9),
        )
        assertEquals(LocalDate.of(2024, 11, 11), nights.first().date)
    }

    @Test
    fun `the list stops where the model run stops`() {
        val nights = AstroWeather.nights(
            forecast = forecast(cloudPercent = 5.0, days = 3),
            location = location,
            nowMillis = nowAt(LocalDate.of(2024, 11, 10), 20),
            maxNights = 15,
        )
        // Drei Tage Vorhersage tragen die Abende des 10., 11. und 12. — der 13. beginnt nicht mehr.
        assertEquals(3, nights.size)
    }

    @Test
    fun `the best hour is the best one inside the darkness`() {
        val night = AstroWeather.nights(
            forecast = forecast(cloudPercent = 5.0),
            location = location,
            nowMillis = nowAt(LocalDate.of(2024, 11, 10), 20),
        ).first()

        val best = night.bestHour!!
        assertTrue(night.times.isDark(best.millis), "die beste Stunde muss in der Dunkelheit liegen")
        assertTrue(best.score >= AstroNight.USABLE_SCORE)
    }

    /**
     * Der Haken zählt Wolken — aber eine wolkenlose Vollmondnacht darf nicht kommentarlos als
     * geeignet durchgehen. Vollmond war am 25. Januar 2024 um 17:54 UTC.
     */
    @Test
    fun `a cloudless full moon night keeps its tick but is flagged`() {
        val night = AstroWeather.nights(
            forecast = forecast(cloudPercent = 0.0, from = LocalDate.of(2024, 1, 25)),
            location = location,
            nowMillis = nowAt(LocalDate.of(2024, 1, 25), 20),
        ).first()

        assertEquals(NightVerdict.GOOD, night.verdict)
        assertTrue(night.clearDarkMillis > 8 * 3_600_000L)
        assertTrue(night.moonSpoilsTheNight, "der Vollmond muss angemerkt werden")
        assertEquals(NightLimiter.MOON, night.bestHour!!.limiter)
    }

    /** Dieselbe Nacht zwei Wochen später, bei Neumond, ist ungestört. */
    @Test
    fun `a cloudless new moon night is not flagged`() {
        val night = AstroWeather.nights(
            forecast = forecast(cloudPercent = 0.0, from = LocalDate.of(2024, 2, 9)),
            location = location,
            nowMillis = nowAt(LocalDate.of(2024, 2, 9), 20),
        ).first()

        assertEquals(NightVerdict.GOOD, night.verdict)
        assertTrue(!night.moonSpoilsTheNight)
        assertTrue(night.usableDarkMillis > 8 * 3_600_000L)
    }

    /**
     * Nördlich von rund 48,5° wird es zur Sonnenwende überhaupt nicht mehr astronomisch dunkel.
     * Berlin liegt darüber, Zürich knapp darunter — deshalb steht hier ausdrücklich Berlin.
     */
    @Test
    fun `a midsummer night in Berlin has no darkness at all`() {
        val berlin = zurich.copy(
            name = "Berlin",
            latitudeDeg = 52.52,
            longitudeDeg = 13.405,
            timezoneId = "Europe/Berlin",
        )
        val night = AstroWeather.nights(
            forecast = forecast(cloudPercent = 0.0, from = LocalDate.of(2024, 6, 20), place = berlin),
            location = berlin.toObserverLocation(),
            nowMillis = nowAt(LocalDate.of(2024, 6, 20), 20),
        ).first()

        assertEquals(NightVerdict.NO_DARKNESS, night.verdict)
        assertEquals(0L, night.clearDarkMillis)
    }

    /** Zürich dagegen bekommt zur Sonnenwende noch ein knappes Fenster echter Dunkelheit. */
    @Test
    fun `Zurich keeps a sliver of darkness at midsummer`() {
        val night = AstroWeather.nights(
            forecast = forecast(cloudPercent = 0.0, from = LocalDate.of(2024, 6, 20)),
            location = location,
            nowMillis = nowAt(LocalDate.of(2024, 6, 20), 20),
        ).first()

        // Die Sonne sinkt auf 47,4° Breite zur Sonnenwende auf -19,2° und damit gut ein Grad unter
        // die Schwelle; das ergibt knapp zwei Stunden echter Dunkelheit.
        val minutes = night.times.darkDurationMillis / 60_000
        assertTrue(minutes in 90..140, "es waren $minutes min")
    }
}

class BortleScaleTest {

    @Test
    fun `the class boundaries follow the brightness ratio`() {
        assertEquals(1, BortleScale.levelFor(0.005))
        assertEquals(3, BortleScale.levelFor(0.03))
        assertEquals(5, BortleScale.levelFor(0.2))
        assertEquals(7, BortleScale.levelFor(1.0))
        assertEquals(9, BortleScale.levelFor(5.0))
    }

    @Test
    fun `a bigger town means a brighter sky`() {
        val village = BortleScale.estimate(zurich.copy(population = 400))!!
        val town = BortleScale.estimate(zurich.copy(population = 20_000))!!
        val city = BortleScale.estimate(zurich)!!

        assertTrue(village < town, "Dorf $village gegen Kleinstadt $town")
        assertTrue(town < city, "Kleinstadt $town gegen Großstadt $city")
        assertEquals(8, city)
    }

    @Test
    fun `distance makes it darker, monotonically`() {
        var previous = BortleScale.brightnessRatio(300_000, 0.0)
        var distance = 1.0
        while (distance <= 120.0) {
            val ratio = BortleScale.brightnessRatio(300_000, distance)
            assertTrue(ratio <= previous + 1e-12, "bei $distance km wurde es wieder heller")
            previous = ratio
            distance += 1.0
        }
        // Aus der Stadt heraus lohnt sich das Fahren: gut hundert Kilometer sind mehrere Stufen.
        assertTrue(BortleScale.estimate(zurich.copy(populationDistanceKm = 100.0))!! <= 3)
    }

    @Test
    fun `inside the built-up area the distance does not matter yet`() {
        val centre = BortleScale.brightnessRatio(341_730, 0.0)
        val edge = BortleScale.brightnessRatio(341_730, BortleScale.settlementRadiusKm(341_730))
        assertEquals(centre, edge, 1e-12)
    }

    @Test
    fun `a set value beats the estimate`() {
        val manual = zurich.copy(bortleOverride = 4)
        assertEquals(4, BortleScale.resolve(manual))
        assertEquals(8, BortleScale.resolve(zurich))
        assertNull(BortleScale.resolve(zurich.copy(population = null)))
    }

    @Test
    fun `every class carries its description`() {
        assertEquals(9, BortleScale.CLASSES.size)
        BortleScale.CLASSES.forEachIndexed { index, bortle ->
            assertEquals(index + 1, bortle.level)
            assertTrue(bortle.title.isNotBlank() && bortle.consequence.isNotBlank())
        }
        assertEquals(1, BortleScale.forLevel(-3).level)
        assertEquals(9, BortleScale.forLevel(42).level)
    }
}

class OpenMeteoTest {

    @Test
    fun `the forecast names its model and asks for what it can carry`() {
        val urls = OpenMeteo.forecastUrls(47.3769, 8.5417, WeatherModel.ECMWF_IFS)
        assertEquals(2, urls.size)

        val primary = urls.first()
        assertTrue(primary.startsWith(OpenMeteo.FORECAST_URL))
        assertTrue(primary.contains("latitude=47.37690"))
        assertTrue(primary.contains("longitude=8.54170"))
        assertTrue(primary.contains("models=ecmwf_ifs025"))
        assertTrue(primary.contains("cloud_cover_high"))
        assertTrue(primary.contains("wind_speed_250hPa"))
        assertTrue(primary.contains("timeformat=unixtime"))
        assertTrue(primary.contains("forecast_days=15"))

        // Die Rückfallebene lässt genau die Größen weg, die ein Lauf auch mal nicht führt.
        val fallback = urls.last()
        assertTrue(fallback.contains("cloud_cover"))
        assertTrue(!fallback.contains("wind_speed_250hPa"))
    }

    @Test
    fun `a short range model is not asked for two weeks`() {
        val url = OpenMeteo.forecastUrls(47.3769, 8.5417, WeatherModel.ICON_D2).first()
        assertTrue(url.contains("models=icon_d2"))
        assertTrue(url.contains("forecast_days=3"), url)
    }

    @Test
    fun `every model has a plausible definition`() {
        // Die Liste ist von Hand gepflegt; ein Tippfehler in einem Bezeichner fällt sonst erst
        // draußen im Feld auf, wo kein Netz ist.
        WeatherModel.entries.forEach { model ->
            assertTrue(model.id.isNotBlank() && model.id == model.id.lowercase(), model.name)
            assertTrue(model.metaPath.isNotBlank(), model.name)
            assertTrue(model.resolutionKm in 0.5..50.0, "${model.name}: ${model.resolutionKm}")
            assertTrue(model.requestDays in 1..OpenMeteo.MAX_FORECAST_DAYS, model.name)
            assertTrue(model.about.length > 40, model.name)
        }
        // Feinstes Gitter zuerst, und ECMWF als einziges globales am Ende.
        assertEquals(WeatherModel.ORDERED.sortedBy { it.resolutionKm }, WeatherModel.ORDERED)
        assertEquals(WeatherModel.ECMWF_IFS, WeatherModel.ORDERED.last())
        assertEquals(WeatherModel.ECMWF_IFS, WeatherModel.DEFAULT)
    }

    @Test
    fun `the metadata says when the model last ran and where it is valid`() {
        val status = OpenMeteo.parseModelStatus(SAMPLE_META, WeatherModel.ICON_EU)

        assertEquals(WeatherModel.ICON_EU, status.model)
        assertEquals(1_756_134_000_000L, status.runMillis)
        assertEquals(1_756_144_680_000L, status.availableSinceMillis)
        assertEquals(1_756_245_600_000L, status.dataEndMillis)
        assertEquals(10_800, status.updateIntervalSeconds)
        assertEquals(3_600, status.stepSeconds)

        // Das Gebiet steckt als BBOX mitten in der WKT-Beschreibung.
        val bounds = assertNotNull(status.bounds)
        assertEquals(29.5, bounds.minLatitudeDeg, 1e-9)
        assertEquals(-23.5, bounds.minLongitudeDeg, 1e-9)
        assertEquals(70.5, bounds.maxLatitudeDeg, 1e-9)
        assertEquals(62.5, bounds.maxLongitudeDeg, 1e-9)

        assertTrue(status.covers(47.38, 8.54), "Zürich liegt im ICON-EU-Gebiet")
        assertTrue(!status.covers(40.7, -74.0), "New York liegt es nicht")
    }

    @Test
    fun `the run age is measured from its publication`() {
        val status = OpenMeteo.parseModelStatus(SAMPLE_META, WeatherModel.ICON_EU)
        val published = 1_756_144_680_000L

        assertEquals(0L, status.ageMillis(published))
        assertEquals(90 * 60_000L, status.ageMillis(published + 90 * 60_000L))
        // Nie negativ: eine Uhr, die nachgeht, darf keinen Lauf aus der Zukunft melden.
        assertEquals(0L, status.ageMillis(published - 60_000L))

        assertTrue(!status.isOverdue(published + 3 * 3_600_000L))
        assertTrue(status.isOverdue(published + 9 * 3_600_000L))
    }

    @Test
    fun `metadata without a usable area claims nothing`() {
        val status = OpenMeteo.parseModelStatus("""{"last_run_initialisation_time":1756134000}""", WeatherModel.ICON_D2)
        assertNull(status.bounds)
        assertNull(status.availableSinceMillis)
        assertTrue(status.covers(0.0, 0.0), "ohne Grenzen wird nichts behauptet")
    }

    @Test
    fun `empty hours at the end of a short model are cut off`() {
        // ICON-D2 auf sieben Tage angefragt: knapp drei Tage Werte, danach Nullen. Blieben die
        // stehen, würde die Nachtbewertung sie als „keine Wolken gemeldet" lesen.
        val forecast = OpenMeteo.parseForecast(SAMPLE_PADDED, zurich, WeatherModel.ICON_D2)

        assertEquals(2, forecast.hours.size)
        assertEquals(1_731_272_400_000L, forecast.lastMillis)
    }

    @Test
    fun `a run that is entirely outside its area yields nothing`() {
        val forecast = OpenMeteo.parseForecast(SAMPLE_ALL_NULL, zurich, WeatherModel.AROME_HD)
        assertTrue(forecast.isEmpty)
    }

    @Test
    fun `place names are encoded`() {
        val url = OpenMeteo.geocodingUrl("Sankt Moritz")
        assertTrue(url.startsWith(OpenMeteo.GEOCODING_URL))
        assertTrue(url.contains("name=Sankt+Moritz") || url.contains("name=Sankt%20Moritz"))
        assertTrue(url.contains("language=de"))
    }

    @Test
    fun `hourly columns are matched to their timestamps`() {
        val forecast = OpenMeteo.parseForecast(SAMPLE_FORECAST, zurich, WeatherModel.ICON_EU)

        assertEquals(3, forecast.hours.size)
        assertEquals(1_731_268_800_000L, forecast.hours[0].millis)
        assertEquals(1_731_276_000_000L, forecast.hours[2].millis)

        val first = forecast.hours.first()
        assertEquals(12.0, first.cloudTotalPercent!!, 1e-9)
        assertEquals(4.0, first.cloudLowPercent!!, 1e-9)
        assertEquals(6.3, first.temperatureC!!, 1e-9)
        assertEquals(2.1, first.dewPointC!!, 1e-9)
        assertEquals(9.4, first.windSpeedKmh!!, 1e-9)
        assertEquals(88.0, first.jetStreamKmh!!, 1e-9)

        // Der Zeitstempel ist UTC, die Anzeige läuft über die Zone des Ortes.
        assertEquals(ZoneId.of("Europe/Zurich"), forecast.zone)
        assertEquals(WeatherModel.ICON_EU, forecast.model)
    }

    @Test
    fun `a gap in a column stays a gap`() {
        val forecast = OpenMeteo.parseForecast(SAMPLE_FORECAST, zurich, WeatherModel.ICON_EU)
        assertNull(forecast.hours[1].cloudTotalPercent)
        assertNull(forecast.hours[2].windGustsKmh)
    }

    @Test
    fun `a column the run does not carry is simply absent`() {
        val forecast = OpenMeteo.parseForecast(SAMPLE_WITHOUT_JETSTREAM, zurich, WeatherModel.ICON_D2)
        assertTrue(forecast.hours.all { it.jetStreamKmh == null })
        assertEquals(2, forecast.hours.size)
    }

    @Test
    fun `the elevation of the model grid point wins over the typed one`() {
        val forecast = OpenMeteo.parseForecast(SAMPLE_FORECAST, zurich.copy(elevationM = 0.0), WeatherModel.ICON_EU)
        assertEquals(432.0, forecast.place.elevationM, 1e-9)
    }

    @Test
    fun `a rejected request carries its reason`() {
        val failure = assertFailsWith<IllegalStateException> {
            OpenMeteo.parseForecast(
                """{"error":true,"reason":"Cannot initialize WeatherVariable from invalid String"}""",
                zurich,
                WeatherModel.ICON_EU,
            )
        }
        assertTrue(failure.message!!.contains("WeatherVariable"))
    }

    @Test
    fun `place hits keep name, country and population`() {
        val places = OpenMeteo.parsePlaces(SAMPLE_PLACES)

        assertEquals(2, places.size)
        assertEquals("Zürich", places[0].name)
        assertEquals("Schweiz", places[0].country)
        assertEquals(341_730, places[0].population)
        assertEquals(ZoneId.of("Europe/Zurich"), places[0].zone)
        assertEquals("Zürich, Schweiz", places[0].label)

        // Ohne Treffer ist die Liste leer, das ist kein Fehler.
        assertTrue(OpenMeteo.parsePlaces("""{"generationtime_ms":0.3}""").isEmpty())
    }

    @Test
    fun `an unknown timezone falls back on the offset`() {
        val forecast = OpenMeteo.parseForecast(SAMPLE_UNKNOWN_ZONE, zurich.copy(timezoneId = null), WeatherModel.ECMWF_IFS)
        assertEquals(ZoneOffset.ofTotalSeconds(3600), forecast.zone)
    }

    private companion object {
        /** Drei Stunden mit je einer Lücke — so sieht eine echte Antwort mit fehlendem Wert aus. */
        const val SAMPLE_FORECAST = """
        {
          "latitude": 47.375, "longitude": 8.5, "elevation": 432.0,
          "utc_offset_seconds": 3600, "timezone": "Europe/Zurich",
          "hourly": {
            "time": [1731268800, 1731272400, 1731276000],
            "cloud_cover": [12, null, 88],
            "cloud_cover_low": [4, 10, 80],
            "cloud_cover_mid": [0, 5, 40],
            "cloud_cover_high": [8, 12, 20],
            "temperature_2m": [6.3, 5.8, 5.1],
            "dew_point_2m": [2.1, 2.0, 2.4],
            "relative_humidity_2m": [74, 77, 83],
            "precipitation": [0.0, 0.0, 0.3],
            "wind_speed_10m": [9.4, 8.1, 11.0],
            "wind_gusts_10m": [21.0, 19.0, null],
            "pressure_msl": [1018.2, 1017.9, 1017.1],
            "wind_speed_250hPa": [88, 91, 96]
          }
        }
        """

        const val SAMPLE_WITHOUT_JETSTREAM = """
        {
          "elevation": 432.0, "utc_offset_seconds": 3600, "timezone": "Europe/Zurich",
          "hourly": {
            "time": [1731268800, 1731272400],
            "cloud_cover": [12, 30],
            "temperature_2m": [6.3, 5.8],
            "dew_point_2m": [2.1, 2.0],
            "precipitation": [0.0, 0.0],
            "wind_speed_10m": [9.4, 8.1]
          }
        }
        """

        /** Wie ein Kurzfristmodell antwortet, das über die eigene Reichweite hinaus gefragt wurde. */
        const val SAMPLE_PADDED = """
        {
          "elevation": 432.0, "utc_offset_seconds": 3600, "timezone": "Europe/Zurich",
          "hourly": {
            "time": [1731268800, 1731272400, 1731276000, 1731279600],
            "cloud_cover": [12, 30, null, null],
            "temperature_2m": [6.3, 5.8, null, null]
          }
        }
        """

        /** Ein Ort außerhalb des Modellgebiets: Zeitstempel ja, Werte nein. */
        const val SAMPLE_ALL_NULL = """
        {
          "utc_offset_seconds": 3600, "timezone": "Europe/Zurich",
          "hourly": {
            "time": [1731268800, 1731272400],
            "cloud_cover": [null, null],
            "cloud_cover_low": [null, null]
          }
        }
        """

        /**
         * Gekürzte `meta.json` von ICON-EU, Aufbau unverändert vom Dienst übernommen — inklusive
         * der WKT-Beschreibung, in der die Gebietsgrenzen stecken.
         */
        const val SAMPLE_META = """
        {
          "chunk_time_length": 193,
          "crs_wkt": "GEOGCRS[\"WGS 84\",\n    DATUM[\"World Geodetic System 1984\"],\n    USAGE[\n        SCOPE[\"grid\"],\n        BBOX[29.5,-23.5,70.5,62.5]]]",
          "data_end_time": 1756245600,
          "last_run_availability_time": 1756144680,
          "last_run_initialisation_time": 1756134000,
          "last_run_modification_time": 1756144680,
          "temporal_resolution_seconds": 3600,
          "update_interval_seconds": 10800
        }
        """

        const val SAMPLE_UNKNOWN_ZONE = """
        {
          "utc_offset_seconds": 3600, "timezone": "Nirgendwo/Irgendwo",
          "hourly": { "time": [1731268800], "cloud_cover": [12] }
        }
        """

        const val SAMPLE_PLACES = """
        {
          "results": [
            {
              "id": 2657896, "name": "Zürich", "latitude": 47.36667, "longitude": 8.55,
              "elevation": 429.0, "feature_code": "PPLA", "country_code": "CH",
              "admin1": "Zürich", "country": "Schweiz", "population": 341730,
              "timezone": "Europe/Zurich"
            },
            {
              "id": 6295538, "name": "Zürich (Kreis 11)", "latitude": 47.42, "longitude": 8.53,
              "country": "Schweiz", "timezone": "Europe/Zurich"
            }
          ]
        }
        """
    }
}
