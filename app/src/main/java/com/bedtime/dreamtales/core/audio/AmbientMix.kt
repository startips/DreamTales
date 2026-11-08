package com.bedtime.dreamtales.core.audio

import java.util.Locale

data class AmbientTrackInfo(
    val id: String,
    val title: String,
    val emoji: String,
    val assetName: String
)

data class AmbientPreset(
    val name: String,
    val mix: Map<String, Float>
)

/**
 * 多轨白噪音混音纯逻辑：解析、裁剪、序列化与内置配方。
 * 与 Android 平台无关，可直接单元测试。
 */
object AmbientMix {

    const val MAX_PRESETS = 8
    const val MIN_AUDIBLE_VOLUME = 0.02f

    val TRACKS: List<AmbientTrackInfo> = listOf(
        AmbientTrackInfo("rain", "森林细雨", "🌧️", "ambient/ambient_rain.wav"),
        AmbientTrackInfo("waves", "宁静海浪", "🌊", "ambient/ambient_waves.wav"),
        AmbientTrackInfo("fire", "壁炉柴火", "🔥", "ambient/ambient_fire.wav"),
        AmbientTrackInfo("crickets", "夏夜虫鸣", "🌾", "ambient/ambient_crickets.wav"),
        AmbientTrackInfo("lullaby", "摇篮音盒", "🎶", "ambient/ambient_lullaby.wav")
    )

    val TRACK_IDS: Set<String> = TRACKS.map { it.id }.toSet()

    fun trackInfo(id: String): AmbientTrackInfo? = TRACKS.firstOrNull { it.id == id }

    fun sanitize(mix: Map<String, Float>): Map<String, Float> {
        return mix.entries
            .filter { it.key in TRACK_IDS }
            .mapNotNull { entry ->
                val volume = entry.value.coerceIn(0f, 1f)
                if (volume < MIN_AUDIBLE_VOLUME) null else entry.key to volume
            }
            .sortedBy { it.first }
            .toMap()
    }

    fun encode(mix: Map<String, Float>): String {
        return sanitize(mix).entries.joinToString(",") { (id, volume) ->
            "$id=${formatVolume(volume)}"
        }
    }

    fun decode(raw: String?): Map<String, Float> {
        if (raw.isNullOrBlank()) return emptyMap()
        val parsed = mutableMapOf<String, Float>()
        raw.split(',').forEach { part ->
            val pieces = part.split('=', limit = 2)
            if (pieces.size == 2) {
                val id = pieces[0].trim()
                val volume = pieces[1].trim().toFloatOrNull()
                if (id.isNotBlank() && volume != null) parsed[id] = volume
            }
        }
        return sanitize(parsed)
    }

    fun encodePresets(presets: List<AmbientPreset>): String {
        return presets
            .take(MAX_PRESETS)
            .mapNotNull { preset ->
                val name = cleanPresetName(preset.name)
                val mix = sanitize(preset.mix)
                if (name.isBlank() || mix.isEmpty()) null else "$name|${encode(mix)}"
            }
            .joinToString("\n")
    }

    fun decodePresets(raw: String?): List<AmbientPreset> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lineSequence()
            .mapNotNull { line ->
                val parts = line.split('|', limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val name = cleanPresetName(parts[0])
                val mix = decode(parts[1])
                if (name.isBlank() || mix.isEmpty()) null else AmbientPreset(name, mix)
            }
            .take(MAX_PRESETS)
            .toList()
    }

    fun summary(mix: Map<String, Float>): String {
        val safe = sanitize(mix)
        if (safe.isEmpty()) return "关闭"
        val tracks = safe.keys.mapNotNull { trackInfo(it) }
        val emojis = tracks.joinToString("") { it.emoji }
        return "$emojis ${safe.size} 轨混音"
    }

    fun activeEmojis(mix: Map<String, Float>): String {
        return sanitize(mix).keys.mapNotNull { trackInfo(it)?.emoji }.joinToString("")
    }

    fun defaultPresets(): List<AmbientPreset> = listOf(
        AmbientPreset("雨夜森林", mapOf("rain" to 0.35f, "crickets" to 0.12f)),
        AmbientPreset("海边篝火", mapOf("waves" to 0.32f, "fire" to 0.18f)),
        AmbientPreset("摇篮星夜", mapOf("lullaby" to 0.25f, "crickets" to 0.10f))
    )

    fun cleanPresetName(raw: String): String {
        return raw.trim()
            .replace("|", " ")
            .replace("\n", " ")
            .replace("\r", " ")
            .replace(Regex("\\s+"), " ")
            .take(12)
            .trim()
    }

    private fun formatVolume(volume: Float): String {
        return String.format(Locale.US, "%.2f", volume.coerceIn(0f, 1f))
    }
}
