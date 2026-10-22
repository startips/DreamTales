package com.bedtime.dreamtales.core.utils

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * API 请求平滑限速与防超频退避拦截器。
 * 1. 平滑流量限制：强制控制相邻请求之间的最小时间间隔，杜绝瞬时并发突发击穿 API 限制。
 * 2. 遇到 HTTP 429 (Too Many Requests) 或 503 限流时，执行指数退避安全重试。
 */
class RateLimitInterceptor(
    private val minIntervalMillis: Long = 1200L,
    private val maxRetries: Int = 2
) : Interceptor {

    private val lastRequestTime = AtomicLong(0L)
    private val rateLock = Any()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // 1. 流量平滑整形：确保请求间隔不低于 minIntervalMillis
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

        // 2. 发起请求并对 429 限频响应做自动指数退避重试
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
