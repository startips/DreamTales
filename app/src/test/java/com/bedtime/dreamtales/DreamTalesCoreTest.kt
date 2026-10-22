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
        val storyContent = "天黑了，小星星打了个哈欠。森林里的小动物都睡着了！水牛伯伯说：‘明天见。’晚安，做个好梦。"
        val sentences = com.bedtime.dreamtales.core.StorySpeechHelper.parseStorySentences(storyContent)
        assertEquals(4, sentences.size)
        assertEquals("天黑了，小星星打了个哈欠。", sentences[0])
        assertEquals("森林里的小动物都睡着了！", sentences[1])
        assertEquals("水牛伯伯说：‘明天见。’", sentences[2])
        assertEquals("晚安，做个好梦。", sentences[3])

        val intervals = com.bedtime.dreamtales.core.StorySpeechHelper.calculateLyricIntervals(sentences)
        assertEquals(4, intervals.size)
        assertEquals(0f, intervals[0].startFraction, 0.001f)
        assertEquals(1f, intervals.last().endFraction, 0.001f)
        assertTrue("首句结束时间应当等于第二句开始时间", intervals[0].endFraction == intervals[1].startFraction)

        // 测试进度映射索引
        assertEquals(0, com.bedtime.dreamtales.core.StorySpeechHelper.getActiveSentenceIndex(0.01f, intervals))
        assertEquals(1, com.bedtime.dreamtales.core.StorySpeechHelper.getActiveSentenceIndex(intervals[1].startFraction + 0.01f, intervals))
        assertEquals(3, com.bedtime.dreamtales.core.StorySpeechHelper.getActiveSentenceIndex(0.99f, intervals))
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

    @Test
    fun testSpokenIntroGenerationAndDeduplication() {
        val title = "小马过河"
        val content = "阳光暖洋洋的早晨，小马驮着麦子去磨坊。"

        val intro = com.bedtime.dreamtales.core.StorySpeechHelper.buildSpokenIntro(title)
        assertTrue("开场导引语应包含故事标题", intro.contains("《小马过河》"))
        assertTrue("开场导引语应包含固定温柔引导词", intro.contains("小朋友，我们要讲的故事是"))

        val fullText = com.bedtime.dreamtales.core.StorySpeechHelper.buildFullSpokenStoryText(title, content)
        assertTrue("合成全文第一句应为开场导引白", fullText.startsWith("小朋友，我们要讲的故事是《小马过河》。"))
        assertTrue("合成全文应包含后续正文", fullText.contains("小马驮着麦子去磨坊"))

        // 测试防重复添加：如果全文已有开场导引语，不应双重添加
        val doubleWrapped = com.bedtime.dreamtales.core.StorySpeechHelper.buildFullSpokenStoryText(title, fullText)
        assertEquals("防重复注入机制应保持文本不变", fullText, doubleWrapped)
    }

    @Test
    fun testPlaybackModeCyclingSequence() {
        var mode = com.bedtime.dreamtales.service.PlaybackMode.SEQUENTIAL
        fun nextMode(current: com.bedtime.dreamtales.service.PlaybackMode): com.bedtime.dreamtales.service.PlaybackMode {
            return when (current) {
                com.bedtime.dreamtales.service.PlaybackMode.SEQUENTIAL -> com.bedtime.dreamtales.service.PlaybackMode.REPEAT_ONE
                com.bedtime.dreamtales.service.PlaybackMode.REPEAT_ONE -> com.bedtime.dreamtales.service.PlaybackMode.STOP_AFTER_CURRENT
                com.bedtime.dreamtales.service.PlaybackMode.STOP_AFTER_CURRENT -> com.bedtime.dreamtales.service.PlaybackMode.SEQUENTIAL
            }
        }

        mode = nextMode(mode)
        assertEquals(com.bedtime.dreamtales.service.PlaybackMode.REPEAT_ONE, mode)

        mode = nextMode(mode)
        assertEquals(com.bedtime.dreamtales.service.PlaybackMode.STOP_AFTER_CURRENT, mode)

        mode = nextMode(mode)
        assertEquals(com.bedtime.dreamtales.service.PlaybackMode.SEQUENTIAL, mode)
    }

    @Test
    fun testSequentialNextStoryIndexLookup() {
        val stories = listOf(
            com.bedtime.dreamtales.data.local.entity.Story(id = 10, title = "Story 10", targetAge = "3-6", content = "", durationSeconds = 100),
            com.bedtime.dreamtales.data.local.entity.Story(id = 20, title = "Story 20", targetAge = "3-6", content = "", durationSeconds = 100),
            com.bedtime.dreamtales.data.local.entity.Story(id = 30, title = "Story 30", targetAge = "3-6", content = "", durationSeconds = 100)
        )

        fun getNextStory(currentId: Long): com.bedtime.dreamtales.data.local.entity.Story {
            val idx = stories.indexOfFirst { it.id == currentId }
            val nextIdx = if (idx >= 0 && idx + 1 < stories.size) idx + 1 else 0
            return stories[nextIdx]
        }

        assertEquals(20L, getNextStory(10L).id)
        assertEquals(30L, getNextStory(20L).id)
        assertEquals("播到最后一首应自动循环至文库第一首", 10L, getNextStory(30L).id)
        assertEquals("未在列表中的未知ID默认从首篇开始", 10L, getNextStory(999L).id)
    }

    @Test
    fun testMultiDimensionSortingOrders() {
        val s1 = com.bedtime.dreamtales.data.local.entity.Story(id = 1, title = "S1", targetAge = "3-6", content = "", durationSeconds = 100, playCount = 10, isFavorite = false, createdAt = 1000)
        val s2 = com.bedtime.dreamtales.data.local.entity.Story(id = 2, title = "S2", targetAge = "3-6", content = "", durationSeconds = 100, playCount = 2, isFavorite = true, createdAt = 3000)
        val s3 = com.bedtime.dreamtales.data.local.entity.Story(id = 3, title = "S3", targetAge = "3-6", content = "", durationSeconds = 100, playCount = 20, isFavorite = false, createdAt = 2000)
        val list = listOf(s1, s2, s3)

        // 1. 收藏优先 (FAVORITE_FIRST)
        val favFirst = list.sortedWith(compareByDescending<com.bedtime.dreamtales.data.local.entity.Story> { it.isFavorite }.thenByDescending { it.playCount }.thenByDescending { it.createdAt })
        assertEquals("S2 收藏置顶", "S2", favFirst[0].title)
        assertEquals("S3 播放量最高排次位", "S3", favFirst[1].title)
        assertEquals("S1 排末位", "S1", favFirst[2].title)

        // 2. 最多收听 (MOST_PLAYED)
        val mostPlayed = list.sortedWith(compareByDescending<com.bedtime.dreamtales.data.local.entity.Story> { it.playCount }.thenByDescending { it.createdAt })
        assertEquals("S3 最多收听置顶 (20次)", "S3", mostPlayed[0].title)
        assertEquals("S1 次之 (10次)", "S1", mostPlayed[1].title)
        assertEquals("S2 最后 (2次)", "S2", mostPlayed[2].title)

        // 3. 最新加入 (NEWEST_FIRST)
        val newest = list.sortedByDescending { it.createdAt }
        assertEquals("S2 最新创建置顶 (3000)", "S2", newest[0].title)
        assertEquals("S3 次之 (2000)", "S3", newest[1].title)
        assertEquals("S1 最老 (1000)", "S1", newest[2].title)
    }

    @Test
    fun testFavoritesTabFiltering() {
        val s1 = com.bedtime.dreamtales.data.local.entity.Story(id = 1, title = "S1", targetAge = "3-6", content = "", durationSeconds = 100, isFavorite = true)
        val s2 = com.bedtime.dreamtales.data.local.entity.Story(id = 2, title = "S2", targetAge = "3-6", content = "", durationSeconds = 100, isFavorite = false)
        val s3 = com.bedtime.dreamtales.data.local.entity.Story(id = 3, title = "S3", targetAge = "3-6", content = "", durationSeconds = 100, isFavorite = true)
        val list = listOf(s1, s2, s3)

        val favoritesOnly = list.filter { it.isFavorite }
        assertEquals(2, favoritesOnly.size)
        assertTrue(favoritesOnly.all { it.isFavorite })

        val emptyFavorites = list.filter { false }
        assertTrue("空收藏应为空列表", emptyFavorites.isEmpty())
    }

    @Test
    fun testStoryBackupJsonPackageSerializationAndDeduplication() {
        val item1 = com.bedtime.dreamtales.data.repository.StoryExportItem(
            title = "小恐龙找妈妈",
            targetAge = "3-6岁",
            content = "森林深处有一只可爱的小恐龙...",
            durationSeconds = 180,
            iconEmoji = "🦕",
            isFavorite = true
        )
        val item2 = com.bedtime.dreamtales.data.repository.StoryExportItem(
            title = "月亮上的小城堡",
            targetAge = "5-7岁",
            content = "银河旁边有一座金光闪闪的小城堡...",
            durationSeconds = 200,
            iconEmoji = "🏰",
            isFavorite = false
        )
        val pkg = com.bedtime.dreamtales.data.repository.StoryBackupPackage(
            stories = listOf(item1, item2)
        )

        val gson = com.google.gson.Gson()
        val jsonStr = gson.toJson(pkg)
        assertTrue("JSON 应包含应用标识", jsonStr.contains("DreamTales"))
        assertTrue("JSON 应包含小恐龙故事", jsonStr.contains("小恐龙找妈妈"))

        // 反序列化解析
        val parsedPkg = gson.fromJson(jsonStr, com.bedtime.dreamtales.data.repository.StoryBackupPackage::class.java)
        assertEquals(2, parsedPkg.stories.size)
        assertEquals("🦕", parsedPkg.stories[0].iconEmoji)

        // 模拟去重检测
        val existingTitles = setOf("小恐龙找妈妈") // 已存在同名故事
        var imported = 0
        var skipped = 0
        parsedPkg.stories.forEach { story ->
            if (existingTitles.contains(story.title)) {
                skipped++
            } else {
                imported++
            }
        }
        assertEquals("应成功导入 1 篇新故事", 1, imported)
        assertEquals("应跳过 1 篇重复故事", 1, skipped)
    }

    @Test
    fun testAudioCacheSizeFormatting() {
        val bytes = 34_567_890L
        val mb = bytes / (1024f * 1024f)
        assertEquals(32.966f, mb, 0.01f)
        val formatted = String.format("%.1f MB", mb)
        assertEquals("33.0 MB", formatted)
    }
}
