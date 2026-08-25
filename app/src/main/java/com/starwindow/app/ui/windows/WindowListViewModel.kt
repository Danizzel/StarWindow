package com.starwindow.app.ui.windows

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starwindow.app.AppContainer
import com.starwindow.app.core.geometry.SkyWindow
import com.starwindow.app.data.tracking.TrackedWindow
import com.starwindow.app.data.tracking.TrackingStore
import com.starwindow.app.data.windows.SkyWindowRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class WindowListViewModel(
    private val repository: SkyWindowRepository,
    private val trackingStore: TrackingStore,
) : ViewModel() {

    val windows = repository.windows

    /** Which window the viewfinder is currently pointing at, so the list can mark it. */
    val trackedWindowId: StateFlow<String?> = trackingStore.target
        .map { (it as? TrackedWindow)?.windowId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch { repository.load() }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.delete(id) }
        // A pointer to a window that no longer exists reads as a bug, not as a feature.
        trackingStore.clearWindow(id)
    }

    /**
     * Points the viewfinder at a saved window.
     *
     * A window is horizon-fixed, so this is the answer to "where was that gap between the roofs
     * again" — the same arrow that leads to a galaxy leads back to a window, and once the phone is
     * turned far enough the window's own outline appears in the picture.
     */
    fun track(window: SkyWindow) = trackingStore.track(TrackedWindow.of(window))

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                WindowListViewModel(container.windowRepository, container.trackingStore)
            }
        }
    }
}
