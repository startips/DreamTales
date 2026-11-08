package com.bedtime.dreamtales.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import com.bedtime.dreamtales.core.constants.AppConfig
import com.bedtime.dreamtales.core.story.StoryPageSplitter
import com.bedtime.dreamtales.data.api.ApiClient
import com.bedtime.dreamtales.data.api.CpaChatRequest
import com.bedtime.dreamtales.data.api.CpaMessage
import com.bedtime.dreamtales.data.local.AppDatabase
import com.bedtime.dreamtales.data.local.entity.StoryPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 睡前故事专属绘本插画管理仓库 (Local-First)
 * 基于 gemini-3.1-flash-image 模型进行智能生图，并将高清插画持久化缓存至应用私有沙盒。
 */
class StoryImageRepository(private val context: Context) {

    private val appConfig by lazy { AppConfig.getInstance(context) }
    private val storyDao by lazy { AppDatabase.getInstance(context).storyDao() }
    private val storyPageDao by lazy { AppDatabase.getInstance(context).storyPageDao() }

    /**
     * 获取指定故事已缓存的本地插画路径（如果存在）
     */
    fun getExistingCoverPath(storyId: Long): String? {
        val file = File(context.filesDir, "story_covers/cover_${storyId}.jpg")
        return if (file.exists() && file.length() > 0) file.absolutePath else null
    }

    /**
     * 使用 gemini-3.1-flash-image 为故事绘制并持久化保存睡前绘本插画
     */
    suspend fun generateAndSaveIllustration(
        storyId: Long,
        storyTitle: String,
        storyContent: String
    ): String = withContext(Dispatchers.IO) {
        // 针对儿童睡前绘本定制温润安抚提示词，滤除一切文字干扰与刺激元素
        val prompt = "请为儿童睡前童话《$storyTitle》绘制一张高品质睡前绘本插画。画面艺术风格：柔和温润的梦幻水彩手绘风，带有安抚入眠的静谧治愈感，月夜与星光点缀，绝不要包含任何文字、字符或标题水印。画面核心主题：$storyTitle。"

        val imageBytes = requestImageBytes(prompt)

        // 保存到应用私有沙盒目录 filesDir/story_covers/cover_${storyId}.jpg
        val coversDir = File(context.filesDir, "story_covers")
        if (!coversDir.exists()) {
            coversDir.mkdirs()
        }

        val coverFile = File(coversDir, "cover_${storyId}.jpg")
        coverFile.writeBytes(imageBytes)

        // 同步更新 Room 数据库
        try {
            storyDao.updateCoverImagePath(storyId, coverFile.absolutePath)
        } catch (e: Exception) {
            // ignore database sync error if file is successfully written
        }

        coverFile.absolutePath
    }

    /**
     * 为同一篇故事生成 2~4 页连环画（每页一段文字 + 一张专属插画）。
     * 采用顺序生成，便于展示进度，并在失败时保留已成功页面。
     */
    suspend fun generateAndSaveStoryPages(
        storyId: Long,
        storyTitle: String,
        storyContent: String,
        pageCount: Int = StoryPageSplitter.DEFAULT_PAGE_COUNT,
        onProgress: (Int) -> Unit = {}
    ): List<StoryPage> = withContext(Dispatchers.IO) {
        val drafts = StoryPageSplitter.splitIntoPages(storyContent, pageCount)
        if (drafts.isEmpty()) {
            throw IllegalArgumentException("故事正文为空，无法生成连环画")
        }

        val pagesDir = File(context.filesDir, "story_pages")
        if (!pagesDir.exists()) pagesDir.mkdirs()

        val generationId = System.currentTimeMillis()
        val generatedFiles = mutableListOf<File>()
        val savedPages = mutableListOf<StoryPage>()
        var lastError: Exception? = null

        drafts.forEachIndexed { index, draft ->
            onProgress(5 + (index * 80 / drafts.size))
            var imagePath: String? = null
            try {
                val sceneText = draft.text
                    .replace(Regex("^小朋友，我们要讲的故事是《[^》]*》。\\s*"), "")
                    .take(180)
                val prompt = "请为儿童睡前绘本《$storyTitle》绘制第 ${index + 1} 页插画。" +
                    "画面艺术风格：柔和温润的梦幻水彩手绘风，安静治愈，月夜与星光点缀，绝不包含任何文字、字符或标题水印。" +
                    "本页画面内容：$sceneText"
                val bytes = requestImageBytes(prompt)
                val pageFile = File(pagesDir, "story_${storyId}_page_${index}_$generationId.jpg")
                pageFile.writeBytes(bytes)
                generatedFiles.add(pageFile)
                imagePath = pageFile.absolutePath
            } catch (e: Exception) {
                lastError = e
            }
            // 即使单页插图失败，也保留该页文字，避免连环画正文出现断层
            savedPages.add(
                StoryPage(
                    storyId = storyId,
                    pageIndex = index,
                    text = draft.text,
                    imagePath = imagePath
                )
            )
        }

        if (savedPages.all { it.imagePath == null }) {
            generatedFiles.forEach { it.delete() }
            throw RuntimeException(lastError?.message ?: "连环画生成失败，请稍后再试")
        }

        // 新版本生成成功后再清理旧页面，避免失败时丢失已有连环画
        val keepNames = generatedFiles.map { it.name }.toSet()
        pagesDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("story_${storyId}_page_") && file.name !in keepNames) {
                file.delete()
            }
        }
        storyPageDao.deleteForStory(storyId)
        storyPageDao.insertAll(savedPages)
        onProgress(100)
        savedPages
    }

    /**
     * 统一的绘本图片请求与 Base64 解码入口。
     */
    private suspend fun requestImageBytes(prompt: String): ByteArray {
        val apiKey = appConfig.getCpaApiKey()
        val baseUrl = appConfig.getCpaBaseUrl().trimEnd('/') + "/chat/completions"

        val request = CpaChatRequest(
            model = "gemini-3.1-flash-image",
            messages = listOf(CpaMessage(role = "user", content = prompt)),
            max_tokens = 1500
        )

        val response = ApiClient.cpaService.createChatCompletion(
            url = baseUrl,
            authorization = "Bearer $apiKey",
            request = request
        )

        if (!response.isSuccessful) {
            val errBody = response.errorBody()?.string().orEmpty()
            val friendlyMsg = when {
                errBody.contains("RESOURCE_EXHAUSTED") || errBody.contains("model_cooldown") || response.code() == 429 -> {
                    "绘本模型上游渠道配额耗尽正在冷却中，请在 API 管理后台补充/更换渠道 Key 或稍后再试"
                }
                errBody.contains("permission_error") -> {
                    "当前 API 令牌未开启 gemini-3.1-flash-image 模型权限，请在后台放行"
                }
                errBody.contains("insufficient_quota") -> {
                    "当前 API 账户额度不足，请检查后台余额"
                }
                else -> "绘制绘本插画失败 (HTTP ${response.code()}): ${errBody.take(100)}"
            }
            throw RuntimeException(friendlyMsg)
        }

        val choice = response.body()?.choices?.firstOrNull()
            ?: throw RuntimeException("大模型未返回绘本插画数据")

        // 优先从 images 列表中获取 Base64 图片 Data URI
        val dataUrl = choice.message?.images?.firstOrNull()?.image_url?.url
            ?: choice.message?.content.orEmpty()

        if (dataUrl.isBlank()) {
            throw RuntimeException("大模型未生成有效图像内容")
        }

        val base64Data = if (dataUrl.contains(",")) {
            dataUrl.substringAfter(",")
        } else {
            dataUrl
        }.trim()

        val imageBytes = try {
            Base64.decode(base64Data, Base64.DEFAULT)
        } catch (e: Exception) {
            throw RuntimeException("解析插画数据失败: ${e.message}")
        }

        if (imageBytes.isEmpty()) {
            throw RuntimeException("生成的图像数据为空")
        }
        return imageBytes
    }

    /**
     * 将家长从手机相册挑选的图片采样压缩并持久化保存为故事专属封面（免权限、防OOM、自动校正拍摄旋转）
     */
    suspend fun saveImageFromUri(storyId: Long, sourceUri: Uri): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver

        // 1. 获取图片原始宽高，计算合理的采样率（限制长边不超过 1440px，防止大图引发 OOM）
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        val streamOpened = resolver.openInputStream(sourceUri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
            true
        } ?: false

        if (!streamOpened) {
            throw IllegalArgumentException("无法打开所选图片文件")
        }

        if (options.outWidth <= 0 || options.outHeight <= 0) {
            throw IllegalArgumentException("无法解析所选图片尺寸")
        }

        val maxDim = maxOf(options.outWidth, options.outHeight)
        var sampleSize = 1
        while (maxDim / sampleSize > 1440) {
            sampleSize *= 2
        }

        // 2. 采样解码真实 Bitmap
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }
        val rawBitmap = resolver.openInputStream(sourceUri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        } ?: throw IllegalArgumentException("解析所选图片失败")

        // 3. 检查 EXIF 旋转角（防止手机拍摄照片颠倒或横置）
        val finalBitmap = try {
            val orientation = resolver.openInputStream(sourceUri)?.use { exifStream ->
                val exif = ExifInterface(exifStream)
                exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL

            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            }
            if (!matrix.isIdentity) {
                Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true).also {
                    rawBitmap.recycle()
                }
            } else {
                rawBitmap
            }
        } catch (e: Exception) {
            rawBitmap
        }

        // 4. 安全保存到应用私有沙盒目录
        val coversDir = File(context.filesDir, "story_covers")
        if (!coversDir.exists()) {
            coversDir.mkdirs()
        }

        val coverFile = File(coversDir, "cover_${storyId}.jpg")
        FileOutputStream(coverFile).use { outStream ->
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outStream)
        }
        finalBitmap.recycle()

        // 5. 同步更新 Room 数据库
        try {
            storyDao.updateCoverImagePath(storyId, coverFile.absolutePath)
        } catch (e: Exception) {
            // ignore
        }

        coverFile.absolutePath
    }

    /**
     * 清除指定故事的自定义绘本封面
     */
    suspend fun clearStoryCover(storyId: Long) = withContext(Dispatchers.IO) {
        val coverFile = File(context.filesDir, "story_covers/cover_${storyId}.jpg")
        if (coverFile.exists()) {
            coverFile.delete()
        }
        try {
            storyDao.updateCoverImagePath(storyId, "")
        } catch (e: Exception) {
            // ignore
        }
    }
}
