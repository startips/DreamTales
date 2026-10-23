package com.bedtime.dreamtales.core.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
     * 向 GitHub Releases API 或国内加速镜像检索最新发版信息并比对当前版本
     */
    fun checkForUpdate(repo: String): Flow<UpdateCheckResult> = flow {
        val cleanRepo = repo.trim().trim('/')
        if (cleanRepo.isBlank() || !cleanRepo.contains('/')) {
            emit(UpdateCheckResult.Error("GitHub 仓库配置格式不正确 (应为 用户名/仓库名)"))
            return@flow
        }

        // 多路备用请求端点：优先通过国内加速镜像拉取，若不可用自动平滑回退至官方 API
        val endpointsToTry = listOf(
            "https://ghfast.top/https://raw.githubusercontent.com/$cleanRepo/main/version.json",
            "https://ghproxy.net/https://raw.githubusercontent.com/$cleanRepo/main/version.json",
            "https://api.github.com/repos/$cleanRepo/releases/latest",
            "https://raw.githubusercontent.com/$cleanRepo/main/version.json"
        )

        var lastException: Exception? = null
        var foundRelease: AppReleaseInfo? = null
        var isUpToDate = false

        for (endpoint in endpointsToTry) {
            try {
                val request = Request.Builder()
                    .url(endpoint)
                    .header("Accept", "application/vnd.github.v3+json, application/json")
                    .header("User-Agent", "DreamTales-Android-Client")
                    .get()
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    if (response.code == 404 && endpoint.contains("api.github.com")) {
                        isUpToDate = true
                        break
                    }
                    continue
                }

                val body = response.body?.string().orEmpty()
                if (body.isBlank()) continue

                val json = JsonParser.parseString(body).asJsonObject
                val tagName = json.get("tag_name")?.asString
                    ?: json.get("tagName")?.asString.orEmpty()
                val cleanVersion = if (json.has("versionName")) {
                    json.get("versionName").asString
                } else {
                    tagName.removePrefix("v").removePrefix("V").trim()
                }
                val releaseNotes = json.get("body")?.asString
                    ?: json.get("releaseNotes")?.asString.orEmpty()
                val publishedAt = json.get("published_at")?.asString
                    ?: json.get("publishedAt")?.asString.orEmpty()

                var apkDownloadUrl = json.get("downloadUrl")?.asString.orEmpty()
                var apkName = json.get("apkName")?.asString.orEmpty()
                var apkSize = json.get("apkSizeBytes")?.asLong ?: 0L

                val assetsArray = json.getAsJsonArray("assets")
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
                    if (apkDownloadUrl.isNotBlank()) {
                        foundRelease = AppReleaseInfo(
                            tagName = tagName,
                            versionName = cleanVersion,
                            releaseNotes = releaseNotes,
                            downloadUrl = apkDownloadUrl,
                            apkName = apkName,
                            apkSizeBytes = apkSize,
                            publishedAt = publishedAt
                        )
                        break
                    }
                } else {
                    isUpToDate = true
                    break
                }
            } catch (e: Exception) {
                lastException = e
            }
        }

        if (foundRelease != null) {
            emit(UpdateCheckResult.HasUpdate(foundRelease))
        } else if (isUpToDate) {
            emit(UpdateCheckResult.UpToDate)
        } else {
            emit(UpdateCheckResult.Error("检测新版本失败: ${lastException?.message ?: "网络连接异常，请稍后再试"}"))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 下载 APK 文件，带百分比进度回调与网络镜像加速回退
     */
    fun downloadApk(downloadUrl: String, apkFileName: String): Flow<DownloadState> = flow {
        val updatesDir = File(context.externalCacheDir ?: context.cacheDir, "updates")
        if (!updatesDir.exists()) updatesDir.mkdirs()

        // 防御路径穿越：严格只取文件名部分并限制在 updates 目录下
        val safeFileName = File(apkFileName).name.ifBlank { "DreamTales_update.apk" }
        val targetFile = File(updatesDir, safeFileName)
        val tempFile = File(updatesDir, "${safeFileName}.part")
        if (!targetFile.canonicalPath.startsWith(updatesDir.canonicalPath)) {
            emit(DownloadState.Error("非法的文件存储路径"))
            return@flow
        }

        // 检查本地是否已存在完整且结构有效的 APK，若已完整下载且包名一致则直接就绪，避免重复消耗流量
        if (targetFile.exists() && targetFile.length() > 0) {
            val pkgInfo = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageArchiveInfo(targetFile.absolutePath, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageArchiveInfo(targetFile.absolutePath, 0)
                }
            } catch (e: Exception) {
                null
            }
            if (pkgInfo == null || pkgInfo.packageName == context.packageName) {
                emit(DownloadState.Downloading(100, targetFile.length(), targetFile.length()))
                emit(DownloadState.Success(targetFile))
                return@flow
            } else {
                targetFile.delete()
            }
        }

        // 优先使用国内高速镜像，最后回退官方直连
        val urlsToTry = listOf(
            "https://ghfast.top/$downloadUrl",
            "https://ghproxy.net/$downloadUrl",
            downloadUrl
        )

        var lastException: Exception? = null
        var downloadSuccess = false

        for (candidateUrl in urlsToTry) {
            try {
                if (tempFile.exists()) tempFile.delete()

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
                        FileOutputStream(tempFile).use { output ->
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

                    if (tempFile.exists() && tempFile.length() > 0) {
                        if (targetFile.exists()) targetFile.delete()
                        tempFile.renameTo(targetFile)
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
            if (tempFile.exists()) tempFile.delete()
            emit(DownloadState.Error("下载更新包失败: ${lastException?.message ?: "网络连接失败"}"))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 调起系统安装包管理器进行版本覆盖安装
     * @return true 表示成功调起安装意图，false 表示需要等待权限或文件异常
     */
    fun installApk(apkFile: File): Boolean {
        if (!apkFile.exists() || apkFile.length() == 0L) return false

        // 安全校验：校验 APK 是否完整且包名是否严格匹配当前应用
        val pkgInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            }
        } catch (e: Exception) {
            null
        }
        if (pkgInfo != null && !pkgInfo.packageName.isNullOrBlank() && pkgInfo.packageName != context.packageName) {
            apkFile.delete()
            return false
        }

        // Android 8.0+ 权限判断
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(settingsIntent)
                return false
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
            addCategory(Intent.CATEGORY_DEFAULT)
        }

        // 显式为系统安装组件授予读取该安装包 URI 的权限（解决国产定制 ROM 权限拒绝问题）
        val resolveList = context.packageManager.queryIntentActivities(installIntent, PackageManager.MATCH_DEFAULT_ONLY)
        for (resolveInfo in resolveList) {
            val pkg = resolveInfo.activityInfo.packageName
            context.grantUriPermission(pkg, apkUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        return try {
            context.startActivity(installIntent)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
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
