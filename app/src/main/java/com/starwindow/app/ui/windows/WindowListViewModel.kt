package com.starwindow.app.ui.windows

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starwindow.app.AppContainer
import com.starwindow.app.data.windows.SkyWindowRepository
import kotlinx.coroutines.launch

class WindowListViewModel(private val repository: SkyWindowRepository) : ViewModel() {

    val windows = repository.windows

    init {
        viewModelScope.launch { repository.load() }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { WindowListViewModel(container.windowRepository) }
        }
    }
}
