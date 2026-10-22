package com.bedtime.dreamtales.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bedtime.dreamtales.core.constants.AppConfig
import com.bedtime.dreamtales.data.api.StoryCandidate
import com.bedtime.dreamtales.data.local.entity.Story
import com.bedtime.dreamtales.data.local.entity.VoiceProfile
import com.bedtime.dreamtales.data.repository.StoryRepository
import com.bedtime.dreamtales.data.repository.VoiceRepository
import com.bedtime.dreamtales.service.StoryPlaybackService
import com.bedtime.dreamtales.service.StoryPlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class SynthesisUiState {
    object Idle : SynthesisUiState()
    data class Synthesizing(val storyId: Long, val storyTitle: String, val progress: Int) : SynthesisUiState()
    data class ReadyToPlay(val story: Story, val localFilePath: String) : SynthesisUiState()
    data class Error(val message: String) : SynthesisUiState()
}

sealed class StoryGenerationUiState {
    object Idle : StoryGenerationUiState()
    data class SearchingCandidates(val query: String) : StoryGenerationUiState()
    data class CandidatesReady(val query: String, val candidates: List<StoryCandidate>) : StoryGenerationUiState()
    data class GeneratingStory(val title: String) : StoryGenerationUiState()
    data class Success(val story: Story) : StoryGenerationUiState()
    data class Error(val message: String) : StoryGenerationUiState()
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val storyRepository = StoryRepository(application)
    private val voiceRepository = VoiceRepository(application)
    private val appConfig = AppConfig.getInstance(application)

    val stories: StateFlow<List<Story>> = storyRepository.getAllStories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val defaultVoice: StateFlow<VoiceProfile?> = voiceRepository.getDefaultVoiceProfile()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val allVoices: StateFlow<List<VoiceProfile>> = voiceRepository.getAllVoiceProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val completedCaches: StateFlow<List<com.bedtime.dreamtales.data.local.entity.StoryAudioCache>> = storyRepository.getAllCompleteCaches()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playbackState: StateFlow<StoryPlaybackState> = StoryPlaybackService.playbackState

    private val _themeMode = MutableStateFlow(appConfig.getThemeMode())
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    init {
        viewModelScope.launch {
            storyRepository.ensureClassicStoriesPopulated()
            voiceRepository.insertPresetVoiceIfEmpty()
            val voice = voiceRepository.getDefaultVoiceProfileSync()
            if (voice != null) {
                storyRepository.preCacheUpcomingStories(voice)
            }
        }
    }

    private val _synthesisState = MutableStateFlow<SynthesisUiState>(SynthesisUiState.Idle)
    val synthesisState: StateFlow<SynthesisUiState> = _synthesisState.asStateFlow()

    private val _generationState = MutableStateFlow<StoryGenerationUiState>(StoryGenerationUiState.Idle)
    val generationState: StateFlow<StoryGenerationUiState> = _generationState.asStateFlow()

    fun setThemeMode(mode: String) {
        appConfig.setThemeMode(mode)
        _themeMode.value = mode
    }

    fun toggleFavorite(story: Story) {
        viewModelScope.launch {
            storyRepository.setFavorite(story.id, !story.isFavorite)
        }
    }

    fun selectVoiceProfile(profileId: Long) {
        viewModelScope.launch {
            voiceRepository.setDefaultVoiceProfile(profileId)
        }
    }

    fun onStoryClicked(story: Story, onNavigateToPlayer: () -> Unit) {
        val currentPlayback = StoryPlaybackService.playbackState.value
        // 如果当前已在播放/就绪该故事，直接无缝进入沉浸播放器
        if (currentPlayback.storyId == story.id && currentPlayback.storyTitle.isNotBlank()) {
            onNavigateToPlayer()
            return
        }

        // 防抖保护：若当前已有故事在合成中，避免快速重复并发
        if (_synthesisState.value is SynthesisUiState.Synthesizing) {
            return
        }

        val currentVoice = defaultVoice.value
        if (currentVoice == null) {
            _synthesisState.value = SynthesisUiState.Error("请先选择或录制一个专属伴读音色")
            return
        }

        viewModelScope.launch {
            _synthesisState.value = SynthesisUiState.Synthesizing(story.id, story.title, 0)
            try {
                val filePath = storyRepository.getOrSynthesizeStoryAudio(
                    story = story,
                    voiceProfile = currentVoice,
                    onProgress = { progress ->
                        _synthesisState.value = SynthesisUiState.Synthesizing(story.id, story.title, progress)
                    }
                )
                // 记录播放频次
                storyRepository.recordStoryPlayed(story.id)
                _synthesisState.value = SynthesisUiState.ReadyToPlay(story, filePath)
            } catch (e: IllegalStateException) {
                _synthesisState.value = SynthesisUiState.Error(e.message ?: "未配置 API Key")
            } catch (e: Exception) {
                _synthesisState.value = SynthesisUiState.Error("合成失败: ${e.message ?: "网络或服务异常"}")
            }
        }
    }

    /**
     * 搜索故事：先检索推荐 3 个候选故事卡片供二次点选
     */
    fun searchStoryCandidates(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            _generationState.value = StoryGenerationUiState.SearchingCandidates(query)
            try {
                val candidates = storyRepository.searchStoryCandidatesWithCpa(query.trim())
                _generationState.value = StoryGenerationUiState.CandidatesReady(query, candidates)
            } catch (e: Exception) {
                _generationState.value = StoryGenerationUiState.Error("检索候选故事失败: ${e.message ?: "网络异常"}")
            }
        }
    }

    /**
     * 二次确认：用户选中某个候选故事后，生成完整正文并自动加入文库
     */
    fun confirmAndGenerateStory(candidate: StoryCandidate) {
        viewModelScope.launch {
            _generationState.value = StoryGenerationUiState.GeneratingStory(candidate.title)
            try {
                val story = storyRepository.generateFullStoryFromCandidate(candidate)
                _generationState.value = StoryGenerationUiState.Success(story)
            } catch (e: Exception) {
                _generationState.value = StoryGenerationUiState.Error("创作故事失败: ${e.message ?: "网络异常"}")
            }
        }
    }

    fun clearCandidates() {
        _generationState.value = StoryGenerationUiState.Idle
    }

    fun resetSynthesisState() {
        _synthesisState.value = SynthesisUiState.Idle
    }

    fun resetGenerationState() {
        _generationState.value = StoryGenerationUiState.Idle
    }

    fun getMimoApiKey(): String = try {
        appConfig.getMimoApiKey()
    } catch (e: Exception) {
        ""
    }

    fun saveMimoApiKey(key: String) {
        appConfig.setMimoApiKey(key)
    }

    fun hasMimoApiKey(): Boolean = appConfig.hasMimoApiKey()
}
