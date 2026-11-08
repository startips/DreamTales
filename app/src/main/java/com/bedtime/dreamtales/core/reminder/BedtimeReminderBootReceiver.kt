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
            Intent.ACTION_MY_PACKAGE_REPLACED -> BedtimeReminderScheduler.schedule(context)
        }
    }
}
