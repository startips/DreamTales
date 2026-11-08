package com.bedtime.dreamtales.data.repository

import android.content.Context
import com.bedtime.dreamtales.core.playback.PlaybackResumePolicy
import com.bedtime.dreamtales.data.local.AppDatabase
import com.bedtime.dreamtales.data.local.entity.PlaybackPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * 断点续播进度仓库。
 */
class PlaybackProgressRepository(context: Context) {

    private val dao = AppDatabase.getInstance(context).playbackPositionDao()

    fun getMostRecentFlow(): Flow<PlaybackPosition?> = dao.getMostRecentFlow()

    suspend fun getPosition(storyId: Long): PlaybackPosition? = withContext(Dispatchers.IO) {
        dao.getByStoryId(storyId)
    }

    suspend fun save(storyId: Long, positionMs: Long, durationMs: Long) = withContext(Dispatchers.IO) {
        if (storyId <= 0L || positionMs < 0L) return@withContext
        if (!PlaybackResumePolicy.shouldPersist(positionMs, durationMs)) {
            dao.deleteByStoryId(storyId)
            return@withContext
        }
        dao.upsert(
            PlaybackPosition(
                storyId = storyId,
                positionMs = positionMs,
                durationMs = durationMs.coerceAtLeast(0L),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun clear(storyId: Long) = withContext(Dispatchers.IO) {
        if (storyId > 0L) dao.deleteByStoryId(storyId)
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        dao.clearAll()
    }
}
