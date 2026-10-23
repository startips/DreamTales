package com.bedtime.dreamtales

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bedtime.dreamtales.core.theme.DreamTalesTheme
import com.bedtime.dreamtales.service.FlipSensorManager
import com.bedtime.dreamtales.service.StoryPlaybackService
import com.bedtime.dreamtales.ui.home.HomeScreen
import com.bedtime.dreamtales.ui.home.HomeViewModel
import com.bedtime.dreamtales.ui.navigation.Screen
import com.bedtime.dreamtales.ui.player.SleepPlayerScreen
import com.bedtime.dreamtales.ui.player.SleepPlayerViewModel
import com.bedtime.dreamtales.ui.record.RecordVoiceScreen
import com.bedtime.dreamtales.ui.record.RecordVoiceViewModel
import com.bedtime.dreamtales.ui.splash.SplashScreen

class MainActivity : ComponentActivity() {

    private lateinit var flipSensorManager: FlipSensorManager
    private var playbackService: StoryPlaybackService? = null
    private var isBound = false

    private val homeViewModel: HomeViewModel by viewModels()
    private val recordViewModel: RecordVoiceViewModel by viewModels()
    private val playerViewModel: SleepPlayerViewModel by viewModels()
    private var pendingStoryToPlay: Pair<com.bedtime.dreamtales.data.local.entity.Story, String>? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? StoryPlaybackService.PlaybackBinder
            if (binder != null) {
                playbackService = binder.getService()
                playerViewModel.attachService(binder.getService())
                isBound = true
                pendingStoryToPlay?.let { (story, path) ->
                    playbackService?.playStoryAudio(
                        storyId = story.id,
                        storyTitle = story.title,
                        storyContent = com.bedtime.dreamtales.core.StorySpeechHelper.buildFullSpokenStoryText(story.title, story.content),
                        localFilePath = path,
                        autoPlay = false
                    )
                    pendingStoryToPlay = null
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        flipSensorManager = FlipSensorManager(this)

        // 请求通知权限 (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    101
                )
            }
        }

        // 启动并绑定前台播放服务
        val serviceIntent = Intent(this, StoryPlaybackService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        setContent {
            val themeMode by homeViewModel.themeMode.collectAsState()

            DreamTalesTheme(themeMode = themeMode) {
                val navController = rememberNavController()

                NavHost(
                    navController = navController,
                    startDestination = Screen.Splash.route
                ) {
                    composable(Screen.Splash.route) {
                        SplashScreen(
                            onSplashFinished = {
                                navController.navigate(Screen.Home.route) {
                                    popUpTo(Screen.Splash.route) { inclusive = true }
                                }
                            }
                        )
                    }

                    composable(Screen.Home.route) {
                        HomeScreen(
                            viewModel = homeViewModel,
                            onNavigateToRecord = {
                                navController.navigate(Screen.RecordVoice.route)
                            },
                            onNavigateToPlayer = {
                                navController.navigate(Screen.SleepPlayer.route)
                            },
                            onTogglePlayPause = {
                                playbackService?.togglePlayPause()
                            },
                            onPlayStory = { story, localFilePath ->
                                // autoPlay = false：进入播放器先处于就绪待命模式，用户主动点击后再开播
                                val currentService = playbackService
                                if (currentService != null) {
                                    currentService.playStoryAudio(
                                        storyId = story.id,
                                        storyTitle = story.title,
                                        storyContent = com.bedtime.dreamtales.core.StorySpeechHelper.buildFullSpokenStoryText(story.title, story.content),
                                        localFilePath = localFilePath,
                                        autoPlay = false
                                    )
                                } else {
                                    pendingStoryToPlay = story to localFilePath
                                }
                                navController.navigate(Screen.SleepPlayer.route)
                            }
                        )
                    }

                    composable(Screen.RecordVoice.route) {
                        RecordVoiceScreen(
                            viewModel = recordViewModel,
                            onNavigateBack = {
                                navController.popBackStack()
                            },
                            onVoiceClonedSuccess = {
                                navController.popBackStack()
                            }
                        )
                    }

                    composable(Screen.SleepPlayer.route) {
                        SleepPlayerScreen(
                            viewModel = playerViewModel,
                            flipSensorManager = flipSensorManager,
                            onNavigateBack = {
                                navController.popBackStack()
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 翻转扣屏传感器改由 SleepPlayerScreen 播放界面按需按时启动，避免全局空转耗电
    }

    override fun onPause() {
        super.onPause()
        flipSensorManager.stopListening()
        FlipSensorManager.applyWindowBrightness(this, false)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
