package com.athletedata.openAthleteMetrics.data.db

import java.time.LocalDate
import java.time.ZoneId

internal fun LocalDate.toLocalStartMs(): Long =
    atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
