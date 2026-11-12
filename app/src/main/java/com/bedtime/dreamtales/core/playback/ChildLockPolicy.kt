package com.bedtime.dreamtales.core.playback

/**
 * 播放页儿童锁的纯策略常量与计算逻辑。
 */
object ChildLockPolicy {
    const val UNLOCK_HOLD_DURATION_MS = 2_500L
    const val HINT_AUTO_HIDE_MS = 3_000L

    fun progressFraction(
        elapsedMs: Long,
        durationMs: Long = UNLOCK_HOLD_DURATION_MS
    ): Float {
        if (durationMs <= 0L) return 1f
        if (elapsedMs <= 0L) return 0f
        return (elapsedMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    fun isUnlocked(
        elapsedMs: Long,
        durationMs: Long = UNLOCK_HOLD_DURATION_MS
    ): Boolean {
        return durationMs <= 0L || elapsedMs >= durationMs
    }
}
