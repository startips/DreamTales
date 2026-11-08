package com.bedtime.dreamtales.core.widget

import com.bedtime.dreamtales.data.local.entity.Story

/**
 * 桌面小组件的选篇与文案逻辑（纯函数，便于测试）。
 */
object WidgetStorySelector {

    fun pick(stories: List<Story>): Story? {
        if (stories.isEmpty()) return null
        return stories.sortedWith(
            compareByDescending<Story> { it.isFavorite }
                .thenByDescending { it.playCount }
                .thenByDescending { it.createdAt }
        ).first()
    }

    fun title(story: Story?): String = story?.title?.takeIf { it.isNotBlank() } ?: "今夜童话已备好"

    fun subtitle(story: Story?): String {
        if (story == null) return "打开月眠开始温柔伴读"
        val age = story.targetAge.ifBlank { "3-6岁" }
        val minutes = (story.durationSeconds / 60).coerceAtLeast(1)
        return "$age · $minutes 分钟 · 轻点一键伴读"
    }

    fun playButtonLabel(story: Story?): String = if (story == null) "打开文库" else "▶ 一键伴读"
}
