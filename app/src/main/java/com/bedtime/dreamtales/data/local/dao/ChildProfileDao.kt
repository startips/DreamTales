package com.bedtime.dreamtales.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.bedtime.dreamtales.data.local.entity.ChildProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChildProfileDao {

    @Query("SELECT * FROM child_profiles ORDER BY createdAt ASC, id ASC")
    fun getAllFlow(): Flow<List<ChildProfileEntity>>

    @Query("SELECT * FROM child_profiles ORDER BY createdAt ASC, id ASC")
    suspend fun getAllSync(): List<ChildProfileEntity>

    @Query("SELECT * FROM child_profiles WHERE isSelected = 1 LIMIT 1")
    fun getSelectedFlow(): Flow<ChildProfileEntity?>

    @Query("SELECT * FROM child_profiles WHERE isSelected = 1 LIMIT 1")
    suspend fun getSelectedSync(): ChildProfileEntity?

    @Query("SELECT * FROM child_profiles WHERE id = :id LIMIT 1")
    suspend fun getByIdSync(id: Long): ChildProfileEntity?

    @Query("SELECT COUNT(*) FROM child_profiles")
    suspend fun countSync(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: ChildProfileEntity): Long

    @Update
    suspend fun update(profile: ChildProfileEntity)

    @Query("UPDATE child_profiles SET isSelected = 0")
    suspend fun clearSelection()

    @Query("UPDATE child_profiles SET isSelected = CASE WHEN id = :id THEN 1 ELSE 0 END")
    suspend fun selectById(id: Long)

    @Query("DELETE FROM child_profiles WHERE id = :id")
    suspend fun deleteById(id: Long)
}
