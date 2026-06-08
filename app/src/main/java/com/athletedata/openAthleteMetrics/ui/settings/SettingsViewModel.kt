package com.athletedata.openAthleteMetrics.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.athletedata.openAthleteMetrics.data.db.AppDatabase
import com.athletedata.openAthleteMetrics.data.model.ThemePreference
import com.athletedata.openAthleteMetrics.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ResetStep { None, Confirm, TypeDelete }

data class SettingsUiState(
    val importPendingUri: Uri? = null,
    val resetStep: ResetStep = ResetStep.None,
    val resetTypedText: String = "",
    val isBusy: Boolean = false,
)

sealed interface SettingsEffect {
    data class ShowSnackbar(val message: String) : SettingsEffect
    data object NavigateToDashboard : SettingsEffect
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val appDatabase: AppDatabase,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val themePreference: StateFlow<ThemePreference> = settingsRepository
        .getThemePreference()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ThemePreference.SYSTEM,
        )

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _effects = Channel<SettingsEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    // ── Theme ─────────────────────────────────────────────────────────────────

    fun setTheme(pref: ThemePreference) {
        viewModelScope.launch { settingsRepository.setThemePreference(pref) }
    }

    // ── Export ────────────────────────────────────────────────────────────────

    fun exportDatabase(destinationUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isBusy = true) }
            try {
                // Flush WAL so all committed data is in the main db file before copying.
                appDatabase.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
                val dbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)
                context.contentResolver.openOutputStream(destinationUri)?.use { out ->
                    dbFile.inputStream().use { input -> input.copyTo(out) }
                } ?: error("Could not open output stream")
                _effects.send(SettingsEffect.ShowSnackbar("Database exported successfully"))
            } catch (e: Exception) {
                _effects.send(SettingsEffect.ShowSnackbar("Export failed: ${e.message}"))
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    // ── Import ────────────────────────────────────────────────────────────────

    fun onImportUriChosen(uri: Uri) {
        _uiState.update { it.copy(importPendingUri = uri) }
    }

    fun dismissImportDialog() {
        _uiState.update { it.copy(importPendingUri = null) }
    }

    fun confirmImport() {
        val uri = _uiState.value.importPendingUri ?: return
        _uiState.update { it.copy(importPendingUri = null, isBusy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Close the Room connection so the file is not locked during the copy.
                // Room's underlying SQLiteOpenHelper will reopen the file on the next DAO call.
                appDatabase.close()
                val dbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dbFile.outputStream().use { out -> input.copyTo(out) }
                } ?: error("Could not open source file")
                _effects.send(SettingsEffect.ShowSnackbar("Database imported. App data replaced."))
                _effects.send(SettingsEffect.NavigateToDashboard)
            } catch (e: Exception) {
                _effects.send(SettingsEffect.ShowSnackbar("Import failed: ${e.message}"))
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    // ── Reset ─────────────────────────────────────────────────────────────────

    fun onResetClicked() {
        _uiState.update { it.copy(resetStep = ResetStep.Confirm) }
    }

    fun onResetContinued() {
        _uiState.update { it.copy(resetStep = ResetStep.TypeDelete, resetTypedText = "") }
    }

    fun onResetTyped(text: String) {
        _uiState.update { it.copy(resetTypedText = text) }
    }

    fun dismissReset() {
        _uiState.update { it.copy(resetStep = ResetStep.None, resetTypedText = "") }
    }

    fun confirmReset() {
        if (_uiState.value.resetTypedText.trim() != "DELETE") return
        _uiState.update { it.copy(resetStep = ResetStep.None, resetTypedText = "", isBusy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Delete in FK-safe order (child tables first).
                // question_definitions is intentionally excluded — those are built-in app data.
                appDatabase.rawDeviceDataDao().deleteAll()
                appDatabase.syncSessionDao().deleteAll()
                appDatabase.deviceDao().deleteAll()
                appDatabase.questionResponseDao().deleteAll()
                appDatabase.metricReadingDao().deleteAll()
                appDatabase.sleepSessionDao().deleteAll()
                appDatabase.dailySummaryDao().deleteAll()
                appDatabase.dailyContextDao().deleteAll()
                appDatabase.activityDao().deleteAll()
                settingsRepository.clearAllPreferences()
                _effects.send(SettingsEffect.ShowSnackbar("All data deleted"))
                _effects.send(SettingsEffect.NavigateToDashboard)
            } catch (e: Exception) {
                _effects.send(SettingsEffect.ShowSnackbar("Reset failed: ${e.message}"))
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }
}
