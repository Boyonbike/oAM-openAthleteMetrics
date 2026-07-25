package com.athletedata.openAthleteMetrics.ble.companion

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.os.Build
import androidx.annotation.RequiresApi
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Lets the OS wake this process when a CDM-associated band comes into BLE range, without an
 * active scan. CompanionDeviceService is API 33+ only (see AndroidManifest's <service>
 * declaration and CompanionDeviceAssociator.startObservingPresence, both gated accordingly) -
 * on lower API levels BackgroundSyncWorker's periodic cadence is the only background trigger.
 * Pure adapter with no sync logic of its own, matching BleSyncService's thin-anchor pattern.
 */
@AndroidEntryPoint
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class SyncCompanionDeviceService : CompanionDeviceService() {

    @Inject
    lateinit var presenceSyncTrigger: CompanionPresenceSyncTrigger

    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        presenceSyncTrigger.onDevicePresent()
    }

    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        // No-op: nothing to tear down: syncOneDevice's own connect/disconnect cycle owns the
        // BLE connection lifecycle regardless of what triggered the run.
    }
}
