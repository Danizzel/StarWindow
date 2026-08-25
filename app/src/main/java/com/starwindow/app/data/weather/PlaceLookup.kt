package com.starwindow.app.data.weather

import android.content.Context
import android.location.Geocoder
import android.util.Log
import com.starwindow.app.core.astro.ObserverLocation
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Macht aus einer GPS-Position einen benannten Ort.
 *
 * Zwei Schritte, weil keine Quelle allein reicht: Android nennt zu einer Position die Ortschaft,
 * kennt aber keine Einwohnerzahl; Open-Meteo kennt die Einwohnerzahl, sucht aber nur nach Namen.
 * Zusammen ergeben sie das, woran die Bortle-Schätzung hängt — welcher Ort in der Nähe liegt, wie
 * groß er ist und wie weit weg.
 *
 * Wichtig dabei: Die **Koordinaten bleiben die eigenen**. Übernommen werden nur Name, Zeitzone und
 * Einwohnerzahl der Ortschaft. Wer fünf Kilometer außerhalb steht, bekommt das Wetter für seinen
 * Standort und die Lichtverschmutzung für die Entfernung dorthin — nicht das Stadtzentrum.
 */
class PlaceLookup(
    context: Context,
    private val repository: WeatherRepository,
) {

    private val appContext = context.applicationContext

    /**
     * Beschreibt [location] so gut es geht.
     *
     * Schlägt nie fehl: Ohne Geocoder, ohne Netz oder ohne Treffer kommt die reine Position
     * zurück. Dann fehlt die Bortle-Schätzung — und das ist ehrlicher, als eine zu erfinden.
     */
    suspend fun describe(location: ObserverLocation): WeatherPlace = withContext(Dispatchers.IO) {
        val fallback = WeatherPlace.fromObserver(location)
        val name = reverseGeocode(location) ?: return@withContext fallback

        val matches = repository.searchPlaces(name).getOrNull().orEmpty()
        val nearest = matches
            .map { it to it.toObserverLocation().distanceKmTo(location) }
            .filter { (_, distanceKm) -> distanceKm <= MAX_MATCH_DISTANCE_KM }
            .minByOrNull { (_, distanceKm) -> distanceKm }
            ?: return@withContext fallback.copy(name = name)

        val (place, distanceKm) = nearest
        // Die Ortschaft liefert Namen und Größe, der Standort die Koordinaten.
        place.copy(
            latitudeDeg = location.latitudeDeg,
            longitudeDeg = location.longitudeDeg,
            elevationM = location.elevationM,
            populationDistanceKm = distanceKm,
        )
    }

    /**
     * Name der nächsten Ortschaft über den Geocoder des Systems.
     *
     * Bewusst die synchrone, ab Android 13 abgekündigte Fassung: Sie gibt es seit jeher, sie
     * funktioniert weiterhin, und dieser Aufruf läuft ohnehin schon auf einem Hintergrund-Thread.
     * Der Rückruf-Ersatz brächte hier nur eine zweite Codebahn für dasselbe Ergebnis.
     */
    @Suppress("DEPRECATION")
    private fun reverseGeocode(location: ObserverLocation): String? {
        if (!Geocoder.isPresent()) return null
        return try {
            Geocoder(appContext, Locale.GERMANY)
                .getFromLocation(location.latitudeDeg, location.longitudeDeg, 1)
                ?.firstOrNull()
                ?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
                ?.takeIf { it.isNotBlank() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.i(TAG, "Ortsname zur Position nicht ermittelbar", e)
            null
        }
    }

    companion object {
        private const val TAG = "PlaceLookup"

        /** Weiter weg ist es nicht mehr derselbe Ort, sondern ein gleichnamiger woanders. */
        private const val MAX_MATCH_DISTANCE_KM = 50.0
    }
}
