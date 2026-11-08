package com.bedtime.dreamtales.core.playback

import kotlin.math.abs

/**
 * 播放速度与最后 5 分钟渐弱系数的组合策略。
 */
object PlaybackSpeedPolicy {

    val OPTIONS = listOf(0.8f, 0.9f, 1.0f)
    const val DEFAULT_SPEED = 1.0f
    const val MIN_FADE_MULTIPLIER = 0.75f

    fun normalizeBaseSpeed(speed: Float): Float {
        return OPTIONS.minByOrNull { abs(it - speed) } ?: DEFAULT_SPEED
    }

    /**
     * 基础速度 × 渐弱系数。渐弱只影响睡眠定时最后 5 分钟，不覆盖用户选择的速度。
     */
    fun effectiveSpeed(baseSpeed: Float, fadeMultiplier: Float): Float {
        val base = baseSpeed.coerceIn(0.5f, 1.5f)
        val fade = fadeMultiplier.coerceIn(MIN_FADE_MULTIPLIER, 1.0f)
        return (base * fade).coerceIn(0.4f, 1.5f)
    }
}
