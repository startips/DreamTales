package com.bedtime.dreamtales

import android.app.Application
import com.bedtime.dreamtales.data.repository.StoryRepository
import com.bedtime.dreamtales.data.repository.VoiceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DreamTalesApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 预热 Room 数据库、补齐经典童话库、预置音色及后台预加载
        CoroutineScope(Dispatchers.IO).launch {
            val storyRepo = StoryRepository(this@DreamTalesApp)
            storyRepo.ensureClassicStoriesPopulated()
            val voiceRepo = VoiceRepository(this@DreamTalesApp)
            voiceRepo.insertPresetVoiceIfEmpty()
            val defaultVoice = voiceRepo.getDefaultVoiceProfileSync()
            if (defaultVoice != null) {
                storyRepo.preCacheUpcomingStories(defaultVoice)
            }
        }
    }
}
