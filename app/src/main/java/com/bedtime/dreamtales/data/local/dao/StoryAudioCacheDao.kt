package com.bedtime.dreamtales.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bedtime.dreamtales.data.local.entity.StoryAudioCache
import kotlinx.coroutines.flow.Flow

@Dao
interface StoryAudioCacheDao {

    @Query("SELECT * FROM story_audio_caches WHERE storyId = :storyId AND voiceProfileId = :voiceProfileId LIMIT 1")
    suspend fun getCache(storyId: Long, voiceProfileId: Long): StoryAudioCache?

    @Query("SELECT * FROM story_audio_caches WHERE storyId = :storyId AND voiceProfileId = :voiceProfileId LIMIT 1")
    fun getCacheFlow(storyId: Long, voiceProfileId: Long): Flow<StoryAudioCache?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(cache: StoryAudioCache): Long

    @Query("DELETE FROM story_audio_caches WHERE storyId = :storyId AND voiceProfileId = :voiceProfileId")
    suspend fun deleteCache(storyId: Long, voiceProfileId: Long)

    @Query("SELECT * FROM story_audio_caches WHERE isComplete = 1")
    fun getAllCompleteCaches(): Flow<List<StoryAudioCache>>
}
