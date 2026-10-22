package com.bedtime.dreamtales.core.constants

object AppConstants {
    // 睡眠定时与衰减配置
    const val SLEEP_TIMER_FADE_START_MINUTES = 5
    const val STEP_INTERVAL_MILLIS = 20_000L // 每 20 秒执行一次步进衰减
    const val PLAYBACK_SPEED_START = 1.0f
    const val PLAYBACK_SPEED_TARGET = 0.75f
    const val PLAYBACK_SPEED_STEP = 0.02f

    // 录音硬性门槛参数
    const val AUDIO_SAMPLE_RATE = 16000 // 采样率 16000Hz
    const val AUDIO_CHANNEL_COUNT = 1 // 单声道
    const val AUDIO_BITS_PER_SAMPLE = 16 // 16 位 PCM
    const val MIN_RECORDING_DURATION_SEC = 15 // 录音时长限制 15s <= 时长 <= 30s
    const val MAX_RECORDING_DURATION_SEC = 30
    const val MIN_DB_RMS_THRESHOLD = 35.0 // 平均分贝 < 35dB 拦截

    // 翻转扣屏传感器参数
    const val ACCEL_Z_FACE_DOWN_THRESHOLD = -8.5f // Z < -8.5 m/s²
    const val ACCEL_XY_FLAT_THRESHOLD = 3.0f // |X| < 3.0, |Y| < 3.0 m/s²
    const val PROXIMITY_COVER_DISTANCE = 0.0f // distance == 0.0
    const val FACE_DOWN_HOLD_MILLIS = 300L // 持续 300ms 判定为扣屏
    const val FACE_UP_HOLD_MILLIS = 200L // 持续 200ms 判定为解除

    // 屏幕亮度定义（暗光物理工程）
    const val BRIGHTNESS_FACE_DOWN = 0.0f // 扣屏时彻底熄屏/极暗防误触
    const val BRIGHTNESS_WARM_DIM = -1.0f // 正常持机时跟随系统自然亮度，保证文字按钮字字清晰

    // 默认倒计时档位（分钟）
    val TIMER_PRESETS_MINUTES = listOf(15, 30, 45)

    // 通知渠道
    const val PLAYBACK_NOTIFICATION_CHANNEL_ID = "dreamtales_playback_channel"
    const val PLAYBACK_NOTIFICATION_ID = 1001
}
