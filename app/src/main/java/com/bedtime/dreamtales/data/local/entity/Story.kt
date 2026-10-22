package com.bedtime.dreamtales.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "stories")
data class Story(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,                 // 故事标题
    val targetAge: String,             // 适用年龄（"2-4岁", "5-7岁", "8岁以上"）
    val content: String,               // 纯文本内容（按段落划分，无悬疑问句）
    val durationSeconds: Int,          // 预估播放时长
    val isAiGenerated: Boolean = false,// 是否为 AI 每日生成
    val dateLabel: String = "",        // 例如："10月22日 · 今夜特献" 或 "经典收录"
    val iconEmoji: String = "",        // 题材语义图标（如 🐴, 🦌, 👧, 🦕 等）
    val playCount: Int = 0,            // 播放频次（用于主页热度排序）
    val isFavorite: Boolean = false,   // 是否收藏（宝宝最爱听）
    val createdAt: Long = System.currentTimeMillis()
)
