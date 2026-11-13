package com.bedtime.dreamtales.core.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.bedtime.dreamtales.MainActivity
import com.bedtime.dreamtales.R
import com.bedtime.dreamtales.core.constants.AppConfig

/**
 * 晚安提醒调度：三级保证「准点」。
 *
 * 1. 已授予「闹钟和提醒」特殊权限时：`setExactAndAllowWhileIdle`（window=0，Doze 下仍精确）；
 * 2. 未授予时先尝试系统闹钟通道 `setAlarmClock`（免权限，但部分 ROM 会降级）；
 * 3. 最终兜底 `setAndAllowWhileIdle`（系统窗口最长 1 小时）。
 */
object BedtimeReminderScheduler {

    private const val TAG = "BedtimeReminder"

    const val ACTION_FIRE = "com.bedtime.dreamtales.action.BEDTIME_REMINDER"
    const val ACTION_START_TONIGHT_QUEUE = "com.bedtime.dreamtales.action.START_TONIGHT_QUEUE"
    const val EXTRA_START_TONIGHT_QUEUE = "extra_start_tonight_queue"

    private const val CHANNEL_ID = "dreamtales_bedtime_reminder"
    private const val NOTIFICATION_ID = 2001
    private const val REQUEST_CODE_ALARM = 3001
    private const val REQUEST_CODE_OPEN = 3002
    private const val REQUEST_CODE_START = 3003

    /** 当前是否已获得精确闹钟（闹钟和提醒）特殊权限。 */
    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
    }

    fun schedule(context: Context) {
        val appConfig = AppConfig.getInstance(context)
        val settings = appConfig.getBedtimeReminder()
        cancel(context)
        if (!settings.enabled) return

        val triggerAt = BedtimeReminderCalculator.nextTriggerMillis(
            nowMillis = System.currentTimeMillis(),
            hour = settings.hour,
            minute = settings.minute
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_ALARM,
            Intent(context, BedtimeReminderReceiver::class.java).apply {
                action = ACTION_FIRE
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 1. 有精确闹钟权限：最稳，窗口为 0
        if (canScheduleExactAlarms(context)) {
            val precise = runCatching {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
            if (precise.isSuccess) return
            Log.w(TAG, "setExactAndAllowWhileIdle 失败，回退闹钟通道", precise.exceptionOrNull())
        }

        // 2. 退而求其次：系统闹钟通道（部分 ROM 会静默降级为 1 小时窗口）
        val showIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE_OPEN,
            Intent(context, MainActivity::class.java).apply {
                action = ACTION_FIRE
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val alarmClock = runCatching {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAt, showIntent),
                pendingIntent
            )
        }
        if (alarmClock.isSuccess) return
        Log.w(TAG, "setAlarmClock 失败，回退尽力准点", alarmClock.exceptionOrNull())

        // 3. 兜底：至少仍能提醒（可能延迟，最长 1 小时）
        runCatching {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_ALARM,
            Intent(context, BedtimeReminderReceiver::class.java).apply {
                action = ACTION_FIRE
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    /**
     * 提醒开关打开前先确认通知链路可用，避免界面显示“已开启”但到点静默无通知。
     */
    fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return false
        }
        val channel = context.getSystemService(NotificationManager::class.java)
            ?.getNotificationChannel(CHANNEL_ID)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun showNotification(context: Context) {
        ensureChannel(context)

        // Android 13+ 通知权限被拒绝或通知被系统关闭时静默跳过，提醒闹钟本身仍然保留
        if (!canPostNotifications(context)) {
            return
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_FIRE
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE_OPEN,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val startIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_START_TONIGHT_QUEUE
            putExtra(EXTRA_START_TONIGHT_QUEUE, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val startPendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE_START,
            startIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_moon)
            .setContentTitle("🌙 该准备睡觉啦")
            .setContentText("今晚想听哪篇故事？轻点打开月眠。")
            .setStyle(NotificationCompat.BigTextStyle().bigText("今晚想听哪篇故事？轻点打开月眠，陪宝贝慢慢入睡。"))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .addAction(R.drawable.ic_notification_moon, "开始今晚故事", startPendingIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "晚安提醒",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "每天固定时间提醒家长准备睡前故事"
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }
}
