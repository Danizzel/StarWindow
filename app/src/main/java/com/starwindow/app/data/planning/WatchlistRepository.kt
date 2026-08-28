package com.starwindow.app.data.planning

import android.content.Context
import android.util.Log
import com.starwindow.app.data.catalog.SkyObject
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
import java.time.LocalDate
import java.util.UUID

/**
 * Die vorgemerkten Objekte, auf der Platte.
 *
 * Machart wie [PlanRepository] und `SkyWindowRepository`: eine JSON-Datei, über eine temporäre
 * geschrieben, damit ein Absturz mitten im Schreiben keine halbe Datei hinterlässt, und eine
 * defekte Datei wird umbenannt statt gelöscht.
 *
 * Der Unterschied zur Planung: Hier hängt kein Alarm an einem einzelnen Eintrag. Geprüft wird die
 * ganze Liste einmal am Tag ([WatchScheduler]), weil die Bedingung nicht an einem Datum hängt,
 * sondern am Himmel und am Wetter — und beides ändert sich täglich. Ein Eintrag mehr in der Liste
 * kostet deshalb keinen Alarm mehr, sondern ein paar Dutzend trigonometrische Auswertungen in der
 * nächtlichen Prüfung.
 */
class WatchlistRepository(context: Context) {

    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, FILE_NAME)
    private val mutex = Mutex()
    private val state = MutableStateFlow<List<WatchedObject>>(emptyList())

    /** Alles Vorgemerkte, zuletzt Hinzugefügtes zuerst. */
    val watched: StateFlow<List<WatchedObject>> = state.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock { state.value = readFile() }
    }

    /**
     * Merkt ein Objekt vor, oder gibt den bestehenden Eintrag zurück.
     *
     * Kein zweiter Eintrag für dasselbe Objekt: „Bescheid geben" ist ein Zustand und keine
     * Handlung, die sich stapeln ließe.
     */
    suspend fun add(obj: SkyObject): WatchedObject = withContext(Dispatchers.IO) {
        mutex.withLock {
            state.value.firstOrNull { it.objectId == obj.id }?.let { return@withLock it }
            val entry = WatchedObject.of(obj, newId())
            val updated = listOf(entry) + state.value
            writeFile(updated)
            state.value = updated
            // Ohne Alarm bliebe die Liste eine Liste. Der Alarm wird beim ersten Eintrag gesetzt
            // und danach bei jedem weiteren erneut — das ist derselbe Alarm, kein zweiter.
            WatchScheduler(appContext).scheduleDailyCheck()
            entry
        }
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = state.value.filterNot { it.id == id }
            writeFile(updated)
            state.value = updated
            if (updated.isEmpty()) WatchScheduler(appContext).cancelDailyCheck()
        }
    }

    suspend fun removeObject(objectId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = state.value.filterNot { it.objectId == objectId }
            writeFile(updated)
            state.value = updated
            if (updated.isEmpty()) WatchScheduler(appContext).cancelDailyCheck()
        }
    }

    /** Ändert einen Eintrag. Der Aufrufer bekommt den alten und gibt den neuen zurück. */
    suspend fun update(id: String, transform: (WatchedObject) -> WatchedObject) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val updated = state.value.map { if (it.id == id) transform(it) else it }
                writeFile(updated)
                state.value = updated
            }
        }

    /**
     * Hält fest, dass für diese Nacht gemeldet wurde.
     *
     * Wird aus der nächtlichen Prüfung heraus aufgerufen und ist der Grund, warum die Ruhezeit
     * überhaupt greift: Ohne diesen Vermerk stünde morgen dieselbe Rechnung mit demselben Ergebnis
     * an, und die Meldung käme noch einmal.
     *
     * Geht bewusst über die **Datei** und nicht über [watched]: Der Aufrufer ist ein Empfänger in
     * einem frisch gestarteten Prozess, in dem der Zustand leer ist. Würde hier über den leeren
     * Zustand abgebildet, schriebe die Prüfung eine leere Liste zurück und löschte die Merkliste
     * genau in dem Moment, in dem sie das erste Mal etwas gemeldet hat.
     */
    suspend fun markNotified(ids: Collection<String>, date: LocalDate) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        mutex.withLock {
            val updated = readFile().map { entry ->
                if (entry.id in ids) entry.copy(lastNotifiedEpochDay = date.toEpochDay()) else entry
            }
            writeFile(updated)
            state.value = updated
        }
    }

    fun isWatched(objectId: String): Boolean = state.value.any { it.objectId == objectId }

    fun forObject(objectId: String): WatchedObject? =
        state.value.firstOrNull { it.objectId == objectId }

    /** Liest die Liste einmalig, ohne den Zustand zu berühren — für die nächtliche Prüfung. */
    suspend fun readOnce(): List<WatchedObject> = withContext(Dispatchers.IO) {
        mutex.withLock { readFile() }
    }

    private fun readFile(): List<WatchedObject> {
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString<List<WatchedObject>>(file.readText())
        } catch (e: Exception) {
            Log.w(TAG, "Konnte die Merkliste nicht lesen", e)
            runCatching { file.renameTo(File(file.parentFile, "$FILE_NAME.corrupt")) }
            emptyList()
        }
    }

    private fun writeFile(entries: List<WatchedObject>) {
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        temp.writeText(json.encodeToString(entries))
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }

    companion object {
        private const val TAG = "WatchlistRepository"
        private const val FILE_NAME = "watchlist.json"

        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun newId(): String = UUID.randomUUID().toString()
    }
}
