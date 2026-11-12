package com.bedtime.dreamtales.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一次睡前陪伴会话。用于统计聆听时长、入睡耗时与连续陪伴天数。
 */
@Entity(
    tableName = "sleep_sessions",
    indices = [
        Index(value = ["startedAt"]),
        Index(value = ["childProfileId"])
    ]
)
data class SleepSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val storyId: Long = 0,
    val storyTitle: String = "",
    val startedAt: Long = 0,
    val endedAt: Long = 0,
    val listenedSeconds: Int = 0,
    val estimatedSleepSeconds: Int = 0,
    val endedBy: String = "MANUAL",
    val selectedTimerMinutes: Int = 0,
    val childProfileId: Long = 0
)
