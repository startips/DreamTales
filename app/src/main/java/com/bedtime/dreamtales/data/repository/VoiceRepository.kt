package com.bedtime.dreamtales.data.repository

import android.content.Context
import com.bedtime.dreamtales.core.constants.AppConfig
import com.bedtime.dreamtales.data.api.ApiClient
import com.bedtime.dreamtales.data.local.AppDatabase
import com.bedtime.dreamtales.data.local.entity.VoiceProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

class VoiceRepository(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val voiceDao = db.voiceProfileDao()
    private val appConfig = AppConfig.getInstance(context)

    fun getAllVoiceProfiles(): Flow<List<VoiceProfile>> = voiceDao.getAllVoiceProfiles()

    fun getDefaultVoiceProfile(): Flow<VoiceProfile?> = voiceDao.getDefaultVoiceProfile()

    suspend fun getDefaultVoiceProfileSync(): VoiceProfile? = voiceDao.getDefaultVoiceProfileSync()

    suspend fun setDefaultVoiceProfile(profileId: Long) {
        voiceDao.setDefaultVoiceProfile(profileId)
    }

    /**
     * 上传/保存录音样本并注册专属音色（优先使用小米 MiMo 免费即时复刻）
     */
    suspend fun cloneAndSaveVoice(name: String, wavFile: File): VoiceProfile = withContext(Dispatchers.IO) {
        if (!wavFile.exists() || wavFile.length() == 0L) {
            throw IllegalArgumentException("录音样本文件不存在或为空")
        }

        val destDir = File(context.filesDir, "voice_samples").apply { if (!exists()) mkdirs() }
        val permanentWav = File(destDir, "voice_sample_${System.currentTimeMillis()}.wav")
        wavFile.copyTo(permanentWav, overwrite = true)

        val voiceId: String
        if (appConfig.hasMimoApiKey()) {
            // 小米 MiMo 采用 Zero-shot 零样本即时复刻，无需云端异步排队训练
            appConfig.getMimoApiKey()
            voiceId = "mimo:clone"
        } else if (appConfig.hasBailianApiKey()) {
            val apiKey = appConfig.getBailianApiKey()
            val requestFile = permanentWav.asRequestBody("audio/wav".toMediaTypeOrNull())
            val bodyPart = MultipartBody.Part.createFormData("audio", permanentWav.name, requestFile)
            val prefixPart = "dreamtales".toRequestBody("text/plain".toMediaTypeOrNull())
            val targetModelPart = "cosyvoice-v1".toRequestBody("text/plain".toMediaTypeOrNull())

            val response = ApiClient.cosyVoiceService.cloneVoice(
                authorization = "Bearer $apiKey",
                audio = bodyPart,
                prefix = prefixPart,
                targetModel = targetModelPart
            )

            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                voiceId = body.output?.voiceId
                    ?: throw RuntimeException("克隆接口未返回 voice_id: ${body.message ?: "未知原因"}")
            } else {
                val errBody = response.errorBody()?.string().orEmpty()
                throw RuntimeException("阿里百炼音色克隆失败 (HTTP ${response.code()}): $errBody")
            }
        } else {
            throw IllegalStateException("未配置小米 MiMo API Key，请进入设置界面填入")
        }

        // 写入 Room 实体并设为默认
        val newProfile = VoiceProfile(
            name = name.ifBlank { "我的专属晚安声" },
            cloudVoiceId = voiceId,
            localSamplePath = permanentWav.absolutePath,
            isDefault = true,
            createdAt = System.currentTimeMillis()
        )

        val newId = voiceDao.insertVoiceProfile(newProfile)
        voiceDao.setDefaultVoiceProfile(newId)

        newProfile.copy(id = newId)
    }

    suspend fun insertPresetVoiceIfEmpty() {
        val all = voiceDao.getAllVoiceProfilesSync()

        // 清理旧的 Google 和百炼音色，保持文库纯净无冗余调用
        all.filter { it.cloudVoiceId.startsWith("google:") || it.cloudVoiceId.startsWith("cosyvoice") }
            .forEach { voiceDao.deleteVoiceProfile(it) }

        val remaining = voiceDao.getAllVoiceProfilesSync()
        val hasMimo = remaining.any { it.cloudVoiceId == "mimo:default" }
        if (!hasMimo) {
            val mimoVoice = VoiceProfile(
                name = "温柔月光 · 小米原生 (MiMo TTS)",
                cloudVoiceId = "mimo:default",
                localSamplePath = "",
                isDefault = true,
                createdAt = System.currentTimeMillis() - 4000
            )
            val id = voiceDao.insertVoiceProfile(mimoVoice)
            voiceDao.setDefaultVoiceProfile(id)
        } else if (remaining.none { it.isDefault }) {
            val defaultOne = remaining.firstOrNull { it.cloudVoiceId == "mimo:default" } ?: remaining.first()
            voiceDao.setDefaultVoiceProfile(defaultOne.id)
        }
    }
}
