package com.starwindow.app.core.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.starwindow.app.core.astro.ObserverLocation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * Observer position from the platform [LocationManager]. Deliberately no Play Services dependency:
 * the app only needs the position to a few hundred metres (that is well under a thousandth of a
 * degree of sky), and staying on the platform API keeps the build free of Google dependencies.
 */
class LocationTracker(context: Context) {

    private val appContext = context.applicationContext
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Best cached fix, or null when there is none or permission is missing. */
    fun lastKnown(): ObserverLocation? {
        if (!hasPermission) return null
        return try {
            locationManager.getProviders(true)
                .mapNotNull { locationManager.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
                ?.toObserverLocation()
        } catch (_: SecurityException) {
            null
        }
    }

    /** Stream of fixes. Emits the last known position immediately, if there is one. */
    fun locations(
        minTimeMs: Long = 10_000L,
        minDistanceM: Float = 50f,
    ): Flow<ObserverLocation> = callbackFlow {
        if (!hasPermission) {
            close()
            return@callbackFlow
        }

        lastKnown()?.let { trySend(it) }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(location.toObserverLocation())
            }

            // Required on API < 30; harmless afterwards.
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }

        val providers = buildList {
            if (locationManager.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                add(LocationManager.GPS_PROVIDER)
            }
            if (locationManager.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                add(LocationManager.NETWORK_PROVIDER)
            }
        }

        try {
            providers.forEach { provider ->
                locationManager.requestLocationUpdates(
                    provider,
                    minTimeMs,
                    minDistanceM,
                    listener,
                    appContext.mainLooper,
                )
            }
        } catch (_: SecurityException) {
            close()
            return@callbackFlow
        }

        awaitClose { locationManager.removeUpdates(listener) }
    }.conflate()
}

private fun Location.toObserverLocation() = ObserverLocation(
    latitudeDeg = latitude,
    longitudeDeg = longitude,
    elevationM = if (hasAltitude()) altitude else 0.0,
    accuracyM = if (hasAccuracy()) accuracy else null,
    manual = false,
)
