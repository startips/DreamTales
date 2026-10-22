package com.bedtime.dreamtales.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.bedtime.dreamtales.data.local.entity.VoiceProfile
import kotlinx.coroutines.flow.Flow

@Dao
interface VoiceProfileDao {

    @Query("SELECT * FROM voice_profiles ORDER BY createdAt DESC")
    fun getAllVoiceProfiles(): Flow<List<VoiceProfile>>

    @Query("SELECT * FROM voice_profiles ORDER BY createdAt DESC")
    suspend fun getAllVoiceProfilesSync(): List<VoiceProfile>

    @Query("SELECT * FROM voice_profiles WHERE isDefault = 1 LIMIT 1")
    fun getDefaultVoiceProfile(): Flow<VoiceProfile?>

    @Query("SELECT * FROM voice_profiles WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefaultVoiceProfileSync(): VoiceProfile?

    @Query("SELECT * FROM voice_profiles WHERE id = :id LIMIT 1")
    suspend fun getVoiceProfileById(id: Long): VoiceProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVoiceProfile(profile: VoiceProfile): Long

    @Update
    suspend fun updateVoiceProfile(profile: VoiceProfile)

    @Delete
    suspend fun deleteVoiceProfile(profile: VoiceProfile)

    @Query("UPDATE voice_profiles SET isDefault = 0")
    suspend fun clearDefaultStatus()

    @Query("UPDATE voice_profiles SET isDefault = 1 WHERE id = :profileId")
    suspend fun markAsDefault(profileId: Long)

    @Transaction
    suspend fun setDefaultVoiceProfile(profileId: Long) {
        clearDefaultStatus()
        markAsDefault(profileId)
    }
}
