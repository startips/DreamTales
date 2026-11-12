package com.bedtime.dreamtales.core.image

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * 纯采样倍数计算，不依赖 Android Runtime，便于单元测试。
 */
object BitmapSampleCalculator {
    fun calculateInSampleSize(
        srcWidth: Int,
        srcHeight: Int,
        reqMaxDimension: Int
    ): Int {
        if (srcWidth <= 0 || srcHeight <= 0 || reqMaxDimension <= 0) return 1
        val maxSrc = max(srcWidth, srcHeight)
        var inSampleSize = 1
        while ((maxSrc / inSampleSize) > reqMaxDimension * 2) {
            inSampleSize *= 2
        }
        return inSampleSize.coerceAtLeast(1)
    }
}

/**
 * 封面/插画缩略图异步解码与内存缓存：
 * 1. 先查进程内 LruCache（命中则零闪烁直接返回）；
 * 2. 未命中时切到 Dispatchers.IO 先读尺寸再按需采样解码，避免主线程卡顿与大图 OOM。
 */
object CoverBitmapLoader {
    private const val CACHE_CAPACITY = 48
    private val cache = LruCache<String, ImageBitmap>(CACHE_CAPACITY)

    fun getCached(path: String?, maxDimensionPx: Int): ImageBitmap? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.exists() || file.length() <= 0L) return null
        val key = cacheKey(file.absolutePath, maxDimensionPx, file.lastModified())
        return synchronized(cache) { cache.get(key) }
    }

    suspend fun loadSampled(path: String?, maxDimensionPx: Int): ImageBitmap? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.exists() || file.length() <= 0L) return null
        val key = cacheKey(file.absolutePath, maxDimensionPx, file.lastModified())
        synchronized(cache) { cache.get(key) }?.let { return it }

        return withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                val sampleSize = BitmapSampleCalculator.calculateInSampleSize(
                    srcWidth = bounds.outWidth,
                    srcHeight = bounds.outHeight,
                    reqMaxDimension = maxDimensionPx
                )
                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                }
                BitmapFactory.decodeFile(file.absolutePath, decodeOptions)?.asImageBitmap()
            }.getOrNull()?.also { decoded ->
                synchronized(cache) {
                    cache.put(key, decoded)
                }
            }
        }
    }

    private fun cacheKey(path: String, maxDimensionPx: Int, lastModified: Long): String {
        return "$path#$maxDimensionPx#$lastModified"
    }
}

@Composable
fun rememberCoverBitmap(
    path: String?,
    maxDimensionPx: Int = 512
): ImageBitmap? {
    val cached = CoverBitmapLoader.getCached(path, maxDimensionPx)
    val bitmap by produceState<ImageBitmap?>(initialValue = cached, path, maxDimensionPx) {
        if (cached != null) {
            value = cached
        } else if (path.isNullOrBlank()) {
            value = null
        } else {
            value = CoverBitmapLoader.loadSampled(path, maxDimensionPx)
        }
    }
    return bitmap
}
