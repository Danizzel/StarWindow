package com.starwindow.app

import android.app.Application
import android.content.Context
import com.starwindow.app.core.sensors.LocationTracker
import com.starwindow.app.core.sensors.OrientationTracker
import com.starwindow.app.data.catalog.AssetCatalogSource
import com.starwindow.app.data.catalog.CatalogRepository
import com.starwindow.app.data.catalog.ConstellationRepository
import com.starwindow.app.data.catalog.ObjectNotesRepository
import com.starwindow.app.data.favorites.FavoritesRepository
import com.starwindow.app.data.images.SkyImageLoader
import com.starwindow.app.data.planning.PlanRepository
import com.starwindow.app.data.planning.WatchScheduler
import com.starwindow.app.data.planning.WatchlistRepository
import com.starwindow.app.data.tracking.TrackingStore
import com.starwindow.app.data.weather.ForecastCache
import com.starwindow.app.data.weather.PlaceLookup
import com.starwindow.app.data.weather.WeatherRepository
import com.starwindow.app.data.windows.SettingsStore
import com.starwindow.app.data.windows.SkyWindowRepository
import com.starwindow.app.domain.ConstellationTransitCalculator
import com.starwindow.app.domain.TransitCalculator
import java.io.File

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
    val favoritesRepository = FavoritesRepository(context)
    val settingsStore = SettingsStore(context)
    val trackingStore = TrackingStore(context)
    val planRepository = PlanRepository(context)
    val watchlistRepository = WatchlistRepository(context)
    val watchScheduler = WatchScheduler(context)
    val skyImageLoader = SkyImageLoader(context.cacheDir)

    /**
     * Die Vorhersage bekommt eine Ablage auf der Platte: Sie überlebt damit das Beenden der App und
     * ist der einzige Weg, wie eine Erinnerung um 17 Uhr — in einem frisch gestarteten Prozess und
     * womöglich ohne Empfang — überhaupt etwas über die Nacht sagen kann.
     */
    val forecastCache = ForecastCache(File(context.filesDir, ForecastCache.FILE_NAME))
    val weatherRepository = WeatherRepository(diskCache = forecastCache)
    val placeLookup = PlaceLookup(context, weatherRepository)
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
