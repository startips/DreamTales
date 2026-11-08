package com.bedtime.dreamtales.core.model

/**
 * 宝宝专属档案：用于把真实小主角写进 AI 童话。
 * 所有字段在进入提示词前都会经过 [StoryPersonalization.sanitize] 清洗。
 */
data class ChildProfile(
    val name: String = "",
    val ageLabel: String = "",
    val interests: String = "",
    val todayNote: String = "",
    val isEnabled: Boolean = false
) {
    val hasName: Boolean
        get() = name.trim().isNotEmpty()

    val displayName: String
        get() = if (hasName) name.trim() else "宝贝"

    val isActive: Boolean
        get() = isEnabled && hasName
}
