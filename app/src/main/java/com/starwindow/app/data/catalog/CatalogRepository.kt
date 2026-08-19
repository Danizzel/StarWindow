package com.starwindow.app.data.catalog

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Holds the merged catalogue in memory. It is a few thousand entries at most, so there is no
 * database here yet — when the online sources land this is the place to put a disk cache behind.
 */
class CatalogRepository(private val sources: List<CatalogSource>) {

    private val mutex = Mutex()
    private var cached: List<SkyObject>? = null

    /** All objects from every source that loaded successfully, brightest first. */
    suspend fun objects(): List<SkyObject> = mutex.withLock {
        cached ?: loadAll().also { cached = it }
    }

    /** Objects that can be seen at all from this latitude, brightest first. */
    suspend fun objectsVisibleFrom(latitudeDeg: Double, magnitudeLimit: Double?): List<SkyObject> =
        objects().filter { obj ->
            val maxAltitude = 90.0 - kotlin.math.abs(latitudeDeg - obj.decDeg)
            maxAltitude > 0.0 && (magnitudeLimit == null || (obj.magnitude ?: 99.0) <= magnitudeLimit)
        }

    suspend fun refresh() = mutex.withLock {
        cached = loadAll()
    }

    private suspend fun loadAll(): List<SkyObject> = sources
        .mapNotNull { it.load().getOrNull() }
        .flatten()
        .distinctBy { it.id }
        .sortedBy { it.magnitude ?: 99.0 }
}
