package com.bedtime.dreamtales.data.repository

import android.content.Context
import android.util.Base64
import com.bedtime.dreamtales.BuildConfig
import com.bedtime.dreamtales.core.StorySpeechHelper
import com.bedtime.dreamtales.core.constants.AppConfig
import com.bedtime.dreamtales.data.api.ApiClient
import com.bedtime.dreamtales.data.api.CpaChatRequest
import com.bedtime.dreamtales.data.api.CpaMessage
import com.bedtime.dreamtales.data.api.GeneratedStoryPayload
import com.bedtime.dreamtales.data.api.MimoAudioConfig
import com.bedtime.dreamtales.data.api.MimoChatRequest
import com.bedtime.dreamtales.data.api.MimoMessage
import com.bedtime.dreamtales.data.api.StoryCandidate
import com.bedtime.dreamtales.data.local.AppDatabase
import com.bedtime.dreamtales.data.local.entity.Story
import com.bedtime.dreamtales.data.local.entity.StoryAudioCache
import com.bedtime.dreamtales.data.local.entity.VoiceProfile
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class StoryRepository(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val storyDao = db.storyDao()
    private val cacheDao = db.storyAudioCacheDao()
    private val voiceDao = db.voiceProfileDao()
    private val appConfig = AppConfig.getInstance(context)
    private val synthesisMutex = Mutex()

    fun getAllStories(): Flow<List<Story>> = storyDao.getAllStories()

    suspend fun getAllStoriesSync(): List<Story> = storyDao.getAllStoriesSync()

    fun getAllCompleteCaches(): Flow<List<StoryAudioCache>> = cacheDao.getAllCompleteCaches()

    suspend fun getStoryById(storyId: Long): Story? = storyDao.getStoryById(storyId)

    suspend fun recordStoryPlayed(storyId: Long) = withContext(Dispatchers.IO) {
        storyDao.incrementPlayCount(storyId)
    }

    suspend fun setFavorite(storyId: Long, isFavorite: Boolean) = withContext(Dispatchers.IO) {
        storyDao.setFavorite(storyId, isFavorite)
    }

    suspend fun getAudioCacheSizeBytes(): Long = withContext(Dispatchers.IO) {
        val audioDir = File(context.filesDir, "audio_caches")
        if (!audioDir.exists()) return@withContext 0L
        audioDir.listFiles()?.filter { it.name.endsWith(".wav") }?.sumOf { it.length() } ?: 0L
    }

    suspend fun getAudioCacheCount(): Int = withContext(Dispatchers.IO) {
        val audioDir = File(context.filesDir, "audio_caches")
        if (!audioDir.exists()) return@withContext 0
        audioDir.listFiles()?.count { it.name.endsWith(".wav") } ?: 0
    }

    suspend fun clearAllAudioCaches(): Boolean = withContext(Dispatchers.IO) {
        try {
            cacheDao.clearAllCaches()
            val audioDir = File(context.filesDir, "audio_caches")
            if (audioDir.exists()) {
                audioDir.listFiles()?.forEach { file ->
                    if (file.name.endsWith(".wav")) {
                        file.delete()
                    }
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun cleanLegacyAudioCaches() = withContext(Dispatchers.IO) {
        val audioDir = File(context.filesDir, "audio_caches")
        if (!audioDir.exists()) return@withContext
        // 清理旧版本中无 hash 的遗留音频文件，确保音频与当前包含开场白的文本完全对齐
        audioDir.listFiles()?.forEach { file ->
            if (file.name.matches(Regex("story_\\d+_voice_\\d+\\.wav"))) {
                file.delete()
            }
        }
    }

    suspend fun ensureClassicStoriesPopulated() = withContext(Dispatchers.IO) {
        val count = storyDao.getStoryCount()
        if (count < 11) {
            storyDao.insertAll(com.bedtime.dreamtales.data.local.ClassicStoriesSeed.getClassicStories())
        }
    }

    /**
     * 后台静默预缓存前 3 篇未合成的故事，实现夜间 0 毫秒秒开（带平滑延迟保护）
     */
    suspend fun preCacheUpcomingStories(voiceProfile: VoiceProfile) = withContext(Dispatchers.IO) {
        val all = storyDao.getAllStoriesSync()
        for (story in all.take(3)) {
            val spokenStoryText = StorySpeechHelper.buildFullSpokenStoryText(story.title, story.content)
            val contentHash = spokenStoryText.hashCode().toUInt().toString(16)
            val audioDir = File(context.filesDir, "audio_caches")
            val targetFile = File(audioDir, "story_${story.id}_voice_${voiceProfile.id}_$contentHash.wav")

            val existing = cacheDao.getCache(story.id, voiceProfile.id)
            if (existing == null || !existing.isComplete || !File(existing.localFilePath).exists() || existing.localFilePath != targetFile.absolutePath) {
                try {
                    getOrSynthesizeStoryAudio(story, voiceProfile) { /* silent */ }
                    delay(3000L)
                } catch (e: Exception) {
                    // silent fail in background
                }
            }
        }
    }

    /**
     * 获取或合成故事的本地音频文件路径（Local-First 原则，统一由小米 MiMo 免费高清合成）
     */
    suspend fun getOrSynthesizeStoryAudio(
        story: Story,
        voiceProfile: VoiceProfile,
        onProgress: (Int) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        val spokenStoryText = StorySpeechHelper.buildFullSpokenStoryText(story.title, story.content)
        val contentHash = spokenStoryText.hashCode().toUInt().toString(16)

        // 1. 准备沙盒目标存储路径（文件名包含文本哈希，确保文本与语音严格一致）
        val audioDir = File(context.filesDir, "audio_caches")
        if (!audioDir.exists()) audioDir.mkdirs()

        val targetFile = File(audioDir, "story_${story.id}_voice_${voiceProfile.id}_$contentHash.wav")

        // 2. 检查本地沙盒中是否已有该故事与音色的合成缓存（且确保哈希版本严格一致）
        val existingCache = cacheDao.getCache(story.id, voiceProfile.id)
        if (existingCache != null && existingCache.isComplete) {
            val file = File(existingCache.localFilePath)
            if (file.exists() && file.length() > 0 && file.absolutePath == targetFile.absolutePath) {
                onProgress(100)
                return@withContext file.absolutePath
            } else {
                // 如果旧文件是旧文本生成的（例如未包含开场白），安全清理旧文件与旧记录
                file.delete()
                cacheDao.deleteCache(story.id, voiceProfile.id)
            }
        }

        onProgress(10)

        // 3. 互斥队列防并发与排队执行合成（小米 MiMo 免费高速合成）
        synthesisMutex.withLock {
            // 双重锁检查，防止排队等待期间前一个任务已合成完毕
            val doubleCheck = cacheDao.getCache(story.id, voiceProfile.id)
            if (doubleCheck != null && doubleCheck.isComplete) {
                val cachedFile = File(doubleCheck.localFilePath)
                if (cachedFile.exists() && cachedFile.length() > 0 && cachedFile.absolutePath == targetFile.absolutePath) {
                    onProgress(100)
                    return@withContext cachedFile.absolutePath
                }
            }

            synthesizeWithXiaomiMimo(story, voiceProfile, targetFile, onProgress)

            if (!targetFile.exists() || targetFile.length() == 0L) {
                targetFile.delete()
                throw RuntimeException("音频文件落盘失败，本地沙盒文件为空")
            }

            onProgress(95)

            // 4. 更新 Room 持久化缓存记录
            val cacheRecord = StoryAudioCache(
                storyId = story.id,
                voiceProfileId = voiceProfile.id,
                localFilePath = targetFile.absolutePath,
                isComplete = true,
                createdAt = System.currentTimeMillis()
            )
            cacheDao.insertOrUpdate(cacheRecord)

            onProgress(100)
            targetFile.absolutePath
        }
    }

    private suspend fun synthesizeWithXiaomiMimo(
        story: Story,
        voiceProfile: VoiceProfile,
        targetFile: File,
        onProgress: (Int) -> Unit
    ) {
        val apiKey = appConfig.getMimoApiKey()
        onProgress(20)

        val isClone = voiceProfile.cloudVoiceId == "mimo:clone" || voiceProfile.localSamplePath.isNotBlank()
        val model = if (isClone) "mimo-v2.5-tts-voiceclone" else "mimo-v2.5-tts"

        val audioConfig = if (isClone) {
            val sampleFile = File(voiceProfile.localSamplePath)
            if (!sampleFile.exists() || sampleFile.length() == 0L) {
                throw IllegalStateException("专属克隆音频样本文件丢失，请重新录制")
            }
            val base64Sample = Base64.encodeToString(sampleFile.readBytes(), Base64.NO_WRAP)
            MimoAudioConfig(
                format = "wav",
                voice = "data:audio/wav;base64,$base64Sample"
            )
        } else {
            MimoAudioConfig(
                format = "wav",
                voice = "mimo_default"
            )
        }

        val spokenStoryText = StorySpeechHelper.buildFullSpokenStoryText(story.title, story.content)

        val request = MimoChatRequest(
            model = model,
            messages = listOf(
                MimoMessage(
                    role = "user",
                    content = "请用极其舒缓、温柔慢速、充满安睡感的低语语调朗读，语气温和安抚，慢速柔和。"
                ),
                MimoMessage(
                    role = "assistant",
                    content = spokenStoryText
                )
            ),
            audio = audioConfig
        )

        onProgress(40)
        val response = ApiClient.mimoService.createChatCompletion(apiKey, request)
        if (!response.isSuccessful) {
            val errBody = response.errorBody()?.string().orEmpty()
            throw RuntimeException("小米 MiMo 语音生成失败 (HTTP ${response.code()}): $errBody")
        }

        onProgress(75)
        val body = response.body()
        if (body?.error != null) {
            throw RuntimeException("小米 MiMo 接口报错: ${body.error.message ?: body.error.code}")
        }

        val base64Data = body?.choices?.firstOrNull()?.message?.audio?.data
            ?: throw RuntimeException("小米 MiMo 未返回音频数据")

        val audioBytes = Base64.decode(base64Data, Base64.DEFAULT)
        targetFile.writeBytes(audioBytes)
        onProgress(90)
    }

    /**
     * 第一步：调用 CPA 大模型检索并推荐 3 个相关故事候选卡片（供用户二次选择）
     */
    suspend fun searchStoryCandidatesWithCpa(
        query: String
    ): List<StoryCandidate> = withContext(Dispatchers.IO) {
        val apiKey = appConfig.getCpaApiKey()
        val baseUrl = appConfig.getCpaBaseUrl().trimEnd('/') + "/chat/completions"
        val model = appConfig.getCpaModel()

        val prompt = """
            用户正在为孩子搜索睡前童话，搜索词为：[$query]。
            请检索并构思 3 个最契合该主题的高质量睡前童话候选卡片（如果是经典名篇，列出名篇及其治愈改编方向）。
            请为每个故事匹配一个最生动贴切的单一 Emoji 图标（例如九色鹿用 🦌，小红帽用 👧，恐龙用 🦕，森林动物用 🦊，星空梦境用 🌙 等）。

            【严格格式要求】：
            必须且只能直接返回一个 JSON 数组（不要包含任何 markdown 代码块标识），格式示例：
            [
              {
                "title": "故事名称（10字以内）",
                "targetAge": "3-6岁",
                "themeTag": "经典安抚 / 友谊互助 / 勇敢探索",
                "summary": "一句话治愈简介，描述温馨入眠情境（25字以内）",
                "iconEmoji": "🦌"
              }
            ]
        """.trimIndent()

        val request = CpaChatRequest(
            model = model,
            messages = listOf(CpaMessage(role = "user", content = prompt)),
            max_tokens = 600,
            temperature = 0.6f
        )

        val response = ApiClient.cpaService.createChatCompletion(
            url = baseUrl,
            authorization = "Bearer $apiKey",
            request = request
        )

        if (!response.isSuccessful) {
            val errBody = response.errorBody()?.string().orEmpty()
            throw RuntimeException("检索故事失败 (HTTP ${response.code()}): $errBody")
        }

        val choice = response.body()?.choices?.firstOrNull()
            ?: throw RuntimeException("大模型未返回故事候选")
        val contentText = choice.message?.content.orEmpty()
        val cleanJson = contentText.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()

        try {
            val listType = object : TypeToken<List<StoryCandidate>>() {}.type
            val result: List<StoryCandidate> = Gson().fromJson(cleanJson, listType)
            if (result.isNotEmpty()) result else listOf(
                StoryCandidate(title = query, targetAge = "3-6岁", summary = "关于${query}的温馨睡前伴读故事", iconEmoji = "🌙")
            )
        } catch (e: Exception) {
            listOf(
                StoryCandidate(title = query, targetAge = "3-6岁", summary = "关于${query}的温馨睡前伴读故事", iconEmoji = "🌙")
            )
        }
    }

    /**
     * 第二步：根据候选卡片编织完整睡前正文草稿（仅生成内容，暂不落盘入库，供用户阅读全文确认）
     */
    suspend fun generateStoryDraftFromCandidate(
        candidate: StoryCandidate
    ): Story = withContext(Dispatchers.IO) {
        val apiKey = appConfig.getCpaApiKey()
        val baseUrl = appConfig.getCpaBaseUrl().trimEnd('/') + "/chat/completions"
        val model = appConfig.getCpaModel()

        val prompt = """
            你是一位专为儿童编写睡前安抚童话的文学家。请为故事《${candidate.title}》（适龄：${candidate.targetAge}，简介：${candidate.summary}）编写完整睡前正文。

            【严格创作准则】：
            1. 语言极其温柔、舒缓、具有呼吸节奏感，多用“轻轻”、“暖融融”、“沙沙”、“悄悄”等安抚词汇。过滤掉一切打斗、争吵与悬疑惊险。
            2. 正文字数控制在 400 ~ 550 字左右。段落间自然换行，适合慢语速朗读。
            3. 结尾必须是一句温柔自然的入眠晚安导引语（例如引导孩子闭上眼睛、平稳呼吸进入梦乡）。
            4. 必须且只能直接返回纯 JSON 格式（不要包含 markdown 代码块标识），格式如下：
            {
              "title": "${candidate.title}",
              "targetAge": "${candidate.targetAge}",
              "dateLabel": "睡前精选 · 灵感定制",
              "content": "故事完整正文",
              "iconEmoji": "${candidate.iconEmoji.ifBlank { "🌙" }}",
              "durationSeconds": 180
            }
        """.trimIndent()

        val request = CpaChatRequest(
            model = model,
            messages = listOf(CpaMessage(role = "user", content = prompt)),
            max_tokens = 1000,
            temperature = 0.7f
        )

        val response = ApiClient.cpaService.createChatCompletion(
            url = baseUrl,
            authorization = "Bearer $apiKey",
            request = request
        )

        if (!response.isSuccessful) {
            val errBody = response.errorBody()?.string().orEmpty()
            throw RuntimeException("编织童话失败 (HTTP ${response.code()}): $errBody")
        }

        val choice = response.body()?.choices?.firstOrNull()
            ?: throw RuntimeException("大模型未返回故事正文")
        val contentText = choice.message?.content.orEmpty()
        val cleanJson = contentText.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()

        val parsed = try {
            Gson().fromJson(cleanJson, GeneratedStoryPayload::class.java)
        } catch (e: Exception) {
            throw RuntimeException("解析故事 JSON 失败: ${e.message}\n原始文本: $cleanJson")
        }

        val estimatedDuration = if (parsed.durationSeconds > 0) {
            parsed.durationSeconds
        } else {
            ((parsed.content.length / 3.5).toInt()).coerceIn(60, 600)
        }

        Story(
            id = 0,
            title = parsed.title.ifBlank { candidate.title },
            targetAge = parsed.targetAge.ifBlank { candidate.targetAge },
            content = parsed.content,
            durationSeconds = estimatedDuration,
            isAiGenerated = true,
            dateLabel = parsed.dateLabel.ifBlank { "灵感伴读" },
            iconEmoji = parsed.iconEmoji.ifBlank { candidate.iconEmoji.ifBlank { "🌙" } },
            playCount = 0,
            createdAt = System.currentTimeMillis()
        )
    }

    /**
     * 将用户已确认收录的故事正式持久化保存至文库
     */
    suspend fun saveStoryToLibrary(story: Story): Story = withContext(Dispatchers.IO) {
        val id = storyDao.insertStory(story.copy(createdAt = System.currentTimeMillis()))
        story.copy(id = id)
    }

    /**
     * 兼容接口：用户点选后直接生成并入库
     */
    suspend fun generateFullStoryFromCandidate(
        candidate: StoryCandidate
    ): Story = withContext(Dispatchers.IO) {
        val draft = generateStoryDraftFromCandidate(candidate)
        saveStoryToLibrary(draft)
    }

    /**
     * 手动/智能粘贴录入新故事并持久化至 Room 文库
     */
    suspend fun createCustomStory(
        title: String,
        content: String,
        targetAge: String = "3-6岁",
        iconEmoji: String = "🌙"
    ): Story = withContext(Dispatchers.IO) {
        val cleanTitle = title.trim()
        val cleanContent = content.trim()
        val estimatedDuration = ((cleanContent.length / 3.5).toInt()).coerceIn(60, 600)
        val newStory = Story(
            title = cleanTitle,
            targetAge = targetAge.ifBlank { "3-6岁" },
            content = cleanContent,
            durationSeconds = estimatedDuration,
            isAiGenerated = false,
            dateLabel = "自录收录",
            iconEmoji = iconEmoji.ifBlank { "🌙" },
            isFavorite = false,
            playCount = 0,
            createdAt = System.currentTimeMillis()
        )
        val id = storyDao.insertStory(newStory)
        newStory.copy(id = id)
    }

    /**
     * 兼容接口：直接生成单篇故事
     */
    suspend fun searchOrGenerateStoryWithCpa(
        queryOrTopic: String,
        targetAge: String = "3-6岁"
    ): Story = withContext(Dispatchers.IO) {
        val candidate = StoryCandidate(
            title = queryOrTopic,
            targetAge = targetAge,
            summary = "关于$queryOrTopic 的温和睡前故事",
            iconEmoji = "🌙"
        )
        generateFullStoryFromCandidate(candidate)
    }

    /**
     * 导出故事书单至标准 JSON 格式（支持多选故事导出或全量导出）
     */
    suspend fun exportStoriesToJson(selectedStoryIds: Set<Long> = emptySet()): String = withContext(Dispatchers.IO) {
        val allStories = storyDao.getAllStoriesSync()
        val targetStories = if (selectedStoryIds.isEmpty()) {
            allStories
        } else {
            allStories.filter { it.id in selectedStoryIds }
        }

        val exportItems = targetStories.map { story ->
            StoryExportItem(
                title = story.title,
                targetAge = story.targetAge,
                content = story.content,
                durationSeconds = story.durationSeconds,
                dateLabel = story.dateLabel,
                iconEmoji = story.iconEmoji,
                isFavorite = story.isFavorite
            )
        }

        val backupPackage = StoryBackupPackage(
            app = "DreamTales",
            version = BuildConfig.VERSION_NAME,
            exportedAt = System.currentTimeMillis(),
            stories = exportItems
        )

        GsonBuilder().setPrettyPrinting().create().toJson(backupPackage)
    }

    /**
     * 导入外部 JSON 故事书单（智能识别去重并持久化收录）
     */
    suspend fun importStoriesFromJson(jsonContent: String): ImportResult = withContext(Dispatchers.IO) {
        val cleanJson = jsonContent.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()

        if (cleanJson.isBlank()) {
            throw IllegalArgumentException("导入内容不能为空")
        }

        val items: List<StoryExportItem> = try {
            val pkg = Gson().fromJson(cleanJson, StoryBackupPackage::class.java)
            if (pkg?.stories != null && pkg.stories.isNotEmpty()) {
                pkg.stories
            } else {
                val listType = object : TypeToken<List<StoryExportItem>>() {}.type
                Gson().fromJson(cleanJson, listType)
            }
        } catch (e: Exception) {
            val listType = object : TypeToken<List<StoryExportItem>>() {}.type
            Gson().fromJson(cleanJson, listType)
        }

        if (items.isNullOrEmpty()) {
            throw IllegalArgumentException("未能从文件中解析出有效的故事数据")
        }

        val existingStories = storyDao.getAllStoriesSync()
        val existingTitles = existingStories.map { it.title.trim() }.toSet()

        var importedCount = 0
        var skippedCount = 0
        val newStories = mutableListOf<Story>()
        val now = System.currentTimeMillis()

        items.forEachIndexed { index, item ->
            if (existingTitles.contains(item.title.trim())) {
                skippedCount++
            } else {
                newStories.add(
                    Story(
                        title = item.title.trim(),
                        targetAge = item.targetAge.ifBlank { "3-6岁" },
                        content = item.content.trim(),
                        durationSeconds = if (item.durationSeconds > 0) item.durationSeconds else 180,
                        isAiGenerated = true,
                        dateLabel = item.dateLabel.ifBlank { "外部收录" },
                        iconEmoji = item.iconEmoji.ifBlank { "🌙" },
                        isFavorite = item.isFavorite,
                        playCount = 0,
                        createdAt = now - (index * 50)
                    )
                )
                importedCount++
            }
        }

        if (newStories.isNotEmpty()) {
            storyDao.insertAll(newStories)
        }

        ImportResult(
            totalFound = items.size,
            importedCount = importedCount,
            skippedCount = skippedCount
        )
    }
}

data class StoryExportItem(
    val title: String = "",
    val targetAge: String = "3-6岁",
    val content: String = "",
    val durationSeconds: Int = 180,
    val dateLabel: String = "经典童话",
    val iconEmoji: String = "🌙",
    val isFavorite: Boolean = false
)

data class StoryBackupPackage(
    val app: String = "DreamTales",
    val version: String = "1.6.0",
    val exportedAt: Long = System.currentTimeMillis(),
    val stories: List<StoryExportItem> = emptyList()
)

data class ImportResult(
    val totalFound: Int,
    val importedCount: Int,
    val skippedCount: Int
)
