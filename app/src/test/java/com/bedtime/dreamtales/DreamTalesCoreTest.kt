package com.bedtime.dreamtales

import com.bedtime.dreamtales.core.constants.AppConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max

class DreamTalesCoreTest {

    @Test
    fun testFlipSensorConditions() {
        // 条件：Z < -8.5 m/s² 且 |X| < 3.0 m/s² 且 |Y| < 3.0 m/s² 且 distance == 0.0
        val x1 = 1.2f
        val y1 = -0.8f
        val z1 = -9.6f
        val distance1 = 0.0f

        val isAccelMet1 = z1 < AppConstants.ACCEL_Z_FACE_DOWN_THRESHOLD &&
                abs(x1) < AppConstants.ACCEL_XY_FLAT_THRESHOLD &&
                abs(y1) < AppConstants.ACCEL_XY_FLAT_THRESHOLD
        val isDistanceMet1 = distance1 <= AppConstants.PROXIMITY_COVER_DISTANCE

        assertTrue("正面朝下且遮挡时应当判定满足条件", isAccelMet1 && isDistanceMet1)

        // 手机直立或正面朝上时
        val z2 = 9.8f
        val isAccelMet2 = z2 < AppConstants.ACCEL_Z_FACE_DOWN_THRESHOLD
        assertFalse("手机正面朝上时不应满足扣屏加速度条件", isAccelMet2)

        // 倾斜超过 3.0 m/s²
        val x3 = 4.5f
        val z3 = -8.9f
        val isAccelMet3 = z3 < AppConstants.ACCEL_Z_FACE_DOWN_THRESHOLD &&
                abs(x3) < AppConstants.ACCEL_XY_FLAT_THRESHOLD
        assertFalse("倾斜过大时不应满足平整扣屏条件", isAccelMet3)
    }

    @Test
    fun testLogarithmicVolumeDecayCurve() {
        // 声学对数音量衰减公式：V(p) = ln(1 + 9 * p) / ln(10)
        // p 为声学剩余占比 [0.0, 1.0]
        fun calculateVolume(p: Double): Double {
            return (ln(1.0 + 9.0 * p) / ln(10.0)).coerceIn(0.0, 1.0)
        }

        // 满量程时为 1.0
        assertEquals(1.0, calculateVolume(1.0), 0.001)

        // 归零时为 0.0
        assertEquals(0.0, calculateVolume(0.0), 0.001)

        // 中间值应当呈现对数声学凹函数特性：衰减先慢后快，符合人耳响度感知
        val midVolume = calculateVolume(0.5)
        assertTrue("中点声学音量应当高于线性0.5以符合对数感知", midVolume > 0.5)
        assertEquals(0.74, midVolume, 0.01)
    }

    @Test
    fun testPlaybackSpeedDecay() {
        // 5 分钟 (300 秒)，每 20 秒执行一次步进 -0.02f
        var currentSpeed = AppConstants.PLAYBACK_SPEED_START
        val steps = (5 * 60) / (AppConstants.STEP_INTERVAL_MILLIS / 1000)
        assertEquals(15L, steps)

        for (i in 1..steps) {
            currentSpeed = max(
                AppConstants.PLAYBACK_SPEED_TARGET,
                currentSpeed - AppConstants.PLAYBACK_SPEED_STEP
            )
        }

        // 最终语速应平滑降至 0.75f 目标
        assertEquals(AppConstants.PLAYBACK_SPEED_TARGET, currentSpeed, 0.001f)
    }

    @Test
    fun testDecibelCalculation() {
        fun calculateDecibels(rms: Double): Double {
            if (rms <= 1.0) return 0.0
            val db = 20.0 * log10(rms / 32767.0) + 90.0
            return db.coerceIn(0.0, 100.0)
        }

        // 静音/微弱噪声
        val silenceDb = calculateDecibels(1.0)
        assertEquals(0.0, silenceDb, 0.001)

        // 弱音 (< 35 dB)
        val quietDb = calculateDecibels(50.0)
        assertTrue("微弱环境底噪应当低于 35 dB 门槛", quietDb < AppConstants.MIN_DB_RMS_THRESHOLD)

        // 正常清晰人声 (50 ~ 75 dB)
        val voiceDb = calculateDecibels(2000.0)
        assertTrue("正常语调应当高于 35 dB 门槛", voiceDb > AppConstants.MIN_DB_RMS_THRESHOLD)
        assertTrue("正常人声分贝应在合理区间", voiceDb in 40.0..80.0)
    }

    @Test
    fun testGeminiStoryJsonParsing() {
        val sampleJson = """
            {
              "title": "小兔子捡月光",
              "targetAge": "2-4岁",
              "content": "森林深处，小兔子轻轻闭上双眼...",
              "durationSeconds": 180
            }
        """.trimIndent()

        val parsed = com.google.gson.Gson().fromJson(
            sampleJson,
            com.bedtime.dreamtales.data.api.GeneratedStoryPayload::class.java
        )

        assertEquals("小兔子捡月光", parsed.title)
        assertEquals("2-4岁", parsed.targetAge)
        assertEquals(180, parsed.durationSeconds)
        assertTrue("内容不应为空", parsed.content.isNotEmpty())
    }

    @Test
    fun testLyricSentencePacing() {
        val storyContent = "天黑了，小星星打了个哈欠。森林里的小动物都睡着了！晚安，做个好梦。"
        val raw = storyContent.split(Regex("(?<=[。！？\n])")).map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(3, raw.size)
        assertEquals("天黑了，小星星打了个哈欠。", raw[0])
        assertEquals("森林里的小动物都睡着了！", raw[1])
        assertEquals("晚安，做个好梦。", raw[2])

        val totalChars = raw.sumOf { it.length }.coerceAtLeast(1)
        var cumulative = 0
        val intervals = raw.map { text ->
            val start = cumulative
            cumulative += text.length
            val end = cumulative
            Triple(text, start.toFloat() / totalChars, end.toFloat() / totalChars)
        }

        assertEquals(0f, intervals[0].second, 0.001f)
        assertEquals(1f, intervals.last().third, 0.001f)
        assertTrue("首句结束时间应当小于第二句开始", intervals[0].third <= intervals[1].second + 0.001f)
    }

    @Test
    fun testTopStoriesRankingByPlayCountAndFavorite() {
        val s1 = com.bedtime.dreamtales.data.local.entity.Story(
            id = 1, title = "A", targetAge = "3-6", content = "", durationSeconds = 100, playCount = 3, isFavorite = false
        )
        val s2 = com.bedtime.dreamtales.data.local.entity.Story(
            id = 2, title = "B", targetAge = "3-6", content = "", durationSeconds = 100, playCount = 10, isFavorite = false
        )
        val s3 = com.bedtime.dreamtales.data.local.entity.Story(
            id = 3, title = "C", targetAge = "3-6", content = "", durationSeconds = 100, playCount = 1, isFavorite = true
        )
        val list = listOf(s1, s2, s3)
        val sorted = list.sortedWith(compareByDescending<com.bedtime.dreamtales.data.local.entity.Story> { it.isFavorite }.thenByDescending { it.playCount })

        // 收藏优先置顶
        assertEquals("C", sorted[0].title)
        // 其次按照播放次数排序
        assertEquals("B", sorted[1].title)
        assertEquals("A", sorted[2].title)
    }

    @Test
    fun testStoryCandidatesJsonParsing() {
        val sampleJson = """
            [
              {
                "title": "九色鹿与月光河",
                "targetAge": "3-6岁",
                "themeTag": "经典安抚",
                "summary": "神鹿在月光下悄悄保护小动物入梦",
                "iconEmoji": "🦌"
              }
            ]
        """.trimIndent()

        val listType = object : com.google.gson.reflect.TypeToken<List<com.bedtime.dreamtales.data.api.StoryCandidate>>() {}.type
        val result: List<com.bedtime.dreamtales.data.api.StoryCandidate> = com.google.gson.Gson().fromJson(sampleJson, listType)

        assertEquals(1, result.size)
        assertEquals("九色鹿与月光河", result[0].title)
        assertEquals("🦌", result[0].iconEmoji)
    }
}
