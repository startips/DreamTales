package com.bedtime.dreamtales.data.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

data class MimoChatRequest(
    val model: String,
    val messages: List<MimoMessage>,
    val audio: MimoAudioConfig? = null,
    val max_tokens: Int? = null
)

data class MimoMessage(
    val role: String,
    val content: String
)

data class MimoAudioConfig(
    val format: String = "wav",
    val voice: String? = null
)

data class MimoChatResponse(
    val id: String?,
    val choices: List<MimoChoice>?,
    val error: MimoError?
)

data class MimoChoice(
    val index: Int?,
    val message: MimoResponseMessage?,
    val finish_reason: String?
)

data class MimoResponseMessage(
    val role: String?,
    val content: String?,
    val audio: MimoResponseAudio?
)

data class MimoResponseAudio(
    val id: String?,
    val data: String?
)

data class MimoError(
    val code: String?,
    val message: String?,
    val type: String?
)

interface XiaomiMimoService {

    @POST("v1/chat/completions")
    suspend fun createChatCompletion(
        @Header("api-key") apiKey: String,
        @Body request: MimoChatRequest
    ): Response<MimoChatResponse>
}
