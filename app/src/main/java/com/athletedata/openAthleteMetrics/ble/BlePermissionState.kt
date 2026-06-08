package com.athletedata.openAthleteMetrics.ble

import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

class BlePermissionState(
    val allGranted: Boolean,
    val shouldShowRationale: Boolean,
    val requestPermissions: () -> Unit,
)

@Composable
fun rememberBlePermissionState(): BlePermissionState {
    val context = LocalContext.current
    var allGranted by remember { mutableStateOf(BlePermissionHelper.allGranted(context)) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants -> allGranted = grants.values.all { it } }

    val rationale = remember(allGranted) {
        val activity = context as? Activity ?: return@remember false
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            Manifest.permission.BLUETOOTH_SCAN
        else
            Manifest.permission.ACCESS_FINE_LOCATION
        activity.shouldShowRequestPermissionRationale(perm)
    }

    return BlePermissionState(
        allGranted = allGranted,
        shouldShowRationale = rationale,
        requestPermissions = {
            launcher.launch(BlePermissionHelper.requiredPermissions().toTypedArray())
        },
    )
}
