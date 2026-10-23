package com.bedtime.dreamtales.core.utils

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.bedtime.dreamtales.core.constants.AppConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

sealed class RecordingStatus {
    object Idle : RecordingStatus()
    data class Recording(val durationSeconds: Int, val currentDb: Float) : RecordingStatus()
    data class Completed(val wavFile: File, val durationSeconds: Int, val averageDb: Float) : RecordingStatus()
    data class Failed(val reason: String) : RecordingStatus()
}

class AudioRecorderHelper(private val context: Context) {

    private val _status = MutableStateFlow<RecordingStatus>(RecordingStatus.Idle)
    val status: StateFlow<RecordingStatus> = _status.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var outputFile: File? = null

    private val scope = CoroutineScope(Dispatchers.IO)

    @SuppressLint("MissingPermission")
    fun startRecording() {
        val sampleRate = AppConstants.AUDIO_SAMPLE_RATE
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = maxOf(minBufferSize, sampleRate * 2)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                _status.value = RecordingStatus.Failed("麦克风初始化失败，请检查麦克风权限或是否被占用")
                return
            }

            val recordsDir = File(context.filesDir, "records")
            if (!recordsDir.exists()) recordsDir.mkdirs()
            val wavFile = File(recordsDir, "voice_sample_${System.currentTimeMillis()}.wav")
            outputFile = wavFile

            audioRecord?.startRecording()

            recordingJob = scope.launch {
                val pcmBuffer = ShortArray(1024)
                val fos = FileOutputStream(wavFile)
                // 预留 44 字节的 WAV 头
                writeWavHeader(fos, 0, 0, sampleRate, 1, 16)

                var totalBytesWritten = 0L
                var totalRmsSum = 0.0
                var rmsSampleCount = 0L
                val startTimeMillis = System.currentTimeMillis()

                try {
                    while (isActive && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        val shortsRead = audioRecord?.read(pcmBuffer, 0, pcmBuffer.size) ?: 0
                        if (shortsRead > 0) {
                            // 写入文件（小端序 Short 转 Byte）
                            val byteBuffer = ByteBuffer.allocate(shortsRead * 2).order(ByteOrder.LITTLE_ENDIAN)
                            for (i in 0 until shortsRead) {
                                byteBuffer.putShort(pcmBuffer[i])
                            }
                            fos.write(byteBuffer.array(), 0, shortsRead * 2)
                            totalBytesWritten += shortsRead * 2

                            // 计算当前块的 RMS 分贝
                            var sumSquare = 0.0
                            for (i in 0 until shortsRead) {
                                val sample = pcmBuffer[i].toDouble()
                                sumSquare += sample * sample
                            }
                            val rms = sqrt(sumSquare / shortsRead)
                            val db = calculateDecibels(rms)

                            totalRmsSum += db
                            rmsSampleCount++

                            val elapsedSec = ((System.currentTimeMillis() - startTimeMillis) / 1000).toInt()

                            _status.value = RecordingStatus.Recording(
                                durationSeconds = elapsedSec,
                                currentDb = db.toFloat()
                            )

                            // 达到最大 30 秒自动停止
                            if (elapsedSec >= AppConstants.MAX_RECORDING_DURATION_SEC) {
                                break
                            }
                        }
                    }
                } finally {
                    try {
                        fos.close()
                    } catch (e: Exception) {
                        // ignore
                    }
                    // 修正 WAV 头的实际数据长度
                    updateWavHeader(wavFile, totalBytesWritten, sampleRate, 1, 16)

                    // 录音循环退出后，在后台协程安全释放 AudioRecord，杜绝跨线程并发 release 导致的异常
                    try {
                        audioRecord?.stop()
                        audioRecord?.release()
                        audioRecord = null
                    } catch (e: Exception) {
                        // ignore
                    }
                }

                val finalDuration = ((System.currentTimeMillis() - startTimeMillis) / 1000).toInt()
                val averageDb = if (rmsSampleCount > 0) (totalRmsSum / rmsSampleCount).toFloat() else 0f

                // 校验规范硬门槛
                if (finalDuration < AppConstants.MIN_RECORDING_DURATION_SEC) {
                    wavFile.delete()
                    _status.value = RecordingStatus.Failed("录音时长不足 ${AppConstants.MIN_RECORDING_DURATION_SEC} 秒，请长按录满后再提交")
                } else if (averageDb < AppConstants.MIN_DB_RMS_THRESHOLD) {
                    wavFile.delete()
                    _status.value = RecordingStatus.Failed("声音太轻（平均 ${String.format("%.1f", averageDb)} dB），请靠近麦克风重新录制")
                } else {
                    _status.value = RecordingStatus.Completed(
                        wavFile = wavFile,
                        durationSeconds = finalDuration,
                        averageDb = averageDb
                    )
                }
            }
        } catch (e: Exception) {
            _status.value = RecordingStatus.Failed("录音异常: ${e.localizedMessage ?: "未知错误"}")
        }
    }

    fun stopRecording() {
        try {
            // 仅调用 stop() 打断阻塞读，由录音协程的 finally 统一执行 release，防止 native 并发崩溃
            audioRecord?.stop()
        } catch (e: Exception) {
            // 忽略重复调用
        }
    }

    fun reset() {
        stopRecording()
        recordingJob?.cancel()
        try {
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            // ignore
        }
        _status.value = RecordingStatus.Idle
    }

    private fun calculateDecibels(rms: Double): Double {
        if (rms <= 1.0) return 0.0
        // 满量程 32767，将其映射至 0 ~ 90 dB 标称范围
        val db = 20.0 * log10(rms / 32767.0) + 90.0
        return db.coerceIn(0.0, 100.0)
    }

    private fun writeWavHeader(
        out: FileOutputStream,
        totalAudioLen: Long,
        totalDataLen: Long,
        longSampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        val byteRate = (longSampleRate * channels * bitsPerSample / 8).toLong()
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = (totalDataLen shr 8 and 0xff).toByte()
        header[6] = (totalDataLen shr 16 and 0xff).toByte()
        header[7] = (totalDataLen shr 24 and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // PCM
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (longSampleRate and 0xff).toByte()
        header[25] = (longSampleRate shr 8 and 0xff).toByte()
        header[26] = (longSampleRate shr 16 and 0xff).toByte()
        header[27] = (longSampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte()
        header[31] = (byteRate shr 24 and 0xff).toByte()
        header[32] = (channels * bitsPerSample / 8).toByte() // block align
        header[33] = 0
        header[34] = bitsPerSample.toByte()
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = (totalAudioLen shr 8 and 0xff).toByte()
        header[42] = (totalAudioLen shr 16 and 0xff).toByte()
        header[43] = (totalAudioLen shr 24 and 0xff).toByte()
        out.write(header, 0, 44)
    }

    private fun updateWavHeader(
        file: File,
        totalAudioLen: Long,
        longSampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        val totalDataLen = totalAudioLen + 36
        val byteRate = (longSampleRate * channels * bitsPerSample / 8).toLong()
        val raf = RandomAccessFile(file, "rw")
        try {
            raf.seek(4)
            raf.write(
                byteArrayOf(
                    (totalDataLen and 0xff).toByte(),
                    (totalDataLen shr 8 and 0xff).toByte(),
                    (totalDataLen shr 16 and 0xff).toByte(),
                    (totalDataLen shr 24 and 0xff).toByte()
                )
            )
            raf.seek(40)
            raf.write(
                byteArrayOf(
                    (totalAudioLen and 0xff).toByte(),
                    (totalAudioLen shr 8 and 0xff).toByte(),
                    (totalAudioLen shr 16 and 0xff).toByte(),
                    (totalAudioLen shr 24 and 0xff).toByte()
                )
            )
        } finally {
            raf.close()
        }
    }
}
