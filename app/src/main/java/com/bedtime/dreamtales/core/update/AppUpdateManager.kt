package com.bedtime.dreamtales.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.bedtime.dreamtales.BuildConfig
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class AppReleaseInfo(
    val tagName: String,
    val versionName: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val apkName: String,
    val apkSizeBytes: Long,
    val publishedAt: String
)

sealed class UpdateCheckResult {
    data class HasUpdate(val releaseInfo: AppReleaseInfo) : UpdateCheckResult()
    object UpToDate : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val progressPercent: Int, val downloadedBytes: Long, val totalBytes: Long) : DownloadState()
    data class Success(val apkFile: File) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

class AppUpdateManager(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * 向 GitHub Releases API 检索最新发版信息并比对当前版本
     */
    fun checkForUpdate(repo: String): Flow<UpdateCheckResult> = flow {
        val cleanRepo = repo.trim().trim('/')
        if (cleanRepo.isBlank() || !cleanRepo.contains('/')) {
            emit(UpdateCheckResult.Error("GitHub 仓库配置格式不正确 (应为 用户名/仓库名)"))
            return@flow
        }

        val url = "https://api.github.com/repos/$cleanRepo/releases/latest"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "DreamTales-Android-Client")
            .get()
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                if (response.code == 404) {
                    emit(UpdateCheckResult.UpToDate)
                } else {
                    emit(UpdateCheckResult.Error("检测失败 (HTTP ${response.code})"))
                }
                return@flow
            }

            val body = response.body?.string().orEmpty()
            if (body.isBlank()) {
                emit(UpdateCheckResult.Error("GitHub 返回响应为空"))
                return@flow
            }

            val json = JsonParser.parseString(body).asJsonObject
            val tagName = json.get("tag_name")?.asString.orEmpty()
            val cleanVersion = tagName.removePrefix("v").removePrefix("V").trim()
            val releaseNotes = json.get("body")?.asString.orEmpty()
            val publishedAt = json.get("published_at")?.asString.orEmpty()

            val assetsArray = json.getAsJsonArray("assets")
            var apkDownloadUrl = ""
            var apkName = ""
            var apkSize = 0L

            if (assetsArray != null && assetsArray.size() > 0) {
                for (element in assetsArray) {
                    val asset = element.asJsonObject
                    val name = asset.get("name")?.asString.orEmpty()
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkName = name
                        apkDownloadUrl = asset.get("browser_download_url")?.asString.orEmpty()
                        apkSize = asset.get("size")?.asLong ?: 0L
                        break
                    }
                }
            }

            val currentVersion = BuildConfig.VERSION_NAME
            if (isRemoteNewer(cleanVersion, currentVersion)) {
                if (apkDownloadUrl.isBlank()) {
                    emit(UpdateCheckResult.Error("发现新版本 $tagName，但未找到 APK 安装包附件"))
                } else {
                    emit(
                        UpdateCheckResult.HasUpdate(
                            AppReleaseInfo(
                                tagName = tagName,
                                versionName = cleanVersion,
                                releaseNotes = releaseNotes,
                                downloadUrl = apkDownloadUrl,
                                apkName = apkName,
                                apkSizeBytes = apkSize,
                                publishedAt = publishedAt
                            )
                        )
                    )
                }
            } else {
                emit(UpdateCheckResult.UpToDate)
            }
        } catch (e: Exception) {
            emit(UpdateCheckResult.Error("连接 GitHub 超时或失败: ${e.message ?: "网络异常"}"))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 下载 APK 文件，带百分比进度回调与网络镜像加速回退
     */
    fun downloadApk(downloadUrl: String, apkFileName: String): Flow<DownloadState> = flow {
        val updatesDir = File(context.externalCacheDir ?: context.cacheDir, "updates")
        if (!updatesDir.exists()) updatesDir.mkdirs()

        val targetFile = File(updatesDir, apkFileName)
        // 检查本地是否已存在完整且结构有效的 APK，若已完整下载则直接就绪，避免重复消耗流量
        if (targetFile.exists() && targetFile.length() > 0) {
            val pkgInfo = try {
                context.packageManager.getPackageArchiveInfo(targetFile.absolutePath, 0)
            } catch (e: Exception) {
                null
            }
            if (pkgInfo != null) {
                emit(DownloadState.Downloading(100, targetFile.length(), targetFile.length()))
                emit(DownloadState.Success(targetFile))
                return@flow
            } else {
                targetFile.delete()
            }
        }

        // 尝试直连与公共镜像
        val urlsToTry = listOf(
            downloadUrl,
            "https://ghfast.top/$downloadUrl",
            "https://ghproxy.net/$downloadUrl"
        )

        var lastException: Exception? = null
        var downloadSuccess = false

        for (candidateUrl in urlsToTry) {
            try {
                val request = Request.Builder()
                    .url(candidateUrl)
                    .header("User-Agent", "DreamTales-Android-Client")
                    .get()
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful && response.body != null) {
                    val body = response.body!!
                    val totalBytes = body.contentLength()
                    var downloadedBytes = 0L

                    body.byteStream().use { input ->
                        FileOutputStream(targetFile).use { output ->
                            val buffer = ByteArray(8 * 1024)
                            var bytesRead: Int
                            var lastPercent = 0

                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead

                                val percent = if (totalBytes > 0) {
                                    ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                                } else 0

                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    emit(DownloadState.Downloading(percent, downloadedBytes, totalBytes))
                                }
                            }
                            output.flush()
                        }
                    }

                    if (targetFile.exists() && targetFile.length() > 0) {
                        downloadSuccess = true
                        emit(DownloadState.Success(targetFile))
                        break
                    }
                }
            } catch (e: Exception) {
                lastException = e
            }
        }

        if (!downloadSuccess) {
            emit(DownloadState.Error("下载更新包失败: ${lastException?.message ?: "网络连接失败"}"))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 调起系统安装包管理器进行版本覆盖安装
     */
    fun installApk(apkFile: File) {
        if (!apkFile.exists() || apkFile.length() == 0L) return

        // Android 8.0+ 权限判断
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(settingsIntent)
                return
            }
        }

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
    }

    companion object {
        /**
         * 语义化版本号对比（如 "2.1.0" > "2.0.0"）
         */
        fun isRemoteNewer(remoteVer: String, currentVer: String): Boolean {
            val remoteParts = remoteVer.split(".").mapNotNull { it.filter { ch -> ch.isDigit() }.toIntOrNull() }
            val currentParts = currentVer.split(".").mapNotNull { it.filter { ch -> ch.isDigit() }.toIntOrNull() }

            val maxLength = maxOf(remoteParts.size, currentParts.size)
            for (i in 0 until maxLength) {
                val r = remoteParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
            return false
        }
    }
}
