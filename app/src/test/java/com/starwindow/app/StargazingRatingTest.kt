package com.starwindow.app

import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.data.weather.WeatherForecast
import com.starwindow.app.data.weather.WeatherHour
import com.starwindow.app.data.weather.WeatherModel
import com.starwindow.app.data.weather.WeatherPlace
import com.starwindow.app.domain.AstroWeather
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val ZURICH = WeatherPlace(
    name = "Zürich", region = "Zürich", country = "Schweiz",
    latitudeDeg = 47.3769, longitudeDeg = 8.5417, elevationM = 429.0,
    timezoneId = "Europe/Zurich", population = 341_730,
)

/** Tromsø: Im Juni wird es dort überhaupt nicht dunkel — die Nacht ohne Nenner. */
private val TROMSO = WeatherPlace(
    name = "Tromsø", region = "Troms", country = "Norwegen",
    latitudeDeg = 69.65, longitudeDeg = 18.96, elevationM = 10.0,
    timezoneId = "Europe/Oslo", population = 77_000,
)

/**
 * Die Bewertung, die ganz oben in der Wetteransicht steht.
 *
 * Sie ersetzt eine Entscheidung — deshalb muss vor allem eines stimmen: dass sie zwischen einer
 * durchgehend brauchbaren Nacht und einer mit einem einzigen guten Loch unterscheidet. Genau das
 * kann [com.starwindow.app.domain.AstroNight.bestScore] allein nicht.
 */
class StargazingRatingTest {

    private val zone: ZoneId = ZoneId.of("Europe/Zurich")

    private fun night(
        place: WeatherPlace = ZURICH,
        zoneId: ZoneId = zone,
        date: LocalDate = LocalDate.of(2024, 11, 10),
        cloudsByHour: (Int) -> Double,
    ) = AstroWeather.nights(
        forecast = WeatherForecast(
            place = place,
            zone = zoneId,
            model = WeatherModel.ECMWF_IFS,
            hours = (0 until 96).map { index ->
                val millis = date.atStartOfDay(zoneId).toInstant().toEpochMilli() +
                    index * 3_600_000L
                WeatherHour(
                    millis = millis,
                    cloudTotalPercent = cloudsByHour(index),
                    cloudLowPercent = null,
                    cloudMidPercent = null,
                    cloudHighPercent = null,
                    temperatureC = 5.0,
                    dewPointC = -2.0,
                    humidityPercent = 60.0,
                    windSpeedKmh = 5.0,
                    windGustsKmh = 10.0,
                    precipitationMm = 0.0,
                )
            },
            fetchedAtMillis = date.atStartOfDay(zoneId).toInstant().toEpochMilli(),
        ),
        location = ObserverLocation(place.latitudeDeg, place.longitudeDeg, place.elevationM ?: 0.0),
        nowMillis = date.atTime(20, 0).atZone(zoneId).toInstant().toEpochMilli(),
    ).first()

    @Test
    fun `a clear night rates high and says so`() {
        val clear = night { 5.0 }
        assertTrue(clear.stargazingRating >= 70, "nur ${clear.stargazingRating} %")
        assertTrue(clear.ratingLabel in setOf("Sehr gut", "Gut"), clear.ratingLabel)
    }

    @Test
    fun `an overcast night rates low and says so`() {
        val overcast = night { 98.0 }
        assertTrue(overcast.stargazingRating <= 25, "noch ${overcast.stargazingRating} %")
        assertTrue(overcast.ratingLabel in setOf("Schlecht", "Aussichtslos"), overcast.ratingLabel)
    }

    /**
     * Der Kern: Eine durchgehend klare Nacht behält ihre beste Stunde als Bewertung, eine Nacht mit
     * einem einzigen klaren Loch nicht. Geprüft wird das an jeder Nacht gegen ihre **eigene** beste
     * Stunde — zwei verschiedene Nächte haben nie dieselbe, weil auch der Mond woanders steht.
     */
    @Test
    fun `a lone clear hour is damped, a clear night is not`() {
        val throughout = night { 5.0 }
        // Nicht exakt die beste Stunde: Am 10. November steht der Mond einen Teil der Nacht über
        // dem Horizont und drückt einzelne Dunkelstunden unter die Brauchbarkeitsschwelle. Genau
        // das soll die Dämpfung ja abbilden — nur eben kaum.
        assertTrue(
            throughout.stargazingRating >= throughout.bestScore - 5,
            "durchgehend brauchbar, trotzdem ${throughout.stargazingRating} % " +
                "gegen beste Stunde ${throughout.bestScore} %",
        )

        // Nur die Stunde um ein Uhr nachts ist frei, der Rest zu.
        val oneHole = night { index -> if (index % 24 == 1) 5.0 else 95.0 }
        assertTrue(
            oneHole.stargazingRating < oneHole.bestScore,
            "${oneHole.stargazingRating} % gegen beste Stunde ${oneHole.bestScore} %",
        )
        assertTrue(
            oneHole.stargazingRating < throughout.stargazingRating,
            "Loch ${oneHole.stargazingRating} % gegen durchgehend ${throughout.stargazingRating} %",
        )
    }

    /** Und sie fällt nicht auf null: Für diese eine Stunde fährt man notfalls trotzdem hinaus. */
    @Test
    fun `one clear hour is still worth something`() {
        val oneHole = night { index -> if (index % 24 == 1) 5.0 else 95.0 }
        assertTrue(oneHole.stargazingRating >= oneHole.bestScore * 0.55)
    }

    /** Eine Nacht ohne Dunkelheit ist keine schlechte Nacht, sondern gar keine. */
    @Test
    fun `a night without darkness is named as such`() {
        val polar = night(
            place = TROMSO,
            zoneId = ZoneId.of("Europe/Oslo"),
            date = LocalDate.of(2024, 6, 20),
        ) { 0.0 }
        assertEquals(0L, polar.times.darkDurationMillis)
        assertEquals("keine Dunkelheit", polar.ratingLabel)
    }

    /** Die Zahl bleibt in ihren Grenzen, egal was die Vorhersage hergibt. */
    @Test
    fun `the rating stays between zero and a hundred`() {
        for (clouds in listOf(0.0, 30.0, 60.0, 100.0)) {
            val rating = night { clouds }.stargazingRating
            assertTrue(rating in 0..100, "bei $clouds % Bewölkung kam $rating heraus")
        }
    }
}
