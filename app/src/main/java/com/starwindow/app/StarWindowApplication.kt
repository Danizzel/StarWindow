package com.starwindow.app

import android.app.Application
import android.content.Context
import com.starwindow.app.core.sensors.LocationTracker
import com.starwindow.app.core.sensors.OrientationTracker
import com.starwindow.app.data.catalog.AssetCatalogSource
import com.starwindow.app.data.catalog.CatalogRepository
import com.starwindow.app.data.catalog.ConstellationRepository
import com.starwindow.app.data.catalog.ObjectNotesRepository
import com.starwindow.app.data.images.SkyImageLoader
import com.starwindow.app.data.tracking.TrackingStore
import com.starwindow.app.data.windows.SettingsStore
import com.starwindow.app.data.windows.SkyWindowRepository
import com.starwindow.app.domain.ConstellationTransitCalculator
import com.starwindow.app.domain.TransitCalculator

/**
 * Hand-rolled service locator. The app is small enough that a DI framework would cost more than it
 * saves; if that changes, this class is the single seam to replace.
 */
class AppContainer(context: Context) {
    val orientationTracker = OrientationTracker(context)
    val locationTracker = LocationTracker(context)
    val windowRepository = SkyWindowRepository(context)
    val catalogRepository = CatalogRepository(
        listOf(
            AssetCatalogSource(context, AssetCatalogSource.STARS_ASSET),
            AssetCatalogSource(context, AssetCatalogSource.DEEP_SKY_ASSET),
        )
    )
    val constellationRepository = ConstellationRepository(context)
    val objectNotesRepository = ObjectNotesRepository(context)
    val settingsStore = SettingsStore(context)
    val trackingStore = TrackingStore(context)
    val skyImageLoader = SkyImageLoader(context.cacheDir)
    val transitCalculator = TransitCalculator()
    val constellationTransitCalculator = ConstellationTransitCalculator()
}

class StarWindowApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Reaches the container from anywhere that has a [Context]. */
val Context.appContainer: AppContainer
    get() = (applicationContext as StarWindowApplication).container
