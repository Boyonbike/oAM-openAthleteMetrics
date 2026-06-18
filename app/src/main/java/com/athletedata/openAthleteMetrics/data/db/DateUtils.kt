package com.athletedata.openAthleteMetrics.data.db

import java.time.LocalDate
import java.time.ZoneOffset

internal fun LocalDate.toUtcStartMs(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
