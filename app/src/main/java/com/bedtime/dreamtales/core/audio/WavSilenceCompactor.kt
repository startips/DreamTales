package com.bedtime.dreamtales.core.audio

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.abs

/**
 * 处理 TTS 返回的 PCM WAV 中异常的超长静音。
 *
 * 部分语音合成结果会在正文中间/末尾插入几十秒甚至几分钟的数字静音，
 * 导致播放器显示 5 分钟时长，但实际朗读只有两分钟左右，剩余时间只剩背景音。
 *
 * 这里只处理 PCM 16-bit WAV：
 * - 超过 3 秒的静音段压缩为 0.6 秒呼吸间隔
 * - 首尾各保留 0.2 秒自然留白
 * - 3 秒以内的正常停顿保持不变
 */
object WavSilenceCompactor {

    private const val PCM_FORMAT = 1
    private const val BITS_PER_SAMPLE = 16
    private const val SILENCE_THRESHOLD = 200
    private const val MAX_GAP_MS = 3_000
    private const val GAP_KEEP_MS = 600
    private const val EDGE_KEEP_MS = 200
    private const val MIN_SAVING_MS = 100

    fun compactInPlace(file: File): Boolean {
        if (!file.exists() || file.length() < 44) return false

        return runCatching {
            val bytes = file.readBytes()
            val info = parseWav(bytes) ?: return false
            val rate = info.sampleRate
            val frameSize = info.frameSize
            val frames = info.dataSize / frameSize
            if (frames <= 0) return false

            val maxGapFrames = rate * MAX_GAP_MS / 1000
            val gapKeepFrames = rate * GAP_KEEP_MS / 1000
            val edgeKeepFrames = rate * EDGE_KEEP_MS / 1000

            // 先按“连续有声片段”分组；只有超过 3 秒的静音才切开
            val segments = mutableListOf<IntRange>()
            var segmentStart = -1
            var previousActive = -1
            var firstActive = -1
            var lastActive = -1

            for (frame in 0 until frames) {
                if (isSilent(bytes, info.dataOffset, frame, info.channels, frameSize)) continue
                if (firstActive < 0) firstActive = frame
                lastActive = frame
                if (segmentStart < 0) {
                    segmentStart = frame
                } else if (previousActive >= 0 && frame - previousActive > maxGapFrames) {
                    segments.add(segmentStart..previousActive)
                    segmentStart = frame
                }
                previousActive = frame
            }
            if (firstActive < 0) return false
            if (segmentStart >= 0) segments.add(segmentStart..lastActive)

            val paddedSegments = segments.mapIndexed { index, range ->
                val start = if (index == 0) {
                    (range.first - edgeKeepFrames).coerceAtLeast(0)
                } else {
                    range.first
                }
                val end = if (index == segments.lastIndex) {
                    (range.last + edgeKeepFrames).coerceAtMost(frames - 1)
                } else {
                    range.last
                }
                start..end
            }

            val gapBytes = gapKeepFrames * frameSize
            var newDataSize = 0L
            paddedSegments.forEach { range ->
                newDataSize += (range.last - range.first + 1).toLong() * frameSize
            }
            newDataSize += gapBytes.toLong() * (paddedSegments.size - 1).coerceAtLeast(0)

            val minSavingBytes = rate.toLong() * frameSize * MIN_SAVING_MS / 1000
            if (newDataSize >= info.dataSize - minSavingBytes) return false

            val header = bytes.copyOfRange(0, info.dataOffset)
            writeIntLE(header, 4, (header.size + newDataSize - 8).toInt())
            writeIntLE(header, info.dataOffset - 4, newDataSize.toInt())

            val temp = File(file.parentFile, file.name + ".tmp")
            try {
                FileOutputStream(temp).use { output ->
                    output.write(header)
                    paddedSegments.forEachIndexed { index, range ->
                        if (index > 0) {
                            repeat(gapBytes) { output.write(0) }
                        }
                        val startByte = info.dataOffset + range.first * frameSize
                        val byteCount = (range.last - range.first + 1) * frameSize
                        output.write(bytes, startByte, byteCount)
                    }
                    output.flush()
                    output.fd.sync()
                }
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
                true
            } catch (e: Exception) {
                temp.delete()
                false
            }
        }.getOrDefault(false)
    }

    private fun isSilent(
        bytes: ByteArray,
        dataOffset: Int,
        frame: Int,
        channels: Int,
        frameSize: Int
    ): Boolean {
        val base = dataOffset + frame * frameSize
        for (channel in 0 until channels) {
            val index = base + channel * 2
            if (index + 1 >= bytes.size) return true
            val sample = ((bytes[index].toInt() and 0xFF) or (bytes[index + 1].toInt() shl 8))
                .toShort()
                .toInt()
            if (abs(sample) >= SILENCE_THRESHOLD) return false
        }
        return true
    }

    private fun parseWav(bytes: ByteArray): WavInfo? {
        if (bytes.size < 44) return null
        if (bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) != "RIFF") return null
        if (bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) != "WAVE") return null

        var offset = 12
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var dataOffset = -1
        var dataSize = -1

        while (offset + 8 <= bytes.size) {
            val id = bytes.copyOfRange(offset, offset + 4).toString(Charsets.US_ASCII)
            val size = readIntLE(bytes, offset + 4)
            if (size < 0) return null
            val chunkStart = offset + 8
            if (chunkStart > bytes.size) return null

            when (id) {
                "fmt " -> {
                    if (chunkStart + 16 > bytes.size) return null
                    val audioFormat = readShortLE(bytes, chunkStart)
                    channels = readShortLE(bytes, chunkStart + 2)
                    sampleRate = readIntLE(bytes, chunkStart + 4)
                    bitsPerSample = readShortLE(bytes, chunkStart + 14)
                    if (audioFormat != PCM_FORMAT || bitsPerSample != BITS_PER_SAMPLE) return null
                    if (channels !in 1..2 || sampleRate <= 0) return null
                }
                "data" -> {
                    dataOffset = chunkStart
                    dataSize = minOf(size, bytes.size - chunkStart)
                }
            }

            offset = chunkStart + size + (size and 1)
        }

        if (dataOffset < 0 || dataSize <= 0) return null
        val frameSize = channels * (bitsPerSample / 8)
        if (frameSize <= 0) return null
        return WavInfo(
            dataOffset = dataOffset,
            dataSize = dataSize,
            channels = channels,
            sampleRate = sampleRate,
            frameSize = frameSize
        )
    }

    private fun readIntLE(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return -1
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readShortLE(bytes: ByteArray, offset: Int): Int {
        if (offset + 2 > bytes.size) return -1
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun writeIntLE(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private data class WavInfo(
        val dataOffset: Int,
        val dataSize: Int,
        val channels: Int,
        val sampleRate: Int,
        val frameSize: Int
    )
}
