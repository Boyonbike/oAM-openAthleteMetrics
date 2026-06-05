package com.athletedata.app.data.repository

import com.athletedata.app.data.db.DailyContextDao
import com.athletedata.app.data.db.toEntity
import com.athletedata.app.data.db.toModel
import com.athletedata.app.data.model.DailyContext
import com.athletedata.app.data.model.DataSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomDailyContextRepository @Inject constructor(
    private val dao: DailyContextDao,
) : DailyContextRepository {

    /**
     * Inserts or replaces the context for [context.date].
     * Called by the Daily Questions screen ViewModel on save, and by the seeder.
     */
    override suspend fun upsert(context: DailyContext) {
        dao.upsert(context.toEntity())
    }

    /**
     * Live stream of the context for [date].
     * Observed by the Daily Overview and Daily Questions ViewModels.
     * Emits null when the user hasn't filled in questions for that day yet.
     */
    override fun getForDate(date: LocalDate): Flow<DailyContext?> =
        dao.getContextForDate(date).map { it?.toModel() }

    /**
     * Live stream of contexts in [[from], [to]] inclusive, oldest first.
     * Observed by the Questions history screen.
     */
    override fun getForRange(from: LocalDate, to: LocalDate): Flow<List<DailyContext>> =
        dao.getContextsForRange(from, to).map { entities -> entities.map { it.toModel() } }

    /**
     * Removes context rows for the given [source].
     *
     * Because `daily_context` has no source column, [DataSource.SEEDER] maps to
     * a delete-all. Other source values are no-ops — the only caller is the debug
     * seeder cleanup, which always passes SEEDER.
     */
    override suspend fun deleteBySource(source: DataSource) {
        if (source == DataSource.SEEDER) dao.deleteAll()
    }
}
