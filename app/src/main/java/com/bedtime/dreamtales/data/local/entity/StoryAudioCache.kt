package com.bedtime.dreamtales.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "story_audio_caches",
    indices = [Index(value = ["storyId", "voiceProfileId"], unique = true)]
)
data class StoryAudioCache(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val storyId: Long,
    val voiceProfileId: Long,
    val localFilePath: String,         // 本地 MP3 文件绝对路径
    val isComplete: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
