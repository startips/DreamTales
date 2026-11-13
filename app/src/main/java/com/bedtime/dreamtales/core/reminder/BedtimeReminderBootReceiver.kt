package com.bedtime.dreamtales.core.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 重启或应用更新后恢复晚安提醒。
 */
class BedtimeReminderBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> BedtimeReminderScheduler.schedule(context)

            // 用户刚授予「闹钟和提醒」权限：立刻把提醒重排为精确闹钟
            ACTION_EXACT_ALARM_PERMISSION_STATE_CHANGED -> {
                if (BedtimeReminderScheduler.canScheduleExactAlarms(context)) {
                    BedtimeReminderScheduler.schedule(context)
                }
            }
        }
    }

    companion object {
        private const val ACTION_EXACT_ALARM_PERMISSION_STATE_CHANGED =
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
    }
}
