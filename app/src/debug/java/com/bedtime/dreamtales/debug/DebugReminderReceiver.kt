package com.bedtime.dreamtales.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bedtime.dreamtales.core.reminder.BedtimeReminderScheduler

/**
 * 仅 Debug 构建使用：通过 adb 广播触发晚安提醒通知，便于真机验收。
 */
class DebugReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_SHOW_REMINDER) return
        BedtimeReminderScheduler.showNotification(context)
    }

    companion object {
        const val ACTION_SHOW_REMINDER = "com.bedtime.dreamtales.debug.SHOW_REMINDER"
    }
}
