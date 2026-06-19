package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.BaseReadingDao
import com.athletedata.openAthleteMetrics.data.model.DataSource
import timber.log.Timber

abstract class BaseRoomReadingRepository<T, D : BaseReadingDao<T>> : BaseReadingRepository<T> {

    protected abstract val dao: D
    protected abstract val readingLabel: String

    override suspend fun insert(entity: T) {
        try {
            dao.insert(entity)
        } catch (e: Exception) {
            Timber.e(e, "Failed to insert $readingLabel reading")
            throw e
        }
    }

    override suspend fun insertAll(entities: List<T>) {
        try {
            dao.insertAll(entities)
        } catch (e: Exception) {
            Timber.e(e, "Failed to insert all $readingLabel readings")
            throw e
        }
    }

    override suspend fun insertAllOrIgnore(entities: List<T>): List<Long> {
        return try {
            dao.insertAllOrIgnore(entities)
        } catch (e: Exception) {
            Timber.e(e, "Failed to insert all $readingLabel readings (or-ignore)")
            throw e
        }
    }

    override suspend fun deleteBySource(source: DataSource) {
        try {
            dao.deleteBySource(source)
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete $readingLabel readings by source")
            throw e
        }
    }

    override suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<T> =
        dao.getReadingsInRangeOnce(startMs, endMs)
}
