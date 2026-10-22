package com.bedtime.dreamtales.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.bedtime.dreamtales.MainActivity
import com.bedtime.dreamtales.core.StorySpeechHelper
import com.bedtime.dreamtales.core.constants.AppConfig
import com.bedtime.dreamtales.core.constants.AppConstants
import com.bedtime.dreamtales.data.repository.StoryRepository
import com.bedtime.dreamtales.data.repository.VoiceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.ln
import kotlin.math.max

enum class PlaybackMode(val title: String, val iconEmoji: String) {
    SEQUENTIAL("顺序连播", "🔀"),
    REPEAT_ONE("单曲循环", "🔁"),
    STOP_AFTER_CURRENT("播完即止", "⏹️")
}

data class StoryPlaybackState(
    val storyId: Long = 0L,
    val storyTitle: String = "",
    val storyContent: String = "",
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val remainingTimerSeconds: Int = 0,
    val selectedTimerMinutes: Int = 0,
    val isTimerActive: Boolean = false,
    val currentVolume: Float = 1.0f,
    val currentSpeed: Float = 1.0f,
    val playbackMode: PlaybackMode = PlaybackMode.SEQUENTIAL
)

class StoryPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var exoPlayer: ExoPlayer
    private var wakeLock: PowerManager.WakeLock? = null

    private val binder = PlaybackBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private var sleepTimerJob: Job? = null
    private var progressTrackingJob: Job? = null

    private val storyRepository by lazy { StoryRepository(applicationContext) }
    private val voiceRepository by lazy { VoiceRepository(applicationContext) }
    private val appConfig by lazy { AppConfig.getInstance(applicationContext) }

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

        // 2. 初始化 ExoPlayer
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .setUsage(C.USAGE_MEDIA)
            .build()

        exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        val savedModeName = appConfig.getPlaybackMode()
        val initialMode = try {
            PlaybackMode.valueOf(savedModeName)
        } catch (e: Exception) {
            PlaybackMode.SEQUENTIAL
        }
        updateState { it.copy(playbackMode = initialMode) }
        applyExoPlayerRepeatMode(initialMode)

        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateState { it.copy(isPlaying = isPlaying) }
                if (isPlaying) {
                    acquireWakeLock()
                    startProgressTracker()
                    updateNotification(playbackState.value.storyTitle, isPlaying = true)
                } else {
                    releaseWakeLock()
                    updateNotification(playbackState.value.storyTitle, isPlaying = false)
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    updateState {
                        it.copy(totalDurationMs = max(0L, exoPlayer.duration))
                    }
                } else if (state == Player.STATE_ENDED) {
                    handlePlaybackEnded()
                }
            }
        })

        // 3. 构建 MediaSession
        val sessionIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            sessionIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(pendingIntent)
            .build()

        createNotificationChannel()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
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
        autoPlay: Boolean = false
    ) {
        val file = File(localFilePath)
        if (!file.exists() || file.length() == 0L) {
            throw IllegalArgumentException("本地故事音频文件不存在: $localFilePath")
        }

        // 如果当前已在播放同一个故事，避免从头重新加载与重置倒计时
        val currentState = playbackState.value
        if (currentState.storyId == storyId && exoPlayer.playbackState != Player.STATE_IDLE && exoPlayer.playbackState != Player.STATE_ENDED) {
            updateState { it.copy(storyContent = storyContent) }
            if (autoPlay && !exoPlayer.isPlaying) {
                exoPlayer.play()
            }
            if (!currentState.isTimerActive || currentState.remainingTimerSeconds <= 0) {
                startSleepTimer(30)
            }
            updateNotification(storyTitle, isPlaying = exoPlayer.isPlaying)
            return
        }

        exoPlayer.volume = 1.0f
        exoPlayer.playbackParameters = PlaybackParameters(1.0f)

        updateState {
            it.copy(
                storyId = storyId,
                storyTitle = storyTitle,
                storyContent = storyContent,
                currentVolume = 1.0f,
                currentSpeed = 1.0f,
                isPlaying = autoPlay
            )
        }

        val mediaItem = MediaItem.fromUri(file.toURI().toString())
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        if (autoPlay) {
            exoPlayer.play()
        } else {
            exoPlayer.pause()
        }

        // 默认自动准备 30 分钟伴读倒计时（处于挂起等待开播态）
        if (!currentState.isTimerActive || currentState.remainingTimerSeconds <= 0) {
            startSleepTimer(30)
        }

        updateNotification(storyTitle, isPlaying = autoPlay)
    }

    fun togglePlayPause() {
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        } else {
            exoPlayer.play()
        }
    }

    fun pause() {
        exoPlayer.pause()
    }

    fun resume() {
        exoPlayer.play()
    }

    fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
    }

    fun setPlaybackMode(mode: PlaybackMode) {
        appConfig.setPlaybackMode(mode.name)
        updateState { it.copy(playbackMode = mode) }
        applyExoPlayerRepeatMode(mode)
    }

    fun cyclePlaybackMode(): PlaybackMode {
        val current = playbackState.value.playbackMode
        val next = when (current) {
            PlaybackMode.SEQUENTIAL -> PlaybackMode.REPEAT_ONE
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

    fun playNext() {
        playNextStoryInSequence()
    }

    fun playPrevious() {
        serviceScope.launch {
            val current = playbackState.value
            val allStories = storyRepository.getAllStoriesSync()
            if (allStories.isEmpty()) return@launch

            val currentIndex = allStories.indexOfFirst { it.id == current.storyId }
            val prevIndex = if (currentIndex > 0) {
                currentIndex - 1
            } else {
                allStories.lastIndex
            }
            val prevStory = allStories[prevIndex]
            val defaultVoice = voiceRepository.getDefaultVoiceProfileSync()
            if (defaultVoice != null) {
                try {
                    val filePath = storyRepository.getOrSynthesizeStoryAudio(prevStory, defaultVoice)
                    storyRepository.recordStoryPlayed(prevStory.id)
                    playStoryAudio(
                        storyId = prevStory.id,
                        storyTitle = prevStory.title,
                        storyContent = StorySpeechHelper.buildFullSpokenStoryText(prevStory.title, prevStory.content),
                        localFilePath = filePath,
                        autoPlay = true
                    )
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
    }

    private fun handlePlaybackEnded() {
        val current = playbackState.value
        when (current.playbackMode) {
            PlaybackMode.REPEAT_ONE -> {
                exoPlayer.seekTo(0)
                exoPlayer.play()
            }
            PlaybackMode.STOP_AFTER_CURRENT -> {
                exoPlayer.pause()
                exoPlayer.seekTo(0)
                updateState { it.copy(isPlaying = false, currentPositionMs = 0L) }
                updateNotification(current.storyTitle, isPlaying = false)
            }
            PlaybackMode.SEQUENTIAL -> {
                if (current.isTimerActive && current.remainingTimerSeconds <= 0) {
                    exoPlayer.pause()
                    updateState { it.copy(isPlaying = false) }
                    return
                }
                playNextStoryInSequence()
            }
        }
    }

    private fun playNextStoryInSequence() {
        serviceScope.launch {
            delay(1500L) // 睡前呼吸间隔 1.5 秒
            val current = playbackState.value
            val allStories = storyRepository.getAllStoriesSync()
            if (allStories.isEmpty()) return@launch

            val currentIndex = allStories.indexOfFirst { it.id == current.storyId }
            val nextIndex = if (currentIndex >= 0 && currentIndex + 1 < allStories.size) {
                currentIndex + 1
            } else {
                0
            }
            val nextStory = allStories[nextIndex]
            val defaultVoice = voiceRepository.getDefaultVoiceProfileSync()
            if (defaultVoice != null) {
                try {
                    val filePath = storyRepository.getOrSynthesizeStoryAudio(nextStory, defaultVoice)
                    storyRepository.recordStoryPlayed(nextStory.id)
                    playStoryAudio(
                        storyId = nextStory.id,
                        storyTitle = nextStory.title,
                        storyContent = StorySpeechHelper.buildFullSpokenStoryText(nextStory.title, nextStory.content),
                        localFilePath = filePath,
                        autoPlay = true
                    )
                } catch (e: Exception) {
                    exoPlayer.pause()
                    updateState { it.copy(isPlaying = false) }
                }
            }
        }
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
        val totalSeconds = durationMinutes * 60

        updateState {
            it.copy(
                remainingTimerSeconds = totalSeconds,
                selectedTimerMinutes = durationMinutes,
                isTimerActive = true,
                currentVolume = 1.0f,
                currentSpeed = 1.0f
            )
        }
        exoPlayer.volume = 1.0f
        exoPlayer.playbackParameters = PlaybackParameters(1.0f)

        sleepTimerJob = serviceScope.launch {
            var remaining = totalSeconds
            val fadeThresholdSeconds = AppConstants.SLEEP_TIMER_FADE_START_MINUTES * 60

            var currentSpeed = AppConstants.PLAYBACK_SPEED_START

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
                        currentSpeed = max(
                            AppConstants.PLAYBACK_SPEED_TARGET,
                            currentSpeed - AppConstants.PLAYBACK_SPEED_STEP
                        )
                        exoPlayer.playbackParameters = PlaybackParameters(currentSpeed)
                    }

                    // 2. 按照对数声学衰减曲线平滑降低音量至 0.0f
                    // p 为声学剩余占比 [0.0, 1.0]
                    val p = (remaining.toDouble() / fadeThresholdSeconds.toDouble()).coerceIn(0.0, 1.0)
                    val logarithmicVolume = (ln(1.0 + 9.0 * p) / ln(10.0)).toFloat().coerceIn(0.0f, 1.0f)
                    exoPlayer.volume = logarithmicVolume

                    updateState {
                        it.copy(
                            remainingTimerSeconds = remaining,
                            currentVolume = logarithmicVolume,
                            currentSpeed = currentSpeed
                        )
                    }
                } else {
                    updateState { it.copy(remainingTimerSeconds = remaining) }
                }
            }

            // 倒计时归零：执行停播与资源释放
            if (remaining <= 0) {
                exoPlayer.pause()
                releaseWakeLock()
                updateState {
                    it.copy(
                        remainingTimerSeconds = 0,
                        selectedTimerMinutes = 0,
                        isTimerActive = false,
                        isPlaying = false,
                        currentVolume = 0.0f
                    )
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        exoPlayer.volume = 1.0f
        exoPlayer.playbackParameters = PlaybackParameters(1.0f)
        updateState {
            it.copy(
                remainingTimerSeconds = 0,
                selectedTimerMinutes = 0,
                isTimerActive = false,
                currentVolume = 1.0f,
                currentSpeed = 1.0f
            )
        }
    }

    private fun startProgressTracker() {
        progressTrackingJob?.cancel()
        progressTrackingJob = serviceScope.launch {
            while (isActive && exoPlayer.isPlaying) {
                updateState {
                    it.copy(
                        currentPositionMs = exoPlayer.currentPosition,
                        totalDurationMs = max(0L, exoPlayer.duration)
                    )
                }
                delay(500L)
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

        val notification = NotificationCompat.Builder(this, AppConstants.PLAYBACK_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("DreamTales 睡前伴读")
            .setContentText(if (title.isNotBlank()) "正在播放: $title" else "伴读已就绪")
            .setContentIntent(pendingIntent)
            .setOngoing(isPlaying)
            .setSilent(true)
            .build()

        startForeground(AppConstants.PLAYBACK_NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        sleepTimerJob?.cancel()
        progressTrackingJob?.cancel()
        releaseWakeLock()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
    }

    companion object {
        private val _playbackState = MutableStateFlow(StoryPlaybackState())
        val playbackState: StateFlow<StoryPlaybackState> = _playbackState.asStateFlow()

        private fun updateState(updater: (StoryPlaybackState) -> StoryPlaybackState) {
            _playbackState.value = updater(_playbackState.value)
        }
    }
}
