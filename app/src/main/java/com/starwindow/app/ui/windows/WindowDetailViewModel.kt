package com.starwindow.app.ui.windows

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starwindow.app.AppContainer
import com.starwindow.app.core.geometry.SkyWindow
import com.starwindow.app.data.catalog.CatalogRepository
import com.starwindow.app.data.windows.SkyWindowRepository
import com.starwindow.app.domain.TransitCalculator
import com.starwindow.app.domain.TransitSearchResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WindowDetailUiState(
    val window: SkyWindow? = null,
    val result: TransitSearchResult? = null,
    val hoursAhead: Int = 24,
    val magnitudeLimit: Double = 8.0,
    val isSearching: Boolean = false,
    val error: String? = null,
)

class WindowDetailViewModel(
    private val windowId: String,
    private val windowRepository: SkyWindowRepository,
    private val catalogRepository: CatalogRepository,
    private val transitCalculator: TransitCalculator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WindowDetailUiState())
    val uiState: StateFlow<WindowDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            windowRepository.load()
            val window = windowRepository.find(windowId)
            _uiState.update { it.copy(window = window) }
            if (window == null) {
                _uiState.update { it.copy(error = "Fenster nicht gefunden") }
            } else {
                search()
            }
        }
    }

    fun setHoursAhead(hours: Int) {
        _uiState.update { it.copy(hoursAhead = hours) }
        search()
    }

    fun setMagnitudeLimit(limit: Double) {
        _uiState.update { it.copy(magnitudeLimit = limit) }
        search()
    }

    fun search() {
        val window = _uiState.value.window ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true, error = null) }
            val state = _uiState.value
            val objects = catalogRepository.objectsVisibleFrom(
                latitudeDeg = window.observer.latitudeDeg,
                magnitudeLimit = state.magnitudeLimit,
            )
            val now = System.currentTimeMillis()
            val result = runCatching {
                transitCalculator.search(
                    window = window,
                    objects = objects,
                    fromMillis = now,
                    toMillis = now + state.hoursAhead * 3_600_000L,
                )
            }
            _uiState.update {
                it.copy(
                    isSearching = false,
                    result = result.getOrNull(),
                    error = result.exceptionOrNull()?.message,
                )
            }
        }
    }

    companion object {
        fun factory(container: AppContainer, windowId: String) = viewModelFactory {
            initializer {
                WindowDetailViewModel(
                    windowId = windowId,
                    windowRepository = container.windowRepository,
                    catalogRepository = container.catalogRepository,
                    transitCalculator = container.transitCalculator,
                )
            }
        }
    }
}
