package com.bedtime.dreamtales.data.repository

import android.content.Context
import com.bedtime.dreamtales.core.stats.SleepSessionSnapshot
import com.bedtime.dreamtales.core.stats.SleepStatsCalculator
import com.bedtime.dreamtales.core.stats.SleepWeeklyReport
import com.bedtime.dreamtales.data.local.AppDatabase
import com.bedtime.dreamtales.data.local.entity.SleepSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 睡前陪伴日志仓库：负责写入会话与生成周报。
 */
class SleepStatsRepository(context: Context) {

    private val sessionDao = AppDatabase.getInstance(context).sleepSessionDao()

    fun getWeeklyReportFlow(childProfileId: Long? = null): Flow<SleepWeeklyReport> {
        return sessionDao.getRecentFlow(limit = 300).map { sessions ->
            SleepStatsCalculator.build(
                sessions = sessions.map { it.toSnapshot() },
                childProfileId = childProfileId
            )
        }
    }

    suspend fun recordSession(session: SleepSession): Long = sessionDao.insert(session)

    suspend fun getRecentSessions(limit: Int = 60): List<SleepSession> = sessionDao.getRecentSync(limit)

    suspend fun getAllSessions(): List<SleepSession> = sessionDao.getAllSync()

    suspend fun clearAll() = sessionDao.clearAll()

    private fun SleepSession.toSnapshot(): SleepSessionSnapshot {
        return SleepSessionSnapshot(
            storyTitle = storyTitle,
            startedAt = startedAt,
            endedAt = endedAt,
            listenedSeconds = listenedSeconds,
            estimatedSleepSeconds = estimatedSleepSeconds,
            endedBy = endedBy,
            childProfileId = childProfileId
        )
    }
}
