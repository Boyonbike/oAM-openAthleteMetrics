package com.athletedata.openAthleteMetrics.ble

import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncContextSerializer @Inject constructor() {
    fun toJson(context: SyncContext): String = Json.encodeToString(context)
}
