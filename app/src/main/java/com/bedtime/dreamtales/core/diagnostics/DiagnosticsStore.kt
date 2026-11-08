package com.bedtime.dreamtales.core.diagnostics

import android.app.ActivityManager
import android.app.Application
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.content.FileProvider
import com.bedtime.dreamtales.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.system.exitProcess

/**
 * 轻量崩溃/ANR 诊断日志：
 * - 崩溃时同步写入私有 filesDir/diagnostics
 * - 启动时读取系统记录的应用退出原因（Android 11+，包含 ANR / native crash）
 * - 用户可在「关于应用」中一键导出并分享，不自动上传
 *
 * 日志不包含故事正文、宝宝档案或 API Key。
 */
object DiagnosticsStore {

    private const val TAG = "DiagnosticsStore"
    internal const val PREFS = "dreamtales_diagnostics"
    internal const val KEY_LAST_EXIT_TS = "last_exit_ts"
    private const val MAX_LOG_FILES = 12

    private val secretPattern = Regex(
        "(?i)(sk-[A-Za-z0-9_-]{8,}|bearer\\s+[A-Za-z0-9._~+/=-]{8,}|api[_-]?key\\s*[=:]\\s*[^\\s,;]+)"
    )

    fun install(application: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrash(application, thread, throwable) }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    fun collectExitInfo(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching { DiagnosticsExitInfoApi30.collect(context) }
            .onFailure { Log.w(TAG, "读取系统退出记录失败", it) }
    }

    fun exportReport(context: Context): File {
        val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "YueMian-diagnostics-${System.currentTimeMillis()}.txt")
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(buildDeviceInfo(context))
            writer.write("\n\n===== 诊断记录 =====\n")
            val logs = File(context.filesDir, "diagnostics")
                .listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
            if (logs.isEmpty()) {
                writer.write("暂无崩溃/ANR 记录。\n")
            } else {
                logs.forEach { log ->
                    writer.write("\n--- ${log.name} ---\n")
                    writer.write(
                        sanitize(runCatching { log.readText() }.getOrDefault("读取失败"))
                    )
                    writer.write("\n")
                }
            }
        }
        return file
    }

    fun shareReport(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "月眠 诊断日志")
            putExtra(
                Intent.EXTRA_TEXT,
                "月眠 诊断日志，可附加到 GitHub Issue：https://github.com/startips/DreamTales/issues"
            )
            clipData = ClipData.newRawUri("diagnostics", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, "分享诊断日志").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    private fun writeCrash(context: Context, thread: Thread, throwable: Throwable) {
        val stackTrace = StringWriter().also { writer ->
            PrintWriter(writer).use { throwable.printStackTrace(it) }
        }.toString()
        writeLog(
            context,
            "crash",
            buildString {
                appendLine("崩溃时间: ${formatTime(System.currentTimeMillis())}")
                appendLine("线程: ${thread.name}")
                appendLine()
                appendLine(buildDeviceInfo(context))
                appendLine()
                appendLine("--- 堆栈 ---")
                appendLine(stackTrace)
            }
        )
    }

    internal fun writeLog(context: Context, prefix: String, content: String) {
        runCatching {
            val dir = File(context.filesDir, "diagnostics").apply { mkdirs() }
            val file = File(dir, "$prefix-${System.currentTimeMillis()}.txt")
            FileOutputStream(file).use { output ->
                output.write(sanitize(content).toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            dir.listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_LOG_FILES)
                ?.forEach { it.delete() }
        }.onFailure { Log.w(TAG, "写入诊断日志失败", it) }
    }

    private fun buildDeviceInfo(context: Context): String {
        val metrics = context.resources.displayMetrics
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return buildString {
            appendLine("月眠 诊断日志")
            appendLine("生成时间: ${formatTime(System.currentTimeMillis())}")
            appendLine("应用版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("构建类型: ${if (BuildConfig.DEBUG) "debug" else "release"}")
            appendLine("包名: ${context.packageName}")
            appendLine("厂商: ${Build.MANUFACTURER}")
            appendLine("品牌: ${Build.BRAND}")
            appendLine("型号: ${Build.MODEL}")
            appendLine("设备: ${Build.DEVICE}")
            appendLine("产品: ${Build.PRODUCT}")
            appendLine("硬件: ${Build.HARDWARE}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("安全补丁: ${Build.VERSION.SECURITY_PATCH}")
            appendLine("ROM: ${Build.DISPLAY}")
            appendLine("增量版本: ${Build.VERSION.INCREMENTAL}")
            appendLine("指纹: ${Build.FINGERPRINT}")
            appendLine("屏幕: ${metrics.widthPixels}x${metrics.heightPixels} @ ${metrics.density} (${metrics.densityDpi}dpi)")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("低内存设备: ${activityManager?.isLowRamDevice}")
            appendLine("最大堆: ${Runtime.getRuntime().maxMemory() / 1024 / 1024} MB")
            appendLine("Locale: ${Locale.getDefault()}")
            appendLine("时区: ${TimeZone.getDefault().id}")
            appendLine("说明: 本日志不包含故事正文、宝宝档案或 API Key。")
        }
    }

    internal fun sanitize(text: String): String {
        return secretPattern.replace(text) { match ->
            when {
                match.value.startsWith("sk-", ignoreCase = true) -> "sk-***"
                match.value.startsWith("bearer", ignoreCase = true) -> "Bearer ***"
                else -> "api_key=***"
            }
        }
    }

    internal fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestamp))
    }
}
