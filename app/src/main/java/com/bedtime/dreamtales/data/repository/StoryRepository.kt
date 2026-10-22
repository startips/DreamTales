package com.bedtime.dreamtales.data.repository

import android.content.Context
import android.util.Base64
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

    fun getAllCompleteCaches(): Flow<List<StoryAudioCache>> = cacheDao.getAllCompleteCaches()

    suspend fun getStoryById(storyId: Long): Story? = storyDao.getStoryById(storyId)

    suspend fun recordStoryPlayed(storyId: Long) = withContext(Dispatchers.IO) {
        storyDao.incrementPlayCount(storyId)
    }

    suspend fun setFavorite(storyId: Long, isFavorite: Boolean) = withContext(Dispatchers.IO) {
        storyDao.setFavorite(storyId, isFavorite)
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
            val existing = cacheDao.getCache(story.id, voiceProfile.id)
            if (existing == null || !existing.isComplete || !File(existing.localFilePath).exists()) {
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
        // 1. 检查本地沙盒中是否已有该故事与音色的合成缓存
        val existingCache = cacheDao.getCache(story.id, voiceProfile.id)
        if (existingCache != null && existingCache.isComplete) {
            val file = File(existingCache.localFilePath)
            if (file.exists() && file.length() > 0) {
                onProgress(100)
                return@withContext file.absolutePath
            }
        }

        onProgress(10)

        // 2. 准备沙盒目标存储路径
        val audioDir = File(context.filesDir, "audio_caches")
        if (!audioDir.exists()) audioDir.mkdirs()

        val targetFile = File(audioDir, "story_${story.id}_voice_${voiceProfile.id}.wav")

        // 3. 互斥队列防并发与排队执行合成（小米 MiMo 免费高速合成）
        synthesisMutex.withLock {
            // 双重锁检查，防止排队等待期间前一个任务已合成完毕
            val doubleCheck = cacheDao.getCache(story.id, voiceProfile.id)
            if (doubleCheck != null && doubleCheck.isComplete) {
                val cachedFile = File(doubleCheck.localFilePath)
                if (cachedFile.exists() && cachedFile.length() > 0) {
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

        val request = MimoChatRequest(
            model = model,
            messages = listOf(
                MimoMessage(
                    role = "user",
                    content = "请用极其舒缓、温柔慢速、充满安睡感的低语语调朗读，语气温和安抚，慢速柔和。"
                ),
                MimoMessage(
                    role = "assistant",
                    content = story.content
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
     * 第二步：用户在候选卡片中点选后，调用 CPA 大模型编织完整伴读故事并落盘
     */
    suspend fun generateFullStoryFromCandidate(
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

        val newStory = Story(
            title = parsed.title.ifBlank { candidate.title },
            targetAge = parsed.targetAge.ifBlank { candidate.targetAge },
            content = parsed.content,
            durationSeconds = if (parsed.durationSeconds > 0) parsed.durationSeconds else 180,
            isAiGenerated = true,
            dateLabel = parsed.dateLabel.ifBlank { "灵感伴读" },
            iconEmoji = parsed.iconEmoji.ifBlank { candidate.iconEmoji.ifBlank { "🌙" } },
            playCount = 1,
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
}
