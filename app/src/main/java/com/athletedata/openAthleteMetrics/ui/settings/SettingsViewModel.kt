package com.athletedata.openAthleteMetrics.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.athletedata.openAthleteMetrics.ble.BleEngine
import com.athletedata.openAthleteMetrics.ble.driver.DriverRegistry
import com.athletedata.openAthleteMetrics.ble.driver.DriverStorage
import com.athletedata.openAthleteMetrics.data.db.AppDatabase
import com.athletedata.openAthleteMetrics.data.model.ThemePreference
import com.athletedata.openAthleteMetrics.data.repository.DeviceRepository
import com.athletedata.openAthleteMetrics.data.repository.SettingsRepository
import com.athletedata.openAthleteMetrics.data.repository.UserProfileRepository
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

data class SettingsUiState(
    val importPendingUri: Uri? = null,
    val isBusy: Boolean = false,
    // RESET-SYSTEM
    val showResetSheet: Boolean = false,
    val metricsSelected: Boolean = false,
    val profileSelected: Boolean = false,
    val devicesSelected: Boolean = false,
    val showResetConfirmDialog: Boolean = false,
    val resetConfirmText: String = "",
)

sealed interface SettingsEffect {
    data class ShowSnackbar(val message: String) : SettingsEffect
    data object NavigateToDashboard : SettingsEffect
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val appDatabase: AppDatabase,
    @param:ApplicationContext private val context: Context,
    // RESET-SYSTEM
    private val userProfileRepository: UserProfileRepository,
    private val deviceRepository: DeviceRepository,
    private val driverRegistry: DriverRegistry,
    private val driverStorage: DriverStorage,
    private val bleEngine: BleEngine,
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
    // RESET-SYSTEM

    fun openResetSheet() {
        _uiState.update { it.copy(showResetSheet = true) }
    }

    fun dismissResetSheet() {
        _uiState.update {
            it.copy(
                showResetSheet = false,
                metricsSelected = false,
                profileSelected = false,
                devicesSelected = false,
                showResetConfirmDialog = false,
                resetConfirmText = "",
            )
        }
    }

    fun toggleMetrics() {
        _uiState.update { it.copy(metricsSelected = !it.metricsSelected) }
    }

    fun toggleProfile() {
        _uiState.update { it.copy(profileSelected = !it.profileSelected) }
    }

    fun toggleDevices() {
        _uiState.update { it.copy(devicesSelected = !it.devicesSelected) }
    }

    fun onDeleteSelectedClicked() {
        val s = _uiState.value
        if (!s.metricsSelected && !s.profileSelected && !s.devicesSelected) return
        _uiState.update { it.copy(showResetConfirmDialog = true, resetConfirmText = "") }
    }

    fun onResetConfirmTextChanged(text: String) {
        _uiState.update { it.copy(resetConfirmText = text) }
    }

    fun dismissResetConfirmDialog() {
        _uiState.update { it.copy(showResetConfirmDialog = false, resetConfirmText = "") }
    }

    fun executeReset() {
        val state = _uiState.value
        if (state.resetConfirmText != "DELETE") return
        _uiState.update { it.copy(isBusy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (state.metricsSelected) {
                    // Delete in FK-safe order (child tables first).
                    // question_definitions is intentionally excluded — those are built-in app data.
                    appDatabase.questionResponseDao().deleteAll()
                    appDatabase.sleepStageDao().deleteAll()
                    appDatabase.activeCalorieReadingDao().deleteAll()
                    appDatabase.totalCalorieReadingDao().deleteAll()
                    appDatabase.hrReadingDao().deleteAll()
                    appDatabase.hrvReadingDao().deleteAll()
                    appDatabase.spO2ReadingDao().deleteAll()
                    appDatabase.respirationReadingDao().deleteAll()
                    appDatabase.skinTempReadingDao().deleteAll()
                    appDatabase.stepsReadingDao().deleteAll()
                    appDatabase.bloodPressureReadingDao().deleteAll()
                    appDatabase.glucoseReadingDao().deleteAll()
                    appDatabase.metricReadingStagingDao().deleteAll()
                    appDatabase.sleepSessionDao().deleteAll()
                    appDatabase.dailySummaryDao().deleteAll()
                    appDatabase.dailyContextDao().deleteAll()
                    appDatabase.activityDao().deleteAll()
                }
                if (state.profileSelected) {
                    userProfileRepository.deleteProfile()
                }
                if (state.devicesSelected) {
                    bleEngine.disconnect()
                    val drivers = driverStorage.loadAllDrivers()
                    drivers.forEach { driverRegistry.unregister(it.id) }
                    drivers.forEach { driverStorage.deleteDriver(it.id) }
                    deviceRepository.deleteAll()
                }
                _effects.send(SettingsEffect.ShowSnackbar("Deletion complete."))
            } catch (e: Exception) {
                _effects.send(SettingsEffect.ShowSnackbar("Error during deletion — some data may not have been removed."))
            } finally {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        showResetSheet = false,
                        showResetConfirmDialog = false,
                        metricsSelected = false,
                        profileSelected = false,
                        devicesSelected = false,
                        resetConfirmText = "",
                    )
                }
            }
        }
    }
}
