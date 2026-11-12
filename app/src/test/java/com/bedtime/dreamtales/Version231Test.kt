package com.bedtime.dreamtales

import com.bedtime.dreamtales.core.audio.OfflineCacheCalculator
import com.bedtime.dreamtales.core.network.BatchDownloadPermission
import com.bedtime.dreamtales.core.network.NetworkDownloadPolicy
import com.bedtime.dreamtales.core.story.StoryPackCatalog
import com.bedtime.dreamtales.data.local.entity.Story
import com.bedtime.dreamtales.data.local.entity.StoryAudioCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Version231Test {

    // ---------------- 情绪/场景故事处方包 ----------------

    @Test
    fun testStoryPackCatalogHasValidOfflineContentAndNoFakeActivity() {
        val packs = StoryPackCatalog.ALL_PACKS
        assertEquals(6, packs.size)
        assertEquals(packs.size, packs.map { it.id }.distinct().size)

        packs.forEach { pack ->
            assertTrue("故事包标题不能为空: ${pack.id}", pack.title.isNotBlank())
            assertTrue("故事包提示词不能为空: ${pack.id}", pack.promptContext.isNotBlank())
            assertTrue("每个故事包至少 3 篇故事: ${pack.id}", pack.curatedStories.size >= 3)
            assertTrue("每个故事包至少 3 个 AI 灵感主题: ${pack.id}", pack.quickTopics.size >= 3)

            pack.curatedStories.forEach { sample ->
                assertTrue(sample.title.isNotBlank())
                assertTrue(sample.content.length >= 150)
                val story = sample.toStory(pack.title)
                assertEquals("处方故事不应预置播放次数", 0, story.playCount)
                assertFalse("处方故事不应预置收藏", story.isFavorite)
            }
        }
        assertNotNull(StoryPackCatalog.findById("dark"))
    }

    // ---------------- 离线缓存管理 ----------------

    @Test
    fun testOfflineCacheCalculatorBuildsItemsAndBatchFilters() {
        val stories = listOf(
            Story(id = 1L, title = "小马过河", targetAge = "2-4岁", content = "正文1", durationSeconds = 180, isFavorite = true),
            Story(id = 2L, title = "小蝌蚪找妈妈", targetAge = "2-4岁", content = "正文2", durationSeconds = 180, isFavorite = false),
            Story(id = 3L, title = "三只小猪", targetAge = "2-4岁", content = "正文3", durationSeconds = 180, isFavorite = true)
        )
        val caches = listOf(
            StoryAudioCache(storyId = 1L, voiceProfileId = 10L, localFilePath = "/tmp/s1.wav", isComplete = true),
            StoryAudioCache(storyId = 2L, voiceProfileId = 99L, localFilePath = "/tmp/s2_other.wav", isComplete = true),
            StoryAudioCache(storyId = 3L, voiceProfileId = 10L, localFilePath = "/tmp/s3_missing.wav", isComplete = true)
        )
        val sizes = mapOf(
            "/tmp/s1.wav" to 1_572_864L,
            "/tmp/s2_other.wav" to 2_000_000L,
            "/tmp/s3_missing.wav" to 0L
        )

        val items = OfflineCacheCalculator.buildItems(
            stories = stories,
            completeCaches = caches,
            voiceProfileId = 10L,
            fileSizeProvider = { path -> sizes[path] ?: 0L }
        )

        assertEquals(3, items.size)
        assertTrue(items[0].isCached)
        assertFalse("其他音色的缓存不应计入当前音色", items[1].isCached)
        assertFalse("空文件/缺失文件不应计入已离线", items[2].isCached)
        assertEquals(1, OfflineCacheCalculator.cachedCount(items))
        assertEquals(1_572_864L, OfflineCacheCalculator.totalCachedBytes(items))

        val pendingAll = OfflineCacheCalculator.pendingForBatch(items, favoritesOnly = false)
        assertEquals(listOf(2L, 3L), pendingAll.map { it.id })

        val pendingFav = OfflineCacheCalculator.pendingForBatch(items, favoritesOnly = true)
        assertEquals(listOf(3L), pendingFav.map { it.id })
    }

    @Test
    fun testOfflineSizeFormatting() {
        assertEquals("0 KB", OfflineCacheCalculator.formatSizeLabel(0L))
        assertEquals("512 KB", OfflineCacheCalculator.formatSizeLabel(512 * 1024L))
        assertEquals("1.5 MB", OfflineCacheCalculator.formatSizeLabel((1.5 * 1024 * 1024).toLong()))
        assertEquals("512 MB", OfflineCacheCalculator.formatFreeSpaceLabel(512f))
        assertEquals("2.0 GB", OfflineCacheCalculator.formatFreeSpaceLabel(2048f))
    }

    // ---------------- 网络策略 ----------------

    @Test
    fun testNetworkDownloadPolicy() {
        assertEquals(
            BatchDownloadPermission.OFFLINE,
            NetworkDownloadPolicy.evaluate(wifiOnly = false, isConnected = false, isUnmetered = false)
        )
        assertEquals(
            BatchDownloadPermission.CELLULAR_BLOCKED,
            NetworkDownloadPolicy.evaluate(wifiOnly = true, isConnected = true, isUnmetered = false)
        )
        assertEquals(
            BatchDownloadPermission.ALLOWED,
            NetworkDownloadPolicy.evaluate(wifiOnly = false, isConnected = true, isUnmetered = false)
        )
        assertEquals(
            BatchDownloadPermission.ALLOWED,
            NetworkDownloadPolicy.evaluate(wifiOnly = true, isConnected = true, isUnmetered = true)
        )
    }
}
