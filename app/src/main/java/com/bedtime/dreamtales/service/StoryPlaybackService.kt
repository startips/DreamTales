package com.bedtime.dreamtales.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.bedtime.dreamtales.MainActivity
import com.bedtime.dreamtales.R
import com.bedtime.dreamtales.core.StorySpeechHelper
import com.bedtime.dreamtales.core.audio.AmbientMix
import com.bedtime.dreamtales.core.constants.AppConfig
import com.bedtime.dreamtales.core.constants.AppConstants
import com.bedtime.dreamtales.core.playback.PlaybackQueuePolicy
import com.bedtime.dreamtales.core.playback.PlaybackResumePolicy
import com.bedtime.dreamtales.core.playback.PlaybackSpeedPolicy
import com.bedtime.dreamtales.core.playback.SleepTimerPolicy
import com.bedtime.dreamtales.data.local.entity.SleepSession
import com.bedtime.dreamtales.data.local.entity.Story
import com.bedtime.dreamtales.data.repository.ChildProfileRepository
import com.bedtime.dreamtales.data.repository.PlaybackProgressRepository
import com.bedtime.dreamtales.data.repository.SleepStatsRepository
import com.bedtime.dreamtales.data.repository.StoryRepository
import com.bedtime.dreamtales.data.repository.TonightQueueRepository
import com.bedtime.dreamtales.data.repository.VoiceRepository
import com.bedtime.dreamtales.widget.DreamTalesWidgetProvider
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import java.io.File
import kotlin.math.ln
import kotlin.math.max

enum class PlaybackMode(val title: String, val iconEmoji: String) {
    SEQUENTIAL("顺序连播", "🔁"),
    SHUFFLE("随机播放", "🔀"),
    REPEAT_ONE("单曲循环", "🔂"),
    STOP_AFTER_CURRENT("播完即止", "⏹️")
}

enum class AmbientSound(val id: String, val title: String, val iconEmoji: String, val assetName: String?) {
    OFF("off", "无背景音", "🚫", null),
    RAIN("rain", "森林细雨", "🌧️", "ambient/ambient_rain.wav"),
    WAVES("waves", "宁静海浪", "🌊", "ambient/ambient_waves.wav"),
    FIRE("fire", "壁炉柴火", "🔥", "ambient/ambient_fire.wav"),
    CRICKETS("crickets", "夏夜虫鸣", "🌾", "ambient/ambient_crickets.wav"),
    LULLABY("lullaby", "摇篮音盒", "🎶", "ambient/ambient_lullaby.wav");

    companion object {
        fun fromId(id: String): AmbientSound? =
            values().firstOrNull { it.id == id && it != OFF }
    }
}

data class StoryPlaybackState(
    val storyId: Long = 0L,
    val storyTitle: String = "",
    val storyContent: String = "",
    val coverImagePath: String? = null,
    val storyEmoji: String = "🌙",
    val isAmbientOnly: Boolean = false,
    val isGeneratingCover: Boolean = false,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val remainingTimerSeconds: Int = 0,
    val selectedTimerMinutes: Int = 0,
    val isTimerActive: Boolean = false,
    val currentVolume: Float = 1.0f,
    val systemVolumeRatio: Float = 1.0f,
    val currentSpeed: Float = 1.0f,
    val basePlaybackSpeed: Float = 1.0f,
    val playbackMode: PlaybackMode = PlaybackMode.SEQUENTIAL,
    val ambientSound: AmbientSound = AmbientSound.OFF,
    val ambientVolume: Float = 0.25f,
    val ambientMix: Map<String, Float> = emptyMap(),
    /** 自动连播切到未缓存故事时，正在后台合成音频 */
    val isPreparingStory: Boolean = false,
    val preparingStoryTitle: String = ""
)

@SuppressLint("UnsafeOptInUsageError")
class StoryPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var exoPlayer: ExoPlayer
    private lateinit var ambientAudioAttributes: AudioAttributes
    private val ambientPlayers = mutableMapOf<AmbientSound, ExoPlayer>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentStoryFavorite = false
    private val toggleFavoriteCommand = SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY)

    private val binder = PlaybackBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private var sleepTimerJob: Job? = null
    private var progressTrackingJob: Job? = null
    private var manualSkipJob: Job? = null
    private var prefetchJob: Job? = null

    // 睡眠定时状态机：区分「系统默认挂载」「用户主动取消」「定时刚归零」
    private var timerCancelledByUser = false
    private var timerExpiredRecently = false
    private var lastRequestedTimerMinutes = 30

    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val storyRepository by lazy { StoryRepository(applicationContext) }
    private val voiceRepository by lazy { VoiceRepository(applicationContext) }
    private val sleepStatsRepository by lazy { SleepStatsRepository(applicationContext) }
    private val progressRepository by lazy { PlaybackProgressRepository(applicationContext) }
    private val queueRepository by lazy { TonightQueueRepository(applicationContext) }
    private val childProfileRepository by lazy { ChildProfileRepository(applicationContext) }
    private val appConfig by lazy { AppConfig.getInstance(applicationContext) }

    private var fadeMultiplier: Float = 1.0f
    private var basePlaybackSpeed: Float = PlaybackSpeedPolicy.DEFAULT_SPEED
    private var fadeSpeedMultiplier: Float = 1.0f
    private var lastProgressSaveAt: Long = 0L
    private var ambientPausedForEmptyMix = false

    // 睡前陪伴会话统计
    private var sessionStoryId: Long = 0L
    private var sessionStoryTitle: String = ""
    private var sessionChildProfileId: Long = 0L
    @Volatile
    private var activeChildProfileId: Long = 0L
    private var sessionStartedAt: Long = 0L
    private var sessionListenedMs: Long = 0L
    private var sessionLastResumeAt: Long = 0L
    private var sessionPausedAt: Long = 0L
    private var sessionTimerMinutes: Int = 0
    private var bedtimeStartedAt: Long = 0L

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateVolumeState()
        }
    }

    private val mediaActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_TOGGLE_PLAYBACK -> togglePlayPause()
                ACTION_TOGGLE_FAVORITE -> toggleCurrentFavorite()
                StoryRepository.ACTION_FAVORITE_CHANGED -> {
                    val storyId = intent.getLongExtra(StoryRepository.EXTRA_STORY_ID, 0L)
                    if (storyId == playbackState.value.storyId) {
                        currentStoryFavorite = intent.getBooleanExtra(
                            StoryRepository.EXTRA_IS_FAVORITE,
                            false
                        )
                        mediaSession?.setCustomLayout(buildSessionCustomLayout())
                        updateNotification(playbackState.value.storyTitle, exoPlayer.isPlaying)
                    }
                }
            }
        }
    }

    private val mediaSessionCallback = object : MediaSession.Callback {
        /**
         * 把「收藏」自定义命令开放给系统媒体控制器（锁屏/通知栏/蓝牙）。
         * 不声明的话，部分 ROM 会直接忽略 setCustomLayout 里的自定义按钮。
         */
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                .buildUpon()
                .apply {
                    if (!playbackState.value.isAmbientOnly) add(toggleFavoriteCommand)
                }
                .build()
            return MediaSession.ConnectionResult.accept(
                sessionCommands,
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
            )
        }

        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            session.setCustomLayout(controller, buildSessionCustomLayout())
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            return when (customCommand.customAction) {
                ACTION_TOGGLE_FAVORITE -> {
                    toggleCurrentFavorite()
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                else -> super.onCustomCommand(session, controller, customCommand, args)
            }
        }
    }

    /**
     * 锁屏/通知栏媒体卡片上的收藏按钮。收藏状态变化时同步刷新图标与小组件。
     */
    private fun toggleCurrentFavorite() {
        val storyId = playbackState.value.storyId
        if (storyId == 0L) return
        val newValue = !currentStoryFavorite
        currentStoryFavorite = newValue
        mediaSession?.setCustomLayout(buildSessionCustomLayout())
        updateNotification(playbackState.value.storyTitle, exoPlayer.isPlaying)
        serviceScope.launch {
            runCatching {
                storyRepository.setFavorite(storyId, newValue)
                DreamTalesWidgetProvider.refresh(applicationContext)
            }
        }
    }

    private fun refreshCurrentFavoriteState(storyId: Long) {
        if (storyId == 0L) {
            currentStoryFavorite = false
            mediaSession?.setCustomLayout(buildSessionCustomLayout())
            updateNotification(playbackState.value.storyTitle, exoPlayer.isPlaying)
            return
        }
        serviceScope.launch {
            val favorite = runCatching {
                storyRepository.getStoryById(storyId)?.isFavorite == true
            }.getOrDefault(false)
            currentStoryFavorite = favorite
            mediaSession?.setCustomLayout(buildSessionCustomLayout())
            updateNotification(playbackState.value.storyTitle, exoPlayer.isPlaying)
        }
    }

    private fun buildSessionCustomLayout(): List<CommandButton> {
        if (playbackState.value.isAmbientOnly) return emptyList()
        val heartIcon = if (currentStoryFavorite) {
            CommandButton.ICON_HEART_FILLED
        } else {
            CommandButton.ICON_HEART_UNFILLED
        }
        return listOf(
            CommandButton.Builder(heartIcon)
                .setSessionCommand(toggleFavoriteCommand)
                .setDisplayName(if (currentStoryFavorite) "取消收藏" else "收藏")
                .build()
        )
    }

    /**
     * HyperOS 的锁屏媒体卡片读取的是平台 PlaybackState 里的 legacy custom actions。
     * 把收藏命令登记到媒体通知控制器后，Media3 才会把爱心按钮写入 legacy PlaybackState。
     */
    private fun ensureFavoriteCommandAvailable() {
        val session = mediaSession ?: return
        val controller = session.mediaNotificationControllerInfo ?: return
        val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
            .buildUpon()
            .apply {
                if (!playbackState.value.isAmbientOnly) add(toggleFavoriteCommand)
            }
            .build()
        runCatching {
            session.setAvailableCommands(
                controller,
                commands,
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
            )
        }
    }

    inner class PlaybackBinder : Binder() {
        fun getService(): StoryPlaybackService = this@StoryPlaybackService
    }

    override fun onCreate() {
        super.onCreate()

        // 1. 初始化唤醒锁 (PARTIAL_WAKE_LOCK)
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "DreamTales:StoryPlaybackWakeLock"
        ).apply {
            setReferenceCounted(false)
        }

        // 2. 初始化 ExoPlayer（故事主声道）
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .setUsage(C.USAGE_MEDIA)
            .build()

        exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        // 3. 多轨助眠白噪音播放器按需惰性创建（最多 5 轨混音）
        ambientAudioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val savedModeName = appConfig.getPlaybackMode()
        val initialMode = try {
            PlaybackMode.valueOf(savedModeName)
        } catch (e: Exception) {
            PlaybackMode.SEQUENTIAL
        }
        basePlaybackSpeed = PlaybackSpeedPolicy.normalizeBaseSpeed(appConfig.getPlaybackSpeed())
        updateState { it.copy(playbackMode = initialMode, basePlaybackSpeed = basePlaybackSpeed) }
        applyExoPlayerRepeatMode(initialMode)
        applyPlaybackSpeed()

        serviceScope.launch {
            childProfileRepository.ensureMigratedFromLegacy()
            childProfileRepository.getSelectedProfileFlow().collect { selected ->
                activeChildProfileId = selected?.id ?: 0L
            }
        }

        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateState { it.copy(isPlaying = isPlaying) }
                if (isPlaying) {
                    resetFadeIfTimerFinished()
                    acquireWakeLock()
                    startProgressTracker()
                    startOrResumePlaybackSession()
                    updateNotification(playbackState.value.storyTitle, isPlaying = true)
                    ambientPlayers.values.forEach { player ->
                        if (player.mediaItemCount > 0) player.play()
                    }
                } else {
                    savePlaybackPosition()
                    pausePlaybackSessionAccumulation()
                    releaseWakeLock()
                    updateNotification(playbackState.value.storyTitle, isPlaying = false)
                    ambientPlayers.values.forEach { it.pause() }
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    if (!playbackState.value.isAmbientOnly) {
                        updateState {
                            it.copy(
                                totalDurationMs = max(0L, exoPlayer.duration),
                                currentPositionMs = max(0L, exoPlayer.currentPosition)
                            )
                        }
                    }
                } else if (state == Player.STATE_ENDED) {
                    handlePlaybackEnded()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                updateState {
                    it.copy(isPlaying = false)
                }
                releaseWakeLock()
            }
        })

        // 4. 监听手机硬件系统音量变动
        try {
            val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            ContextCompat.registerReceiver(
                this,
                volumeReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
        } catch (e: Exception) {
            // ignore
        }
        updateVolumeState()

        // 恢复上次保存的多轨白噪音配方
        val savedAmbientMix = appConfig.getAmbientMix()
        if (savedAmbientMix.isNotEmpty()) {
            applyAmbientMix(savedAmbientMix, persist = false)
        }

        // 5. 构建 MediaSession（通过 ForwardingPlayer 暴露“上一集/下一集”，让锁屏媒体卡片显示切集按钮）
        val sessionIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            sessionIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val sessionPlayer = object : ForwardingPlayer(exoPlayer) {
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .addAll(
                        Player.COMMAND_SEEK_TO_NEXT,
                        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_PREVIOUS,
                        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
                    )
                    .build()
            }

            override fun hasNext(): Boolean = !StoryPlaybackService.playbackState.value.isAmbientOnly
            override fun hasPrevious(): Boolean = !StoryPlaybackService.playbackState.value.isAmbientOnly
            override fun hasNextMediaItem(): Boolean = !StoryPlaybackService.playbackState.value.isAmbientOnly
            override fun hasPreviousMediaItem(): Boolean = !StoryPlaybackService.playbackState.value.isAmbientOnly
            override fun seekToNext() = playNext()
            override fun seekToNextMediaItem() = playNext()
            override fun seekToPrevious() = playPrevious()
            override fun seekToPreviousMediaItem() = playPrevious()
        }

        mediaSession = MediaSession.Builder(this, sessionPlayer)
            .setSessionActivity(pendingIntent)
            .setCallback(mediaSessionCallback)
            .setCustomLayout(buildSessionCustomLayout())
            .build()
        // 交给 MediaSessionService 管理，才能拿到媒体通知控制器并把它登记到 legacy PlaybackState
        mediaSession?.let { addSession(it) }
        ensureFavoriteCommandAvailable()

        // 6. 监听通知栏媒体按钮（播放/暂停）
        try {
            val mediaFilter = IntentFilter(ACTION_TOGGLE_PLAYBACK)
            mediaFilter.addAction(ACTION_TOGGLE_FAVORITE)
            mediaFilter.addAction(StoryRepository.ACTION_FAVORITE_CHANGED)
            ContextCompat.registerReceiver(
                this,
                mediaActionReceiver,
                mediaFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (e: Exception) {
            // ignore
        }

        createNotificationChannel()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    /**
     * 通知栏/锁屏卡片由本服务的 updateNotification 统一管理，
     * 这里阻止 Media3 再额外发布一条默认媒体通知。
     */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        // no-op
    }

    override fun onBind(intent: Intent?): IBinder {
        super.onBind(intent)
        return binder
    }

    /**
     * 播放本地私有沙盒中的音频文件（Local-First 原则）
     * autoPlay = false: 进播放页面处于待命状态，由用户点击后再开播
     */
    fun playStoryAudio(
        storyId: Long,
        storyTitle: String,
        storyContent: String,
        localFilePath: String,
        autoPlay: Boolean = false,
        viaAutoAdvance: Boolean = false
    ) {
        val file = File(localFilePath)
        if (!file.exists() || file.length() == 0L) {
            throw IllegalArgumentException("本地故事音频文件不存在: $localFilePath")
        }

        // 如果当前已在播放同一个故事，避免从头重新加载与重置倒计时
        val currentState = playbackState.value
        if (currentState.storyId == storyId && exoPlayer.playbackState != Player.STATE_IDLE && exoPlayer.playbackState != Player.STATE_ENDED) {
            updateState { it.copy(storyContent = storyContent) }
            refreshCurrentFavoriteState(storyId)
            if (autoPlay && !exoPlayer.isPlaying) {
                resetFadeIfTimerFinished()
                armDefaultTimerIfNeeded(viaAutoAdvance)
                exoPlayer.play()
            }
            updateNotification(storyTitle, isPlaying = exoPlayer.isPlaying)
            return
        }

        // 切换到另一篇故事：结算上一段陪伴会话（入睡耗时只在睡眠定时归零时记录）
        if (currentState.storyId != 0L && currentState.storyId != storyId) {
            savePlaybackPosition()
            finalizePlaybackSession("SWITCHED")
        }

        fadeMultiplier = 1.0f
        fadeSpeedMultiplier = 1.0f
        ambientPausedForEmptyMix = false
        exoPlayer.volume = 1.0f
        applyPlaybackSpeed()
        applyAmbientVolume()
        updateVolumeState()
        exoPlayer.repeatMode = if (currentState.playbackMode == PlaybackMode.REPEAT_ONE) {
            Player.REPEAT_MODE_ONE
        } else {
            Player.REPEAT_MODE_OFF
        }

        val existingCover = File(applicationContext.filesDir, "story_covers/cover_${storyId}.jpg")
            .takeIf { it.exists() && it.length() > 0 }?.absolutePath

        updateState {
            it.copy(
                storyId = storyId,
                storyTitle = storyTitle,
                storyContent = storyContent,
                coverImagePath = existingCover,
                storyEmoji = "🌙",
                isAmbientOnly = false,
                currentSpeed = PlaybackSpeedPolicy.effectiveSpeed(basePlaybackSpeed, 1.0f),
                basePlaybackSpeed = basePlaybackSpeed,
                isPlaying = autoPlay,
                currentPositionMs = 0L,
                totalDurationMs = 0L
            )
        }
        refreshCurrentFavoriteState(storyId)
        serviceScope.launch {
            val emoji = runCatching {
                storyRepository.getStoryById(storyId)?.iconEmoji
            }.getOrNull()
            if (!emoji.isNullOrBlank()) {
                updateState { it.copy(storyEmoji = emoji) }
            }
        }

        val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.seekTo(0L)

        // 读取断点进度后再 prepare，避免 autoPlay 时先从头播出一小段
        serviceScope.launch {
            val saved = runCatching { progressRepository.getPosition(storyId) }.getOrNull()
            if (playbackState.value.storyId != storyId) return@launch
            exoPlayer.prepare()
            if (playbackState.value.storyId != storyId) return@launch
            if (saved != null) {
                val duration = exoPlayer.duration.takeIf { it > 0L } ?: saved.durationMs
                if (PlaybackResumePolicy.shouldResume(saved.positionMs, duration)) {
                    val target = PlaybackResumePolicy.clampPosition(saved.positionMs, duration)
                    exoPlayer.seekTo(target)
                    updateState { it.copy(currentPositionMs = target) }
                } else {
                    progressRepository.clear(storyId)
                }
            }
            if (autoPlay) {
                exoPlayer.play()
            } else {
                exoPlayer.pause()
            }
        }

        // 默认定时只在「用户手动开始播放」时挂载；
        // 自动连播不重新挂（避免定时归零后又被偷偷续上 30 分钟），
        // 用户手动取消后也不会被重新挂回来。
        armDefaultTimerIfNeeded(viaAutoAdvance)

        // 播放稳定 10 秒后，后台预取「今晚故事单」的下一首，避免连播切歌时现合成
        if (!viaAutoAdvance) {
            scheduleNextQueuePrefetch(storyId)
        }

        updateNotification(storyTitle, isPlaying = autoPlay)
    }

    /**
     * 预取队列里的下一首（低优先级：延迟 10 秒、前台点播时让位）。
     */
    private fun scheduleNextQueuePrefetch(currentStoryId: Long) {
        prefetchJob?.cancel()
        prefetchJob = serviceScope.launch {
            delay(10_000L)
            if (storyRepository.isPreCacheSuppressed()) return@launch
            val ids = queueRepository.getStoryIdsSync()
            val index = PlaybackQueuePolicy.indexOf(ids, currentStoryId)
            if (index < 0) return@launch
            val next = PlaybackQueuePolicy.nextIndex(index, ids.size) ?: return@launch
            val story = storyRepository.getStoryById(ids[next]) ?: return@launch
            val voice = voiceRepository.getDefaultVoiceProfileSync() ?: return@launch
            if (storyRepository.hasCachedAudio(story, voice)) return@launch
            runCatching { storyRepository.getOrSynthesizeStoryAudio(story, voice) }
        }
    }

    /**
     * 仅当没有活动定时、用户没有主动取消、且不是自动连播时才挂默认 30 分钟定时。
     */
    private fun armDefaultTimerIfNeeded(viaAutoAdvance: Boolean) {
        val state = playbackState.value
        val shouldArm = SleepTimerPolicy.shouldArmDefaultTimer(
            isTimerActive = state.isTimerActive,
            remainingSeconds = state.remainingTimerSeconds,
            cancelledByUser = timerCancelledByUser,
            expiredRecently = timerExpiredRecently,
            viaAutoAdvance = viaAutoAdvance
        )
        if (shouldArm) {
            startSleepTimer(lastRequestedTimerMinutes.coerceAtLeast(1))
        }
    }

    /**
     * 纯声景模式：不朗读故事，只播放多轨自然声，适合孩子已经睡着之后。
     */
    fun startAmbientOnly() {
        val current = playbackState.value
        if (current.isAmbientOnly && exoPlayer.isPlaying) return

        if (!current.isAmbientOnly) {
            savePlaybackPosition()
            finalizePlaybackSession("SWITCHED")
        }
        prefetchJob?.cancel()

        fadeMultiplier = 1.0f
        fadeSpeedMultiplier = 1.0f
        ambientPausedForEmptyMix = false
        exoPlayer.volume = 1.0f
        applyPlaybackSpeed()
        exoPlayer.repeatMode = Player.REPEAT_MODE_ONE
        currentStoryFavorite = false
        mediaSession?.setCustomLayout(emptyList())

        val mix = appConfig.getAmbientMix().ifEmpty {
            mapOf(AmbientSound.RAIN.id to 0.25f)
        }
        applyAmbientMix(mix, persist = true)

        updateState {
            it.copy(
                storyId = 0L,
                storyTitle = "月眠声景",
                storyContent = "",
                coverImagePath = null,
                storyEmoji = "🌿",
                isAmbientOnly = true,
                isPlaying = true,
                currentPositionMs = 0L,
                totalDurationMs = 0L,
                basePlaybackSpeed = basePlaybackSpeed,
                currentSpeed = PlaybackSpeedPolicy.effectiveSpeed(basePlaybackSpeed, 1.0f)
            )
        }

        // 用一段静音循环承载前台播放服务与睡眠定时，真正的听感全部来自 ambientPlayers
        exoPlayer.setMediaItem(MediaItem.fromUri("asset:///ambient/silence_loop.wav"))
        exoPlayer.prepare()
        exoPlayer.play()

        armDefaultTimerIfNeeded(viaAutoAdvance = false)
        updateNotification("月眠声景", isPlaying = true)
    }

    /**
     * 0.8x / 0.9x / 1.0x 基础语速；最后 5 分钟渐弱会在此基础上继续减速。
     */
    fun setPlaybackSpeed(speed: Float) {
        basePlaybackSpeed = PlaybackSpeedPolicy.normalizeBaseSpeed(speed)
        appConfig.setPlaybackSpeed(basePlaybackSpeed)
        applyPlaybackSpeed()
    }

    private fun applyPlaybackSpeed() {
        val effective = PlaybackSpeedPolicy.effectiveSpeed(basePlaybackSpeed, fadeSpeedMultiplier)
        exoPlayer.playbackParameters = PlaybackParameters(effective)
        updateState { it.copy(currentSpeed = effective, basePlaybackSpeed = basePlaybackSpeed) }
    }

    private fun savePlaybackPosition() {
        val state = playbackState.value
        if (state.storyId == 0L || state.isAmbientOnly) return
        if (exoPlayer.playbackState == Player.STATE_IDLE) return
        val position = exoPlayer.currentPosition.coerceAtLeast(0L)
        val duration = exoPlayer.duration.takeIf { it > 0L } ?: state.totalDurationMs
        if (position <= 0L && duration <= 0L) return
        val storyId = state.storyId
        serviceScope.launch {
            runCatching { progressRepository.save(storyId, position, duration) }
        }
    }

    /**
     * 服务销毁前保存进度：不能再用 serviceScope（随后会被 cancel），
     * 否则最后一次断点进度会丢失。
     */
    private fun savePlaybackPositionOnExit() {
        val state = playbackState.value
        if (state.storyId == 0L || state.isAmbientOnly) return
        if (exoPlayer.playbackState == Player.STATE_IDLE) return
        val position = exoPlayer.currentPosition.coerceAtLeast(0L)
        val duration = exoPlayer.duration.takeIf { it > 0L } ?: state.totalDurationMs
        if (position <= 0L && duration <= 0L) return
        val storyId = state.storyId
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { progressRepository.save(storyId, position, duration) }
        }
    }

    fun updateCoverImagePath(path: String?) {
        updateState { it.copy(coverImagePath = path) }
    }

    fun setGeneratingCover(generating: Boolean) {
        updateState { it.copy(isGeneratingCover = generating) }
    }

    fun togglePlayPause() {
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        } else {
            // 纯声景没有选择任何音轨时，不启动无意义的静音循环
            if (playbackState.value.isAmbientOnly && playbackState.value.ambientMix.isEmpty()) return
            resetFadeIfTimerFinished()
            // 定时已归零后手动续播：重新挂上上次选择的定时，避免整夜无人叫停
            if (SleepTimerPolicy.shouldRearmAfterExpiry(
                    isTimerActive = playbackState.value.isTimerActive,
                    cancelledByUser = timerCancelledByUser,
                    expiredRecently = timerExpiredRecently
                )
            ) {
                startSleepTimer(lastRequestedTimerMinutes.coerceAtLeast(1))
            }
            if (exoPlayer.playbackState == Player.STATE_ENDED) {
                exoPlayer.seekTo(0L)
            }
            exoPlayer.play()
        }
    }

    fun pause() {
        exoPlayer.pause()
    }

    fun resume() {
        resetFadeIfTimerFinished()
        if (SleepTimerPolicy.shouldRearmAfterExpiry(
                isTimerActive = playbackState.value.isTimerActive,
                cancelledByUser = timerCancelledByUser,
                expiredRecently = timerExpiredRecently
            )
        ) {
            startSleepTimer(lastRequestedTimerMinutes.coerceAtLeast(1))
        }
        if (exoPlayer.playbackState == Player.STATE_ENDED) {
            exoPlayer.seekTo(0L)
        }
        exoPlayer.play()
    }

    /**
     * 睡眠定时把音量降到 0 后，用户再次手动播放时应恢复正常音量与基础语速，
     * 否则会出现“播放了但完全没声音”的错觉。
     */
    private fun resetFadeIfTimerFinished() {
        val state = playbackState.value
        if (!state.isTimerActive && fadeMultiplier < 1.0f) {
            fadeMultiplier = 1.0f
            fadeSpeedMultiplier = 1.0f
            exoPlayer.volume = 1.0f
            applyPlaybackSpeed()
            applyAmbientVolume()
            updateVolumeState()
        }
    }

    fun seekTo(positionMs: Long) {
        val targetPos = positionMs.coerceAtLeast(0L)
        exoPlayer.seekTo(targetPos)
        updateState { it.copy(currentPositionMs = targetPos) }
        savePlaybackPosition()
    }

    fun setPlaybackMode(mode: PlaybackMode) {
        appConfig.setPlaybackMode(mode.name)
        updateState { it.copy(playbackMode = mode) }
        applyExoPlayerRepeatMode(mode)
    }

    fun cyclePlaybackMode(): PlaybackMode {
        val current = playbackState.value.playbackMode
        val next = when (current) {
            PlaybackMode.SEQUENTIAL -> PlaybackMode.SHUFFLE
            PlaybackMode.SHUFFLE -> PlaybackMode.REPEAT_ONE
            PlaybackMode.REPEAT_ONE -> PlaybackMode.STOP_AFTER_CURRENT
            PlaybackMode.STOP_AFTER_CURRENT -> PlaybackMode.SEQUENTIAL
        }
        setPlaybackMode(next)
        return next
    }

    private fun applyExoPlayerRepeatMode(mode: PlaybackMode) {
        exoPlayer.repeatMode = if (mode == PlaybackMode.REPEAT_ONE) {
            Player.REPEAT_MODE_ONE
        } else {
            Player.REPEAT_MODE_OFF
        }
    }

    fun setAmbientSound(ambient: AmbientSound) {
        if (ambient == AmbientSound.OFF || ambient.assetName == null) {
            applyAmbientMix(emptyMap(), persist = true)
        } else {
            val volume = playbackState.value.ambientVolume.coerceAtLeast(0.15f)
            applyAmbientMix(mapOf(ambient.id to volume), persist = true)
        }
    }

    fun setAmbientVolume(volume: Float) {
        val clamped = volume.coerceIn(0.0f, 1.0f)
        val current = playbackState.value
        val mix = if (current.ambientMix.size == 1) {
            current.ambientMix.mapValues { clamped }
        } else {
            current.ambientMix
        }
        applyAmbientMix(mix, persist = true)
        updateState { it.copy(ambientVolume = clamped) }
    }

    /**
     * 设置任意多轨混音。传入空 Map 表示关闭背景音。
     * persist = false 用于滑杆拖动中的实时试听，松手后再落盘，避免每帧写加密配置。
     */
    fun setAmbientMix(mix: Map<String, Float>, persist: Boolean = true) {
        applyAmbientMix(mix, persist = persist)
    }

    /**
     * 独立调整某一轨的音量；音量低于可闻阈值时自动移除该轨。
     */
    fun setAmbientTrack(trackId: String, volume: Float) {
        val mix = playbackState.value.ambientMix.toMutableMap()
        if (volume < AmbientMix.MIN_AUDIBLE_VOLUME) {
            mix.remove(trackId)
        } else {
            mix[trackId] = volume.coerceIn(0f, 1f)
        }
        applyAmbientMix(mix, persist = true)
    }

    private fun applyAmbientMix(mix: Map<String, Float>, persist: Boolean) {
        val safeMix = AmbientMix.sanitize(mix)
        val desiredSounds = safeMix.keys.mapNotNull { AmbientSound.fromId(it) }

        // 停止并释放不再使用的音轨
        ambientPlayers.keys
            .filter { it !in desiredSounds }
            .toList()
            .forEach { sound ->
                ambientPlayers.remove(sound)?.run {
                    pause()
                    clearMediaItems()
                    release()
                }
            }

        // 创建或更新当前音轨
        desiredSounds.forEach { sound ->
            val player = ambientPlayers.getOrPut(sound) { createAmbientPlayer() }
            if (player.mediaItemCount == 0) {
                player.setMediaItem(MediaItem.fromUri("asset:///${sound.assetName}"))
                player.prepare()
            }
            player.volume = (safeMix[sound.id] ?: 0f) * fadeMultiplier
            if (exoPlayer.isPlaying) {
                player.play()
            } else {
                player.pause()
            }
        }

        val first = desiredSounds.firstOrNull()
        updateState {
            it.copy(
                ambientMix = safeMix,
                ambientSound = first ?: AmbientSound.OFF,
                ambientVolume = first?.let { sound -> safeMix[sound.id] } ?: it.ambientVolume
            )
        }
        if (persist) {
            appConfig.setAmbientMix(safeMix)
        }

        // 纯声景模式下清空全部音轨：停止静音载体与唤醒锁，避免后台空转。
        if (playbackState.value.isAmbientOnly) {
            if (safeMix.isEmpty()) {
                ambientPausedForEmptyMix = true
                if (exoPlayer.isPlaying) exoPlayer.pause()
                pauseAmbientPlayers()
                releaseWakeLock()
                updateState { it.copy(isPlaying = false) }
                updateNotification("月眠声景", isPlaying = false)
            } else if (ambientPausedForEmptyMix) {
                ambientPausedForEmptyMix = false
                resetFadeIfTimerFinished()
                if (exoPlayer.playbackState != Player.STATE_IDLE) {
                    exoPlayer.play()
                }
                updateState { it.copy(isPlaying = true) }
                updateNotification("月眠声景", isPlaying = true)
            }
        }
    }

    private fun createAmbientPlayer(): ExoPlayer {
        return ExoPlayer.Builder(this)
            .setAudioAttributes(ambientAudioAttributes, false)
            .build().apply {
                repeatMode = Player.REPEAT_MODE_ONE
            }
    }

    private fun applyAmbientVolume() {
        val state = playbackState.value
        ambientPlayers.forEach { (sound, player) ->
            val trackVolume = state.ambientMix[sound.id] ?: 0f
            player.volume = (trackVolume * fadeMultiplier).coerceIn(0.0f, 1.0f)
        }
    }

    private fun getSystemVolumeRatio(): Float {
        return try {
            val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max > 0) cur.toFloat() / max.toFloat() else 1.0f
        } catch (e: Exception) {
            1.0f
        }
    }

    private fun updateVolumeState() {
        val sysRatio = getSystemVolumeRatio()
        val effectiveVol = sysRatio * fadeMultiplier
        updateState {
            it.copy(
                systemVolumeRatio = sysRatio,
                currentVolume = effectiveVol
            )
        }
    }

    fun playNext() {
        val current = playbackState.value
        if (current.isAmbientOnly) return
        if (current.playbackMode == PlaybackMode.SHUFFLE) {
            playRandomStory()
        } else {
            // 手动切歌立即响应，不再等待自动连播的 1.5 秒呼吸间隔
            playNextStoryInSequence(immediate = true)
        }
    }

    fun playPrevious() {
        val current = playbackState.value
        if (current.isAmbientOnly) return
        if (current.playbackMode == PlaybackMode.SHUFFLE) {
            playRandomStory()
        } else {
            playPreviousStoryInSequence()
        }
    }

    /**
     * 如果当前故事属于「今晚故事单」，返回队列 ID 和当前下标；否则返回 null。
     */
    private suspend fun queueSnapshot(): Pair<List<Long>, Int>? {
        val ids = queueRepository.getStoryIdsSync()
        if (ids.isEmpty()) return null
        val index = PlaybackQueuePolicy.indexOf(ids, playbackState.value.storyId)
        return if (index >= 0) ids to index else null
    }

    private suspend fun playStoryFromLibrary(story: Story, autoPlay: Boolean): Boolean {
        // 睡眠定时刚归零时的自动连播：尊重「该睡了」，直接结束而不是再播下一篇
        if (SleepTimerPolicy.shouldBlockAutoAdvance(
                expiredRecently = timerExpiredRecently,
                isTimerActive = playbackState.value.isTimerActive
            )
        ) {
            return false
        }
        val defaultVoice = voiceRepository.getDefaultVoiceProfileSync() ?: return false
        return try {
            // 未缓存时先给出「正在准备」状态，避免连播切歌时几秒静默像卡死
            val needSynthesis = !storyRepository.hasCachedAudio(story, defaultVoice)
            if (needSynthesis) {
                setPreparing(story.title)
            }
            val filePath = storyRepository.getOrSynthesizeStoryAudio(story, defaultVoice)
            storyRepository.recordStoryPlayed(story.id)
            setPreparing(null)
            playStoryAudio(
                storyId = story.id,
                storyTitle = story.title,
                storyContent = StorySpeechHelper.buildFullSpokenStoryText(story.title, story.content),
                localFilePath = filePath,
                autoPlay = autoPlay,
                viaAutoAdvance = true
            )
            true
        } catch (e: Exception) {
            setPreparing(null)
            false
        }
    }

    private fun setPreparing(title: String?) {
        updateState {
            it.copy(
                isPreparingStory = title != null,
                preparingStoryTitle = title ?: ""
            )
        }
        if (title != null) {
            updateNotification("正在准备《$title》", isPlaying = false)
        }
    }

    private suspend fun playQueueStoryAt(ids: List<Long>, index: Int): Boolean {
        if (index !in ids.indices) return false
        val story = storyRepository.getStoryById(ids[index]) ?: return false
        return playStoryFromLibrary(story, autoPlay = true)
    }

    private fun playRandomStory() {
        manualSkipJob?.cancel()
        manualSkipJob = serviceScope.launch {
            val current = playbackState.value
            val queueIds = queueRepository.getStoryIdsSync()
            val inQueue = PlaybackQueuePolicy.indexOf(queueIds, current.storyId) >= 0
            val candidates = if (queueIds.isNotEmpty() && inQueue) {
                queueIds
                    .filter { it != current.storyId }
                    .mapNotNull { storyRepository.getStoryById(it) }
            } else {
                val allStories = storyRepository.getAllStoriesSync()
                if (allStories.size > 1) {
                    allStories.filter { it.id != current.storyId }
                } else {
                    allStories
                }
            }
            if (candidates.isEmpty()) return@launch
            candidates.shuffled().forEach { story ->
                if (playStoryFromLibrary(story, autoPlay = true)) return@launch
            }
        }
    }

    private fun playPreviousStoryInSequence() {
        manualSkipJob?.cancel()
        manualSkipJob = serviceScope.launch {
            val queue = queueSnapshot()
            if (queue != null) {
                val (ids, index) = queue
                var cursor = index
                var attempts = 0
                while (attempts < ids.size) {
                    val previous = PlaybackQueuePolicy.previousIndex(cursor, ids.size) ?: break
                    if (playQueueStoryAt(ids, previous)) return@launch
                    cursor = previous
                    attempts++
                }
                stopPlaybackAtEnd()
                return@launch
            }

            val current = playbackState.value
            val allStories = storyRepository.getAllStoriesSync()
            if (allStories.isEmpty()) return@launch

            val currentIndex = allStories.indexOfFirst { it.id == current.storyId }
            val prevIndex = if (currentIndex > 0) currentIndex - 1 else allStories.lastIndex
            val prevStory = allStories[prevIndex]
            if (!playStoryFromLibrary(prevStory, autoPlay = true)) {
                stopPlaybackAtEnd()
            }
        }
    }

    private fun handlePlaybackEnded() {
        val current = playbackState.value
        if (current.storyId != 0L) {
            serviceScope.launch { runCatching { progressRepository.clear(current.storyId) } }
        }

        when (current.playbackMode) {
            PlaybackMode.REPEAT_ONE -> {
                exoPlayer.seekTo(0)
                exoPlayer.play()
            }
            PlaybackMode.STOP_AFTER_CURRENT -> {
                // 从「今晚故事单」开播时，即使全局模式是播完即止，也应把故事单顺序播完；
                // 只有最后一篇播完才停止。
                serviceScope.launch {
                    val queue = queueSnapshot()
                    val hasNext = queue != null &&
                        PlaybackQueuePolicy.nextIndex(queue.second, queue.first.size) != null
                    if (hasNext) {
                        playNextStoryInSequence()
                    } else {
                        stopPlaybackAtEnd()
                    }
                }
            }
            PlaybackMode.SHUFFLE -> {
                if (current.isTimerActive && current.remainingTimerSeconds <= 0) {
                    exoPlayer.pause()
                    pauseAmbientPlayers()
                    updateState { it.copy(isPlaying = false) }
                    finalizePlaybackSession("TIMER")
                    return
                }
                serviceScope.launch {
                    delay(1500L)
                    playRandomStory()
                }
            }
            PlaybackMode.SEQUENTIAL -> {
                if (current.isTimerActive && current.remainingTimerSeconds <= 0) {
                    exoPlayer.pause()
                    pauseAmbientPlayers()
                    updateState { it.copy(isPlaying = false) }
                    finalizePlaybackSession("TIMER")
                    return
                }
                playNextStoryInSequence()
            }
        }
    }

    private fun playNextStoryInSequence(immediate: Boolean = false) {
        manualSkipJob?.cancel()
        manualSkipJob = serviceScope.launch {
            if (!immediate) delay(1500L) // 自然播完后的睡前呼吸间隔

            val queue = queueSnapshot()
            if (queue != null) {
                val (ids, index) = queue
                var cursor = index
                var attempts = 0
                while (attempts < ids.size) {
                    val next = PlaybackQueuePolicy.nextIndex(cursor, ids.size) ?: break
                    if (playQueueStoryAt(ids, next)) return@launch
                    cursor = next
                    attempts++
                }
                stopPlaybackAtEnd()
                return@launch
            }

            val current = playbackState.value
            val allStories = storyRepository.getAllStoriesSync()
            if (allStories.isEmpty()) {
                stopPlaybackAtEnd()
                return@launch
            }

            val currentIndex = allStories.indexOfFirst { it.id == current.storyId }
            val startIndex = if (currentIndex >= 0) currentIndex else -1
            for (offset in 1..allStories.size) {
                val nextIndex = ((startIndex + offset) % allStories.size + allStories.size) % allStories.size
                if (playStoryFromLibrary(allStories[nextIndex], autoPlay = true)) return@launch
            }
            stopPlaybackAtEnd()
        }
    }

    private fun stopPlaybackAtEnd() {
        val current = playbackState.value
        exoPlayer.pause()
        pauseAmbientPlayers()
        val endPosition = maxOf(
            current.totalDurationMs,
            exoPlayer.duration,
            exoPlayer.currentPosition
        ).coerceAtLeast(0L)
        updateState {
            it.copy(
                isPlaying = false,
                currentPositionMs = endPosition,
                totalDurationMs = maxOf(it.totalDurationMs, endPosition).coerceAtLeast(0L)
            )
        }
        finalizePlaybackSession("COMPLETED")
        updateNotification(current.storyTitle, isPlaying = false)
    }

    /**
     * 渐衰与减速协程控制器规范实现：
     * - 提供方法 startSleepTimer(durationMinutes: Int)
     * - 在剩余时间进入最后 5 分钟（300秒）阶段启动步进衰减循环（每 20 秒执行一次）：
     *   - 语速递减：从 1.0f 平滑降至 0.75f（步进：-0.02f）
     *   - 音量衰减：从 1.0f 降至 0.0f（按照对数声学衰减曲线）
     * - 倒计时归零：调用 player.pause()，停止前台通知，释放唤醒锁
     */
    fun startSleepTimer(durationMinutes: Int) {
        sleepTimerJob?.cancel()
        val safeMinutes = durationMinutes.coerceAtLeast(1)
        val totalSeconds = safeMinutes * 60
        sessionTimerMinutes = safeMinutes
        lastRequestedTimerMinutes = safeMinutes
        timerCancelledByUser = false
        timerExpiredRecently = false

        fadeMultiplier = 1.0f
        fadeSpeedMultiplier = 1.0f
        exoPlayer.volume = 1.0f
        applyPlaybackSpeed()
        applyAmbientVolume()
        updateVolumeState()

        updateState {
            it.copy(
                remainingTimerSeconds = totalSeconds,
                selectedTimerMinutes = durationMinutes,
                isTimerActive = true,
                currentSpeed = PlaybackSpeedPolicy.effectiveSpeed(basePlaybackSpeed, 1.0f)
            )
        }

        sleepTimerJob = serviceScope.launch {
            var remaining = totalSeconds
            val fadeThresholdSeconds = AppConstants.SLEEP_TIMER_FADE_START_MINUTES * 60

            var fadeSpeed = 1.0f

            while (isActive && remaining > 0) {
                delay(1000L)

                // 当伴读音频暂停时，倒计时同步挂起暂停，不扣减剩余秒数
                if (!exoPlayer.isPlaying) {
                    continue
                }

                remaining--

                // 判断是否已进入最后 5 分钟阶段
                if (remaining <= fadeThresholdSeconds) {
                    val fadeTimePassed = fadeThresholdSeconds - remaining

                    // 1. 每 20 秒执行一次语速步进递减
                    if (fadeTimePassed > 0 && fadeTimePassed % (AppConstants.STEP_INTERVAL_MILLIS / 1000) == 0L) {
                        fadeSpeed = max(
                            AppConstants.PLAYBACK_SPEED_TARGET,
                            fadeSpeed - AppConstants.PLAYBACK_SPEED_STEP
                        )
                        fadeSpeedMultiplier = fadeSpeed
                        applyPlaybackSpeed()
                    }

                    // 2. 按照对数声学衰减曲线平滑降低音量至 0.0f
                    val p = (remaining.toDouble() / fadeThresholdSeconds.toDouble()).coerceIn(0.0, 1.0)
                    val logarithmicVolume = (ln(1.0 + 9.0 * p) / ln(10.0)).toFloat().coerceIn(0.0f, 1.0f)

                    fadeMultiplier = logarithmicVolume
                    exoPlayer.volume = fadeMultiplier
                    applyAmbientVolume()
                    updateVolumeState()

                    updateState {
                        it.copy(
                            remainingTimerSeconds = remaining,
                            currentSpeed = PlaybackSpeedPolicy.effectiveSpeed(basePlaybackSpeed, fadeSpeedMultiplier)
                        )
                    }
                } else {
                    updateVolumeState()
                    updateState { it.copy(remainingTimerSeconds = remaining) }
                }
            }

            // 倒计时归零：执行停播与资源释放
            if (remaining <= 0) {
                fadeMultiplier = 0.0f
                timerExpiredRecently = true
                exoPlayer.pause()
                pauseAmbientPlayers()
                releaseWakeLock()
                finalizePlaybackSession("TIMER")
                updateVolumeState()
                updateState {
                    it.copy(
                        remainingTimerSeconds = 0,
                        selectedTimerMinutes = 0,
                        isTimerActive = false,
                        isPlaying = false
                    )
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        // 用户主动取消：本次播放会话内不再自动挂默认定时
        timerCancelledByUser = true
        timerExpiredRecently = false
        fadeMultiplier = 1.0f
        fadeSpeedMultiplier = 1.0f
        exoPlayer.volume = 1.0f
        applyPlaybackSpeed()
        applyAmbientVolume()
        updateVolumeState()
        updateState {
            it.copy(
                remainingTimerSeconds = 0,
                selectedTimerMinutes = 0,
                isTimerActive = false,
                currentSpeed = PlaybackSpeedPolicy.effectiveSpeed(basePlaybackSpeed, 1.0f)
            )
        }
    }

    private fun pauseAmbientPlayers() {
        ambientPlayers.values.forEach { it.pause() }
    }

    // ---------------- 睡前陪伴会话统计 ----------------

    private fun startOrResumePlaybackSession() {
        val state = playbackState.value
        if (state.storyId == 0L) return
        val now = System.currentTimeMillis()
        if (sessionStartedAt == 0L) {
            sessionStartedAt = now
            sessionStoryId = state.storyId
            sessionStoryTitle = state.storyTitle
            sessionChildProfileId = activeChildProfileId
            sessionListenedMs = 0L
            sessionTimerMinutes = state.selectedTimerMinutes
            sessionPausedAt = 0L
            if (bedtimeStartedAt == 0L) bedtimeStartedAt = now
        } else if (sessionPausedAt > 0L && now - sessionPausedAt > 15 * 60 * 1000L) {
            // 中途离开超过 15 分钟，重新开始计算这次入睡的起点
            bedtimeStartedAt = now
        }
        sessionPausedAt = 0L
        if (sessionLastResumeAt == 0L) {
            sessionLastResumeAt = now
            if (bedtimeStartedAt == 0L) bedtimeStartedAt = now
        }
    }

    private fun pausePlaybackSessionAccumulation() {
        if (sessionStartedAt == 0L || sessionLastResumeAt == 0L) return
        val now = System.currentTimeMillis()
        sessionListenedMs += (now - sessionLastResumeAt).coerceAtLeast(0L)
        sessionLastResumeAt = 0L
        sessionPausedAt = now
    }

    private fun finalizePlaybackSession(endedBy: String) {
        if (sessionStartedAt == 0L) return
        pausePlaybackSessionAccumulation()

        val endedAt = System.currentTimeMillis()
        val listenedSeconds = (sessionListenedMs / 1000L).toInt().coerceAtLeast(0)
        val bedtimeStart = bedtimeStartedAt.takeIf { it > 0L } ?: sessionStartedAt
        val estimatedSleepSeconds = if (endedBy == "TIMER") {
            val elapsedSeconds = ((endedAt - bedtimeStart) / 1000L).toInt().coerceAtLeast(0)
            maxOf(listenedSeconds, elapsedSeconds).coerceAtMost(12 * 60 * 60)
        } else {
            0
        }

        if (listenedSeconds >= 5) {
            val session = SleepSession(
                storyId = sessionStoryId,
                storyTitle = sessionStoryTitle,
                startedAt = sessionStartedAt,
                endedAt = endedAt,
                listenedSeconds = listenedSeconds,
                estimatedSleepSeconds = estimatedSleepSeconds,
                endedBy = endedBy,
                selectedTimerMinutes = sessionTimerMinutes,
                childProfileId = sessionChildProfileId
            )
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { sleepStatsRepository.recordSession(session) }
            }
        }

        sessionStartedAt = 0L
        sessionStoryId = 0L
        sessionStoryTitle = ""
        sessionChildProfileId = 0L
        sessionListenedMs = 0L
        sessionLastResumeAt = 0L
        sessionPausedAt = 0L
        sessionTimerMinutes = 0
        if (endedBy == "TIMER") {
            bedtimeStartedAt = 0L
        }
    }

    private fun startProgressTracker() {
        progressTrackingJob?.cancel()
        progressTrackingJob = serviceScope.launch {
            while (isActive && exoPlayer.isPlaying) {
                updateVolumeState()
                if (!playbackState.value.isAmbientOnly) {
                    updateState {
                        it.copy(
                            currentPositionMs = exoPlayer.currentPosition,
                            totalDurationMs = max(0L, exoPlayer.duration)
                        )
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastProgressSaveAt >= 5_000L) {
                        lastProgressSaveAt = now
                        savePlaybackPosition()
                    }
                }
                delay(100L) // 平滑且高频追踪朗读进度（100ms 毫秒级声画同步）
            }
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(4 * 60 * 60 * 1000L) // 最长保护 4 小时
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                AppConstants.PLAYBACK_NOTIFICATION_CHANNEL_ID,
                "儿童睡前伴读播放服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "播放睡前故事与伴读音频"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(title: String, isPlaying: Boolean) {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val toggleIntent = PendingIntent.getBroadcast(
            this,
            1,
            Intent(ACTION_TOGGLE_PLAYBACK).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val toggleIcon = Icon.createWithResource(
            this,
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
        val favoriteIcon = Icon.createWithResource(
            this,
            if (currentStoryFavorite) {
                androidx.media3.session.R.drawable.media3_icon_heart_filled
            } else {
                androidx.media3.session.R.drawable.media3_icon_heart_unfilled
            }
        )
        val favoriteIntent = PendingIntent.getBroadcast(
            this,
            2,
            Intent(ACTION_TOGGLE_FAVORITE).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val isStoryNotification = playbackState.value.storyId != 0L &&
            !playbackState.value.isAmbientOnly

        val mediaStyle = Notification.MediaStyle()
        if (isStoryNotification) {
            mediaStyle.setShowActionsInCompactView(0, 1)
        } else {
            mediaStyle.setShowActionsInCompactView(0)
        }
        mediaSession?.platformToken?.let { token -> mediaStyle.setMediaSession(token) }

        val builder = Notification.Builder(this, AppConstants.PLAYBACK_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_moon)
            .setContentTitle("月眠 · 睡前伴读")
            .setContentText(if (title.isNotBlank()) "正在播放: $title" else "伴读已就绪")
            .setContentIntent(pendingIntent)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setStyle(mediaStyle)
            .addAction(
                Notification.Action.Builder(
                    toggleIcon,
                    if (isPlaying) "暂停" else "播放",
                    toggleIntent
                ).build()
            )

        if (isStoryNotification) {
            builder.addAction(
                Notification.Action.Builder(
                    favoriteIcon,
                    if (currentStoryFavorite) "取消收藏" else "收藏",
                    favoriteIntent
                ).build()
            )
        }

        val notification = builder.build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                androidx.core.app.ServiceCompat.startForeground(
                    this,
                    AppConstants.PLAYBACK_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(AppConstants.PLAYBACK_NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // 防御 Android 14+ 后台启动前台服务时的权限或安全异常
        }
        ensureFavoriteCommandAvailable()
    }

    override fun onDestroy() {
        savePlaybackPositionOnExit()
        finalizePlaybackSession("SERVICE_STOP")
        super.onDestroy()
        sleepTimerJob?.cancel()
        progressTrackingJob?.cancel()
        manualSkipJob?.cancel()
        prefetchJob?.cancel()
        // 服务被销毁时关闭协程作用域，避免 Room 观察者与定时协程继续泄漏运行
        serviceScope.cancel()
        releaseWakeLock()
        try {
            unregisterReceiver(volumeReceiver)
        } catch (e: Exception) {
            // ignore
        }
        try {
            unregisterReceiver(mediaActionReceiver)
        } catch (e: Exception) {
            // ignore
        }
        ambientPlayers.values.forEach { it.release() }
        ambientPlayers.clear()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        // 服务销毁后清空对外状态，避免首页迷你播放器继续显示已失效的曲目
        updateState { StoryPlaybackState() }
    }

    companion object {
        private const val ACTION_TOGGLE_PLAYBACK = "com.bedtime.dreamtales.action.TOGGLE_PLAYBACK"
        private const val ACTION_TOGGLE_FAVORITE = "com.bedtime.dreamtales.action.TOGGLE_FAVORITE"

        private val _playbackState = MutableStateFlow(StoryPlaybackState())
        val playbackState: StateFlow<StoryPlaybackState> = _playbackState.asStateFlow()

        private fun updateState(updater: (StoryPlaybackState) -> StoryPlaybackState) {
            _playbackState.value = updater(_playbackState.value)
        }
    }
}
