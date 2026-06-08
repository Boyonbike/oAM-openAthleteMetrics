package com.athletedata.openAthleteMetrics.ui.history

import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HistorySessionState @Inject constructor() {
    var metricKey: String? = null
    var date: LocalDate = LocalDate.now()
}
