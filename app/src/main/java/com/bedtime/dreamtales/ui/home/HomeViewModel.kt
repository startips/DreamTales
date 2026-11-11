package com.bedtime.dreamtales.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bedtime.dreamtales.core.constants.AppConfig
import com.bedtime.dreamtales.core.model.ChildProfile
import com.bedtime.dreamtales.core.playback.PlaybackResumePolicy
import com.bedtime.dreamtales.core.reminder.BedtimeReminderScheduler
import com.bedtime.dreamtales.core.reminder.BedtimeReminderSettings
import com.bedtime.dreamtales.core.stats.SleepWeeklyReport
import com.bedtime.dreamtales.data.api.StoryCandidate
import com.bedtime.dreamtales.data.local.entity.Story
import com.bedtime.dreamtales.data.local.entity.VoiceProfile
import com.bedtime.dreamtales.data.repository.ImportResult
import com.bedtime.dreamtales.data.repository.PlaybackProgressRepository
import com.bedtime.dreamtales.data.repository.SleepStatsRepository
import com.bedtime.dreamtales.data.repository.StoryRepository
import com.bedtime.dreamtales.data.repository.TonightQueueRepository
import com.bedtime.dreamtales.data.repository.VoiceRepository
import com.bedtime.dreamtales.service.PlaybackMode
import com.bedtime.dreamtales.service.StoryPlaybackService
import com.bedtime.dreamtales.service.StoryPlaybackState
import com.bedtime.dreamtales.widget.DreamTalesWidgetProvider
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class StorySortOrder(val label: String) {
    FAVORITE_FIRST("收藏优先"),
    MOST_PLAYED("🔥 最多收听"),
    NEWEST_FIRST("⏱️ 最新加入")
}

sealed class SynthesisUiState {
    object Idle : SynthesisUiState()
    data class Synthesizing(val storyId: Long, val storyTitle: String, val progress: Int) : SynthesisUiState()
    data class ReadyToPlay(
        val story: Story,
        val localFilePath: String,
        val autoPlay: Boolean = false
    ) : SynthesisUiState()
    data class Error(val message: String) : SynthesisUiState()
}

sealed class StoryGenerationUiState {
    object Idle : StoryGenerationUiState()
    data class SearchingCandidates(val query: String) : StoryGenerationUiState()
    data class CandidatesReady(val query: String, val candidates: List<StoryCandidate>) : StoryGenerationUiState()
    data class GeneratingStory(val title: String) : StoryGenerationUiState()
    data class PreviewDraft(val draft: Story) : StoryGenerationUiState()
    data class Success(val story: Story) : StoryGenerationUiState()
    data class Error(val message: String) : StoryGenerationUiState()
}

data class ContinueListeningItem(
    val story: Story,
    val positionMs: Long,
    val durationMs: Long
) {
    val progress: Float
        get() = if (durationMs > 0L) {
            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else 0f
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val storyRepository = StoryRepository(application)
    private val voiceRepository = VoiceRepository(application)
    private val sleepStatsRepository = SleepStatsRepository(application)
    private val queueRepository = TonightQueueRepository(application)
    private val progressRepository = PlaybackProgressRepository(application)
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

    private val _childProfile = MutableStateFlow(appConfig.getChildProfile())
    val childProfile: StateFlow<ChildProfile> = _childProfile.asStateFlow()

    val sleepReport: StateFlow<SleepWeeklyReport> = sleepStatsRepository.getWeeklyReportFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SleepWeeklyReport())

    /**
     * 今晚故事单：按队列顺序映射为故事对象，自动忽略已经删除的故事。
     */
    val tonightQueue: StateFlow<List<Story>> = combine(
        storyRepository.getAllStories(),
        queueRepository.getQueueFlow()
    ) { stories, items ->
        val byId = stories.associateBy { it.id }
        items.mapNotNull { byId[it.storyId] }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 最近一次未听完的进度，用于首页「继续听」。
     */
    val continueListening: StateFlow<ContinueListeningItem?> = combine(
        storyRepository.getAllStories(),
        progressRepository.getMostRecentFlow(),
        StoryPlaybackService.playbackState
    ) { stories, position, playback ->
        if (position == null) return@combine null
        val story = stories.firstOrNull { it.id == position.storyId } ?: return@combine null
        if (playback.storyId == story.id && playback.isPlaying) return@combine null
        val duration = if (position.durationMs > 0L) {
            position.durationMs
        } else {
            story.durationSeconds * 1000L
        }
        if (PlaybackResumePolicy.shouldResume(position.positionMs, duration)) {
            ContinueListeningItem(story, position.positionMs, duration)
        } else {
            null
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _bedtimeReminder = MutableStateFlow(appConfig.getBedtimeReminder())
    val bedtimeReminder: StateFlow<BedtimeReminderSettings> = _bedtimeReminder.asStateFlow()

    private val _sortOrder = MutableStateFlow(StorySortOrder.FAVORITE_FIRST)
    val sortOrder: StateFlow<StorySortOrder> = _sortOrder.asStateFlow()

    val filteredAndSortedStories: StateFlow<List<Story>> = combine(
        storyRepository.getAllStories(),
        _sortOrder
    ) { all, sort ->
        when (sort) {
            StorySortOrder.FAVORITE_FIRST -> all.sortedWith(
                compareByDescending<Story> { it.isFavorite }
                    .thenByDescending { it.playCount }
                    .thenByDescending { it.createdAt }
            )
            StorySortOrder.MOST_PLAYED -> all.sortedWith(
                compareByDescending<Story> { it.playCount }
                    .thenByDescending { it.createdAt }
            )
            StorySortOrder.NEWEST_FIRST -> all.sortedByDescending { it.createdAt }
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
            if (appConfig.getBedtimeReminder().enabled) {
                BedtimeReminderScheduler.schedule(getApplication())
            }
            refreshCacheInfo()
        }
    }

    private val _synthesisState = MutableStateFlow<SynthesisUiState>(SynthesisUiState.Idle)
    val synthesisState: StateFlow<SynthesisUiState> = _synthesisState.asStateFlow()

    private val _generationState = MutableStateFlow<StoryGenerationUiState>(StoryGenerationUiState.Idle)
    val generationState: StateFlow<StoryGenerationUiState> = _generationState.asStateFlow()

    fun setSortOrder(order: StorySortOrder) {
        _sortOrder.value = order
    }

    fun setThemeMode(mode: String) {
        appConfig.setThemeMode(mode)
        _themeMode.value = mode
    }

    fun saveChildProfile(profile: ChildProfile) {
        appConfig.setChildProfile(profile)
        _childProfile.value = profile
    }

    // ---------------- 今晚故事单 ----------------

    fun addToTonightQueue(storyId: Long) {
        viewModelScope.launch { queueRepository.add(storyId) }
    }

    fun removeFromTonightQueue(storyId: Long) {
        viewModelScope.launch { queueRepository.remove(storyId) }
    }

    fun moveInTonightQueue(storyId: Long, direction: Int) {
        viewModelScope.launch { queueRepository.move(storyId, direction) }
    }

    fun clearTonightQueue() {
        viewModelScope.launch { queueRepository.clear() }
    }

    fun playTonightQueue(onNavigateToPlayer: () -> Unit) {
        viewModelScope.launch {
            val firstId = queueRepository.getStoryIdsSync().firstOrNull() ?: return@launch
            val story = storyRepository.getStoryById(firstId) ?: return@launch
            onStoryClicked(
                story = story,
                onNavigateToPlayer = onNavigateToPlayer,
                autoPlay = true,
                preserveQueue = true
            )
        }
    }

    fun continueStory(item: ContinueListeningItem, onNavigateToPlayer: () -> Unit) {
        onStoryClicked(item.story, onNavigateToPlayer, autoPlay = true)
    }

    // ---------------- 晚安提醒 ----------------

    fun setBedtimeReminder(settings: BedtimeReminderSettings) {
        appConfig.setBedtimeReminder(settings)
        _bedtimeReminder.value = settings
        if (settings.enabled) {
            BedtimeReminderScheduler.schedule(getApplication())
        } else {
            BedtimeReminderScheduler.cancel(getApplication())
        }
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
        val result = storyRepository.importStoriesFromJson(jsonContent)
        DreamTalesWidgetProvider.refresh(getApplication())
        return result
    }

    fun toggleFavorite(story: Story) {
        viewModelScope.launch {
            storyRepository.setFavorite(story.id, !story.isFavorite)
            DreamTalesWidgetProvider.refresh(getApplication())
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
            DreamTalesWidgetProvider.refresh(getApplication())
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

    private var activeSynthesisJob: Job? = null
    private var userRequestedPlayStoryId: Long? = null

    fun onStoryClicked(
        story: Story,
        onNavigateToPlayer: () -> Unit,
        autoPlay: Boolean = false,
        preserveQueue: Boolean = false
    ) {
        val currentPlayback = StoryPlaybackService.playbackState.value
        // 如果当前已在播放/就绪该故事，直接无缝进入沉浸播放器；
        // 但「今晚故事单」或小组件要求自动开播且当前处于暂停时，需要继续走播放链路。
        val needsResumeCurrent = autoPlay && !currentPlayback.isPlaying
        if (currentPlayback.storyId == story.id &&
            currentPlayback.storyTitle.isNotBlank() &&
            !needsResumeCurrent
        ) {
            onNavigateToPlayer()
            return
        }

        val currentVoice = defaultVoice.value
        if (currentVoice == null) {
            _synthesisState.value = SynthesisUiState.Error("请先选择或录制一个专属伴读音色")
            return
        }

        // 手动点播队列之外的故事时才清空「今晚故事单」；
        // 续播/点播仍在故事单里的故事时保留整个队列。
        val keepQueue = preserveQueue || tonightQueue.value.any { it.id == story.id }
        if (!keepQueue) {
            viewModelScope.launch { queueRepository.clear() }
        }

        // 1. 优先检查本地是否已有完整音频缓存：已有缓存零等待直接就绪播放，绝不受任何后台加载任务干扰
        val cached = completedCaches.value.find { it.storyId == story.id && it.voiceProfileId == currentVoice.id }
        if (cached != null) {
            val file = File(cached.localFilePath)
            if (file.exists() && file.length() > 0) {
                viewModelScope.launch {
                    try {
                        // 走仓库统一入口：顺带清理旧缓存里的超长静音，并校验音频哈希
                        val filePath = storyRepository.getOrSynthesizeStoryAudio(story, currentVoice)
                        storyRepository.recordStoryPlayed(story.id)
                        _synthesisState.value = SynthesisUiState.ReadyToPlay(story, filePath, autoPlay)
                    } catch (e: Exception) {
                        _synthesisState.value = SynthesisUiState.Error(
                            getFriendlyErrorMessage(e, "读取本地音频失败")
                        )
                    }
                }
                return
            }
        }

        // 2. 如果用户点击的正是当前正在合成中的故事，标记为用户主动要听，合成完自动进入播放
        val currentSynth = _synthesisState.value
        if (currentSynth is SynthesisUiState.Synthesizing && currentSynth.storyId == story.id) {
            userRequestedPlayStoryId = story.id
            return
        }

        // 3. 用户点击了一个新的未缓存故事：取消旧任务，全力响应当前用户最新点播的故事！
        activeSynthesisJob?.cancel()
        userRequestedPlayStoryId = story.id

        activeSynthesisJob = viewModelScope.launch {
            _synthesisState.value = SynthesisUiState.Synthesizing(story.id, story.title, 0)
            try {
                val filePath = storyRepository.getOrSynthesizeStoryAudio(
                    story = story,
                    voiceProfile = currentVoice,
                    onProgress = { progress ->
                        _synthesisState.value = SynthesisUiState.Synthesizing(story.id, story.title, progress)
                    }
                )
                storyRepository.recordStoryPlayed(story.id)
                // 仅当用户依然想听这个故事时才发出 ReadyToPlay
                if (userRequestedPlayStoryId == story.id) {
                    _synthesisState.value = SynthesisUiState.ReadyToPlay(story, filePath, autoPlay)
                }
            } catch (e: CancellationException) {
                // 任务被用户切换取消，安全退出
                throw e
            } catch (e: IllegalStateException) {
                _synthesisState.value = SynthesisUiState.Error(e.message ?: "未配置 API Key")
            } catch (e: Exception) {
                _synthesisState.value = SynthesisUiState.Error(getFriendlyErrorMessage(e, "合成失败"))
            }
        }
    }

    /**
     * 搜索/录入新故事后后台静默预加载伴读音频：
     * 仅在卡片原位展示加载进度，完成后静默就绪，不自动弹窗，不立马播放。
     */
    fun preloadStoryAudio(story: Story) {
        val currentVoice = defaultVoice.value ?: return

        // 检查是否已有缓存
        val cached = completedCaches.value.find { it.storyId == story.id && it.voiceProfileId == currentVoice.id }
        if (cached != null && File(cached.localFilePath).exists() && File(cached.localFilePath).length() > 0) {
            return
        }

        // 如果用户前台正主动要求播放某个故事，不打断前台点播
        if (_synthesisState.value is SynthesisUiState.Synthesizing && userRequestedPlayStoryId != null) {
            return
        }

        activeSynthesisJob?.cancel()
        userRequestedPlayStoryId = null // 标记为后台静默加载

        activeSynthesisJob = viewModelScope.launch {
            _synthesisState.value = SynthesisUiState.Synthesizing(story.id, story.title, 0)
            try {
                storyRepository.getOrSynthesizeStoryAudio(
                    story = story,
                    voiceProfile = currentVoice,
                    onProgress = { progress ->
                        _synthesisState.value = SynthesisUiState.Synthesizing(story.id, story.title, progress)
                    }
                )
                // 静默加载完成：因为 userRequestedPlayStoryId == null，绝不触发 ReadyToPlay 弹播放器！
                // 恢复 Idle 状态即可，卡片上会自动因为 completedCaches 的更新显示为“已就绪”
                if (userRequestedPlayStoryId == story.id) {
                    val finalCache = completedCaches.value.find { it.storyId == story.id && it.voiceProfileId == currentVoice.id }
                    if (finalCache != null) {
                        _synthesisState.value = SynthesisUiState.ReadyToPlay(story, finalCache.localFilePath)
                    } else {
                        _synthesisState.value = SynthesisUiState.Idle
                    }
                } else {
                    _synthesisState.value = SynthesisUiState.Idle
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _synthesisState.value = SynthesisUiState.Idle
            }
        }
    }

    private var lastQuery: String = ""
    private var lastCandidates: List<StoryCandidate> = emptyList()

    /**
     * 搜索故事：先检索推荐 3 个候选故事卡片供二次点选
     */
    fun searchStoryCandidates(query: String) {
        if (query.isBlank()) return
        lastQuery = query.trim()
        viewModelScope.launch {
            _generationState.value = StoryGenerationUiState.SearchingCandidates(lastQuery)
            try {
                val candidates = storyRepository.searchStoryCandidatesWithCpa(lastQuery)
                lastCandidates = candidates
                _generationState.value = StoryGenerationUiState.CandidatesReady(lastQuery, candidates)
            } catch (e: Exception) {
                _generationState.value = StoryGenerationUiState.Error(getFriendlyErrorMessage(e, "检索候选故事失败"))
            }
        }
    }

    /**
     * 第一步：用户在候选卡片中点选后，调用 CPA 生成完整故事草稿并拉起全文预览弹窗
     */
    fun requestStoryPreview(candidate: StoryCandidate) {
        viewModelScope.launch {
            _generationState.value = StoryGenerationUiState.GeneratingStory(candidate.title)
            try {
                val draft = storyRepository.generateStoryDraftFromCandidate(candidate)
                _generationState.value = StoryGenerationUiState.PreviewDraft(draft)
            } catch (e: Exception) {
                _generationState.value = StoryGenerationUiState.Error(getFriendlyErrorMessage(e, "生成故事正文失败"))
            }
        }
    }

    /**
     * 第二步：用户在全文预览中确认添加，正式存盘至 Room 文库
     */
    fun confirmAndSaveDraft(draft: Story) {
        viewModelScope.launch {
            try {
                val savedStory = storyRepository.saveStoryToLibrary(draft)
                DreamTalesWidgetProvider.refresh(getApplication())
                _generationState.value = StoryGenerationUiState.Success(savedStory)
            } catch (e: Exception) {
                _generationState.value = StoryGenerationUiState.Error(getFriendlyErrorMessage(e, "收录故事失败"))
            }
        }
    }

    /**
     * 取消全文预览：返回到候选卡片列表，方便用户选择其他候选或重新搜索
     */
    fun cancelPreview() {
        if (lastCandidates.isNotEmpty()) {
            _generationState.value = StoryGenerationUiState.CandidatesReady(lastQuery, lastCandidates)
        } else {
            _generationState.value = StoryGenerationUiState.Idle
        }
    }

    /**
     * 兼容接口：直接生成并入库
     */
    fun confirmAndGenerateStory(candidate: StoryCandidate) {
        requestStoryPreview(candidate)
    }

    private fun getFriendlyErrorMessage(e: Throwable, fallbackPrefix: String): String {
        var curr: Throwable? = e
        while (curr != null) {
            if (curr is com.bedtime.dreamtales.core.utils.RateLimitExceededException) {
                return curr.message ?: "🌙 伴读提示：故事讲得有点快啦，小耳朵也要歇一歇哦～（每分钟限15次请求，请稍候再试）"
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

    fun getGithubRepo(): String = appConfig.getGithubRepo()
    fun setGithubRepo(repo: String) = appConfig.setGithubRepo(repo)
}
