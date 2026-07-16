package com.athletedata.openAthleteMetrics.ble

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * OEM battery managers (Xiaomi/Huawei/Samsung-style) can still kill the process despite a
 * running foreground service; asking for an exemption from standard Android battery
 * optimization reduces (but doesn't guarantee against) that risk.
 */
object BatteryOptimizationHelper {

    fun isExempt(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return true
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun requestExemptionIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
}
