package com.bedtime.dreamtales.data.api

import com.google.gson.annotations.SerializedName
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Streaming
import retrofit2.http.Url

data class CosyVoiceTtsRequest(
    @SerializedName("model") val model: String = "cosyvoice-v1",
    @SerializedName("input") val input: TtsInput,
    @SerializedName("parameters") val parameters: TtsParameters
)

data class TtsInput(
    @SerializedName("text") val text: String
)

data class TtsParameters(
    @SerializedName("voice") val voice: String,
    @SerializedName("format") val format: String = "mp3",
    @SerializedName("sample_rate") val sampleRate: Int = 22050,
    @SerializedName("volume") val volume: Int = 50,
    @SerializedName("rate") val rate: Float = 1.0f
)

data class CosyVoiceCloneResponse(
    @SerializedName("request_id") val requestId: String?,
    @SerializedName("output") val output: VoiceCloneOutput?,
    @SerializedName("code") val code: String?,
    @SerializedName("message") val message: String?
)

data class VoiceCloneOutput(
    @SerializedName("voice_id") val voiceId: String?,
    @SerializedName("status") val status: String?
)

interface BailianCosyVoiceService {

    /**
     * 百炼 CosyVoice 文本转语音合成
     */
    @POST("api/v1/services/audio/text-to-speech/synthesis")
    @Streaming
    suspend fun synthesizeSpeech(
        @Header("Authorization") authorization: String,
        @Body request: CosyVoiceTtsRequest
    ): Response<ResponseBody>

    /**
     * 上传声音样本进行声音克隆/声音复刻
     */
    @Multipart
    @POST("api/v1/services/audio/voice-cloning")
    suspend fun cloneVoice(
        @Header("Authorization") authorization: String,
        @Part audio: MultipartBody.Part,
        @Part("prefix") prefix: RequestBody,
        @Part("target_model") targetModel: RequestBody
    ): Response<CosyVoiceCloneResponse>

    /**
     * 通用文件下载（当合成接口返回重定向或远程 audio_url 时）
     */
    @GET
    @Streaming
    suspend fun downloadFile(
        @Url fileUrl: String
    ): Response<ResponseBody>
}
