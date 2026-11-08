package com.bedtime.dreamtales.core.stats

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

data class SleepSessionSnapshot(
    val storyTitle: String = "",
    val startedAt: Long = 0L,
    val endedAt: Long = 0L,
    val listenedSeconds: Int = 0,
    val estimatedSleepSeconds: Int = 0,
    val endedBy: String = "MANUAL"
)

data class SleepDayBucket(
    val date: LocalDate,
    val label: String,
    val listenedMinutes: Int,
    val sessionCount: Int
)

data class SleepWeeklyReport(
    val totalSessions: Int = 0,
    val totalListenedMinutes: Int = 0,
    val nights: Int = 0,
    val averageSleepMinutes: Int? = null,
    val longestStreakDays: Int = 0,
    val favoriteStory: String? = null,
    val dailyBuckets: List<SleepDayBucket> = emptyList(),
    val recentSessions: List<SleepSessionSnapshot> = emptyList()
) {
    val hasData: Boolean get() = totalSessions > 0
}

/**
 * 睡前陪伴统计与入睡耗时推测（纯逻辑，可单元测试）。
 *
 * 入睡耗时来源：由播放服务在睡眠定时归零时记录 —— 从 bedtime 开始到定时停止的总聆听秒数。
 * 手动暂停 / 切换故事 / 服务销毁不会产生“入睡耗时”，避免污染平均值。
 */
object SleepStatsCalculator {

    const val MIN_VALID_SESSION_SECONDS = 20
    const val DAYS_IN_WEEK = 7

    fun build(
        sessions: List<SleepSessionSnapshot>,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ): SleepWeeklyReport {
        val valid = sessions
            .filter { it.listenedSeconds >= MIN_VALID_SESSION_SECONDS }
            .sortedByDescending { it.endedAt }

        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val windowStartDate = today.minusDays((DAYS_IN_WEEK - 1).toLong())
        val windowStartMillis = windowStartDate.atStartOfDay(zone).toInstant().toEpochMilli()

        val weekly = valid.filter { it.endedAt >= windowStartMillis && it.endedAt <= now }

        val totalMinutes = weekly.sumOf { it.listenedSeconds } / 60
        val nights = weekly
            .map { Instant.ofEpochMilli(it.endedAt).atZone(zone).toLocalDate() }
            .distinct()
            .size

        val sleepSamples = weekly.mapNotNull { session ->
            session.estimatedSleepSeconds.takeIf { it > 0 }
        }
        val averageSleep = if (sleepSamples.isEmpty()) {
            null
        } else {
            (sleepSamples.average() / 60.0).roundToInt().coerceAtLeast(1)
        }

        val favorite = weekly
            .filter { it.storyTitle.isNotBlank() }
            .groupBy { it.storyTitle }
            .maxByOrNull { (_, items) -> items.size }
            ?.key

        val buckets = (0 until DAYS_IN_WEEK).map { offset ->
            val date = windowStartDate.plusDays(offset.toLong())
            val daySessions = weekly.filter {
                Instant.ofEpochMilli(it.endedAt).atZone(zone).toLocalDate() == date
            }
            SleepDayBucket(
                date = date,
                label = date.format(DateTimeFormatter.ofPattern("M/d", Locale.CHINA)),
                listenedMinutes = daySessions.sumOf { it.listenedSeconds } / 60,
                sessionCount = daySessions.size
            )
        }

        return SleepWeeklyReport(
            totalSessions = weekly.size,
            totalListenedMinutes = totalMinutes,
            nights = nights,
            averageSleepMinutes = averageSleep,
            longestStreakDays = calculateStreak(valid, today, zone),
            favoriteStory = favorite,
            dailyBuckets = buckets,
            recentSessions = valid.take(7)
        )
    }

    /**
     * 连续陪伴天数：从今天或昨天开始向前连续计数。
     */
    fun calculateStreak(
        sessions: List<SleepSessionSnapshot>,
        today: LocalDate,
        zone: ZoneId
    ): Int {
        val days = sessions
            .filter { it.listenedSeconds >= MIN_VALID_SESSION_SECONDS }
            .map { Instant.ofEpochMilli(it.endedAt).atZone(zone).toLocalDate() }
            .toSet()
        if (days.isEmpty()) return 0

        var cursor = when {
            days.contains(today) -> today
            days.contains(today.minusDays(1)) -> today.minusDays(1)
            else -> return 0
        }

        var streak = 0
        while (days.contains(cursor)) {
            streak++
            cursor = cursor.minusDays(1)
        }
        return streak
    }
}
