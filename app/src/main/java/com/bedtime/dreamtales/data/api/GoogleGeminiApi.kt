package com.bedtime.dreamtales.data.api

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query

data class GeminiGenerateRequest(
    @SerializedName("contents") val contents: List<GeminiContent>,
    @SerializedName("generationConfig") val generationConfig: GeminiGenerationConfig? = null
)

data class GeminiContent(
    @SerializedName("parts") val parts: List<GeminiPart>
)

data class GeminiPart(
    @SerializedName("text") val text: String? = null,
    @SerializedName("inlineData") val inlineData: GeminiInlineData? = null
)

data class GeminiInlineData(
    @SerializedName("mimeType") val mimeType: String,
    @SerializedName("data") val data: String
)

data class GeminiGenerationConfig(
    @SerializedName("temperature") val temperature: Float? = null,
    @SerializedName("responseMimeType") val responseMimeType: String? = null,
    @SerializedName("responseModalities") val responseModalities: List<String>? = null,
    @SerializedName("speechConfig") val speechConfig: GeminiSpeechConfig? = null
)

data class GeminiSpeechConfig(
    @SerializedName("voiceConfig") val voiceConfig: GeminiVoiceConfig
)

data class GeminiVoiceConfig(
    @SerializedName("prebuiltVoiceConfig") val prebuiltVoiceConfig: GeminiPrebuiltVoiceConfig
)

data class GeminiPrebuiltVoiceConfig(
    @SerializedName("voiceName") val voiceName: String
)

data class GeminiGenerateResponse(
    @SerializedName("candidates") val candidates: List<GeminiCandidate>?
)

data class GeminiCandidate(
    @SerializedName("content") val content: GeminiContent?
)

data class GeneratedStoryJson(
    @SerializedName("title") val title: String,
    @SerializedName("targetAge") val targetAge: String,
    @SerializedName("content") val content: String,
    @SerializedName("durationSeconds") val durationSeconds: Int
)

interface GoogleGeminiService {

    @POST("v1beta/models/gemini-2.5-flash:generateContent")
    suspend fun generateStory(
        @Query("key") apiKey: String,
        @Body request: GeminiGenerateRequest
    ): Response<GeminiGenerateResponse>

    @POST("v1beta/models/gemini-2.5-flash-preview-tts:generateContent")
    suspend fun generateAudio(
        @Query("key") apiKey: String,
        @Body request: GeminiGenerateRequest
    ): Response<GeminiGenerateResponse>
}
