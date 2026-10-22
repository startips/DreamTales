package com.bedtime.dreamtales.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "voice_profiles")
data class VoiceProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,                  // 例如："爸爸的晚安声"、"妈妈的轻柔声"
    val cloudVoiceId: String,          // 云端生成的专属音色 ID
    val localSamplePath: String,       // 本地 30 秒原始录音路径
    val isDefault: Boolean = false,    // 是否为当前选中的活跃音色
    val createdAt: Long = System.currentTimeMillis()
)
