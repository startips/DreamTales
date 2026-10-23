package com.bedtime.dreamtales

import com.bedtime.dreamtales.core.constants.AppConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
                com.bedtime.dreamtales.service.PlaybackMode.SEQUENTIAL -> com.bedtime.dreamtales.service.PlaybackMode.SHUFFLE
                com.bedtime.dreamtales.service.PlaybackMode.SHUFFLE -> com.bedtime.dreamtales.service.PlaybackMode.REPEAT_ONE
                com.bedtime.dreamtales.service.PlaybackMode.REPEAT_ONE -> com.bedtime.dreamtales.service.PlaybackMode.STOP_AFTER_CURRENT
                com.bedtime.dreamtales.service.PlaybackMode.STOP_AFTER_CURRENT -> com.bedtime.dreamtales.service.PlaybackMode.SEQUENTIAL
            }
        }

        mode = nextMode(mode)
        assertEquals(com.bedtime.dreamtales.service.PlaybackMode.SHUFFLE, mode)

        mode = nextMode(mode)
        assertEquals(com.bedtime.dreamtales.service.PlaybackMode.REPEAT_ONE, mode)

        mode = nextMode(mode)
        assertEquals(com.bedtime.dreamtales.service.PlaybackMode.STOP_AFTER_CURRENT, mode)

        mode = nextMode(mode)
        assertEquals(com.bedtime.dreamtales.service.PlaybackMode.SEQUENTIAL, mode)
    }

    @Test
    fun testAmbientSoundEnumAndAssetsMapping() {
        val sounds = com.bedtime.dreamtales.service.AmbientSound.values()
        assertEquals(6, sounds.size)
        assertEquals(com.bedtime.dreamtales.service.AmbientSound.OFF, sounds[0])
        assertNull(sounds[0].assetName)

        val rain = com.bedtime.dreamtales.service.AmbientSound.RAIN
        assertEquals("🌧️", rain.iconEmoji)
        assertEquals("ambient/ambient_rain.wav", rain.assetName)

        val waves = com.bedtime.dreamtales.service.AmbientSound.WAVES
        assertEquals("🌊", waves.iconEmoji)
        assertEquals("ambient/ambient_waves.wav", waves.assetName)
    }

    @Test
    fun testEffectiveSystemVolumeCalculation() {
        val systemVolumeRatio = 0.6f // 手机当前硬件音量为 60%
        val fadeMultiplier = 0.5f     // 处于最后5分钟声学衰减中 (50%)
        val effectiveVolume = systemVolumeRatio * fadeMultiplier
        assertEquals(0.3f, effectiveVolume, 0.001f)
        assertEquals(30, (effectiveVolume * 100).toInt())
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
    fun testFavoritesFiltering() {
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
    fun testLocalStoryFuzzySearchFilter() {
        val s1 = com.bedtime.dreamtales.data.local.entity.Story(
            id = 1,
            title = "小马过河",
            targetAge = "3-6岁",
            content = "小马驮着半袋麦子，高兴地往磨坊跑去，一条小河挡住了去路。",
            durationSeconds = 180,
            dateLabel = "经典寓言"
        )
        val s2 = com.bedtime.dreamtales.data.local.entity.Story(
            id = 2,
            title = "月亮晚安",
            targetAge = "1-3岁",
            content = "在一间大大的绿色房间里，有只电话，还有一个红气球。",
            durationSeconds = 120,
            dateLabel = "睡前绘本"
        )
        val s3 = com.bedtime.dreamtales.data.local.entity.Story(
            id = 3,
            title = "小红帽与大灰狼",
            targetAge = "4-7岁",
            content = "森林深处住着外婆，小红帽提着蛋糕和葡萄酒走在小路上。",
            durationSeconds = 240,
            dateLabel = "格林童话"
        )
        val allStories = listOf(s1, s2, s3)

        fun filterStories(query: String): List<com.bedtime.dreamtales.data.local.entity.Story> {
            if (query.isBlank()) return allStories
            val q = query.trim()
            return allStories.filter {
                it.title.contains(q, ignoreCase = true) ||
                it.content.contains(q, ignoreCase = true) ||
                it.targetAge.contains(q, ignoreCase = true) ||
                it.dateLabel.contains(q, ignoreCase = true)
            }
        }

        // 1. 空搜索词应返回全部故事
        assertEquals(3, filterStories("").size)
        assertEquals(3, filterStories("   ").size)

        // 2. 按标题搜索
        val searchByTitle = filterStories("小马")
        assertEquals(1, searchByTitle.size)
        assertEquals("小马过河", searchByTitle[0].title)

        // 3. 按正文内容关键字搜索
        val searchByContent = filterStories("红气球")
        assertEquals(1, searchByContent.size)
        assertEquals("月亮晚安", searchByContent[0].title)

        // 4. 按标签或年龄段搜索
        val searchByTag = filterStories("格林童话")
        assertEquals(1, searchByTag.size)
        assertEquals("小红帽与大灰狼", searchByTag[0].title)

        // 5. 不存在关键字返回空列表
        val searchNonExistent = filterStories("恐龙特急克塞号")
        assertTrue(searchNonExistent.isEmpty())
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

    @Test
    fun testVoiceProfileDeletionProtectionAndFallback() {
        val preset = com.bedtime.dreamtales.data.local.entity.VoiceProfile(
            id = 1,
            name = "温柔月光 · 小米原生 (MiMo TTS)",
            cloudVoiceId = "mimo:default",
            localSamplePath = "",
            isDefault = false
        )
        val custom = com.bedtime.dreamtales.data.local.entity.VoiceProfile(
            id = 2,
            name = "爸爸的晚安声",
            cloudVoiceId = "mimo:clone",
            localSamplePath = "/tmp/sample_2.wav",
            isDefault = true
        )

        fun canDelete(profile: com.bedtime.dreamtales.data.local.entity.VoiceProfile): Boolean {
            return profile.cloudVoiceId != "mimo:default"
        }

        assertFalse("官方预置原生音色严禁删除", canDelete(preset))
        assertTrue("用户录入克隆音色允许删除", canDelete(custom))

        // 模拟删除当前活跃音色后的回退逻辑
        val allVoices = mutableListOf(preset, custom)
        val deleted = custom
        allVoices.remove(deleted)

        val fallback = allVoices.firstOrNull { it.cloudVoiceId == "mimo:default" } ?: allVoices.first()
        assertEquals(1L, fallback.id)
        assertEquals("温柔月光 · 小米原生 (MiMo TTS)", fallback.name)
    }

    @Test
    fun testClipboardAutoParseStoryDraft() {
        // 1. 书名号自动提取标题及 Emoji 匹配
        val raw1 = "《月亮掉进烟囱里》\n从前，在一座静悄悄的红砖小镇上，天黑的时候，银色的月亮缓缓升起。"
        val draft1 = com.bedtime.dreamtales.core.StorySpeechHelper.parseClipboardToStoryDraft(raw1)
        assertEquals("月亮掉进烟囱里", draft1.title)
        assertTrue("正文应不包含书名号行", !draft1.content.startsWith("《"))
        assertTrue("正文应包含故事内容", draft1.content.contains("红砖小镇"))
        assertEquals("月亮题材应匹配 🌙 图标", "🌙", draft1.suggestedEmoji)

        // 2. 首行无书名号但为短句时自动提取标题
        val raw2 = "睡前故事：小熊的萤火虫灯笼\n夜幕降临，森林里升起了雾气。小熊拿着一个小小的竹灯笼。"
        val draft2 = com.bedtime.dreamtales.core.StorySpeechHelper.parseClipboardToStoryDraft(raw2)
        assertEquals("小熊的萤火虫灯笼", draft2.title)
        assertEquals("小熊题材应匹配 🐻 图标", "🐻", draft2.suggestedEmoji)

        // 3. 恐龙题材匹配
        val raw3 = "勇敢的小霸王龙\n在茂密的原始森林里，有一只小恐龙正在找妈妈。"
        val draft3 = com.bedtime.dreamtales.core.StorySpeechHelper.parseClipboardToStoryDraft(raw3)
        assertEquals("勇敢的小霸王龙", draft3.title)
        assertEquals("恐龙题材应匹配 🦕 图标", "🦕", draft3.suggestedEmoji)
    }

    @Test
    fun testApiRateLimiterAllowsUpTo5RequestsPerMinute() {
        val limiter = com.bedtime.dreamtales.core.utils.ApiRateLimiter
        limiter.resetForTest()

        val baseTime = 1_000_000L

        // 前 5 次请求均应被允许
        for (i in 1..5) {
            val result = limiter.acquirePermission(currentTime = baseTime + i * 100)
            assertTrue("第 $i 次请求应在限额内允许放行", result is com.bedtime.dreamtales.core.utils.ApiRateLimiter.Result.Allowed)
            val allowed = result as com.bedtime.dreamtales.core.utils.ApiRateLimiter.Result.Allowed
            assertEquals(5 - i, allowed.remaining)
        }

        // 第 6 次请求应被拦截并返回超限提示
        val exceeded = limiter.acquirePermission(currentTime = baseTime + 2000L)
        assertTrue("第 6 次请求应当超限拦截", exceeded is com.bedtime.dreamtales.core.utils.ApiRateLimiter.Result.Exceeded)
        val ex = exceeded as com.bedtime.dreamtales.core.utils.ApiRateLimiter.Result.Exceeded
        assertTrue("等待秒数应在 1 到 60 秒之间", ex.waitSeconds in 1..60)

        // 模拟时间流逝 60 秒后（超过滑动窗口）
        val after60Sec = baseTime + 60_000L + 200L
        val recovered = limiter.acquirePermission(currentTime = after60Sec)
        assertTrue("60秒窗口过期后应当恢复放行", recovered is com.bedtime.dreamtales.core.utils.ApiRateLimiter.Result.Allowed)

        limiter.resetForTest()
    }

    @Test
    fun testRateLimitExceededExceptionFriendlyMessage() {
        val ex = com.bedtime.dreamtales.core.utils.RateLimitExceededException(
            retryAfterSeconds = 35
        )
        assertTrue("异常消息应包含温和安抚语", ex.message.contains("小耳朵也要歇一歇"))
        assertTrue("异常消息应包含等待秒数", ex.message.contains("35 秒"))
        assertTrue("异常消息应包含限速规则说明", ex.message.contains("每分钟最多请求5次"))
    }

    @Test
    fun testDynamicStoryRecommendations() {
        val pool = com.bedtime.dreamtales.core.StorySpeechHelper.STORY_RECOMMENDATION_POOL
        assertTrue("推荐词库应包含充足的故事题材", pool.size >= 20)

        // 随机获取 5 个推荐词
        val batch1 = com.bedtime.dreamtales.core.StorySpeechHelper.getRandomRecommendations(5)
        assertEquals(5, batch1.size)
        assertEquals(5, batch1.toSet().size) // 互不重复

        // 换一批：排除第一批
        val batch2 = com.bedtime.dreamtales.core.StorySpeechHelper.getRandomRecommendations(5, exclude = batch1)
        assertEquals(5, batch2.size)
        assertTrue("新批次不应包含上一批次的词汇", batch2.none { it in batch1 })
    }

    @Test
    fun testStoryPreviewDraftStateAndPersistenceSeparation() {
        val draftStory = com.bedtime.dreamtales.data.local.entity.Story(
            id = 0, // 草稿阶段尚未存库，id 必须为 0
            title = "神奇的月光飞毯",
            targetAge = "3-6岁",
            content = "深蓝色的夜空下，一张毛茸茸的金色小飞毯静静漂浮着...",
            durationSeconds = 180,
            iconEmoji = "🌙"
        )

        // 验证处于预览状态草稿
        val previewState = com.bedtime.dreamtales.ui.home.StoryGenerationUiState.PreviewDraft(draftStory)
        assertEquals(0L, previewState.draft.id)
        assertEquals("神奇的月光飞毯", previewState.draft.title)
        assertTrue(previewState.draft.content.startsWith("深蓝色"))

        // 验证确认持久化后的状态
        val persistedStory = draftStory.copy(id = 88L)
        val successState = com.bedtime.dreamtales.ui.home.StoryGenerationUiState.Success(persistedStory)
        assertEquals(88L, successState.story.id)
    }

    @Test
    fun testImageApiResponseParsingAndBase64Handling() {
        val sampleBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        val dataUrl = "data:image/png;base64,$sampleBase64"

        val json = """
            {
                "choices": [
                    {
                        "message": {
                            "role": "assistant",
                            "images": [
                                {
                                    "type": "image_url",
                                    "image_url": {
                                        "url": "$dataUrl"
                                    }
                                }
                            ]
                        }
                    }
                ]
            }
        """.trimIndent()

        val resp = com.google.gson.Gson().fromJson(json, com.bedtime.dreamtales.data.api.CpaChatResponse::class.java)
        val extractedUrl = resp.choices?.firstOrNull()?.message?.images?.firstOrNull()?.image_url?.url
        assertNotNull(extractedUrl)
        assertTrue(extractedUrl!!.startsWith("data:image/"))

        val base64Data = extractedUrl.substringAfter(",")
        assertEquals(sampleBase64, base64Data)
        val decoded = java.util.Base64.getDecoder().decode(base64Data)
        assertTrue(decoded.isNotEmpty())
    }

    @Test
    fun testSleepTimerDisplayLogic() {
        fun getTimerDisplay(isTimerActive: Boolean, remainingSeconds: Int): String {
            val isFading = isTimerActive && remainingSeconds in 1..300
            return when {
                isFading -> {
                    val min = remainingSeconds / 60
                    val sec = remainingSeconds % 60
                    "⏱️ ${min}:${String.format("%02d", sec)} 渐降"
                }
                isTimerActive -> {
                    val remMin = (remainingSeconds + 59) / 60
                    "⏱️ ${remMin}分"
                }
                else -> "⏱️ 定时"
            }
        }

        assertEquals("⏱️ 定时", getTimerDisplay(false, 0))
        assertEquals("⏱️ 30分", getTimerDisplay(true, 1800))
        assertEquals("⏱️ 10分", getTimerDisplay(true, 590))
        assertEquals("⏱️ 5:00 渐降", getTimerDisplay(true, 300))
        assertEquals("⏱️ 4:32 渐降", getTimerDisplay(true, 272))
        assertEquals("⏱️ 0:15 渐降", getTimerDisplay(true, 15))
    }

    @Test
    fun testPhotoPickerSampleSizeCalculation() {
        fun calculateSampleSize(width: Int, height: Int, targetMaxDim: Int = 1440): Int {
            val maxDim = maxOf(width, height)
            var sampleSize = 1
            while (maxDim / sampleSize > targetMaxDim) {
                sampleSize *= 2
            }
            return sampleSize
        }

        // 1. 常规尺寸无需缩减
        assertEquals(1, calculateSampleSize(1080, 1080))
        assertEquals(1, calculateSampleSize(1200, 800))

        // 2. 3000x2000 相机大图 (3000 / 4 = 750 <= 1440)
        assertEquals(4, calculateSampleSize(3000, 2000))

        // 3. 6000x4000 亿级像素超大图 (6000 / 8 = 750 <= 1440)
        assertEquals(8, calculateSampleSize(6000, 4000))
    }

    @Test
    fun testStoryPreloadAndNonBlockingOtherStoryClicks() {
        // 模拟文库与缓存环境
        val storyA = com.bedtime.dreamtales.data.local.entity.Story(id = 1, title = "故事A (新搜索)", targetAge = "3-6", content = "正文A", durationSeconds = 180)
        val storyB = com.bedtime.dreamtales.data.local.entity.Story(id = 2, title = "故事B (已缓存)", targetAge = "3-6", content = "正文B", durationSeconds = 120)
        val storyC = com.bedtime.dreamtales.data.local.entity.Story(id = 3, title = "故事C (未缓存)", targetAge = "3-6", content = "正文C", durationSeconds = 150)

        val cachedMap = mutableMapOf<Long, String>(
            storyB.id to "/data/user/0/com.bedtime.dreamtales/files/audio_caches/b.wav"
        )

        var currentSynthesizingStoryId: Long? = storyA.id // 故事A 正在后台加载中
        var userRequestedPlayStoryId: Long? = null         // 后台加载非用户点播
        var navigatingToPlayerStoryId: Long? = null

        fun handleStoryClick(story: com.bedtime.dreamtales.data.local.entity.Story) {
            // 1. 如果已缓存，零延迟直接就绪播放
            val cachedPath = cachedMap[story.id]
            if (cachedPath != null) {
                navigatingToPlayerStoryId = story.id
                return
            }

            // 2. 如果正是正在合成的故事，升级为点播
            if (currentSynthesizingStoryId == story.id) {
                userRequestedPlayStoryId = story.id
                return
            }

            // 3. 如果是新的未缓存故事，切换任务
            currentSynthesizingStoryId = story.id
            userRequestedPlayStoryId = story.id
        }

        // 测试1：在故事A加载期间，点击已缓存的故事B -> 必须立即响应播放，不被故事A阻塞！
        handleStoryClick(storyB)
        assertEquals("故事B应立即就绪播放", 2L, navigatingToPlayerStoryId)

        // 测试2：在故事A加载期间，点击未缓存的故事C -> 必须立即切换当前加载目标至故事C！
        handleStoryClick(storyC)
        assertEquals("合成目标应切换为故事C", 3L, currentSynthesizingStoryId)
        assertEquals("用户点播目标应记录为故事C", 3L, userRequestedPlayStoryId)

        // 测试3：后台静默加载故事A完成时，若用户没有点播它，不应跳转播放
        userRequestedPlayStoryId = null
        val onSilentPreloadComplete: () -> Boolean = {
            userRequestedPlayStoryId == storyA.id // false: 不跳播放页
        }
        assertFalse("静默预加载完成不应自动跳转播放", onSilentPreloadComplete())
    }

    @Test
    fun testSplashCelestialThemeAdaptation() {
        // 夜间模式开屏：背景为夜间深黑，天体为金月 🌙，标语温柔伴宝入梦
        val nightIsDark = true
        val nightEmoji = if (nightIsDark) "🌙" else "☀️"
        val nightSlogan = if (nightIsDark) "经典伴读 · 温柔伴宝入梦" else "经典伴读 · 温暖绘本时光"
        assertEquals("🌙", nightEmoji)
        assertEquals("经典伴读 · 温柔伴宝入梦", nightSlogan)

        // 日间模式开屏：背景为羊皮纸米黄，天体为朝阳 ☀️，标语温暖绘本时光
        val dayIsDark = false
        val dayEmoji = if (dayIsDark) "🌙" else "☀️"
        val daySlogan = if (dayIsDark) "经典伴读 · 温柔伴宝入梦" else "经典伴读 · 温暖绘本时光"
        assertEquals("☀️", dayEmoji)
        assertEquals("经典伴读 · 温暖绘本时光", daySlogan)
    }

    @Test
    fun testThemeModeButtonIconMapping() {
        fun getThemeIcon(mode: String): String {
            return when (mode) {
                "day" -> "☀️"
                "system" -> "📱"
                else -> "🌙"
            }
        }

        assertEquals("🌙", getThemeIcon("night"))
        assertEquals("☀️", getThemeIcon("day"))
        assertEquals("📱", getThemeIcon("system"))
        assertEquals("🌙", getThemeIcon("unknown"))
    }

    @Test
    fun testThemeModeSequentialCycling() {
        fun getNextThemeMode(currentMode: String): Pair<String, String> {
            return when (currentMode) {
                "night" -> "day" to "☀️ 绘本米黄"
                "day" -> "system" to "📱 跟随系统"
                else -> "night" to "🌙 深度暖夜"
            }
        }

        assertEquals("day" to "☀️ 绘本米黄", getNextThemeMode("night"))
        assertEquals("system" to "📱 跟随系统", getNextThemeMode("day"))
        assertEquals("night" to "🌙 深度暖夜", getNextThemeMode("system"))
        assertEquals("night" to "🌙 深度暖夜", getNextThemeMode("other"))
    }
}
