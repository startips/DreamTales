package com.bedtime.dreamtales.core.playback

/**
 * 「今晚故事单」的顺序策略，与 Android/数据库解耦，方便单元测试。
 */
object PlaybackQueuePolicy {

    fun indexOf(storyIds: List<Long>, storyId: Long): Int = storyIds.indexOf(storyId)

    /**
     * 返回下一首的索引；已经是最后一首时返回 null，表示故事单播完应停止。
     */
    fun nextIndex(currentIndex: Int, size: Int): Int? {
        if (size <= 0) return null
        if (currentIndex < 0) return 0
        val next = currentIndex + 1
        return if (next in 0 until size) next else null
    }

    /**
     * 上一首；在第一首时回到最后一首，符合锁屏「上一首」的直觉。
     */
    fun previousIndex(currentIndex: Int, size: Int): Int? {
        if (size <= 0) return null
        if (currentIndex !in 0 until size) return size - 1
        return (currentIndex - 1 + size) % size
    }

    fun hasNext(currentIndex: Int, size: Int): Boolean = nextIndex(currentIndex, size) != null
}
