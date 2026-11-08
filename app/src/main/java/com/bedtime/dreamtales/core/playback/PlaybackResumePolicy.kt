package com.bedtime.dreamtales.core.playback

/**
 * 断点续播的纯策略，方便单元测试。
 */
object PlaybackResumePolicy {

    /** 小于这个进度不记录，避免刚点开就退出留下无意义进度。 */
    const val MIN_PERSIST_MS = 5_000L

    /** 小于这个进度不提示「继续听」。 */
    const val MIN_RESUME_MS = 10_000L

    /** 距离结尾小于这个值视为已经听完。 */
    const val COMPLETION_TAIL_MS = 5_000L

    fun shouldPersist(positionMs: Long, durationMs: Long): Boolean {
        if (positionMs < MIN_PERSIST_MS) return false
        if (durationMs > 0L && positionMs >= durationMs - COMPLETION_TAIL_MS) return false
        return true
    }

    fun shouldResume(positionMs: Long, durationMs: Long): Boolean {
        if (positionMs < MIN_RESUME_MS) return false
        if (durationMs > 0L && positionMs >= durationMs - COMPLETION_TAIL_MS) return false
        return true
    }

    /**
     * 把进度限制在有效范围内，防止缓存重建后 duration 变短导致越界。
     */
    fun clampPosition(positionMs: Long, durationMs: Long): Long {
        val safe = positionMs.coerceAtLeast(0L)
        return if (durationMs > 0L) safe.coerceAtMost(durationMs) else safe
    }
}
