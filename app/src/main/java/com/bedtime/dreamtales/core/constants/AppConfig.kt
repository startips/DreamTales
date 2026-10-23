package com.bedtime.dreamtales.core.constants

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.bedtime.dreamtales.BuildConfig

class AppConfig(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // 兼容回退机制（防止 Keystore 异常崩溃）
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 读取小米 MiMo API Key（用于免费 TTS 与即时声音克隆）。
     */
    fun getMimoApiKey(): String {
        return prefs.getString(KEY_MIMO_API_KEY, DEFAULT_MIMO_API_KEY)?.trim()
            ?.ifEmpty { DEFAULT_MIMO_API_KEY } ?: DEFAULT_MIMO_API_KEY
    }

    /**
     * 判断是否已配置小米 MiMo API Key
     */
    fun hasMimoApiKey(): Boolean = true

    /**
     * 安全写入小米 MiMo API Key
     */
    fun setMimoApiKey(apiKey: String) {
        prefs.edit().putString(KEY_MIMO_API_KEY, apiKey.trim()).apply()
    }

    /**
     * 读取 CPA 故事创作与检索配置
     */
    fun getCpaBaseUrl(): String {
        return prefs.getString(KEY_CPA_BASE_URL, DEFAULT_CPA_BASE_URL)?.trim()
            ?.ifEmpty { DEFAULT_CPA_BASE_URL } ?: DEFAULT_CPA_BASE_URL
    }

    fun getCpaApiKey(): String {
        return prefs.getString(KEY_CPA_API_KEY, DEFAULT_CPA_API_KEY)?.trim()
            ?.ifEmpty { DEFAULT_CPA_API_KEY } ?: DEFAULT_CPA_API_KEY
    }

    fun getCpaModel(): String {
        return prefs.getString(KEY_CPA_MODEL, DEFAULT_CPA_MODEL)?.trim()
            ?.ifEmpty { DEFAULT_CPA_MODEL } ?: DEFAULT_CPA_MODEL
    }

    fun setCpaConfig(baseUrl: String, apiKey: String, model: String) {
        prefs.edit()
            .putString(KEY_CPA_BASE_URL, baseUrl.trim())
            .putString(KEY_CPA_API_KEY, apiKey.trim())
            .putString(KEY_CPA_MODEL, model.trim())
            .apply()
    }

    /**
     * 读取百炼 API Key。若为空，严格遵循 Fail-Fast 原则显式抛出异常。
     */
    fun getBailianApiKey(): String {
        val key = prefs.getString(KEY_BAILIAN_API_KEY, null)?.trim()
        if (key.isNullOrEmpty()) {
            throw IllegalStateException("未配置有效的百炼 API Key，请进入设置界面填入")
        }
        return key
    }

    /**
     * 读取 Google Gemini API Key。若为空，显式抛出异常。
     */
    fun getGeminiApiKey(): String {
        val key = prefs.getString(KEY_GEMINI_API_KEY, null)?.trim()
        if (key.isNullOrEmpty()) {
            throw IllegalStateException("未配置有效的 Google AI Studio API Key，请进入设置界面填入")
        }
        return key
    }

    /**
     * 判断是否已配置百炼 API Key
     */
    fun hasBailianApiKey(): Boolean {
        return !prefs.getString(KEY_BAILIAN_API_KEY, null).isNullOrBlank()
    }

    /**
     * 判断是否已配置 Google Gemini API Key
     */
    fun hasGeminiApiKey(): Boolean {
        return !prefs.getString(KEY_GEMINI_API_KEY, null).isNullOrBlank()
    }

    /**
     * 安全写入百炼 API Key
     */
    fun setBailianApiKey(apiKey: String) {
        prefs.edit().putString(KEY_BAILIAN_API_KEY, apiKey.trim()).apply()
    }

    /**
     * 安全写入 Google Gemini API Key
     */
    fun setGeminiApiKey(apiKey: String) {
        prefs.edit().putString(KEY_GEMINI_API_KEY, apiKey.trim()).apply()
    }

    /**
     * 获取百炼默认音色（当未录制克隆音色时的预置音色）
     */
    fun getDefaultVoice(): String {
        return prefs.getString(KEY_DEFAULT_VOICE, "cosyvoice-v1") ?: "cosyvoice-v1"
    }

    fun setDefaultVoice(voice: String) {
        prefs.edit().putString(KEY_DEFAULT_VOICE, voice).apply()
    }

    fun getThemeMode(): String {
        return prefs.getString(KEY_THEME_MODE, "night") ?: "night"
    }

    fun setThemeMode(mode: String) {
        prefs.edit().putString(KEY_THEME_MODE, mode).apply()
    }

    fun getPlaybackMode(): String {
        return prefs.getString(KEY_PLAYBACK_MODE, "SEQUENTIAL") ?: "SEQUENTIAL"
    }

    fun setPlaybackMode(mode: String) {
        prefs.edit().putString(KEY_PLAYBACK_MODE, mode).apply()
    }

    companion object {
        private const val PREFS_NAME = "dreamtales_secure_prefs"
        private const val KEY_THEME_MODE = "key_theme_mode"
        private const val KEY_PLAYBACK_MODE = "key_playback_mode"
        private const val KEY_MIMO_API_KEY = "key_mimo_api_key"
        private const val KEY_BAILIAN_API_KEY = "key_bailian_api_key"
        private const val KEY_GEMINI_API_KEY = "key_gemini_api_key"
        private const val KEY_DEFAULT_VOICE = "key_default_voice"
        private val DEFAULT_MIMO_API_KEY = BuildConfig.DEFAULT_MIMO_API_KEY

        private const val KEY_CPA_BASE_URL = "key_cpa_base_url"
        private const val KEY_CPA_API_KEY = "key_cpa_api_key"
        private const val KEY_CPA_MODEL = "key_cpa_model"

        val DEFAULT_CPA_BASE_URL = BuildConfig.DEFAULT_CPA_BASE_URL
        val DEFAULT_CPA_API_KEY = BuildConfig.DEFAULT_CPA_API_KEY
        val DEFAULT_CPA_MODEL = BuildConfig.DEFAULT_CPA_MODEL

        @Volatile
        private var instance: AppConfig? = null

        fun getInstance(context: Context): AppConfig {
            return instance ?: synchronized(this) {
                instance ?: AppConfig(context.applicationContext).also { instance = it }
            }
        }
    }
}
