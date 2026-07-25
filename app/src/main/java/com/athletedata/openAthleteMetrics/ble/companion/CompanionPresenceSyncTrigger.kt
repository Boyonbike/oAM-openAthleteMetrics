package com.athletedata.openAthleteMetrics.ble.companion

import com.athletedata.openAthleteMetrics.ble.sync.MultiDeviceSyncOrchestrator
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

private const val TAG = "data-pathway-tracker" // DPT

/**
 * Bridges SyncCompanionDeviceService's onDeviceAppeared callback to the orchestrator, kept
 * as its own class (rather than logic inline in the service) so it's unit-testable with a
 * fake orchestrator without touching the Android service lifecycle.
 */
@Singleton
class CompanionPresenceSyncTrigger @Inject constructor(
    private val multiDeviceSyncOrchestrator: MultiDeviceSyncOrchestrator,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun onDevicePresent() {
        scope.launch {
            val ran = multiDeviceSyncOrchestrator.runIfIdle()
            if (!ran) {
                Timber.tag(TAG).i("CompanionPresenceSyncTrigger: sync already in progress, skipped presence-triggered run")
            }
        }
    }
}
