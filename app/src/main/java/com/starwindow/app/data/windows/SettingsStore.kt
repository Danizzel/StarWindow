package com.starwindow.app.data.windows

import android.content.Context
import androidx.core.content.edit
import com.starwindow.app.core.astro.ObserverLocation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User preferences that survive restarts. Small enough that SharedPreferences is the right tool. */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    private val state = MutableStateFlow(read())
    val settings: StateFlow<Settings> = state.asStateFlow()

    fun setFovScale(value: Double) = update { it.copy(fovScale = value.coerceIn(0.5, 2.0)) }

    fun setManualLocation(location: ObserverLocation?) = update {
        it.copy(manualLocation = location)
    }

    fun setMagnitudeLimit(value: Double) = update { it.copy(magnitudeLimit = value.coerceIn(0.0, 12.0)) }

    fun setShowGraticule(value: Boolean) = update { it.copy(showGraticule = value) }

    fun setShowCatalogOverlay(value: Boolean) = update { it.copy(showCatalogOverlay = value) }

    private fun update(transform: (Settings) -> Settings) {
        val next = transform(state.value)
        prefs.edit {
            putFloat(KEY_FOV_SCALE, next.fovScale.toFloat())
            putFloat(KEY_MAGNITUDE_LIMIT, next.magnitudeLimit.toFloat())
            putBoolean(KEY_SHOW_GRATICULE, next.showGraticule)
            putBoolean(KEY_SHOW_CATALOG, next.showCatalogOverlay)
            val manual = next.manualLocation
            if (manual == null) {
                remove(KEY_MANUAL_LAT)
                remove(KEY_MANUAL_LON)
                remove(KEY_MANUAL_ELEVATION)
            } else {
                putFloat(KEY_MANUAL_LAT, manual.latitudeDeg.toFloat())
                putFloat(KEY_MANUAL_LON, manual.longitudeDeg.toFloat())
                putFloat(KEY_MANUAL_ELEVATION, manual.elevationM.toFloat())
            }
        }
        state.value = next
    }

    private fun read(): Settings {
        val manual = if (prefs.contains(KEY_MANUAL_LAT) && prefs.contains(KEY_MANUAL_LON)) {
            ObserverLocation(
                latitudeDeg = prefs.getFloat(KEY_MANUAL_LAT, 0f).toDouble(),
                longitudeDeg = prefs.getFloat(KEY_MANUAL_LON, 0f).toDouble(),
                elevationM = prefs.getFloat(KEY_MANUAL_ELEVATION, 0f).toDouble(),
                manual = true,
            )
        } else {
            null
        }
        return Settings(
            fovScale = prefs.getFloat(KEY_FOV_SCALE, 1f).toDouble(),
            magnitudeLimit = prefs.getFloat(KEY_MAGNITUDE_LIMIT, 6f).toDouble(),
            showGraticule = prefs.getBoolean(KEY_SHOW_GRATICULE, true),
            showCatalogOverlay = prefs.getBoolean(KEY_SHOW_CATALOG, true),
            manualLocation = manual,
        )
    }

    companion object {
        private const val NAME = "starwindow_settings"
        private const val KEY_FOV_SCALE = "fov_scale"
        private const val KEY_MAGNITUDE_LIMIT = "magnitude_limit"
        private const val KEY_SHOW_GRATICULE = "show_graticule"
        private const val KEY_SHOW_CATALOG = "show_catalog"
        private const val KEY_MANUAL_LAT = "manual_lat"
        private const val KEY_MANUAL_LON = "manual_lon"
        private const val KEY_MANUAL_ELEVATION = "manual_elevation"
    }
}

/**
 * @param fovScale corrects a wrong field of view reported by the camera. Raise it when markers
 *   drift outwards faster than the image while panning.
 */
data class Settings(
    val fovScale: Double = 1.0,
    val magnitudeLimit: Double = 6.0,
    val showGraticule: Boolean = true,
    val showCatalogOverlay: Boolean = true,
    val manualLocation: ObserverLocation? = null,
)
