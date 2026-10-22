package com.bedtime.dreamtales.ui.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.bedtime.dreamtales.service.StoryPlaybackService
import com.bedtime.dreamtales.service.StoryPlaybackState
import kotlinx.coroutines.flow.StateFlow

class SleepPlayerViewModel(application: Application) : AndroidViewModel(application) {

    val playbackState: StateFlow<StoryPlaybackState> = StoryPlaybackService.playbackState
    private var playbackService: StoryPlaybackService? = null

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
}
