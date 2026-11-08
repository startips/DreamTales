package com.bedtime.dreamtales.core.story

import com.bedtime.dreamtales.core.StorySpeechHelper

data class StoryPageDraft(
    val index: Int,
    val text: String,
    val startFraction: Float,
    val endFraction: Float
)

/**
 * 把故事正文按句子边界均匀切分为多页连环画文本。
 * 仅做纯文本计算，便于测试与实际音频进度同步。
 */
object StoryPageSplitter {

    const val DEFAULT_PAGE_COUNT = 3
    const val MIN_PAGE_COUNT = 2
    const val MAX_PAGE_COUNT = 4

    fun splitIntoPages(
        content: String,
        pageCount: Int = DEFAULT_PAGE_COUNT
    ): List<StoryPageDraft> {
        val sentences = StorySpeechHelper.parseStorySentences(content.trim())
            .filter { it.isNotBlank() }
        if (sentences.isEmpty()) return emptyList()

        val safePageCount = pageCount.coerceIn(MIN_PAGE_COUNT, MAX_PAGE_COUNT)
            .coerceAtMost(sentences.size)
        if (safePageCount <= 1) {
            return listOf(
                StoryPageDraft(
                    index = 0,
                    text = sentences.joinToString(""),
                    startFraction = 0f,
                    endFraction = 1f
                )
            )
        }

        val totalWeight = sentences.sumOf { it.length }.coerceAtLeast(1)
        val targetWeight = totalWeight.toDouble() / safePageCount

        val pageTexts = mutableListOf<String>()
        val current = StringBuilder()
        var currentWeight = 0

        sentences.forEachIndexed { index, sentence ->
            current.append(sentence)
            currentWeight += sentence.length

            val remainingSentences = sentences.size - index - 1
            val remainingPages = safePageCount - pageTexts.size - 1
            val shouldClose = remainingPages > 0 &&
                currentWeight >= targetWeight &&
                remainingSentences >= remainingPages

            if (shouldClose) {
                pageTexts.add(current.toString().trim())
                current.clear()
                currentWeight = 0
            }
        }
        if (current.isNotBlank()) {
            pageTexts.add(current.toString().trim())
        }

        return withFractions(pageTexts)
    }

    /**
     * 根据已持久化页面的文本重新计算音频进度占比。
     */
    fun withFractions(pageTexts: List<String>): List<StoryPageDraft> {
        val cleaned = pageTexts.map { it.trim() }.filter { it.isNotBlank() }
        if (cleaned.isEmpty()) return emptyList()
        val total = cleaned.sumOf { it.length }.coerceAtLeast(1)
        var consumed = 0
        return cleaned.mapIndexed { index, text ->
            val start = consumed.toFloat() / total
            consumed += text.length
            val end = if (index == cleaned.lastIndex) 1f else consumed.toFloat() / total
            StoryPageDraft(index, text, start, end)
        }
    }

    fun activePageIndex(progress: Float, pages: List<StoryPageDraft>): Int {
        if (pages.isEmpty()) return 0
        val clamped = progress.coerceIn(0f, 1f)
        for (index in pages.indices) {
            val page = pages[index]
            if (index == pages.lastIndex) {
                if (clamped >= page.startFraction) return index
            } else if (clamped >= page.startFraction && clamped < page.endFraction) {
                return index
            }
        }
        return if (clamped >= 0.98f) pages.lastIndex else 0
    }
}
