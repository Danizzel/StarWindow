package com.starwindow.app

import com.starwindow.app.core.astro.Equatorial
import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.data.catalog.ObjectType
import com.starwindow.app.data.planning.WatchedObject
import com.starwindow.app.data.planning.WeatherDemand
import com.starwindow.app.data.weather.WeatherForecast
import com.starwindow.app.data.weather.WeatherHour
import com.starwindow.app.data.weather.WeatherModel
import com.starwindow.app.data.weather.WeatherPlace
import com.starwindow.app.domain.NightOutlook
import com.starwindow.app.domain.NightOutlooks
import com.starwindow.app.domain.NightVerdict
import com.starwindow.app.domain.ObservationPlanner
import com.starwindow.app.domain.WatchEvaluator
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val zone: ZoneId = ZoneId.of("Europe/Berlin")

private val berlin = WeatherPlace(
    name = "Berlin",
    country = "Deutschland",
    latitudeDeg = 52.52,
    longitudeDeg = 13.405,
    elevationM = 34.0,
    timezoneId = "Europe/Berlin",
)

private val observer = ObserverLocation(
    latitudeDeg = 52.52,
    longitudeDeg = 13.405,
    elevationM = 34.0,
    manual = true,
)

private fun hour(millis: Long, cloud: Double) = WeatherHour(
    millis = millis,
    cloudTotalPercent = cloud,
    temperatureC = 5.0,
    dewPointC = -3.0,
    humidityPercent = 55.0,
    windSpeedKmh = 6.0,
    windGustsKmh = 12.0,
    precipitationMm = 0.0,
    pressureHpa = 1015.0,
)

/**
 * Ein Lauf über [days] Tage ab dem Mittag von [from], stündlich, mit gleichbleibender Bewölkung.
 *
 * Mittags statt mitternachts, weil eine Nacht am Abend beginnt: Ein Lauf, der um Mitternacht
 * anfängt, deckt den ersten Abend nicht ab, und der Test prüfte dann versehentlich die Reichweite
 * statt der Bewertung.
 */
private fun forecast(
    from: LocalDate,
    days: Int,
    cloudPercent: Double,
    fetchedAtMillis: Long = from.atStartOfDay(zone).toInstant().toEpochMilli(),
): WeatherForecast = forecast(from, days, fetchedAtMillis) { cloudPercent }

/** Derselbe Lauf, aber mit einer Bewölkung, die von der Ortszeit abhängt. */
private fun forecast(
    from: LocalDate,
    days: Int,
    fetchedAtMillis: Long = from.atStartOfDay(zone).toInstant().toEpochMilli(),
    cloudAtHour: (Int) -> Double,
): WeatherForecast {
    val start = from.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    val hours = (0 until days * 24).map { index ->
        val millis = start + index * 3_600_000L
        val localHour = java.time.Instant.ofEpochMilli(millis).atZone(zone).hour
        hour(millis, cloudAtHour(localHour))
    }
    return WeatherForecast(
        place = berlin,
        zone = zone,
        model = WeatherModel.ECMWF_IFS,
        hours = hours,
        fetchedAtMillis = fetchedAtMillis,
    )
}

class NightOutlookTest {

    private val night = LocalDate.of(2026, 11, 13)

    @Test
    fun `a clear night is judged good and says how many hours it gives`() {
        val outlook = NightOutlooks.forDate(forecast(night, 3, cloudPercent = 5.0), observer, night, zone)
        assertNotNull(outlook)
        assertEquals(NightVerdict.GOOD, outlook.verdict)
        assertTrue(outlook.clearHours > 2.0, "eine klare Novembernacht in Berlin gibt Stunden her")
        assertTrue(outlook.headline.contains("klar"), outlook.headline)
        assertTrue(outlook.isPromising)
    }

    @Test
    fun `an overcast night is judged poor and names the cloud cover`() {
        val outlook = NightOutlooks.forDate(forecast(night, 3, cloudPercent = 95.0), observer, night, zone)
        assertNotNull(outlook)
        assertEquals(NightVerdict.POOR, outlook.verdict)
        assertTrue(outlook.isDiscouraging)
        assertTrue(outlook.headline.contains("95 % bedeckt"), outlook.headline)
    }

    /**
     * Die Unterscheidung, für die es [NightOutlooks] überhaupt gibt: „außerhalb des Laufs" ist
     * etwas anderes als „schlechte Nacht" und muss auch anders herauskommen.
     */
    @Test
    fun `a night beyond the run yields nothing rather than an empty verdict`() {
        val short = forecast(night, days = 1, cloudPercent = 0.0)
        assertNull(NightOutlooks.forDate(short, observer, night.plusDays(5), zone))
    }

    @Test
    fun `the age of the forecast is spelled out`() {
        val fetched = night.atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
        val run = forecast(night, 3, cloudPercent = 10.0, fetchedAtMillis = fetched)
        val nextMorning = night.plusDays(1).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()

        val outlook = NightOutlooks.forDate(run, observer, night, zone, nowMillis = nextMorning)
        assertNotNull(outlook)
        assertTrue(outlook.source.contains("gestern 18:00"), outlook.source)
        assertTrue(outlook.isStale)
    }

    @Test
    fun `hours are written with a comma`() {
        assertEquals("4,2 h", NightOutlook.hours(4.24))
    }
}

class WatchEvaluatorTest {

    /** M31, ein Ziel, das im November die halbe Nacht hoch steht. */
    private val andromeda = WatchedObject(
        id = "w1",
        objectId = "M31",
        label = "M31 · Andromedagalaxie",
        type = ObjectType.GALAXY,
        raDeg = 10.6847,
        decDeg = 41.269,
    )

    private val night = LocalDate.of(2026, 11, 13)

    private fun outlookFor(cloudPercent: Double, date: LocalDate = night) =
        NightOutlooks.forDate(forecast(date, 3, cloudPercent), observer, date, zone)

    @Test
    fun `a clear night with the object high is a hit`() {
        val hit = WatchEvaluator.evaluate(andromeda, observer, zone, night, outlookFor(5.0))
        assertNotNull(hit)
        assertTrue(hit.goodHours > 1.0, "M31 steht im November lange genug: ${hit.goodHours}")
        assertTrue(hit.goodFromMillis < hit.goodToMillis)
        assertTrue(hit.bestAltitudeDeg > 30.0)
    }

    @Test
    fun `the same night under cloud is no hit`() {
        assertNull(WatchEvaluator.evaluate(andromeda, observer, zone, night, outlookFor(95.0)))
    }

    /**
     * Die Zusage, die die Merkliste gibt, hängt an dieser Zeile: Ohne Vorhersage wird geschwiegen,
     * nicht geraten.
     */
    @Test
    fun `without a forecast there is no hit at all`() {
        assertNull(WatchEvaluator.evaluate(andromeda, observer, zone, night, outlook = null))
    }

    /**
     * Eine Nacht mit **einer Lücke**: bedeckt bis auf eine Stunde um Mitternacht.
     *
     * Genau der Fall, den die beiden Wetterstufen unterscheiden — und der Grund, warum die
     * Merkliste die Überschneidung rechnet statt zwei getrennte Urteile zu vergleichen.
     */
    @Test
    fun `a single clear hour counts only for someone who asked for gaps`() {
        val gap = forecast(night, days = 3) { hour -> if (hour == 23 || hour == 0) 10.0 else 95.0 }
        val outlook = NightOutlooks.forDate(gap, observer, night, zone)
        assertNotNull(outlook)
        assertEquals(NightVerdict.PARTLY, outlook.verdict)

        val strict = andromeda.copy(minUsableHours = 0.5)
        val lenient = strict.copy(weatherDemand = WeatherDemand.ALSO_PARTLY)

        assertNull(WatchEvaluator.evaluate(strict, observer, zone, night, outlook))
        val hit = assertNotNull(WatchEvaluator.evaluate(lenient, observer, zone, night, outlook))
        assertEquals(1.0, hit.goodHours, 1e-6)
    }

    @Test
    fun `the quiet period suppresses a second report`() {
        val justReported = andromeda.copy(lastNotifiedEpochDay = night.minusDays(1).toEpochDay())
        assertNull(WatchEvaluator.evaluate(justReported, observer, zone, night, outlookFor(5.0)))

        val longAgo = andromeda.copy(lastNotifiedEpochDay = night.minusDays(10).toEpochDay())
        assertNotNull(WatchEvaluator.evaluate(longAgo, observer, zone, night, outlookFor(5.0)))
    }

    @Test
    fun `a demand the night cannot meet is not reported`() {
        // Zwölf Stunden gleichzeitig hoch und dunkel gibt eine Novembernacht in Berlin nicht her.
        val greedy = andromeda.copy(minUsableHours = 12.0)
        assertNull(WatchEvaluator.evaluate(greedy, observer, zone, night, outlookFor(5.0)))
    }

    /**
     * Der Fall, für den die Überschneidung gerechnet wird: Ein Objekt, das nur tagsüber über den
     * Horizont kommt, hat auch in der klarsten Nacht kein Fenster.
     */
    @Test
    fun `an object that never rises high enough is never a hit` () {
        val southern = andromeda.copy(id = "w2", objectId = "NGC 104", decDeg = -72.0)
        assertNull(WatchEvaluator.evaluate(southern, observer, zone, night, outlookFor(0.0)))
    }
}

class UsableWindowTest {

    /**
     * Die Zeitspanne muss zur Summe passen — sonst stünde in der Meldung eine Uhrzeit, die mit der
     * genannten Dauer nichts zu tun hat.
     */
    @Test
    fun `the usable window matches the usable total`() {
        val night = ObservationPlanner.nightFor(
            positionJ2000 = Equatorial(10.6847, 41.269),
            observer = observer,
            zone = zone,
            date = LocalDate.of(2026, 11, 13),
        )
        val from = assertNotNull(night.usableFromMillis)
        val to = assertNotNull(night.usableToMillis)
        assertTrue(to > from)
        // Eine einzelne Nacht hat höchstens einen Durchgang über der Mindesthöhe; Summe und
        // Zeitspanne sind dann dasselbe.
        assertEquals(night.usableMillis, to - from)
        assertTrue(night.darkFromMillis!! <= from)
        assertTrue(night.darkToMillis!! >= to)
    }

    @Test
    fun `a stricter minimum altitude shortens the window`() {
        val position = Equatorial(10.6847, 41.269)
        val date = LocalDate.of(2026, 11, 13)
        val gentle = ObservationPlanner.nightFor(position, observer, zone, date, minAltitudeDeg = 25.0)
        val strict = ObservationPlanner.nightFor(position, observer, zone, date, minAltitudeDeg = 50.0)
        assertTrue(
            strict.usableMillis < gentle.usableMillis,
            "50° darf nicht mehr Zeit ergeben als 25°: ${strict.usableHours} gegen ${gentle.usableHours}",
        )
    }
}
