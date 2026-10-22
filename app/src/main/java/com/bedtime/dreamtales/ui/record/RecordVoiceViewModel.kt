package com.bedtime.dreamtales.ui.record

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bedtime.dreamtales.core.utils.AudioRecorderHelper
import com.bedtime.dreamtales.core.utils.AudioSamplePlayer
import com.bedtime.dreamtales.core.utils.RecordingStatus
import com.bedtime.dreamtales.data.local.entity.VoiceProfile
import com.bedtime.dreamtales.data.repository.VoiceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed class CloneUiState {
    object Idle : CloneUiState()
    object Cloning : CloneUiState()
    data class Success(val profile: VoiceProfile) : CloneUiState()
    data class Error(val message: String) : CloneUiState()
}

class RecordVoiceViewModel(application: Application) : AndroidViewModel(application) {

    private val recorderHelper = AudioRecorderHelper(application)
    private val samplePlayer = AudioSamplePlayer()
    private val voiceRepository = VoiceRepository(application)

    val recordingStatus: StateFlow<RecordingStatus> = recorderHelper.status
    val isPlayingSample: StateFlow<Boolean> = samplePlayer.isPlaying

    private val _cloneState = MutableStateFlow<CloneUiState>(CloneUiState.Idle)
    val cloneState: StateFlow<CloneUiState> = _cloneState.asStateFlow()

    fun startRecording() {
        samplePlayer.stop()
        _cloneState.value = CloneUiState.Idle
        recorderHelper.startRecording()
    }

    fun stopRecording() {
        recorderHelper.stopRecording()
    }

    fun playRecordedSample(file: File) {
        samplePlayer.play(file)
    }

    fun stopSamplePlayback() {
        samplePlayer.stop()
    }

    fun uploadVoice(name: String, wavFile: File) {
        viewModelScope.launch {
            _cloneState.value = CloneUiState.Cloning
            try {
                val profile = voiceRepository.cloneAndSaveVoice(name, wavFile)
                _cloneState.value = CloneUiState.Success(profile)
            } catch (e: IllegalStateException) {
                _cloneState.value = CloneUiState.Error(e.message ?: "未配置有效的 API Key")
            } catch (e: Exception) {
                _cloneState.value = CloneUiState.Error("音色克隆失败: ${e.message ?: "网络或服务异常"}")
            }
        }
    }

    fun reset() {
        samplePlayer.stop()
        recorderHelper.reset()
        _cloneState.value = CloneUiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        samplePlayer.stop()
        recorderHelper.stopRecording()
    }
}
