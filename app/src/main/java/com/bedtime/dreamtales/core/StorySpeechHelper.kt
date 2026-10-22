package com.bedtime.dreamtales.core

data class LyricSentence(
    val text: String,
    val startFraction: Float,
    val endFraction: Float
)

data class ParsedStoryDraft(
    val title: String,
    val content: String,
    val suggestedEmoji: String,
    val estimatedDurationMinutes: Int
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

    /**
     * 智能剪贴板文本识别：自动从杂糅文本中拆解故事标题、过滤前缀、提取正文、推荐题材 Emoji 并预估伴读耗时
     */
    fun parseClipboardToStoryDraft(rawText: String): ParsedStoryDraft {
        val text = rawText.trim()
        if (text.isBlank()) {
            return ParsedStoryDraft(title = "", content = "", suggestedEmoji = "🌙", estimatedDurationMinutes = 1)
        }

        var title = ""
        var content = text

        // 1. 尝试匹配首行前部的书名号 《...》 或 【...】
        val bookMatch = Regex("^[《【](.*?)[》】]").find(text)
        if (bookMatch != null && bookMatch.groupValues[1].trim().isNotBlank()) {
            title = bookMatch.groupValues[1].trim()
            content = text.substring(bookMatch.value.length).trim()
        } else {
            // 2. 按行分割判断首行是否可做标题
            val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
            if (lines.size > 1 && lines[0].length <= 25 && !lines[0].endsWith("。") && !lines[0].endsWith("！")) {
                title = lines[0]
                content = lines.drop(1).joinToString("\n\n")
            } else if (lines.size == 1 && lines[0].length <= 20) {
                title = lines[0]
                content = ""
            } else {
                title = lines.firstOrNull()?.take(10)?.plus("的故事") ?: "我的睡前故事"
                content = text
            }
        }

        // 净化标题冗余前缀（如“睡前故事：”等）
        title = title.replace(Regex("^(睡前故事|儿童故事|睡前童话|童话故事|故事)[:：\\s]*"), "").trim()

        // 3. 语义化分析标题与正文关键词，自动推荐最生动的 Emoji
        val full = (title + " " + content).lowercase()
        val emoji = when {
            full.contains("兔") || full.contains("萝卜") -> "🐰"
            full.contains("鹿") -> "🦌"
            full.contains("恐龙") || full.contains("霸王龙") -> "🦕"
            full.contains("熊") -> "🐻"
            full.contains("狐狸") -> "🦊"
            full.contains("猫") -> "🐱"
            full.contains("狗") -> "🐶"
            full.contains("象") -> "🐘"
            full.contains("狮") -> "🦁"
            full.contains("马") || full.contains("独角兽") -> "🦄"
            full.contains("鸟") || full.contains("天鹅") || full.contains("夜莺") || full.contains("鸽") -> "🕊️"
            full.contains("鱼") || full.contains("海豚") || full.contains("鲸") -> "🐬"
            full.contains("花") || full.contains("玫瑰") -> "🌸"
            full.contains("公主") || full.contains("王子") || full.contains("城堡") || full.contains("国王") -> "👑"
            full.contains("车") || full.contains("火车") -> "🚂"
            full.contains("星") || full.contains("星星") -> "⭐"
            full.contains("月亮") || full.contains("夜晚") || full.contains("梦") -> "🌙"
            full.contains("气球") -> "🎈"
            else -> "🌙"
        }

        val charCount = content.length
        val estMin = (charCount / 220).coerceAtLeast(1)

        return ParsedStoryDraft(
            title = title,
            content = content,
            suggestedEmoji = emoji,
            estimatedDurationMinutes = estMin
        )
    }

    /**
     * 高质量睡前童话推荐题材池（精选经典名篇、温柔动物、治愈梦境与陪伴童话）
     */
    val STORY_RECOMMENDATION_POOL = listOf(
        // 经典名篇类
        "九色鹿", "神笔马良", "小红帽", "快乐王子", "拇指姑娘", "小蝌蚪找妈妈", "丑小鸭的宁静湖泊", "豌豆公主的软羽绒",
        // 晚安动物类
        "打哈欠的小熊", "小松鼠的落叶被", "小狐狸看星星", "小青蛙的荷叶伞", "晚睡的小树懒", "小刺猬的甜梦", "小兔子的胡萝卜热汤",
        // 梦境与自然类
        "给月亮织围巾", "云朵棉花糖", "海豚的摇篮曲", "会发光的彩虹森林", "乘风飞行的蒲公英", "银河小摇橹船", "深海珍珠的晚安曲",
        // 陪伴与勇敢类
        "做甜梦的小恐龙", "小行星上的玫瑰", "星空夜行列车", "晚安小星星", "守护梦境的小鹿", "不会飞的小雏鸟", "飞向月球的纸飞机"
    )

    /**
     * 随机获取一组睡前故事推荐词（支持排除当前批次以实现换一批）
     */
    fun getRandomRecommendations(count: Int = 5, exclude: List<String> = emptyList()): List<String> {
        val candidates = STORY_RECOMMENDATION_POOL.filter { it !in exclude }
        return if (candidates.size >= count) {
            candidates.shuffled().take(count)
        } else {
            STORY_RECOMMENDATION_POOL.shuffled().take(count)
        }
    }
}
