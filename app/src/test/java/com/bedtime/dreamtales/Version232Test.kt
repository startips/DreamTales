package com.bedtime.dreamtales

import com.bedtime.dreamtales.core.audio.WavCompactionMarker
import com.bedtime.dreamtales.core.playback.SleepTimerPolicy
import com.bedtime.dreamtales.core.story.ClassicSeedPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class Version232Test {

    // ---------------- 经典故事播种幂等 ----------------

    @Test
    fun testDuplicateSeedRowsKeepEarliestId() {
        val seedTitles = setOf("小马过河", "小蝌蚪找妈妈")
        val entries = listOf(
            "小马过河" to 1L,
            "小蝌蚪找妈妈" to 2L,
            "小马过河" to 21L,
            "小马过河" to 31L,
            "我的自定义故事" to 40L,
            "我的自定义故事" to 41L
        )

        val duplicates = ClassicSeedPolicy.duplicateIdsToRemove(entries, seedTitles)
        assertEquals(listOf(21L, 31L), duplicates)
        // 用户自建同名故事不能被误删
        assertFalse(duplicates.contains(40L))
        assertFalse(duplicates.contains(41L))
    }

    @Test
    fun testMissingSeedsIsTitleIdempotent() {
        val missing = ClassicSeedPolicy.missingSeeds(
            existingTitles = listOf(" 小马过河 ", "小蝌蚪找妈妈"),
            seedTitles = listOf("小马过河", "小蝌蚪找妈妈", "三只小猪的红砖屋")
        )
        assertEquals(listOf("三只小猪的红砖屋"), missing)
    }

    // ---------------- 睡眠定时状态机 ----------------

    @Test
    fun testDefaultTimerArmsOnlyForManualFirstPlay() {
        // 首次手动播放：挂默认定时
        assertTrue(
            SleepTimerPolicy.shouldArmDefaultTimer(
                isTimerActive = false,
                remainingSeconds = 0,
                cancelledByUser = false,
                expiredRecently = false,
                viaAutoAdvance = false
            )
        )
        // 已有活动定时：不重挂
        assertFalse(
            SleepTimerPolicy.shouldArmDefaultTimer(
                isTimerActive = true,
                remainingSeconds = 600,
                cancelledByUser = false,
                expiredRecently = false,
                viaAutoAdvance = false
            )
        )
        // 自动连播：不重挂（避免定时归零后被偷偷续上）
        assertFalse(
            SleepTimerPolicy.shouldArmDefaultTimer(
                isTimerActive = false,
                remainingSeconds = 0,
                cancelledByUser = false,
                expiredRecently = false,
                viaAutoAdvance = true
            )
        )
        // 用户主动取消过：不重挂
        assertFalse(
            SleepTimerPolicy.shouldArmDefaultTimer(
                isTimerActive = false,
                remainingSeconds = 0,
                cancelledByUser = true,
                expiredRecently = false,
                viaAutoAdvance = false
            )
        )
        // 定时刚归零：不自动重挂
        assertFalse(
            SleepTimerPolicy.shouldArmDefaultTimer(
                isTimerActive = false,
                remainingSeconds = 0,
                cancelledByUser = false,
                expiredRecently = true,
                viaAutoAdvance = false
            )
        )
    }

    @Test
    fun testRearmAfterExpiryAndBlockAutoAdvance() {
        // 定时归零后用户手动续播：恢复定时
        assertTrue(
            SleepTimerPolicy.shouldRearmAfterExpiry(
                isTimerActive = false,
                cancelledByUser = false,
                expiredRecently = true
            )
        )
        // 用户取消过：不恢复
        assertFalse(
            SleepTimerPolicy.shouldRearmAfterExpiry(
                isTimerActive = false,
                cancelledByUser = true,
                expiredRecently = true
            )
        )
        // 定时还在跑：不需要恢复
        assertFalse(
            SleepTimerPolicy.shouldRearmAfterExpiry(
                isTimerActive = true,
                cancelledByUser = false,
                expiredRecently = true
            )
        )

        // 定时刚归零：阻止自动连播
        assertTrue(SleepTimerPolicy.shouldBlockAutoAdvance(expiredRecently = true, isTimerActive = false))
        // 定时正常运行时：允许自动连播
        assertFalse(SleepTimerPolicy.shouldBlockAutoAdvance(expiredRecently = false, isTimerActive = false))
        assertFalse(SleepTimerPolicy.shouldBlockAutoAdvance(expiredRecently = true, isTimerActive = true))
    }

    // ---------------- WAV 压缩只做一次 ----------------

    @Test
    fun testCompactionMarkerSkipsSecondPass() {
        val dir = Files.createTempDirectory("wav-marker").toFile()
        try {
            val wav = File(dir, "story_1_voice_1_abc.wav")
            // 构造一个最小 16bit PCM WAV 头 + 少量静音数据
            wav.writeBytes(ByteArray(200))
            assertFalse("未压缩过应为 false", WavCompactionMarker.isCompacted(wav))

            // 标记后应视为已处理，避免每次播放重复扫描整个文件
            val marker = File(dir, wav.name + ".compact")
            marker.writeText("1")
            marker.setLastModified(wav.lastModified() + 1000)
            assertTrue(WavCompactionMarker.isCompacted(wav))

            WavCompactionMarker.clearMarker(wav)
            assertFalse(WavCompactionMarker.isCompacted(wav))
        } finally {
            dir.deleteRecursively()
        }
    }
}