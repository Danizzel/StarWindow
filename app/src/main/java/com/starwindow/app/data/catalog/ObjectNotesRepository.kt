package com.starwindow.app.data.catalog

import android.content.Context
import android.util.Log
import com.starwindow.app.domain.ObjectSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class ObjectNotesFile(
    val version: Int = 1,
    val notes: Map<String, String> = emptyMap(),
    val starNotes: Map<String, String> = emptyMap(),
)

/**
 * The sentence about an object that no catalogue contains.
 *
 * A catalogue row says `EMISSION_NEBULA, 6.0 mag, 120'`, which is everything the transit search
 * needs and nothing a person wants to know. Most of the gap is closed by explaining the *type*,
 * which [com.starwindow.app.domain.ObjectDescription] does for all three thousand entries at once.
 * What is left over is the part that is genuinely per-object — that M51 was the first galaxy in
 * which spiral structure was ever recognised, that the blue haze around the Pleiades is a dust
 * cloud they are merely passing through — and that has to be written by hand. It exists for the
 * couple of hundred objects anyone actually looks at; the rest fall back to the generated text,
 * which is not a gap so much as an honest answer for an anonymous 15th-magnitude galaxy.
 *
 * Looked up by *any* of an entry's designations, folded the same way the search folds them, so
 * `M 45`, `M45` and `Mel022` all find the same note.
 */
class ObjectNotesRepository(context: Context) {

    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private var byDesignation: Map<String, String>? = null
    private var byName: Map<String, String>? = null

    /** The note for this object, or null when nobody has written one. */
    suspend fun noteFor(obj: SkyObject): String? {
        load()
        val designations = byDesignation ?: return null
        val names = byName ?: emptyMap()

        obj.allIdentifiers.forEach { identifier ->
            designations[ObjectSearch.fold(identifier)]?.let { return it }
        }
        (listOf(obj.name) + obj.alternativeNames).forEach { name ->
            if (name.isNotBlank()) names[ObjectSearch.fold(name)]?.let { return it }
        }
        return null
    }

    private suspend fun load() {
        if (byDesignation != null) return
        mutex.withLock {
            if (byDesignation != null) return
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val text = appContext.assets.open(ASSET).bufferedReader().use { it.readText() }
                    json.decodeFromString<ObjectNotesFile>(text)
                }.getOrElse {
                    // A missing description is cosmetic; the sheet works without it.
                    Log.w(TAG, "Objektbeschreibungen nicht lesbar", it)
                    ObjectNotesFile()
                }
            }
            byDesignation = file.notes.mapKeys { ObjectSearch.fold(it.key) }
            byName = file.starNotes.mapKeys { ObjectSearch.fold(it.key) }
        }
    }

    companion object {
        private const val TAG = "ObjectNotes"
        private const val ASSET = "catalog/object_notes.json"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
