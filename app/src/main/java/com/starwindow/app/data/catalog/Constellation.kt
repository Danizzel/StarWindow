package com.starwindow.app.data.catalog

import android.content.Context
import com.starwindow.app.core.astro.Equatorial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One star of a constellation's stick figure. */
@Serializable
data class FigureStar(
    val name: String,
    val raDeg: Double,
    val decDeg: Double,
) {
    val equatorial: Equatorial get() = Equatorial(raDeg, decDeg)
}

/**
 * A constellation as its recognisable figure, not as its official IAU area.
 *
 * The figure is what someone actually looks for in a gap between two roofs, and it answers "is
 * Orion crossing my window" far more usefully than a boundary polygon would: a window a few degrees
 * across almost never contains a whole IAU area, but it very much can contain Orion's belt.
 * Boundaries can be added later without disturbing this.
 */
@Serializable
data class Constellation(
    /** IAU three-letter abbreviation, e.g. `Ori`. */
    val id: String,
    val name: String,
    val stars: List<FigureStar> = emptyList(),
    /** Index pairs into [stars] describing the figure's segments. */
    val lines: List<List<Int>> = emptyList(),
) {
    /** Segments as star pairs, with malformed entries dropped rather than crashing. */
    val segments: List<Pair<FigureStar, FigureStar>>
        get() = lines.mapNotNull { pair ->
            if (pair.size != 2) return@mapNotNull null
            val a = stars.getOrNull(pair[0]) ?: return@mapNotNull null
            val b = stars.getOrNull(pair[1]) ?: return@mapNotNull null
            a to b
        }
}

@Serializable
data class ConstellationFile(
    val version: Int = 1,
    val name: String = "",
    val epoch: String = "J2000",
    val constellations: List<Constellation> = emptyList(),
)

/** Loads the bundled constellation figures. Same shape as [CatalogSource], separate data. */
class ConstellationRepository(
    context: Context,
    private val assetPath: String = DEFAULT_ASSET,
) {

    private val appContext = context.applicationContext
    private var cached: List<Constellation>? = null

    suspend fun constellations(): List<Constellation> {
        cached?.let { return it }
        return withContext(Dispatchers.IO) {
            val loaded = runCatching {
                val text = appContext.assets.open(assetPath).bufferedReader().use { it.readText() }
                json.decodeFromString<ConstellationFile>(text).constellations
            }.getOrDefault(emptyList())
            cached = loaded
            loaded
        }
    }

    companion object {
        const val DEFAULT_ASSET = "catalog/constellations.json"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
