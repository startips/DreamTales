package com.bedtime.dreamtales.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bedtime.dreamtales.data.local.entity.SleepSession
import kotlinx.coroutines.flow.Flow

@Dao
interface SleepSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SleepSession): Long

    @Query("SELECT * FROM sleep_sessions ORDER BY endedAt DESC LIMIT :limit")
    fun getRecentFlow(limit: Int = 60): Flow<List<SleepSession>>

    @Query("SELECT * FROM sleep_sessions ORDER BY endedAt DESC LIMIT :limit")
    suspend fun getRecentSync(limit: Int = 60): List<SleepSession>

    @Query("SELECT * FROM sleep_sessions ORDER BY endedAt DESC")
    suspend fun getAllSync(): List<SleepSession>

    @Query("DELETE FROM sleep_sessions")
    suspend fun clearAll()
}
