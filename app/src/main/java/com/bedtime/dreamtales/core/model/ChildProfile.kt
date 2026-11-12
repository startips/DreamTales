package com.bedtime.dreamtales.core.model

/**
 * 宝宝专属档案：用于把真实小主角写进 AI 童话。
 * 所有字段在进入提示词前都会经过 [StoryPersonalization.sanitize] 清洗。
 */
data class ChildProfile(
    val id: Long = 0L,
    val name: String = "",
    val avatarEmoji: String = ChildProfilePolicy.DEFAULT_AVATAR,
    val ageLabel: String = "",
    val interests: String = "",
    val todayNote: String = "",
    val isEnabled: Boolean = false
) {
    val hasName: Boolean
        get() = name.trim().isNotEmpty()

    val displayName: String
        get() = if (hasName) name.trim() else "宝贝"

    val displayAvatar: String
        get() = ChildProfilePolicy.sanitizeAvatar(avatarEmoji)

    val isActive: Boolean
        get() = isEnabled && hasName
}

object ChildProfilePolicy {
    const val MAX_PROFILES = 5
    const val DEFAULT_AVATAR = "👶"
    val AVATAR_OPTIONS = listOf("👶", "👧", "👦", "🐰", "🦖", "🐻", "🦄", "🌟")

    fun sanitizeAvatar(raw: String): String {
        val cleaned = raw.trim()
        return if (cleaned.isNotEmpty() && cleaned.length <= 4) cleaned else DEFAULT_AVATAR
    }

    fun canAddMore(currentCount: Int): Boolean = currentCount < MAX_PROFILES
}
