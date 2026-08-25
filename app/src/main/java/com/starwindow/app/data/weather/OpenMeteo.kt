package com.starwindow.app.data.weather

import java.net.URLEncoder
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Anbindung an Open-Meteo — Adressbau und Auswertung der Antwort, ohne Netz.
 *
 * Getrennt vom [WeatherRepository], damit genau der Teil testbar ist, der erfahrungsgemäß bricht:
 * die Zuordnung von Spalten zu Zeitstempeln und der Umgang mit Lücken. Der Abruf selbst ist ein
 * Dutzend Zeilen `HttpURLConnection` und steht dort.
 *
 * Gefragt wird immer derselbe Endpunkt mit wechselndem `models=` — welche Modelle zur Wahl stehen
 * und warum, steht bei [WeatherModel].
 */
object OpenMeteo {

    const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
    const val GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search"

    /** Wo der Dienst den Zustand eines Modells ablegt. */
    const val META_URL = "https://api.open-meteo.com/data"

    /** Steht unter der Ansicht, damit die Herkunft der Zahlen sichtbar bleibt. */
    const val SOURCE_LABEL = "open-meteo.com"

    /** Weiter reicht kein Modell der Auswahl. */
    const val MAX_FORECAST_DAYS = 15

    /**
     * Alles, was die Nachtbewertung auswerten kann.
     *
     * Die Reihenfolge ist die der Anzeige, nicht die der Wichtigkeit — die Bewölkung in drei
     * Schichten steht vorn, weil sie zusammen mit der Gesamtbedeckung die eine Frage beantwortet,
     * an der eine Nacht hängt.
     */
    private val FULL_VARIABLES = listOf(
        "cloud_cover",
        "cloud_cover_low",
        "cloud_cover_mid",
        "cloud_cover_high",
        "temperature_2m",
        "dew_point_2m",
        "relative_humidity_2m",
        "precipitation",
        "wind_speed_10m",
        "wind_gusts_10m",
        "pressure_msl",
        "wind_speed_250hPa",
    )

    /**
     * Rückfallebene ohne die Größen, die ein Lauf auch mal nicht führt.
     *
     * Open-Meteo weist eine Anfrage **als Ganzes** zurück, sobald eine einzige Variable im Modell
     * fehlt. Ohne zweiten Versuch stünde die Ansicht dann leer da, obwohl die Bewölkung — das
     * Einzige, was wirklich gebraucht wird — sehr wohl vorliegt.
     */
    private val CORE_VARIABLES = listOf(
        "cloud_cover",
        "cloud_cover_low",
        "cloud_cover_mid",
        "cloud_cover_high",
        "temperature_2m",
        "dew_point_2m",
        "precipitation",
        "wind_speed_10m",
    )

    /** Die beiden Anfragen in der Reihenfolge, in der sie versucht werden. */
    fun forecastUrls(
        latitudeDeg: Double,
        longitudeDeg: Double,
        model: WeatherModel,
    ): List<String> = listOf(
        forecastUrl(latitudeDeg, longitudeDeg, model, FULL_VARIABLES),
        forecastUrl(latitudeDeg, longitudeDeg, model, CORE_VARIABLES),
    )

    fun forecastUrl(
        latitudeDeg: Double,
        longitudeDeg: Double,
        model: WeatherModel,
        variables: List<String>,
    ): String = buildString {
        append(FORECAST_URL)
        append("?latitude=").append(coordinate(latitudeDeg))
        append("&longitude=").append(coordinate(longitudeDeg))
        append("&hourly=").append(variables.joinToString(","))
        append("&models=").append(model.id)
        append("&wind_speed_unit=kmh")
        // Zeitzone des Ortes mitliefern lassen, die Zeitstempel aber als UTC-Epoche: so hängt die
        // Anzeige an der Zeitzone des Ortes, ohne dass irgendwo ein Offset zweimal addiert wird.
        append("&timezone=auto")
        append("&timeformat=unixtime")
        append("&forecast_days=").append(model.requestDays.coerceIn(1, MAX_FORECAST_DAYS))
    }

    /** Wo der Zustand eines Modells steht: Lauf, Verfügbarkeit, Gebiet. */
    fun metaUrl(model: WeatherModel): String = "$META_URL/${model.metaPath}/static/meta.json"

    fun geocodingUrl(query: String, count: Int = 8): String = buildString {
        append(GEOCODING_URL)
        append("?name=").append(URLEncoder.encode(query.trim(), "UTF-8"))
        append("&count=").append(count.coerceIn(1, 20))
        append("&language=de")
        append("&format=json")
    }

    /**
     * Antwort → [WeatherForecast].
     *
     * [place] liefert Name und Bortle-Angabe; Höhe und Zeitzone kommen aus der Antwort, weil das
     * Modell die Höhe seines Gitterpunktes meldet und die vom eingetippten Ort abweichen kann.
     */
    fun parseForecast(body: String, place: WeatherPlace, model: WeatherModel): WeatherForecast {
        val dto = json.decodeFromString<ForecastDto>(body)
        if (dto.error == true) {
            error(dto.reason ?: "Der Wetterdienst hat die Anfrage abgelehnt")
        }
        val hourly = dto.hourly ?: error("Die Antwort enthält keine Stundenwerte")

        val hours = hourly.time.mapIndexed { index, seconds ->
            WeatherHour(
                millis = seconds * 1000L,
                cloudTotalPercent = hourly.cloudCover.at(index),
                cloudLowPercent = hourly.cloudCoverLow.at(index),
                cloudMidPercent = hourly.cloudCoverMid.at(index),
                cloudHighPercent = hourly.cloudCoverHigh.at(index),
                temperatureC = hourly.temperature.at(index),
                dewPointC = hourly.dewPoint.at(index),
                humidityPercent = hourly.humidity.at(index),
                windSpeedKmh = hourly.windSpeed.at(index),
                windGustsKmh = hourly.windGusts.at(index),
                precipitationMm = hourly.precipitation.at(index),
                pressureHpa = hourly.pressure.at(index),
                jetStreamKmh = hourly.jetStream.at(index),
            )
        }

        return WeatherForecast(
            place = place.copy(
                elevationM = dto.elevation ?: place.elevationM,
                timezoneId = dto.timezone ?: place.timezoneId,
            ),
            zone = resolveZone(dto.timezone, dto.utcOffsetSeconds, place),
            model = model,
            hours = trimTrailingGap(hours),
            fetchedAtMillis = System.currentTimeMillis(),
        )
    }

    /**
     * Schneidet die leeren Stunden am Ende ab.
     *
     * Der Dienst füllt jede angefragte Stunde, auch wenn das Modell längst zu Ende ist — ICON-D2
     * liefert auf sieben angefragte Tage knapp drei und den Rest als Nullwerte. Blieben die stehen,
     * würde die Nachtbewertung sie als „keine Bewölkung gemeldet" lesen und zwölf traumhafte Nächte
     * erfinden, die es gar nicht gibt. Die Bewölkung ist dabei der Prüfstein: Ohne sie ist eine
     * Stunde für diese Ansicht wertlos, egal was sonst noch in ihr steht.
     */
    private fun trimTrailingGap(hours: List<WeatherHour>): List<WeatherHour> {
        val last = hours.indexOfLast { it.effectiveCloudPercent != null }
        return if (last < 0) emptyList() else hours.subList(0, last + 1)
    }

    /**
     * Antwort von `meta.json` → [WeatherModelStatus].
     *
     * Das Modellgebiet steht dort als `BBOX[…]` mitten in einer WKT-Beschreibung des
     * Koordinatensystems. Ein vollständiger WKT-Parser wäre für vier Zahlen unverhältnismäßig;
     * fehlt oder ändert sich die Angabe, gibt es eben keine Gebietsgrenzen — dann behauptet die
     * App nichts über die Abdeckung, statt sich zu verrechnen.
     */
    fun parseModelStatus(body: String, model: WeatherModel): WeatherModelStatus {
        val dto = json.decodeFromString<MetaDto>(body)
        return WeatherModelStatus(
            model = model,
            runMillis = dto.runTime?.times(1000L),
            availableSinceMillis = dto.availabilityTime?.times(1000L),
            dataEndMillis = dto.dataEndTime?.times(1000L),
            updateIntervalSeconds = dto.updateInterval,
            stepSeconds = dto.temporalResolution,
            bounds = dto.crsWkt?.let(::parseBounds),
        )
    }

    private fun parseBounds(crsWkt: String): ModelBounds? {
        val match = BBOX_PATTERN.find(crsWkt) ?: return null
        val numbers = match.groupValues.drop(1).map { it.toDoubleOrNull() ?: return null }
        // WKT zählt hier Breite vor Länge, jeweils Minimum vor Maximum.
        return ModelBounds(numbers[0], numbers[1], numbers[2], numbers[3])
    }

    private val BBOX_PATTERN =
        Regex("""BBOX\[\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)\s*]""")

    /** Antwort der Ortssuche → Trefferliste. Ohne Treffer eine leere Liste, kein Fehler. */
    fun parsePlaces(body: String): List<WeatherPlace> =
        json.decodeFromString<GeocodeResponseDto>(body).results.map { result ->
            WeatherPlace(
                name = result.name,
                region = result.admin1,
                country = result.country,
                latitudeDeg = result.latitude,
                longitudeDeg = result.longitude,
                elevationM = result.elevation ?: 0.0,
                timezoneId = result.timezone,
                population = result.population,
            )
        }

    /**
     * Die Zeitzone des Ortes. Der Name ist der Sommerzeit wegen die bessere Quelle als der Offset —
     * ein fester Offset aus einer Antwort im August steht im Oktober eine Stunde daneben.
     */
    private fun resolveZone(
        timezone: String?,
        utcOffsetSeconds: Int?,
        place: WeatherPlace,
    ): ZoneId = timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        ?: place.zone
        ?: utcOffsetSeconds?.let { runCatching { ZoneOffset.ofTotalSeconds(it) }.getOrNull() }
        ?: ZoneId.systemDefault()

    /** Fünf Nachkommastellen sind gut einen Meter — mehr braucht ein 25-km-Gitter nicht. */
    private fun coordinate(value: Double) = "%.5f".format(java.util.Locale.ROOT, value)

    private fun List<Double?>.at(index: Int): Double? = getOrNull(index)

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class ForecastDto(
        val elevation: Double? = null,
        val timezone: String? = null,
        @SerialName("utc_offset_seconds") val utcOffsetSeconds: Int? = null,
        val hourly: HourlyDto? = null,
        val error: Boolean? = null,
        val reason: String? = null,
    )

    @Serializable
    private data class HourlyDto(
        /** Sekunden seit der Epoche, UTC. */
        val time: List<Long> = emptyList(),
        @SerialName("cloud_cover") val cloudCover: List<Double?> = emptyList(),
        @SerialName("cloud_cover_low") val cloudCoverLow: List<Double?> = emptyList(),
        @SerialName("cloud_cover_mid") val cloudCoverMid: List<Double?> = emptyList(),
        @SerialName("cloud_cover_high") val cloudCoverHigh: List<Double?> = emptyList(),
        @SerialName("temperature_2m") val temperature: List<Double?> = emptyList(),
        @SerialName("dew_point_2m") val dewPoint: List<Double?> = emptyList(),
        @SerialName("relative_humidity_2m") val humidity: List<Double?> = emptyList(),
        val precipitation: List<Double?> = emptyList(),
        @SerialName("wind_speed_10m") val windSpeed: List<Double?> = emptyList(),
        @SerialName("wind_gusts_10m") val windGusts: List<Double?> = emptyList(),
        @SerialName("pressure_msl") val pressure: List<Double?> = emptyList(),
        @SerialName("wind_speed_250hPa") val jetStream: List<Double?> = emptyList(),
    )

    @Serializable
    private data class MetaDto(
        @SerialName("last_run_initialisation_time") val runTime: Long? = null,
        @SerialName("last_run_availability_time") val availabilityTime: Long? = null,
        @SerialName("data_end_time") val dataEndTime: Long? = null,
        @SerialName("update_interval_seconds") val updateInterval: Int? = null,
        @SerialName("temporal_resolution_seconds") val temporalResolution: Int? = null,
        @SerialName("crs_wkt") val crsWkt: String? = null,
    )

    @Serializable
    private data class GeocodeResponseDto(val results: List<GeocodeResultDto> = emptyList())

    @Serializable
    private data class GeocodeResultDto(
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val elevation: Double? = null,
        val country: String? = null,
        val admin1: String? = null,
        val timezone: String? = null,
        val population: Int? = null,
    )
}
