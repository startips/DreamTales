package com.bedtime.dreamtales.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
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
    LULLABY("lullaby", "摇篮音盒", "🎶", "ambient/ambient_lullaby.wav")
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
    val systemVolumeRatio: Float = 1.0f,
    val currentSpeed: Float = 1.0f,
    val playbackMode: PlaybackMode = PlaybackMode.SEQUENTIAL,
    val ambientSound: AmbientSound = AmbientSound.OFF,
    val ambientVolume: Float = 0.25f
)

class StoryPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var exoPlayer: ExoPlayer
    private lateinit var ambientPlayer: ExoPlayer
    private var wakeLock: PowerManager.WakeLock? = null

    private val binder = PlaybackBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private var sleepTimerJob: Job? = null
    private var progressTrackingJob: Job? = null

    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val storyRepository by lazy { StoryRepository(applicationContext) }
    private val voiceRepository by lazy { VoiceRepository(applicationContext) }
    private val appConfig by lazy { AppConfig.getInstance(applicationContext) }

    private var fadeMultiplier: Float = 1.0f

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateVolumeState()
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

        // 3. 初始化 AmbientPlayer（双轨助眠白噪音背景音）
        val ambientAudioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        ambientPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(ambientAudioAttributes, false)
            .build().apply {
                repeatMode = Player.REPEAT_MODE_ONE
            }

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
                    if (playbackState.value.ambientSound != AmbientSound.OFF && ambientPlayer.mediaItemCount > 0) {
                        ambientPlayer.play()
                    }
                } else {
                    releaseWakeLock()
                    updateNotification(playbackState.value.storyTitle, isPlaying = false)
                    ambientPlayer.pause()
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

        // 4. 监听手机硬件系统音量变动
        try {
            val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(volumeReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(volumeReceiver, filter)
            }
        } catch (e: Exception) {
            // ignore
        }
        updateVolumeState()

        // 5. 构建 MediaSession
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

        fadeMultiplier = 1.0f
        exoPlayer.volume = 1.0f
        exoPlayer.playbackParameters = PlaybackParameters(1.0f)
        applyAmbientVolume()
        updateVolumeState()

        updateState {
            it.copy(
                storyId = storyId,
                storyTitle = storyTitle,
                storyContent = storyContent,
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
        updateState { it.copy(ambientSound = ambient) }
        if (ambient == AmbientSound.OFF || ambient.assetName == null) {
            ambientPlayer.stop()
            ambientPlayer.clearMediaItems()
        } else {
            val mediaItem = MediaItem.fromUri("asset:///${ambient.assetName}")
            ambientPlayer.setMediaItem(mediaItem)
            ambientPlayer.prepare()
            applyAmbientVolume()
            if (exoPlayer.isPlaying) {
                ambientPlayer.play()
            }
        }
    }

    fun setAmbientVolume(volume: Float) {
        val clamped = volume.coerceIn(0.0f, 1.0f)
        updateState { it.copy(ambientVolume = clamped) }
        applyAmbientVolume()
    }

    private fun applyAmbientVolume() {
        val state = playbackState.value
        ambientPlayer.volume = (state.ambientVolume * fadeMultiplier).coerceIn(0.0f, 1.0f)
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
        if (current.playbackMode == PlaybackMode.SHUFFLE) {
            playRandomStory()
        } else {
            playNextStoryInSequence()
        }
    }

    fun playPrevious() {
        val current = playbackState.value
        if (current.playbackMode == PlaybackMode.SHUFFLE) {
            playRandomStory()
        } else {
            playPreviousStoryInSequence()
        }
    }

    private fun playRandomStory() {
        serviceScope.launch {
            val current = playbackState.value
            val allStories = storyRepository.getAllStoriesSync()
            if (allStories.isEmpty()) return@launch

            val candidates = if (allStories.size > 1) {
                allStories.filter { it.id != current.storyId }
            } else {
                allStories
            }
            val randomStory = candidates.random()
            val defaultVoice = voiceRepository.getDefaultVoiceProfileSync()
            if (defaultVoice != null) {
                try {
                    val filePath = storyRepository.getOrSynthesizeStoryAudio(randomStory, defaultVoice)
                    storyRepository.recordStoryPlayed(randomStory.id)
                    playStoryAudio(
                        storyId = randomStory.id,
                        storyTitle = randomStory.title,
                        storyContent = StorySpeechHelper.buildFullSpokenStoryText(randomStory.title, randomStory.content),
                        localFilePath = filePath,
                        autoPlay = true
                    )
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
    }

    private fun playPreviousStoryInSequence() {
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
                ambientPlayer.pause()
                exoPlayer.seekTo(0)
                updateState { it.copy(isPlaying = false, currentPositionMs = 0L) }
                updateNotification(current.storyTitle, isPlaying = false)
            }
            PlaybackMode.SHUFFLE -> {
                if (current.isTimerActive && current.remainingTimerSeconds <= 0) {
                    exoPlayer.pause()
                    ambientPlayer.pause()
                    updateState { it.copy(isPlaying = false) }
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
                    ambientPlayer.pause()
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
                    ambientPlayer.pause()
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

        fadeMultiplier = 1.0f
        exoPlayer.volume = 1.0f
        exoPlayer.playbackParameters = PlaybackParameters(1.0f)
        applyAmbientVolume()
        updateVolumeState()

        updateState {
            it.copy(
                remainingTimerSeconds = totalSeconds,
                selectedTimerMinutes = durationMinutes,
                isTimerActive = true,
                currentSpeed = 1.0f
            )
        }

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
                    val p = (remaining.toDouble() / fadeThresholdSeconds.toDouble()).coerceIn(0.0, 1.0)
                    val logarithmicVolume = (ln(1.0 + 9.0 * p) / ln(10.0)).toFloat().coerceIn(0.0f, 1.0f)

                    fadeMultiplier = logarithmicVolume
                    exoPlayer.volume = fadeMultiplier
                    applyAmbientVolume()
                    updateVolumeState()

                    updateState {
                        it.copy(
                            remainingTimerSeconds = remaining,
                            currentSpeed = currentSpeed
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
                exoPlayer.pause()
                ambientPlayer.pause()
                releaseWakeLock()
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
        fadeMultiplier = 1.0f
        exoPlayer.volume = 1.0f
        exoPlayer.playbackParameters = PlaybackParameters(1.0f)
        applyAmbientVolume()
        updateVolumeState()
        updateState {
            it.copy(
                remainingTimerSeconds = 0,
                selectedTimerMinutes = 0,
                isTimerActive = false,
                currentSpeed = 1.0f
            )
        }
    }

    private fun startProgressTracker() {
        progressTrackingJob?.cancel()
        progressTrackingJob = serviceScope.launch {
            while (isActive && exoPlayer.isPlaying) {
                updateVolumeState()
                updateState {
                    it.copy(
                        currentPositionMs = exoPlayer.currentPosition,
                        totalDurationMs = max(0L, exoPlayer.duration)
                    )
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
        try {
            unregisterReceiver(volumeReceiver)
        } catch (e: Exception) {
            // ignore
        }
        if (::ambientPlayer.isInitialized) {
            ambientPlayer.release()
        }
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
