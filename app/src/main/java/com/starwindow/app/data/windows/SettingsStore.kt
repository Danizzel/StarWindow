package com.starwindow.app.data.windows

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.core.calibration.Calibration
import com.starwindow.app.core.camera.ExposureSettings
import com.starwindow.app.data.weather.WeatherModel
import com.starwindow.app.data.weather.WeatherPlace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Everything the app remembers between runs, apart from the windows themselves.
 *
 * Stored as a single serialized blob rather than as a heap of individual preference keys: the
 * settings already have to be serializable for the calibration, and one blob keeps related values —
 * a field of view factor and the measurement it came from, say — from ever drifting apart.
 */
@Serializable
data class Settings(
    val magnitudeLimit: Double = 6.0,
    val showGraticule: Boolean = true,
    val showCatalogOverlay: Boolean = true,
    val manualLocation: ObserverLocation? = null,
    val calibration: Calibration = Calibration.NONE,
    val exposure: ExposureSettings = ExposureSettings.AUTO,
    /**
     * Der Ort, für den die Wettervorhersage gilt.
     *
     * Getrennt von [manualLocation]: Der Beobachtungsort für die Fenster ist der, an dem das Handy
     * steht, der Wetterort ist der, für den man plant — und das sind vor der Fahrt zum dunklen
     * Platz zwei verschiedene.
     */
    val weatherPlace: WeatherPlace? = null,
    /** Das Wettermodell, mit dem die Vorhersage gerechnet wird. */
    val weatherModel: WeatherModel = WeatherModel.DEFAULT,
)

class SettingsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    private val state = MutableStateFlow(read())
    val settings: StateFlow<Settings> = state.asStateFlow()

    val current: Settings get() = state.value

    fun setManualLocation(location: ObserverLocation?) = update { it.copy(manualLocation = location) }

    fun setMagnitudeLimit(value: Double) =
        update { it.copy(magnitudeLimit = value.coerceIn(0.0, 12.0)) }

    fun setShowGraticule(value: Boolean) = update { it.copy(showGraticule = value) }

    fun setShowCatalogOverlay(value: Boolean) = update { it.copy(showCatalogOverlay = value) }

    fun setCalibration(calibration: Calibration) = update { it.copy(calibration = calibration) }

    fun setExposure(exposure: ExposureSettings) = update { it.copy(exposure = exposure) }

    fun setWeatherPlace(place: WeatherPlace?) = update { it.copy(weatherPlace = place) }

    fun setWeatherModel(model: WeatherModel) = update { it.copy(weatherModel = model) }

    /** Applies [transform] to the stored settings and persists the result. */
    fun update(transform: (Settings) -> Settings) {
        val next = transform(state.value)
        prefs.edit { putString(KEY_SETTINGS, json.encodeToString(next)) }
        state.value = next
    }

    private fun read(): Settings {
        val stored = prefs.getString(KEY_SETTINGS, null) ?: return Settings()
        return try {
            json.decodeFromString<Settings>(stored)
        } catch (e: Exception) {
            // Settings are not worth crashing over; start clean and note it.
            Log.w(TAG, "Einstellungen nicht lesbar, es wird mit den Voreinstellungen begonnen", e)
            Settings()
        }
    }

    companion object {
        private const val TAG = "SettingsStore"
        private const val NAME = "starwindow_settings"
        private const val KEY_SETTINGS = "settings_json"
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
