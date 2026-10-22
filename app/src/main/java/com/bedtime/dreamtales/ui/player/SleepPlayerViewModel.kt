package com.bedtime.dreamtales.ui.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bedtime.dreamtales.data.repository.StoryImageRepository
import com.bedtime.dreamtales.service.StoryPlaybackService
import com.bedtime.dreamtales.service.StoryPlaybackState
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class SleepPlayerViewModel(application: Application) : AndroidViewModel(application) {

    val playbackState: StateFlow<StoryPlaybackState> = StoryPlaybackService.playbackState
    private var playbackService: StoryPlaybackService? = null
    private val storyImageRepository by lazy { StoryImageRepository(getApplication()) }

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
}
