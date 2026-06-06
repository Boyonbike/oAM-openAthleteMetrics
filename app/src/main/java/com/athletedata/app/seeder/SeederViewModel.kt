package com.athletedata.app.seeder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class SeederState {
    object Idle : SeederState()
    data class Running(val progress: Float) : SeederState()
    object Done : SeederState()
    data class Error(val message: String) : SeederState()
}

@HiltViewModel
class SeederViewModel @Inject constructor(
    private val seederService: SeederService,
) : ViewModel() {

    private val _state = MutableStateFlow<SeederState>(SeederState.Idle)
    val state: StateFlow<SeederState> = _state.asStateFlow()

    fun seedThirtyDays() = seed(30)

    fun seedToday() = seed(1)

    fun clearSeederData() {
        if (_state.value is SeederState.Running) return
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = SeederState.Running(0f)
            try {
                seederService.clearSeederData { progress ->
                    _state.value = SeederState.Running(progress)
                }
                _state.value = SeederState.Done
            } catch (e: Exception) {
                _state.value = SeederState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun resetState() {
        if (_state.value !is SeederState.Running) _state.value = SeederState.Idle
    }

    private fun seed(days: Int) {
        if (_state.value is SeederState.Running) return
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = SeederState.Running(0f)
            try {
                seederService.seedDays(days) { progress ->
                    _state.value = SeederState.Running(progress)
                }
                _state.value = SeederState.Done
            } catch (e: Exception) {
                _state.value = SeederState.Error(e.message ?: "Unknown error")
            }
        }
    }
}
