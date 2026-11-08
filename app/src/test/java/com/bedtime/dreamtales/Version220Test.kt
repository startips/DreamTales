package com.bedtime.dreamtales

import com.bedtime.dreamtales.core.playback.PlaybackQueuePolicy
import com.bedtime.dreamtales.core.playback.PlaybackResumePolicy
import com.bedtime.dreamtales.core.playback.PlaybackSpeedPolicy
import com.bedtime.dreamtales.core.reminder.BedtimeReminderCalculator
import com.bedtime.dreamtales.core.reminder.BedtimeReminderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class Version220Test {

    // ---------------- 断点续播 ----------------

    @Test
    fun testResumePolicyIgnoresTinyAndFinishedProgress() {
        assertFalse(PlaybackResumePolicy.shouldPersist(1_000L, 120_000L))
        assertFalse(PlaybackResumePolicy.shouldResume(5_000L, 120_000L))

        assertTrue(PlaybackResumePolicy.shouldPersist(10_000L, 120_000L))
        assertTrue(PlaybackResumePolicy.shouldResume(10_000L, 120_000L))

        assertFalse("距离结尾 5 秒内视为听完", PlaybackResumePolicy.shouldResume(116_000L, 120_000L))
        assertFalse(PlaybackResumePolicy.shouldPersist(116_000L, 120_000L))
    }

    @Test
    fun testResumePolicyAllowsUnknownDuration() {
        assertTrue(PlaybackResumePolicy.shouldResume(15_000L, 0L))
        assertTrue(PlaybackResumePolicy.shouldPersist(15_000L, 0L))
    }

    @Test
    fun testResumePolicyClampsPosition() {
        assertEquals(120_000L, PlaybackResumePolicy.clampPosition(200_000L, 120_000L))
        assertEquals(30_000L, PlaybackResumePolicy.clampPosition(30_000L, 120_000L))
        assertEquals(0L, PlaybackResumePolicy.clampPosition(-10L, 120_000L))
    }

    // ---------------- 今晚故事单 ----------------

    @Test
    fun testQueueNextStopsAtLastItem() {
        assertEquals(0, PlaybackQueuePolicy.nextIndex(-1, 3))
        assertEquals(1, PlaybackQueuePolicy.nextIndex(0, 3))
        assertEquals(2, PlaybackQueuePolicy.nextIndex(1, 3))
        assertNull(PlaybackQueuePolicy.nextIndex(2, 3))
        assertNull(PlaybackQueuePolicy.nextIndex(0, 0))
        assertFalse(PlaybackQueuePolicy.hasNext(2, 3))
    }

    @Test
    fun testQueuePreviousWrapsToLastItem() {
        assertNull(PlaybackQueuePolicy.previousIndex(0, 0))
        assertEquals(2, PlaybackQueuePolicy.previousIndex(0, 3))
        assertEquals(0, PlaybackQueuePolicy.previousIndex(1, 3))
        assertEquals(2, PlaybackQueuePolicy.previousIndex(-1, 3))
    }

    @Test
    fun testQueueIndexOf() {
        val ids = listOf(11L, 22L, 33L)
        assertEquals(1, PlaybackQueuePolicy.indexOf(ids, 22L))
        assertEquals(-1, PlaybackQueuePolicy.indexOf(ids, 44L))
    }

    // ---------------- 播放速度 ----------------

    @Test
    fun testSpeedPolicyNormalizesAndCombinesFade() {
        assertEquals(0.8f, PlaybackSpeedPolicy.normalizeBaseSpeed(0.8f))
        assertEquals(0.9f, PlaybackSpeedPolicy.normalizeBaseSpeed(0.9f))
        assertEquals(1.0f, PlaybackSpeedPolicy.normalizeBaseSpeed(1.0f))

        assertEquals(1.0f, PlaybackSpeedPolicy.effectiveSpeed(1.0f, 1.0f))
        assertEquals(0.75f, PlaybackSpeedPolicy.effectiveSpeed(1.0f, 0.75f))
        assertEquals(0.6f, PlaybackSpeedPolicy.effectiveSpeed(0.8f, 0.75f))
        assertEquals("渐弱系数不应超过 1", 1.0f, PlaybackSpeedPolicy.effectiveSpeed(1.0f, 1.5f))
        assertEquals("渐弱系数不应低于 0.75", 0.75f, PlaybackSpeedPolicy.effectiveSpeed(1.0f, 0.1f))
    }

    // ---------------- 晚安提醒 ----------------

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun millisOf(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        return LocalDateTime.of(year, month, day, hour, minute)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
    }

    @Test
    fun testReminderSameDayWhenTimeIsAhead() {
        val now = millisOf(2026, 11, 8, 20, 0)
        val expected = millisOf(2026, 11, 8, 20, 30)
        assertEquals(expected, BedtimeReminderCalculator.nextTriggerMillis(now, 20, 30, zone))
    }

    @Test
    fun testReminderRollsToNextDayWhenTimePassedOrEqual() {
        val passed = millisOf(2026, 11, 8, 20, 31)
        assertEquals(
            millisOf(2026, 11, 9, 20, 30),
            BedtimeReminderCalculator.nextTriggerMillis(passed, 20, 30, zone)
        )

        val exact = millisOf(2026, 11, 8, 20, 30)
        assertEquals(
            millisOf(2026, 11, 9, 20, 30),
            BedtimeReminderCalculator.nextTriggerMillis(exact, 20, 30, zone)
        )
    }

    @Test
    fun testReminderClampsInvalidTime() {
        val now = millisOf(2026, 11, 8, 12, 0)
        val trigger = BedtimeReminderCalculator.nextTriggerMillis(now, 25, 99, zone)
        assertEquals(millisOf(2026, 11, 8, 23, 59), trigger)
    }

    @Test
    fun testReminderSettingsLabel() {
        assertEquals("20:30", BedtimeReminderSettings(hour = 20, minute = 30).label)
        assertEquals("08:05", BedtimeReminderSettings(hour = 8, minute = 5).label)
    }
}
