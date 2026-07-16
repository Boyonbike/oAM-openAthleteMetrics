package com.athletedata.openAthleteMetrics.ui.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.athletedata.openAthleteMetrics.ble.BleConnectionState
import com.athletedata.openAthleteMetrics.ble.BleEngine
import com.athletedata.openAthleteMetrics.data.model.WidgetTemplateId
import com.athletedata.openAthleteMetrics.data.repository.DeviceRepository
import com.athletedata.openAthleteMetrics.ui.overview.DashboardNavigationEvent
import com.athletedata.openAthleteMetrics.ui.overview.WidgetTapDestinationResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class NavHostViewModel @Inject constructor(
    private val bleEngine: BleEngine,
    private val deviceRepository: DeviceRepository,
    private val widgetTapResolver: WidgetTapDestinationResolver,
) : ViewModel() {

    val connectionState: StateFlow<BleConnectionState> = bleEngine.connectionState

    val batteryPct: StateFlow<Int?> = combine(
        connectionState,
        deviceRepository.getAllDevices(),
    ) { state, devices ->
        val address = when (state) {
            is BleConnectionState.Connected -> state.deviceAddress
            is BleConnectionState.Syncing -> state.deviceAddress
            is BleConnectionState.Parsing -> state.deviceAddress
            is BleConnectionState.SyncComplete -> state.deviceAddress
            else -> null
        }
        address?.let { addr -> devices.find { it.bleAddress == addr }?.lastBatteryPct }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun onDevicesLongPressed() {
        val state = connectionState.value
        if (state is BleConnectionState.Connected || state is BleConnectionState.Syncing ||
            state is BleConnectionState.Parsing) {
            bleEngine.startSync()
        }
    }

    /** Reuses the exact tap-destination logic the in-app dashboard tap uses (see [WidgetTapDestinationResolver]). */
    suspend fun resolveWidgetTap(templateId: WidgetTemplateId, date: LocalDate): DashboardNavigationEvent =
        widgetTapResolver.resolve(templateId, date)
}
