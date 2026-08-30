package com.starwindow.app.data.favorites

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Die Objekte, die jemand mag.
 *
 * Bewusst etwas anderes als die Merkliste (`WatchlistRepository`), obwohl beide „gemerkte Objekte"
 * halten: Die Merkliste ist ein **Auftrag** — sag mir Bescheid, wenn es passt — und trägt deshalb
 * Bedingungen, einen Alarm und eine Ruhezeit mit sich. Ein Favorit ist eine **Meinung** und trägt
 * nichts: Er sortiert die eigenen Motive aus dreizehntausend Katalogzeilen heraus, und mehr soll er
 * nicht tun. Die beiden zusammenzulegen hieße, jedem Herz eine Benachrichtigung anzuhängen, die
 * niemand bestellt hat.
 *
 * Gespeichert wird eine Liste von Katalog-Kennungen, neueste zuerst — kein Zeitstempel, weil die
 * Reihenfolge schon alles sagt, was aus ihm abzulesen wäre. Machart wie die übrigen Ablagen der
 * App: über eine temporäre Datei geschrieben, damit ein Absturz mitten im Schreiben keine halbe
 * Datei hinterlässt, und eine defekte Datei wird umbenannt statt gelöscht.
 */
class FavoritesRepository(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val mutex = Mutex()
    private val state = MutableStateFlow<List<String>>(emptyList())

    /** Die Kennungen der Favoriten, zuletzt hinzugefügte zuerst. */
    val favorites: StateFlow<List<String>> = state.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock { state.value = readFile() }
    }

    /**
     * Schaltet den Favoriten um und meldet den neuen Zustand.
     *
     * @return true, wenn das Objekt jetzt ein Favorit ist.
     */
    suspend fun toggle(objectId: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = state.value
            val updated = if (objectId in current) {
                current - objectId
            } else {
                listOf(objectId) + current
            }
            writeFile(updated)
            state.value = updated
            objectId in updated
        }
    }

    fun isFavorite(objectId: String): Boolean = objectId in state.value

    private fun readFile(): List<String> {
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString<List<String>>(file.readText())
        } catch (e: Exception) {
            Log.w(TAG, "Konnte die Favoriten nicht lesen", e)
            runCatching { file.renameTo(File(file.parentFile, "$FILE_NAME.corrupt")) }
            emptyList()
        }
    }

    private fun writeFile(ids: List<String>) {
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        temp.writeText(json.encodeToString(ids))
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    private companion object {
        const val TAG = "FavoritesRepository"
        const val FILE_NAME = "favorites.json"
        val json = Json { ignoreUnknownKeys = true }
    }
}
