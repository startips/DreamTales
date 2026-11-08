package com.bedtime.dreamtales.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bedtime.dreamtales.data.local.entity.StoryPage
import kotlinx.coroutines.flow.Flow

@Dao
interface StoryPageDao {

    @Query("SELECT * FROM story_pages WHERE storyId = :storyId ORDER BY pageIndex ASC")
    fun getPagesFlow(storyId: Long): Flow<List<StoryPage>>

    @Query("SELECT * FROM story_pages WHERE storyId = :storyId ORDER BY pageIndex ASC")
    suspend fun getPagesSync(storyId: Long): List<StoryPage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(pages: List<StoryPage>)

    @Query("DELETE FROM story_pages WHERE storyId = :storyId")
    suspend fun deleteForStory(storyId: Long)

    @Query("SELECT COUNT(*) FROM story_pages WHERE storyId = :storyId")
    suspend fun countForStory(storyId: Long): Int
}
