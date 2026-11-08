package com.bedtime.dreamtales.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 每篇故事的最后播放进度。只保留最近一次播放位置，用于「继续听」。
 */
@Entity(tableName = "playback_positions")
data class PlaybackPosition(
    @PrimaryKey val storyId: Long,
    val positionMs: Long,
    val durationMs: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis()
)
