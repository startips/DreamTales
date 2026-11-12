package com.bedtime.dreamtales.core.audio

import com.bedtime.dreamtales.data.local.entity.Story
import com.bedtime.dreamtales.data.local.entity.StoryAudioCache
import java.util.Locale

data class StoryCacheItemState(
    val story: Story,
    val isCached: Boolean,
    val sizeBytes: Long
)

/**
 * 离线音频缓存统计与筛选纯逻辑（不依赖 Android Context，便于单元测试）。
 */
object OfflineCacheCalculator {

    fun buildItems(
        stories: List<Story>,
        completeCaches: List<StoryAudioCache>,
        voiceProfileId: Long?,
        fileSizeProvider: (String) -> Long
    ): List<StoryCacheItemState> {
        val byStory = if (voiceProfileId == null) {
            emptyMap()
        } else {
            completeCaches
                .filter { it.voiceProfileId == voiceProfileId && it.isComplete }
                .associateBy { it.storyId }
        }

        return stories.map { story ->
            val cache = byStory[story.id]
            val bytes = if (cache != null) {
                fileSizeProvider(cache.localFilePath).coerceAtLeast(0L)
            } else {
                0L
            }
            StoryCacheItemState(
                story = story,
                isCached = cache != null && bytes > 0L,
                sizeBytes = bytes
            )
        }
    }

    fun pendingForBatch(
        items: List<StoryCacheItemState>,
        favoritesOnly: Boolean
    ): List<Story> {
        return items
            .asSequence()
            .filter { !it.isCached }
            .filter { !favoritesOnly || it.story.isFavorite }
            .map { it.story }
            .toList()
    }

    fun totalCachedBytes(items: List<StoryCacheItemState>): Long {
        return items.filter { it.isCached }.sumOf { it.sizeBytes }
    }

    fun cachedCount(items: List<StoryCacheItemState>): Int {
        return items.count { it.isCached }
    }

    fun formatSizeLabel(bytes: Long): String {
        if (bytes <= 0L) return "0 KB"
        val kb = bytes / 1024.0
        if (kb < 1024.0) {
            return "${kb.toInt().coerceAtLeast(1)} KB"
        }
        val mb = kb / 1024.0
        return String.format(Locale.US, "%.1f MB", mb)
    }

    fun formatFreeSpaceLabel(freeMb: Float): String {
        if (freeMb <= 0f) return "--"
        return if (freeMb >= 1024f) {
            String.format(Locale.US, "%.1f GB", freeMb / 1024f)
        } else {
            String.format(Locale.US, "%.0f MB", freeMb)
        }
    }
}
