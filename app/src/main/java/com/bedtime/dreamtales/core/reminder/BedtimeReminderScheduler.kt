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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.bedtime.dreamtales.MainActivity
import com.bedtime.dreamtales.R
import com.bedtime.dreamtales.core.constants.AppConfig

/**
 * 晚安提醒：使用系统闹钟通道（setAlarmClock）保证准点，
 * 免申请 SCHEDULE_EXACT_ALARM，且在 Doze 省电模式下仍能精确触发。
 */
object BedtimeReminderScheduler {

    const val ACTION_FIRE = "com.bedtime.dreamtales.action.BEDTIME_REMINDER"
    const val ACTION_START_TONIGHT_QUEUE = "com.bedtime.dreamtales.action.START_TONIGHT_QUEUE"
    const val EXTRA_START_TONIGHT_QUEUE = "extra_start_tonight_queue"

    private const val CHANNEL_ID = "dreamtales_bedtime_reminder"
    private const val NOTIFICATION_ID = 2001
    private const val REQUEST_CODE_ALARM = 3001
    private const val REQUEST_CODE_OPEN = 3002
    private const val REQUEST_CODE_START = 3003

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

        // 用 setAlarmClock 保证准点：它免额外权限、可在 Doze 下精确触发，
        // 而 setAndAllowWhileIdle 的系统窗口可达 1 小时，晚安提醒会明显迟到。
        val showIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE_OPEN,
            Intent(context, MainActivity::class.java).apply {
                action = ACTION_FIRE
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val scheduled = runCatching {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAt, showIntent),
                pendingIntent
            )
        }.isSuccess

        if (!scheduled) {
            // 极少数 ROM 不支持 setAlarmClock 时降级为「尽力准点」，至少仍能提醒
            runCatching {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
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
