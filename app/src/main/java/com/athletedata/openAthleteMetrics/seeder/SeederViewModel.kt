package com.athletedata.openAthleteMetrics.seeder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

sealed class SeederState {
    object Idle : SeederState()
    data class Running(val progress: Float) : SeederState()
    object Done : SeederState()
    data class PartialSuccess(val failedDates: List<LocalDate>) : SeederState()
    data class Error(val message: String) : SeederState()
}

@HiltViewModel
class SeederViewModel @Inject constructor(
    private val seederService: SeederService,
) : ViewModel() {

    private val _state = MutableStateFlow<SeederState>(SeederState.Idle)
    val state: StateFlow<SeederState> = _state.asStateFlow()

    fun seedThirtyDays() {
        if (_state.value is SeederState.Running) return
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = SeederState.Running(0f)
            try {
                val failed = seederService.seedThirtyDays { progress ->
                    _state.value = SeederState.Running(progress)
                }
                _state.value = if (failed.isEmpty()) SeederState.Done
                               else SeederState.PartialSuccess(failed)
            } catch (e: Exception) {
                _state.value = SeederState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun seedToday() {
        if (_state.value is SeederState.Running) return
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = SeederState.Running(0f)
            try {
                val failed = seederService.seedToday { progress ->
                    _state.value = SeederState.Running(progress)
                }
                _state.value = if (failed.isEmpty()) SeederState.Done
                               else SeederState.PartialSuccess(failed)
            } catch (e: Exception) {
                _state.value = SeederState.Error(e.message ?: "Unknown error")
            }
        }
    }

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
}
