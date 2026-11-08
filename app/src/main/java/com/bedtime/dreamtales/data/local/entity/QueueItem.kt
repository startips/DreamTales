package com.bedtime.dreamtales.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 「今晚故事单」中的一篇故事。当前版本只维护一个队列，按 position 升序播放。
 */
@Entity(
    tableName = "playback_queue",
    indices = [Index(value = ["storyId"], unique = true)]
)
data class QueueItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val storyId: Long,
    val position: Int,
    val addedAt: Long = System.currentTimeMillis()
)
