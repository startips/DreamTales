package com.bedtime.dreamtales.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bedtime.dreamtales.data.local.entity.Story
import kotlinx.coroutines.flow.Flow

@Dao
interface StoryDao {

    @Query("SELECT * FROM stories ORDER BY isFavorite DESC, playCount DESC, createdAt DESC, id DESC")
    fun getAllStories(): Flow<List<Story>>

    @Query("SELECT * FROM stories ORDER BY isFavorite DESC, playCount DESC, createdAt DESC, id DESC")
    suspend fun getAllStoriesSync(): List<Story>

    @Query("SELECT * FROM stories WHERE id = :id LIMIT 1")
    suspend fun getStoryById(id: Long): Story?

    @Query("SELECT COUNT(*) FROM stories")
    suspend fun getStoryCount(): Int

    @Query("UPDATE stories SET playCount = playCount + 1 WHERE id = :storyId")
    suspend fun incrementPlayCount(storyId: Long)

    @Query("UPDATE stories SET isFavorite = :isFavorite WHERE id = :storyId")
    suspend fun setFavorite(storyId: Long, isFavorite: Boolean)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStory(story: Story): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(stories: List<Story>)
}
