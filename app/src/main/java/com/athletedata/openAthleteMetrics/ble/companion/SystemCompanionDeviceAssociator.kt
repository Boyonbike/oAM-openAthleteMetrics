package com.athletedata.openAthleteMetrics.ble.companion

import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.IntentSender
import android.os.Build
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber

private const val TAG = "data-pathway-tracker" // DPT

@Singleton
class SystemCompanionDeviceAssociator @Inject constructor(
    private val companionDeviceManager: CompanionDeviceManager,
) : CompanionDeviceAssociator {

    // associate()'s Callback is delivery-based, not suspend-friendly on its own - bridged via
    // suspendCancellableCoroutine so callers (DevicesViewModel) can just await the result.
    // Uses the Handler-based associate() overload (API 26+) rather than the Executor-based one
    // (API 33+) so this works down to minSdk.
    override suspend fun requestAssociation(bleAddress: String, deviceName: String): IntentSender? =
        suspendCancellableCoroutine { continuation ->
            val filter = BluetoothDeviceFilter.Builder()
                .setAddress(bleAddress)
                .build()
            val request = AssociationRequest.Builder()
                .addDeviceFilter(filter)
                .setSingleDevice(true)
                .build()
            val callback = object : CompanionDeviceManager.Callback() {
                override fun onDeviceFound(chooserLauncher: IntentSender) {
                    if (continuation.isActive) continuation.resume(chooserLauncher) {}
                }

                override fun onFailure(error: CharSequence?) {
                    Timber.tag(TAG).w("CDM association request failed for %s: %s", deviceName, error)
                    if (continuation.isActive) continuation.resume(null) {}
                }
            }
            try {
                companionDeviceManager.associate(request, callback, null)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "CDM unavailable, skipping association for %s", deviceName)
                if (continuation.isActive) continuation.resume(null) {}
            }
        }

    override fun startObservingPresence(bleAddress: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        try {
            @Suppress("DEPRECATION")
            companionDeviceManager.startObservingDevicePresence(bleAddress)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to start observing device presence for %s", bleAddress)
        }
    }
}
