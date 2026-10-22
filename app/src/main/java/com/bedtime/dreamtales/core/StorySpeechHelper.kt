package com.bedtime.dreamtales.core

object StorySpeechHelper {

    private const val INTRO_KEYWORD = "我们要讲的故事是"

    /**
     * 生成温柔的开场导引语
     */
    fun buildSpokenIntro(title: String): String {
        return "小朋友，我们要讲的故事是《$title》。"
    }

    /**
     * 合成完整的伴读朗读正文（在正文最前置注入开场白，声画节奏严格一致）
     */
    fun buildFullSpokenStoryText(title: String, content: String): String {
        val cleanContent = content.trim()
        if (cleanContent.contains(INTRO_KEYWORD)) {
            return cleanContent
        }
        val intro = buildSpokenIntro(title)
        return "$intro\n$cleanContent"
    }
}
