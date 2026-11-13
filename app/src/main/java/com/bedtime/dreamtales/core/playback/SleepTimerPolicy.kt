package com.bedtime.dreamtales.core.playback

/**
 * 睡眠定时状态机的纯逻辑，与 Android 播放器解耦，便于单元测试。
 *
 * 目标：把「默认自动挂载」「用户主动取消」「定时刚归零」三种状态彻底分开，
 * 避免出现「取消后又被偷偷续上」或「归零后自动连播导致整夜播放」。
 */
object SleepTimerPolicy {

    /** 是否需要为新开始的播放挂载默认 30 分钟定时。 */
    fun shouldArmDefaultTimer(
        isTimerActive: Boolean,
        remainingSeconds: Int,
        cancelledByUser: Boolean,
        expiredRecently: Boolean,
        viaAutoAdvance: Boolean
    ): Boolean {
        if (isTimerActive && remainingSeconds > 0) return false
        if (viaAutoAdvance) return false
        if (cancelledByUser) return false
        if (expiredRecently) return false
        return true
    }

    /** 定时归零后用户手动续播：是否重新挂上上次选择的定时。 */
    fun shouldRearmAfterExpiry(
        isTimerActive: Boolean,
        cancelledByUser: Boolean,
        expiredRecently: Boolean
    ): Boolean {
        if (isTimerActive) return false
        if (cancelledByUser) return false
        return expiredRecently
    }

    /** 定时刚归零时是否应阻止自动连播（尊重「该睡了」）。 */
    fun shouldBlockAutoAdvance(expiredRecently: Boolean, isTimerActive: Boolean): Boolean {
        return expiredRecently && !isTimerActive
    }
}