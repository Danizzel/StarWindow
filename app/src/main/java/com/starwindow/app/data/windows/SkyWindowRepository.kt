package com.starwindow.app.data.windows

import android.content.Context
import android.util.Log
import com.starwindow.app.core.geometry.SkyWindow
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Stores the saved windows as a single JSON file in the app's private directory.
 *
 * A handful of windows per observing site is the realistic scale, so a file keeps things simple
 * and — more usefully — makes the data trivial to export, diff and hand to a desktop tool. Swap in
 * Room here if the app ever grows to thousands of entries.
 */
class SkyWindowRepository(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val mutex = Mutex()
    private val state = MutableStateFlow<List<SkyWindow>>(emptyList())

    val windows: StateFlow<List<SkyWindow>> = state.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            state.value = readFile()
        }
    }

    suspend fun save(window: SkyWindow) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = state.value.filterNot { it.id == window.id }
            val updated = (current + window).sortedByDescending { it.capturedAtMillis }
            writeFile(updated)
            state.value = updated
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = state.value.filterNot { it.id == id }
            writeFile(updated)
            state.value = updated
        }
    }

    fun find(id: String): SkyWindow? = state.value.firstOrNull { it.id == id }

    /** The stored JSON, for sharing or debugging. */
    suspend fun exportJson(): String = withContext(Dispatchers.IO) {
        json.encodeToString(state.value)
    }

    private fun readFile(): List<SkyWindow> {
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString<List<SkyWindow>>(file.readText())
        } catch (e: Exception) {
            // A corrupt file must not brick the app; keep it around for a possible manual rescue.
            Log.w(TAG, "Konnte gespeicherte Fenster nicht lesen", e)
            runCatching { file.renameTo(File(file.parentFile, "$FILE_NAME.corrupt")) }
            emptyList()
        }
    }

    private fun writeFile(windows: List<SkyWindow>) {
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        temp.writeText(json.encodeToString(windows))
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }

    companion object {
        private const val TAG = "SkyWindowRepository"
        private const val FILE_NAME = "sky_windows.json"
        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
