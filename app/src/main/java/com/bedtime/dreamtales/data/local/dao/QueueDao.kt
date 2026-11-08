package com.bedtime.dreamtales.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.bedtime.dreamtales.data.local.entity.QueueItem
import kotlinx.coroutines.flow.Flow

@Dao
interface QueueDao {

    @Query("SELECT * FROM playback_queue ORDER BY position ASC, id ASC")
    fun getQueueFlow(): Flow<List<QueueItem>>

    @Query("SELECT * FROM playback_queue ORDER BY position ASC, id ASC")
    suspend fun getQueueSync(): List<QueueItem>

    @Query("SELECT storyId FROM playback_queue ORDER BY position ASC, id ASC")
    suspend fun getStoryIdsSync(): List<Long>

    @Query("SELECT COALESCE(MAX(position), -1) FROM playback_queue")
    suspend fun getMaxPosition(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: QueueItem)

    @Query("DELETE FROM playback_queue WHERE storyId = :storyId")
    suspend fun deleteByStoryId(storyId: Long)

    @Query("DELETE FROM playback_queue")
    suspend fun clear()

    /**
     * 按传入顺序重写整个队列。用于上移/下移和清理失效故事。
     */
    @Transaction
    suspend fun replaceAll(storyIds: List<Long>) {
        clear()
        storyIds.distinct().forEachIndexed { index, storyId ->
            insert(QueueItem(storyId = storyId, position = index))
        }
    }
}
