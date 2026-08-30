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

    /**
     * Objects that can be seen at all from this latitude, brightest first.
     *
     * An entry **without** a magnitude passes the brightness limit rather than failing it, as long
     * as it is large enough to be a target at all. Treating a missing value as "infinitely faint"
     * silently dropped two thousand entries — and not a random two thousand: the Sharpless and
     * Lynds nebulae are catalogued by extent, not by integrated brightness, so the rule threw out
     * precisely the large faint clouds this app exists to help photograph. The California Nebula
     * has no useful magnitude and is nearly three degrees across.
     */
    suspend fun objectsVisibleFrom(latitudeDeg: Double, magnitudeLimit: Double?): List<SkyObject> =
        objects().filter { obj ->
            // Sonne und Mond haben keine feste Deklination, an der sich das prüfen ließe — sie
            // wandern über gut 57° Breite und gehen von jedem bewohnten Ort der Erde auf.
            val maxAltitude = if (obj.isMoving) {
                90.0
            } else {
                90.0 - kotlin.math.abs(latitudeDeg - obj.decDeg)
            }
            maxAltitude > 0.0 && isBrightEnough(obj, magnitudeLimit)
        }

    private fun isBrightEnough(obj: SkyObject, magnitudeLimit: Double?): Boolean {
        if (magnitudeLimit == null) return true
        val magnitude = obj.magnitude ?: return (obj.sizeArcmin ?: 0.0) >= MIN_SIZE_WITHOUT_MAGNITUDE_ARCMIN
        return magnitude <= magnitudeLimit
    }

    suspend fun refresh() = mutex.withLock {
        cached = loadAll()
    }

    /**
     * Sonne und Mond kommen aus keiner Datei, sondern aus der Ephemeride — und sie kommen immer,
     * auch wenn keine einzige Katalogdatei geladen werden konnte.
     *
     * Sie stehen am Anfang der Liste, weil sie nach Helligkeit ohnehin dorthin gehören: Es gibt
     * nichts Helleres.
     */
    private suspend fun loadAll(): List<SkyObject> = (
        EphemerisCatalog.all + sources.mapNotNull { it.load().getOrNull() }.flatten()
        )
        .distinctBy { it.id }
        .sortedBy { it.magnitude ?: 99.0 }

    private companion object {
        /** An entry with no magnitude has to be at least this large to count as a target. */
        const val MIN_SIZE_WITHOUT_MAGNITUDE_ARCMIN = 3.0
    }
}
