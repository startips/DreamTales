package com.bedtime.dreamtales.core.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Android 11+（API 30）专属：读取系统记录的应用退出原因。
 *
 * 单独放在一个类里，避免 Android 10 及以下在加载 DiagnosticsStore 时解析
 * ApplicationExitInfo 类型导致类加载异常。
 */
@RequiresApi(Build.VERSION_CODES.R)
internal object DiagnosticsExitInfoApi30 {

    private const val MAX_TRACE_BYTES = 64 * 1024

    fun collect(context: Context) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return
        val exits = activityManager.getHistoricalProcessExitReasons(
            context.packageName,
            0,
            20
        )
        if (exits.isEmpty()) return

        val prefs = context.getSharedPreferences(DiagnosticsStore.PREFS, Context.MODE_PRIVATE)
        val lastTimestamp = prefs.getLong(DiagnosticsStore.KEY_LAST_EXIT_TS, 0L)
        val fresh = exits
            .filter { it.timestamp > lastTimestamp }
            .sortedBy { it.timestamp }
            .takeLast(5)
        if (fresh.isEmpty()) return

        val report = buildString {
            fresh.forEach { info ->
                appendLine("时间: ${DiagnosticsStore.formatTime(info.timestamp)}")
                appendLine("原因: ${reasonLabel(info.reason)} (${info.reason})")
                appendLine("进程: ${info.processName}")
                appendLine("重要度: ${info.importance}")
                appendLine("状态: ${info.status}")
                info.description?.let { appendLine("描述: $it") }
                readTrace(info)?.takeIf { it.isNotBlank() }?.let { trace ->
                    appendLine("--- 系统 trace（截断） ---")
                    appendLine(trace)
                }
                appendLine()
            }
        }
        DiagnosticsStore.writeLog(context, "system-exit", report)
        prefs.edit().putLong(DiagnosticsStore.KEY_LAST_EXIT_TS, fresh.maxOf { it.timestamp }).apply()
    }

    private fun readTrace(info: ApplicationExitInfo): String? {
        val input = info.traceInputStream ?: return null
        return input.use { readLimited(it, MAX_TRACE_BYTES) }
    }

    private fun readLimited(input: InputStream, maxBytes: Int): String {
        val buffer = ByteArray(8 * 1024)
        val output = ByteArrayOutputStream()
        var total = 0
        while (total < maxBytes) {
            val read = input.read(buffer, 0, minOf(buffer.size, maxBytes - total))
            if (read <= 0) break
            output.write(buffer, 0, read)
            total += read
        }
        return output.toByteArray().toString(Charsets.UTF_8)
    }

    private fun reasonLabel(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "REASON_$reason"
    }
}
