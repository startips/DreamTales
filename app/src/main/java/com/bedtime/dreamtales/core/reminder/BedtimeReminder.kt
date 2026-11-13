package com.bedtime.dreamtales.core.reminder

import java.time.Instant
import java.time.ZoneId

/**
 * 晚安提醒设置。
 */
data class BedtimeReminderSettings(
    val enabled: Boolean = false,
    val hour: Int = 20,
    val minute: Int = 30
) {
    val label: String
        get() = "%02d:%02d".format(hour, minute)
}

/**
 * 计算下一次提醒时间。使用系统闹钟通道，正常不会延迟。
 */
object BedtimeReminderCalculator {

    fun nextTriggerMillis(
        nowMillis: Long,
        hour: Int,
        minute: Int,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Long {
        val safeHour = hour.coerceIn(0, 23)
        val safeMinute = minute.coerceIn(0, 59)
        val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
        var target = now
            .withHour(safeHour)
            .withMinute(safeMinute)
            .withSecond(0)
            .withNano(0)
        if (!target.isAfter(now)) {
            target = target.plusDays(1)
        }
        return target.toInstant().toEpochMilli()
    }
}
