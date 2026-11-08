package com.bedtime.dreamtales.core.share

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class ShareStoryEntry(
    val title: String = "",
    val targetAge: String = "3-6岁",
    val content: String = "",
    val durationSeconds: Int = 180,
    val dateLabel: String = "家庭共享",
    val iconEmoji: String = "🌙",
    val isFavorite: Boolean = false,
    val coverFileName: String? = null
)

data class ShareVoiceEntry(
    val name: String = "",
    val cloudVoiceId: String = "",
    val sampleFileName: String? = null,
    val isDefault: Boolean = false
)

data class ShareManifest(
    val app: String = "DreamTales",
    val protocolVersion: Int = 1,
    val deviceName: String = "",
    val exportedAt: Long = System.currentTimeMillis(),
    val stories: List<ShareStoryEntry> = emptyList(),
    val voices: List<ShareVoiceEntry> = emptyList()
)

data class ShareImportPlan(
    val storiesToImport: List<Int>,
    val storiesToSkip: List<Int>,
    val voicesToImport: List<Int>,
    val voicesToSkip: List<Int>
) {
    val storyImportCount: Int get() = storiesToImport.size
    val storySkipCount: Int get() = storiesToSkip.size
    val voiceImportCount: Int get() = voicesToImport.size
    val voiceSkipCount: Int get() = voicesToSkip.size
}

/**
 * 局域网互传协议常量与纯逻辑工具。
 */
object ShareBundleProtocol {

    const val SERVICE_TYPE = "_dreamtales._tcp."
    const val BUNDLE_PATH = "/bundle"
    const val PING_PATH = "/ping"
    const val MANIFEST_ENTRY = "manifest.json"
    const val DEFAULT_PORT = 0 // 0 = 由系统分配空闲端口
    const val MIME_ZIP = "application/zip"
    const val MIME_JSON = "application/json"

    fun storyKey(title: String): String = title.trim().lowercase()
    fun voiceKey(name: String, cloudVoiceId: String): String =
        "${name.trim().lowercase()}|${cloudVoiceId.trim().lowercase()}"

    fun planImport(
        manifest: ShareManifest,
        existingTitles: Set<String>,
        existingVoiceKeys: Set<String>
    ): ShareImportPlan {
        val normalizedTitles = existingTitles.map { storyKey(it) }.toMutableSet()
        val normalizedVoices = existingVoiceKeys.map { it.trim().lowercase() }.toMutableSet()

        val storyImport = mutableListOf<Int>()
        manifest.stories.forEachIndexed { index, story ->
            if (normalizedTitles.add(storyKey(story.title))) {
                storyImport.add(index)
            }
        }
        val storySkip = manifest.stories.indices.filterNot { it in storyImport }

        val voiceImport = mutableListOf<Int>()
        manifest.voices.forEachIndexed { index, voice ->
            val key = voiceKey(voice.name, voice.cloudVoiceId)
            if (normalizedVoices.add(key)) {
                voiceImport.add(index)
            }
        }
        val voiceSkip = manifest.voices.indices.filterNot { it in voiceImport }

        return ShareImportPlan(
            storiesToImport = storyImport,
            storiesToSkip = storySkip,
            voicesToImport = voiceImport,
            voicesToSkip = voiceSkip
        )
    }

    fun downloadUrl(host: String, port: Int): String {
        val safeHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return "http://$safeHost:$port$BUNDLE_PATH"
    }

    fun deviceLabel(deviceName: String?): String {
        val clean = deviceName?.trim().orEmpty()
        return if (clean.isBlank()) "月眠 家庭设备" else clean
    }

    fun encodeQueryValue(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
