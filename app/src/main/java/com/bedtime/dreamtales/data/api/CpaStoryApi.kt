package com.bedtime.dreamtales.data.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

data class CpaChatRequest(
    val model: String,
    val messages: List<CpaMessage>,
    val max_tokens: Int? = 1200,
    val temperature: Float? = 0.7f
)

data class CpaMessage(
    val role: String,
    val content: String
)

data class CpaChatResponse(
    val id: String?,
    val choices: List<CpaChoice>?,
    val error: CpaError?
)

data class CpaChoice(
    val index: Int?,
    val message: CpaResponseMessage?,
    val finish_reason: String?
)

data class CpaResponseMessage(
    val role: String?,
    val content: String?
)

data class CpaError(
    val code: String?,
    val message: String?,
    val type: String?
)

/**
 * 搜索候选故事简要卡片模型
 */
data class StoryCandidate(
    val title: String = "",
    val targetAge: String = "3-6岁",
    val summary: String = "",
    val themeTag: String = "睡前童话",
    val iconEmoji: String = "🌙"
)

/**
 * 完整生成故事响应结构
 */
data class GeneratedStoryPayload(
    val title: String = "",
    val targetAge: String = "3-6岁",
    val dateLabel: String = "经典伴读",
    val content: String = "",
    val iconEmoji: String = "🌙",
    val durationSeconds: Int = 180
)

interface CpaStoryService {

    @POST
    suspend fun createChatCompletion(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body request: CpaChatRequest
    ): Response<CpaChatResponse>
}
