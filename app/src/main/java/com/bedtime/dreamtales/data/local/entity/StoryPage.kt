package com.bedtime.dreamtales.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 故事连环画分页：每篇故事可拥有 2~4 页文字与专属插画。
 */
@Entity(
    tableName = "story_pages",
    indices = [Index(value = ["storyId", "pageIndex"], unique = true)]
)
data class StoryPage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val storyId: Long,
    val pageIndex: Int,
    val text: String,
    val imagePath: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
