package com.bedtime.dreamtales

import com.bedtime.dreamtales.core.StoryPersonalization
import com.bedtime.dreamtales.core.image.BitmapSampleCalculator
import com.bedtime.dreamtales.core.model.ChildProfile
import com.bedtime.dreamtales.core.model.ChildProfilePolicy
import com.bedtime.dreamtales.core.stats.SleepSessionSnapshot
import com.bedtime.dreamtales.core.stats.SleepStatsCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class Version230Test {

    // ---------------- 多宝宝档案 ----------------

    @Test
    fun testChildProfilePolicyLimitsAndAvatarSanitization() {
        assertEquals(5, ChildProfilePolicy.MAX_PROFILES)
        assertTrue(ChildProfilePolicy.canAddMore(0))
        assertTrue(ChildProfilePolicy.canAddMore(4))
        assertFalse(ChildProfilePolicy.canAddMore(5))

        assertEquals("👶", ChildProfilePolicy.sanitizeAvatar(""))
        assertEquals("👶", ChildProfilePolicy.sanitizeAvatar("   "))
        assertEquals("🐰", ChildProfilePolicy.sanitizeAvatar(" 🐰 "))
        assertEquals("👶", ChildProfilePolicy.sanitizeAvatar("too-long-text"))
    }

    @Test
    fun testStoryPersonalizationPreservesIdAndCleansAvatar() {
        val raw = ChildProfile(
            id = 7L,
            name = "  朵朵\n",
            avatarEmoji = "🦖",
            ageLabel = "5岁",
            interests = "小星星",
            isEnabled = true
        )
        val safe = StoryPersonalization.sanitize(raw)
        assertEquals(7L, safe.id)
        assertEquals("🦖", safe.displayAvatar)
        assertEquals("朵朵", safe.name)
        assertTrue(safe.isActive)
    }

    // ---------------- 分宝宝陪伴周报 ----------------

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun epochOf(year: Int, month: Int, day: Int, hour: Int): Long {
        return LocalDate.of(year, month, day)
            .atTime(hour, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
    }

    @Test
    fun testSleepStatsCalculatorFiltersByChildProfileId() {
        val now = epochOf(2026, 11, 12, 22)
        val sessions = listOf(
            SleepSessionSnapshot(
                storyTitle = "小马过河",
                startedAt = epochOf(2026, 11, 12, 20),
                endedAt = epochOf(2026, 11, 12, 20) + 180_000L,
                listenedSeconds = 180,
                estimatedSleepSeconds = 600,
                endedBy = "TIMER",
                childProfileId = 1L
            ),
            SleepSessionSnapshot(
                storyTitle = "小蝌蚪找妈妈",
                startedAt = epochOf(2026, 11, 11, 20),
                endedAt = epochOf(2026, 11, 11, 20) + 120_000L,
                listenedSeconds = 120,
                estimatedSleepSeconds = 300,
                endedBy = "TIMER",
                childProfileId = 1L
            ),
            SleepSessionSnapshot(
                storyTitle = "月亮船的小乘客",
                startedAt = epochOf(2026, 11, 12, 21),
                endedAt = epochOf(2026, 11, 12, 21) + 240_000L,
                listenedSeconds = 240,
                estimatedSleepSeconds = 900,
                endedBy = "TIMER",
                childProfileId = 2L
            )
        )

        val allReport = SleepStatsCalculator.build(sessions, now = now, zone = zone, childProfileId = null)
        assertEquals(3, allReport.totalSessions)
        assertEquals(9, allReport.totalListenedMinutes)
        assertEquals(2, allReport.nights)

        val childOneReport = SleepStatsCalculator.build(sessions, now = now, zone = zone, childProfileId = 1L)
        assertEquals(2, childOneReport.totalSessions)
        assertEquals(5, childOneReport.totalListenedMinutes)
        assertEquals(2, childOneReport.nights)

        val childTwoReport = SleepStatsCalculator.build(sessions, now = now, zone = zone, childProfileId = 2L)
        assertEquals(1, childTwoReport.totalSessions)
        assertEquals(4, childTwoReport.totalListenedMinutes)
        assertEquals("月亮船的小乘客", childTwoReport.favoriteStory)

        val emptyChildReport = SleepStatsCalculator.build(sessions, now = now, zone = zone, childProfileId = 99L)
        assertFalse(emptyChildReport.hasData)
        assertNull(emptyChildReport.favoriteStory)
    }

    // ---------------- 图片采样解码计算 ----------------

    @Test
    fun testBitmapSampleSizeCalculation() {
        assertEquals(1, BitmapSampleCalculator.calculateInSampleSize(192, 192, 192))
        assertEquals(1, BitmapSampleCalculator.calculateInSampleSize(384, 384, 192))
        assertEquals(2, BitmapSampleCalculator.calculateInSampleSize(500, 400, 192))
        assertEquals(4, BitmapSampleCalculator.calculateInSampleSize(800, 600, 192))
        assertEquals(16, BitmapSampleCalculator.calculateInSampleSize(4000, 3000, 192))
        assertEquals(1, BitmapSampleCalculator.calculateInSampleSize(0, 0, 192))
    }
}
