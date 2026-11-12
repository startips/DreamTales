package com.bedtime.dreamtales.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.bedtime.dreamtales.core.model.ChildProfile
import com.bedtime.dreamtales.core.model.ChildProfilePolicy

/**
 * 宝宝专属主角档案实体（支持多孩家庭）。
 */
@Entity(tableName = "child_profiles")
data class ChildProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val avatarEmoji: String = ChildProfilePolicy.DEFAULT_AVATAR,
    val ageLabel: String = "",
    val interests: String = "",
    val todayNote: String = "",
    val isSelected: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toModel(): ChildProfile = ChildProfile(
        id = id,
        name = name,
        avatarEmoji = avatarEmoji,
        ageLabel = ageLabel,
        interests = interests,
        todayNote = todayNote,
        isEnabled = isSelected
    )
}
