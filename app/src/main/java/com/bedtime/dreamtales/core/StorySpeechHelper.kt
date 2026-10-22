package com.bedtime.dreamtales.core

data class LyricSentence(
    val text: String,
    val startFraction: Float,
    val endFraction: Float
)

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

    /**
     * 智能句子解析器：将正文按标点分句，且将闭合引号/书名号归入当前句，杜绝单独孤立标点
     */
    fun parseStorySentences(content: String): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var i = 0
        while (i < content.length) {
            val ch = content[i]
            current.append(ch)
            if (ch == '。' || ch == '！' || ch == '？' || ch == '\n') {
                // 吸收后续所有相连的标点引号/括号
                while (i + 1 < content.length && (content[i + 1] in "’”」』）》'\"")) {
                    i++
                    current.append(content[i])
                }
                val trimmed = current.toString().trim()
                if (trimmed.isNotEmpty()) {
                    result.add(trimmed)
                }
                current = StringBuilder()
            }
            i++
        }
        val trimmed = current.toString().trim()
        if (trimmed.isNotEmpty()) {
            result.add(trimmed)
        }
        return result
    }

    /**
     * 声学加权节奏计算：依据汉字朗读节拍、标点停顿长短与段落气口，计算每句精确的时间权重比例
     */
    fun calculateLyricIntervals(sentences: List<String>): List<LyricSentence> {
        if (sentences.isEmpty()) return emptyList()

        val weights = sentences.map { s ->
            var weight = 0.0
            val isIntro = s.contains(INTRO_KEYWORD)
            for (ch in s) {
                when {
                    ch in "，、；：" -> weight += 1.8 // 短逗停顿
                    ch in "。！？" -> weight += 3.2 // 句末停顿
                    ch in "‘'“”\"《》（）— " -> weight += 0.0 // 无声阅读符号
                    else -> weight += 1.0 // 正常中英文字符
                }
            }
            if (isIntro) {
                weight += 4.0 // 开场导引道出后的睡前空耳气口
            }
            weight.coerceAtLeast(1.0)
        }

        val totalWeight = weights.sum().coerceAtLeast(1.0)
        var cumulative = 0.0

        return sentences.mapIndexed { index, text ->
            val start = (cumulative / totalWeight).toFloat()
            cumulative += weights[index]
            val end = if (index == sentences.lastIndex) 1.0f else (cumulative / totalWeight).toFloat()
            LyricSentence(
                text = text,
                startFraction = start.coerceIn(0f, 1f),
                endFraction = end.coerceIn(0f, 1f)
            )
        }
    }

    /**
     * 依据当前音频进度占比 (0f..1f) 获取当前正在朗读的句子序号
     */
    fun getActiveSentenceIndex(progressFraction: Float, intervals: List<LyricSentence>): Int {
        if (intervals.isEmpty()) return 0
        val clampedProgress = progressFraction.coerceIn(0f, 1f)
        for (i in intervals.indices) {
            val item = intervals[i]
            if (i == intervals.lastIndex) {
                if (clampedProgress >= item.startFraction && clampedProgress <= item.endFraction) {
                    return i
                }
            } else {
                if (clampedProgress >= item.startFraction && clampedProgress < item.endFraction) {
                    return i
                }
            }
        }
        return if (clampedProgress >= 0.98f) intervals.lastIndex else 0
    }
}
