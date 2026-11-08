package com.bedtime.dreamtales.core.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 晚安提醒到点：发通知，并安排下一天。
 */
class BedtimeReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != BedtimeReminderScheduler.ACTION_FIRE) return
        BedtimeReminderScheduler.showNotification(context)
        BedtimeReminderScheduler.schedule(context)
    }
}
