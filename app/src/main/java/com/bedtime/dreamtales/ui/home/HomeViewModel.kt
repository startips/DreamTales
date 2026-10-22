package com.bedtime.dreamtales.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bedtime.dreamtales.core.constants.AppConfig
import com.bedtime.dreamtales.data.api.StoryCandidate
import com.bedtime.dreamtales.data.local.entity.Story
import com.bedtime.dreamtales.data.local.entity.VoiceProfile
import com.bedtime.dreamtales.data.repository.ImportResult
import com.bedtime.dreamtales.data.repository.StoryRepository
import com.bedtime.dreamtales.data.repository.VoiceRepository
import com.bedtime.dreamtales.service.PlaybackMode
import com.bedtime.dreamtales.service.StoryPlaybackService
import com.bedtime.dreamtales.service.StoryPlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class StoryTab {
    ALL,        // 🌙 精选文库
    FAVORITES   // ❤️ 宝贝最爱
}

enum class StorySortOrder(val label: String) {
    FAVORITE_FIRST("❤️ 收藏优先"),
    MOST_PLAYED("🔥 最多收听"),
    NEWEST_FIRST("⏱️ 最新加入")
}

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

    private val _currentTab = MutableStateFlow(StoryTab.ALL)
    val currentTab: StateFlow<StoryTab> = _currentTab.asStateFlow()

    private val _sortOrder = MutableStateFlow(StorySortOrder.FAVORITE_FIRST)
    val sortOrder: StateFlow<StorySortOrder> = _sortOrder.asStateFlow()

    val filteredAndSortedStories: StateFlow<List<Story>> = combine(
        storyRepository.getAllStories(),
        _currentTab,
        _sortOrder
    ) { all, tab, sort ->
        val base = when (tab) {
            StoryTab.ALL -> all
            StoryTab.FAVORITES -> all.filter { it.isFavorite }
        }
        when (sort) {
            StorySortOrder.FAVORITE_FIRST -> base.sortedWith(
                compareByDescending<Story> { it.isFavorite }
                    .thenByDescending { it.playCount }
                    .thenByDescending { it.createdAt }
            )
            StorySortOrder.MOST_PLAYED -> base.sortedWith(
                compareByDescending<Story> { it.playCount }
                    .thenByDescending { it.createdAt }
            )
            StorySortOrder.NEWEST_FIRST -> base.sortedByDescending { it.createdAt }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 缓存统计与状态
    private val _cacheSizeMb = MutableStateFlow(0.0f)
    val cacheSizeMb: StateFlow<Float> = _cacheSizeMb.asStateFlow()

    private val _cacheCount = MutableStateFlow(0)
    val cacheCount: StateFlow<Int> = _cacheCount.asStateFlow()

    init {
        viewModelScope.launch {
            storyRepository.ensureClassicStoriesPopulated()
            storyRepository.cleanLegacyAudioCaches()
            voiceRepository.insertPresetVoiceIfEmpty()
            val voice = voiceRepository.getDefaultVoiceProfileSync()
            if (voice != null) {
                storyRepository.preCacheUpcomingStories(voice)
            }
            refreshCacheInfo()
        }
    }

    private val _synthesisState = MutableStateFlow<SynthesisUiState>(SynthesisUiState.Idle)
    val synthesisState: StateFlow<SynthesisUiState> = _synthesisState.asStateFlow()

    private val _generationState = MutableStateFlow<StoryGenerationUiState>(StoryGenerationUiState.Idle)
    val generationState: StateFlow<StoryGenerationUiState> = _generationState.asStateFlow()

    fun setTab(tab: StoryTab) {
        _currentTab.value = tab
    }

    fun setSortOrder(order: StorySortOrder) {
        _sortOrder.value = order
    }

    fun setThemeMode(mode: String) {
        appConfig.setThemeMode(mode)
        _themeMode.value = mode
    }

    fun setPlaybackMode(mode: PlaybackMode) {
        appConfig.setPlaybackMode(mode.name)
    }

    fun refreshCacheInfo() {
        viewModelScope.launch {
            val bytes = storyRepository.getAudioCacheSizeBytes()
            _cacheSizeMb.value = bytes / (1024f * 1024f)
            _cacheCount.value = storyRepository.getAudioCacheCount()
        }
    }

    fun clearAudioCaches(onCleared: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val success = storyRepository.clearAllAudioCaches()
            refreshCacheInfo()
            onCleared(success)
        }
    }

    suspend fun exportStoriesToJson(selectedStoryIds: Set<Long> = emptySet()): String {
        return storyRepository.exportStoriesToJson(selectedStoryIds)
    }

    suspend fun importStoriesFromJson(jsonContent: String): ImportResult {
        return storyRepository.importStoriesFromJson(jsonContent)
    }

    fun toggleFavorite(story: Story) {
        viewModelScope.launch {
            storyRepository.setFavorite(story.id, !story.isFavorite)
        }
    }

    fun addCustomStory(
        title: String,
        content: String,
        targetAge: String,
        iconEmoji: String,
        onSuccess: (Story) -> Unit
    ) {
        viewModelScope.launch {
            val story = storyRepository.createCustomStory(
                title = title,
                content = content,
                targetAge = targetAge,
                iconEmoji = iconEmoji
            )
            onSuccess(story)
        }
    }

    fun selectVoiceProfile(profileId: Long) {
        viewModelScope.launch {
            voiceRepository.setDefaultVoiceProfile(profileId)
        }
    }

    fun deleteVoiceProfile(profile: VoiceProfile, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val success = voiceRepository.deleteVoiceProfile(profile)
            refreshCacheInfo()
            onComplete(success)
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
                _synthesisState.value = SynthesisUiState.Error(getFriendlyErrorMessage(e, "合成失败"))
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
                _generationState.value = StoryGenerationUiState.Error(getFriendlyErrorMessage(e, "检索候选故事失败"))
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
                _generationState.value = StoryGenerationUiState.Error(getFriendlyErrorMessage(e, "创作故事失败"))
            }
        }
    }

    private fun getFriendlyErrorMessage(e: Throwable, fallbackPrefix: String): String {
        var curr: Throwable? = e
        while (curr != null) {
            if (curr is com.bedtime.dreamtales.core.utils.RateLimitExceededException) {
                return curr.message ?: "🌙 伴读提示：故事讲得有点快啦，小耳朵也要歇一歇哦～（每分钟限10次请求，请稍候再试）"
            }
            curr = curr.cause
        }
        val msg = e.message.orEmpty()
        if (msg.contains("429") || msg.contains("Too Many Requests", ignoreCase = true)) {
            return "🌙 伴读提示：故事服务正在降温安睡中，小耳朵稍作休息，请稍等片刻再试哦~"
        }
        return if (msg.startsWith(fallbackPrefix)) msg else "$fallbackPrefix: ${msg.ifBlank { "网络或服务异常" }}"
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
