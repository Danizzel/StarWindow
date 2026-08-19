package com.starwindow.app.data.catalog

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Where catalogue entries come from. Local assets today, online services later. */
interface CatalogSource {
    val id: String
    val displayName: String

    /** Loads the whole catalogue. Implementations must be safe to call from any dispatcher. */
    suspend fun load(): Result<List<SkyObject>>
}

/** The catalogue shipped inside the APK — always available, no network, no permissions. */
class AssetCatalogSource(
    context: Context,
    private val assetPath: String = DEFAULT_ASSET,
) : CatalogSource {

    private val appContext = context.applicationContext

    override val id: String = "asset:$assetPath"
    override val displayName: String = "Basiskatalog (im Gerät)"

    override suspend fun load(): Result<List<SkyObject>> = withContext(Dispatchers.IO) {
        runCatching {
            val text = appContext.assets.open(assetPath).bufferedReader().use { it.readText() }
            val file = json.decodeFromString<CatalogFile>(text)
            file.objects.map { it.copy(source = file.name.ifBlank { displayName }) }
        }
    }

    companion object {
        const val DEFAULT_ASSET = "catalog/starwindow_core.json"
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Placeholder for the online catalogues.
 *
 * The plan is a VizieR/SIMBAD TAP query restricted to the declination band a window can ever see,
 * something along the lines of
 *
 * ```
 * SELECT main_id, ra, dec, V FROM basic
 *   WHERE dec BETWEEN :decMin AND :decMax AND V < :magnitudeLimit
 * ```
 *
 * plus an on-device cache so a night in the field never needs a connection. Nothing is
 * implemented yet on purpose: the local catalogue covers the whole geometry pipeline, and the
 * online part should be designed together with the caching strategy rather than bolted on.
 */
class RemoteCatalogSource(
    override val id: String = "remote:vizier",
    override val displayName: String = "Online-Katalog (noch nicht angebunden)",
) : CatalogSource {

    override suspend fun load(): Result<List<SkyObject>> =
        Result.failure(NotImplementedError("Online-Kataloge sind noch nicht angebunden"))
}
