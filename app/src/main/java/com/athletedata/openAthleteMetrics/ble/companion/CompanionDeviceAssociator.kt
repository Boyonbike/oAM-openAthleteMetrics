package com.athletedata.openAthleteMetrics.ble.companion

import android.content.IntentSender

/**
 * Wraps the CompanionDeviceManager association request so DevicesViewModel can be tested
 * with a fake, without a real CDM consent UI or a real companion device. A declined or
 * unavailable association (null result) is not an error - the caller falls back to
 * periodic-only background sync for that device.
 */
interface CompanionDeviceAssociator {

    /**
     * Requests a CDM association for [bleAddress]. Returns the [IntentSender] to launch via
     * `ActivityResultContracts.StartIntentSenderForResult` for the user to confirm, or null
     * if CDM is unavailable on this device/OS or the request otherwise failed outright.
     */
    suspend fun requestAssociation(bleAddress: String, deviceName: String): IntentSender?

    /**
     * Arms presence observation for [bleAddress] so the OS can wake the app via
     * SyncCompanionDeviceService.onDeviceAppeared without an active scan. No-ops below API 33
     * (CompanionDeviceService, the callback target, does not exist on earlier API levels).
     * Call only after [requestAssociation]'s IntentSender has been confirmed by the user.
     */
    fun startObservingPresence(bleAddress: String)
}
