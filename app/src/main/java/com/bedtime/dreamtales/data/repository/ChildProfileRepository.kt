package com.bedtime.dreamtales.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.bedtime.dreamtales.core.StoryPersonalization
import com.bedtime.dreamtales.core.constants.AppConfig
import com.bedtime.dreamtales.core.model.ChildProfile
import com.bedtime.dreamtales.core.model.ChildProfilePolicy
import com.bedtime.dreamtales.data.local.AppDatabase
import com.bedtime.dreamtales.data.local.entity.ChildProfileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 多宝宝档案仓库：支持多孩家庭创建、切换与删除档案，
 * 并负责把 v2.2.x 的单份 AppConfig 档案无损迁移到 Room `child_profiles`。
 */
class ChildProfileRepository(context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val dao = db.childProfileDao()
    private val sessionDao = db.sleepSessionDao()
    private val appConfig = AppConfig.getInstance(context)

    fun getAllProfilesFlow(): Flow<List<ChildProfile>> {
        return dao.getAllFlow().map { list -> list.map { it.toModel() } }
    }

    fun getSelectedProfileFlow(): Flow<ChildProfile?> {
        return dao.getSelectedFlow().map { it?.toModel() }
    }

    suspend fun getAllProfilesSync(): List<ChildProfile> = withContext(Dispatchers.IO) {
        ensureMigratedFromLegacy()
        dao.getAllSync().map { it.toModel() }
    }

    suspend fun getActiveChildProfileSync(): ChildProfile = withContext(Dispatchers.IO) {
        ensureMigratedFromLegacy()
        dao.getSelectedSync()?.toModel() ?: ChildProfile()
    }

    suspend fun ensureMigratedFromLegacy() = withContext(Dispatchers.IO) {
        if (appConfig.hasMigratedChildProfiles()) return@withContext
        migrationMutex.withLock {
            if (appConfig.hasMigratedChildProfiles()) return@withLock
            val legacy = appConfig.getChildProfile()
            val hasAnyLegacyContent = legacy.name.isNotBlank() ||
                legacy.ageLabel.isNotBlank() ||
                legacy.interests.isNotBlank() ||
                legacy.todayNote.isNotBlank()
            if (dao.countSync() == 0 && hasAnyLegacyContent) {
                val safe = StoryPersonalization.sanitize(legacy)
                val insertedId = db.withTransaction {
                    val entity = ChildProfileEntity(
                        name = safe.name.ifBlank { "宝贝" },
                        avatarEmoji = safe.displayAvatar,
                        ageLabel = safe.ageLabel,
                        interests = safe.interests,
                        todayNote = safe.todayNote,
                        isSelected = safe.isEnabled,
                        createdAt = System.currentTimeMillis()
                    )
                    val newId = dao.insert(entity)
                    sessionDao.reassignUnassignedSessions(newId)
                    newId
                }
                if (insertedId > 0L) {
                    syncLegacySnapshot()
                }
            }
            appConfig.markChildProfilesMigrated()
        }
    }

    /**
     * 新增或更新宝宝档案。返回保存后的档案 ID；超过上限返回 -1L，空昵称返回 0L。
     */
    suspend fun saveProfile(
        profile: ChildProfile,
        makeSelected: Boolean = profile.isEnabled
    ): Long = withContext(Dispatchers.IO) {
        ensureMigratedFromLegacy()
        val safe = StoryPersonalization.sanitize(profile)
        if (safe.name.isBlank()) return@withContext 0L

        val savedId = db.withTransaction {
            if (safe.id == 0L) {
                if (!ChildProfilePolicy.canAddMore(dao.countSync())) {
                    return@withTransaction -1L
                }
                if (makeSelected) {
                    dao.clearSelection()
                }
                dao.insert(
                    ChildProfileEntity(
                        name = safe.name,
                        avatarEmoji = safe.displayAvatar,
                        ageLabel = safe.ageLabel,
                        interests = safe.interests,
                        todayNote = safe.todayNote,
                        isSelected = makeSelected,
                        createdAt = System.currentTimeMillis()
                    )
                )
            } else {
                val existing = dao.getByIdSync(safe.id)
                if (makeSelected) {
                    dao.clearSelection()
                }
                dao.update(
                    ChildProfileEntity(
                        id = safe.id,
                        name = safe.name,
                        avatarEmoji = safe.displayAvatar,
                        ageLabel = safe.ageLabel,
                        interests = safe.interests,
                        todayNote = safe.todayNote,
                        isSelected = makeSelected,
                        createdAt = existing?.createdAt ?: System.currentTimeMillis()
                    )
                )
                safe.id
            }
        }
        if (savedId > 0L) {
            syncLegacySnapshot()
        }
        savedId
    }

    suspend fun selectProfile(profileId: Long?) = withContext(Dispatchers.IO) {
        ensureMigratedFromLegacy()
        db.withTransaction {
            if (profileId == null || profileId <= 0L) {
                dao.clearSelection()
            } else {
                dao.selectById(profileId)
            }
        }
        syncLegacySnapshot()
    }

    suspend fun deleteProfile(profileId: Long) = withContext(Dispatchers.IO) {
        ensureMigratedFromLegacy()
        db.withTransaction {
            // 删除档案后，原关联的陪伴记录归入未分人总览，不丢失历史时长与周报
            sessionDao.clearChildReference(profileId)
            dao.deleteById(profileId)
        }
        syncLegacySnapshot()
    }

    private suspend fun syncLegacySnapshot() {
        val active = dao.getSelectedSync()?.toModel() ?: ChildProfile()
        appConfig.setChildProfile(active)
    }

    companion object {
        private val migrationMutex = Mutex()
    }
}
