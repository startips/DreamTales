package com.bedtime.dreamtales.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bedtime.dreamtales.data.local.entity.PlaybackPosition
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackPositionDao {

    @Query("SELECT * FROM playback_positions ORDER BY updatedAt DESC LIMIT 1")
    fun getMostRecentFlow(): Flow<PlaybackPosition?>

    @Query("SELECT * FROM playback_positions WHERE storyId = :storyId LIMIT 1")
    suspend fun getByStoryId(storyId: Long): PlaybackPosition?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(position: PlaybackPosition)

    @Query("DELETE FROM playback_positions WHERE storyId = :storyId")
    suspend fun deleteByStoryId(storyId: Long)

    @Query("DELETE FROM playback_positions")
    suspend fun clearAll()
}
