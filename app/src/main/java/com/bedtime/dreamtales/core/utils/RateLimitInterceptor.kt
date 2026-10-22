package com.bedtime.dreamtales.core.utils

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * 伴读服务请求频次超限异常（底层 API 限速）
 */
class RateLimitExceededException(
    val retryAfterSeconds: Int,
    override val message: String = "🌙 伴读提示：故事讲得有点快啦，小耳朵也要歇一歇哦～（每分钟最多请求10次，请等候 ${retryAfterSeconds} 秒后再试）"
) : IOException(message)

/**
 * 底层 API 请求滑动窗口限速器（严格保证每分钟至多 10 次请求）
 */
object ApiRateLimiter {
    const val MAX_REQUESTS_PER_MINUTE = 10
    const val WINDOW_MILLIS = 60_000L

    private val requestTimestamps = ArrayDeque<Long>()
    private val lock = Any()

    sealed class Result {
        data class Allowed(val remaining: Int) : Result()
        data class Exceeded(val waitSeconds: Int) : Result()
    }

    /**
     * 申请一次请求配额。
     * @param currentTime 传入时间戳（便于单元测试模拟时间流逝）
     */
    fun acquirePermission(currentTime: Long = System.currentTimeMillis()): Result {
        synchronized(lock) {
            // 清理超过 60 秒滑动窗口范围的历史请求
            while (requestTimestamps.isNotEmpty() && requestTimestamps.first() <= currentTime - WINDOW_MILLIS) {
                requestTimestamps.removeFirst()
            }

            if (requestTimestamps.size >= MAX_REQUESTS_PER_MINUTE) {
                val oldest = requestTimestamps.first()
                val waitMillis = (oldest + WINDOW_MILLIS) - currentTime
                val waitSeconds = max(1, ((waitMillis + 999) / 1000).toInt())
                return Result.Exceeded(waitSeconds)
            }

            requestTimestamps.addLast(currentTime)
            val remaining = MAX_REQUESTS_PER_MINUTE - requestTimestamps.size
            return Result.Allowed(remaining)
        }
    }

    /**
     * 获取当前滑动窗口内剩余可用请求次数
     */
    fun getRemainingRequests(currentTime: Long = System.currentTimeMillis()): Int {
        synchronized(lock) {
            while (requestTimestamps.isNotEmpty() && requestTimestamps.first() <= currentTime - WINDOW_MILLIS) {
                requestTimestamps.removeFirst()
            }
            return (MAX_REQUESTS_PER_MINUTE - requestTimestamps.size).coerceAtLeast(0)
        }
    }

    /**
     * 重置计数器（供单元测试或登出重置使用）
     */
    fun resetForTest() {
        synchronized(lock) {
            requestTimestamps.clear()
        }
    }
}

/**
 * API 请求平滑限速与防超频退避拦截器。
 * 1. 严格频率限制：每分钟最多允许 10 次请求，超限时直接中断并抛出友好提醒异常 RateLimitExceededException。
 * 2. 流量平滑整形：确保相邻请求间隔不低于 minIntervalMillis，杜绝并发冲击。
 * 3. 遇到服务端 HTTP 429 (Too Many Requests) 时执行安全指数退避重试。
 */
class RateLimitInterceptor(
    private val minIntervalMillis: Long = 1200L,
    private val maxRetries: Int = 2
) : Interceptor {

    private val lastRequestTime = AtomicLong(0L)
    private val rateLock = Any()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // 1. 底层 API 严格限频：每分钟最多 10 次请求
        when (val result = ApiRateLimiter.acquirePermission()) {
            is ApiRateLimiter.Result.Exceeded -> {
                throw RateLimitExceededException(
                    retryAfterSeconds = result.waitSeconds,
                    message = "🌙 伴读提示：故事讲得有点快啦，小耳朵也要歇一歇哦～（每分钟最多请求10次，请等候 ${result.waitSeconds} 秒后再试）"
                )
            }
            is ApiRateLimiter.Result.Allowed -> {
                // 放行
            }
        }

        // 2. 流量平滑整形：确保请求间隔不低于 minIntervalMillis
        synchronized(rateLock) {
            val now = System.currentTimeMillis()
            val last = lastRequestTime.get()
            val elapsed = now - last
            if (elapsed < minIntervalMillis) {
                val sleepTime = minIntervalMillis - elapsed
                try {
                    Thread.sleep(sleepTime)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("请求被限速中断", e)
                }
            }
            lastRequestTime.set(System.currentTimeMillis())
        }

        // 3. 发起请求并对服务端的 429 响应做自动指数退避重试
        var attempt = 0
        var response = chain.proceed(request)

        while (!response.isSuccessful && response.code == 429 && attempt < maxRetries) {
            attempt++
            response.close()

            // 检查服务器是否有 Retry-After 头，若无则使用指数退避 (2000ms, 4000ms)
            val retryAfterSeconds = response.header("Retry-After")?.toLongOrNull()
            val backoffMillis = if (retryAfterSeconds != null && retryAfterSeconds > 0) {
                retryAfterSeconds * 1000L
            } else {
                max(1500L, 2000L * attempt)
            }

            try {
                Thread.sleep(backoffMillis)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("限速重试被中断", e)
            }

            synchronized(rateLock) {
                lastRequestTime.set(System.currentTimeMillis())
            }
            response = chain.proceed(request)
        }

        return response
    }
}
