package com.bedtime.dreamtales.ui.player

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bedtime.dreamtales.core.audio.AmbientMix
import com.bedtime.dreamtales.core.audio.AmbientPreset
import com.bedtime.dreamtales.core.constants.AppConfig
import com.bedtime.dreamtales.data.local.entity.StoryPage
import com.bedtime.dreamtales.data.repository.StoryImageRepository
import com.bedtime.dreamtales.data.repository.StoryRepository
import com.bedtime.dreamtales.service.StoryPlaybackService
import com.bedtime.dreamtales.service.StoryPlaybackState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

sealed class StoryPagesUiState {
    object Idle : StoryPagesUiState()
    data class Generating(val progress: Int) : StoryPagesUiState()
    data class Success(val pageCount: Int) : StoryPagesUiState()
    data class Error(val message: String) : StoryPagesUiState()
}

class SleepPlayerViewModel(application: Application) : AndroidViewModel(application) {

    val playbackState: StateFlow<StoryPlaybackState> = StoryPlaybackService.playbackState
    private var playbackService: StoryPlaybackService? = null
    private val storyImageRepository by lazy { StoryImageRepository(getApplication()) }
    private val storyRepository by lazy { StoryRepository(getApplication()) }
    private val appConfig by lazy { AppConfig.getInstance(getApplication()) }

    @OptIn(ExperimentalCoroutinesApi::class)
    val storyPages: StateFlow<List<StoryPage>> = playbackState
        .map { it.storyId }
        .distinctUntilChanged()
        .flatMapLatest { storyId ->
            if (storyId == 0L) flowOf(emptyList()) else storyRepository.getStoryPagesFlow(storyId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _storyPagesState = MutableStateFlow<StoryPagesUiState>(StoryPagesUiState.Idle)
    val storyPagesState: StateFlow<StoryPagesUiState> = _storyPagesState.asStateFlow()

    init {
        viewModelScope.launch {
            playbackState
                .map { it.storyId }
                .distinctUntilChanged()
                .collect { _storyPagesState.value = StoryPagesUiState.Idle }
        }
    }

    fun attachService(service: StoryPlaybackService) {
        this.playbackService = service
    }

    fun togglePlayPause() {
        playbackService?.togglePlayPause()
    }

    fun startTimer(minutes: Int) {
        playbackService?.startSleepTimer(minutes)
    }

    fun cancelTimer() {
        playbackService?.cancelSleepTimer()
    }

    fun seekTo(positionMs: Long) {
        playbackService?.seekTo(positionMs)
    }

    fun cyclePlaybackMode() {
        playbackService?.cyclePlaybackMode()
    }

    fun setPlaybackMode(mode: com.bedtime.dreamtales.service.PlaybackMode) {
        playbackService?.setPlaybackMode(mode)
    }

    fun playNext() {
        playbackService?.playNext()
    }

    fun playPrevious() {
        playbackService?.playPrevious()
    }

    fun setAmbientSound(ambient: com.bedtime.dreamtales.service.AmbientSound) {
        playbackService?.setAmbientSound(ambient)
    }

    fun setAmbientVolume(volume: Float) {
        playbackService?.setAmbientVolume(volume)
    }

    fun setAmbientTrack(trackId: String, volume: Float) {
        playbackService?.setAmbientTrack(trackId, volume)
    }

    fun setAmbientMix(mix: Map<String, Float>) {
        playbackService?.setAmbientMix(mix)
    }

    fun getAmbientPresets(): List<AmbientPreset> = appConfig.getAmbientPresets()

    fun saveAmbientPreset(name: String, mix: Map<String, Float>): Boolean {
        val presetName = AmbientMix.cleanPresetName(name)
        val safeMix = AmbientMix.sanitize(mix)
        if (presetName.isBlank() || safeMix.isEmpty()) return false
        val presets = appConfig.getAmbientPresets().toMutableList()
        presets.removeAll { it.name == presetName }
        presets.add(0, AmbientPreset(presetName, safeMix))
        appConfig.setAmbientPresets(presets.take(AmbientMix.MAX_PRESETS))
        return true
    }

    fun generateStoryPages(pageCount: Int = 3) {
        val state = playbackState.value
        if (state.storyId == 0L) return
        if (_storyPagesState.value is StoryPagesUiState.Generating) return

        viewModelScope.launch {
            _storyPagesState.value = StoryPagesUiState.Generating(0)
            try {
                withTimeout(240_000L) {
                    val pages = storyImageRepository.generateAndSaveStoryPages(
                        storyId = state.storyId,
                        storyTitle = state.storyTitle,
                        storyContent = state.storyContent,
                        pageCount = pageCount,
                        onProgress = { progress ->
                            _storyPagesState.value = StoryPagesUiState.Generating(progress)
                        }
                    )
                    _storyPagesState.value = StoryPagesUiState.Success(pages.size)
                }
            } catch (e: TimeoutCancellationException) {
                _storyPagesState.value = StoryPagesUiState.Error("连环画绘制超时，请检查网络后重试")
            } catch (e: Exception) {
                _storyPagesState.value = StoryPagesUiState.Error(e.message ?: "连环画绘制失败")
            }
        }
    }

    fun resetStoryPagesState() {
        _storyPagesState.value = StoryPagesUiState.Idle
    }

    fun generateCoverIllustration(onSuccess: (() -> Unit)? = null, onError: ((String) -> Unit)? = null) {
        val state = playbackState.value
        if (state.storyId == 0L || state.isGeneratingCover) return

        viewModelScope.launch {
            playbackService?.setGeneratingCover(true)
            try {
                // 45 秒最长保护，杜绝任何未知网络卡顿导致界面永久转圈
                withTimeout(45_000L) {
                    val path = storyImageRepository.generateAndSaveIllustration(
                        storyId = state.storyId,
                        storyTitle = state.storyTitle,
                        storyContent = state.storyContent
                    )
                    playbackService?.updateCoverImagePath(path)
                    onSuccess?.invoke()
                }
            } catch (e: TimeoutCancellationException) {
                onError?.invoke("绘本生图连接超时，请检查网络或稍后再试")
            } catch (e: Exception) {
                onError?.invoke(e.message ?: "生图失败")
            } finally {
                playbackService?.setGeneratingCover(false)
            }
        }
    }

    /**
     * 将相册选择的照片持久化为当前故事封面
     */
    fun setCoverFromUri(uri: Uri, onSuccess: (() -> Unit)? = null, onError: ((String) -> Unit)? = null) {
        val state = playbackState.value
        if (state.storyId == 0L) return

        viewModelScope.launch {
            try {
                val path = storyImageRepository.saveImageFromUri(state.storyId, uri)
                playbackService?.updateCoverImagePath(path)
                onSuccess?.invoke()
            } catch (e: Exception) {
                onError?.invoke(e.message ?: "保存相册插画失败")
            }
        }
    }

    /**
     * 清除当前故事的自定义封面
     */
    fun clearCover(onSuccess: (() -> Unit)? = null) {
        val state = playbackState.value
        if (state.storyId == 0L) return

        viewModelScope.launch {
            try {
                storyImageRepository.clearStoryCover(state.storyId)
                playbackService?.updateCoverImagePath(null)
                onSuccess?.invoke()
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}
