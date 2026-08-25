package com.starwindow.app.data.tracking

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The one thing the viewfinder is currently pointing the user at.
 *
 * A single value rather than a list: the arrow at the edge of the screen only makes sense for one
 * target at a time, and two arrows would be worse than none.
 *
 * The whole entry is stored, not just its id. Resolving an id would mean the tracked target
 * silently disappears whenever the catalogue changes or a window is deleted underneath it, and the
 * entry is a few hundred bytes.
 */
class TrackingStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    private val state = MutableStateFlow(read())

    /** The tracked object or window, or null when nothing is being tracked. */
    val target: StateFlow<TrackTarget?> = state.asStateFlow()

    val current: TrackTarget? get() = state.value

    fun track(target: TrackTarget) {
        prefs.edit { putString(KEY_TARGET, json.encodeToString(target)) }
        state.value = target
    }

    fun clear() {
        prefs.edit { remove(KEY_TARGET) }
        state.value = null
    }

    /**
     * Stops tracking a window that no longer exists. Leaving it would point at a window the user
     * has just deleted, which reads as a bug rather than as a feature.
     */
    fun clearWindow(windowId: String) {
        val current = state.value
        if (current is TrackedWindow && current.windowId == windowId) clear()
    }

    private fun read(): TrackTarget? {
        val stored = prefs.getString(KEY_TARGET, null) ?: return null
        return try {
            json.decodeFromString<TrackTarget>(stored)
        } catch (e: Exception) {
            // A target that cannot be read is not worth crashing over; start without one.
            Log.w(TAG, "Verfolgtes Ziel nicht lesbar, Verfolgung wird zurückgesetzt", e)
            prefs.edit { remove(KEY_TARGET) }
            null
        }
    }

    companion object {
        private const val TAG = "TrackingStore"
        private const val NAME = "starwindow_tracking"
        private const val KEY_TARGET = "target_json"
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
