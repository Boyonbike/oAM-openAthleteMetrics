package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.HrReadingDao
import com.athletedata.openAthleteMetrics.data.db.HrReadingEntity
import com.athletedata.openAthleteMetrics.data.db.toLocalStartMs
import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomHrReadingRepository @Inject constructor(
    override val dao: HrReadingDao,
) : BaseRoomReadingRepository<HrReadingEntity, HrReadingDao>(), HrReadingRepository {

    override val readingLabel = "HR"

    override fun hasSeederDataForDate(date: LocalDate): Flow<Boolean> {
        val startMs = date.toLocalStartMs()
        val endMs = date.plusDays(1).toLocalStartMs()
        return dao.countSourceDataInRange(DataSource.SEEDER, startMs, endMs).map { it > 0 }
    }
}
