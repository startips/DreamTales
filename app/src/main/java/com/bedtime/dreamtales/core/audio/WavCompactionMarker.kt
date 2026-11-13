package com.bedtime.dreamtales.core.audio

import java.io.File

/**
 * WAV 静音压缩的「只加工一次」标记。
 * 与 WAV 文件同目录保存一个 `.compact` 伴生文件，避免每次播放都整文件扫描。
 */
object WavCompactionMarker {

    private fun markerOf(file: File): File = File(file.parentFile, file.name + ".compact")

    fun isCompacted(file: File): Boolean {
        if (!file.exists()) return false
        val marker = markerOf(file)
        if (!marker.exists()) return false
        return marker.lastModified() >= file.lastModified()
    }

    /**
     * 若尚未压缩则执行一次压缩，并写入标记；返回是否真正改写了文件。
     */
    fun compactOnce(file: File): Boolean {
        if (!file.exists() || file.length() < 44) return false
        if (isCompacted(file)) return false
        val changed = WavSilenceCompactor.compactInPlace(file)
        runCatching {
            val marker = markerOf(file)
            marker.writeText("1")
            // 标记时间必须晚于压缩后的文件时间，避免下次误判为未压缩
            marker.setLastModified(maxOf(System.currentTimeMillis(), file.lastModified() + 1))
        }
        return changed
    }

    fun clearMarker(file: File) {
        runCatching { markerOf(file).delete() }
    }
}