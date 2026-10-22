package com.bedtime.dreamtales.data.api

import com.bedtime.dreamtales.core.utils.RateLimitInterceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {

    private const val BASE_URL = "https://dashscope.aliyuncs.com/"

    private val okHttpClient: OkHttpClient by lazy {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val rateLimitInterceptor = RateLimitInterceptor(minIntervalMillis = 1200L, maxRetries = 2)

        OkHttpClient.Builder()
            .addInterceptor(rateLimitInterceptor)
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    val mimoService: XiaomiMimoService by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.xiaomimimo.com/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(XiaomiMimoService::class.java)
    }

    val cosyVoiceService: BailianCosyVoiceService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(BailianCosyVoiceService::class.java)
    }

    val geminiService: GoogleGeminiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://generativelanguage.googleapis.com/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GoogleGeminiService::class.java)
    }

    val cpaService: CpaStoryService by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.openai.com/v1/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(CpaStoryService::class.java)
    }
}
