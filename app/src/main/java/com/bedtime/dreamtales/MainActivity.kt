package com.bedtime.dreamtales

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bedtime.dreamtales.core.constants.AppConfig
import com.bedtime.dreamtales.core.theme.DreamTalesTheme
import com.bedtime.dreamtales.core.widget.WidgetProtocol
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
import com.bedtime.dreamtales.widget.DreamTalesWidgetProvider

class MainActivity : ComponentActivity() {

    private lateinit var flipSensorManager: FlipSensorManager
    private var playbackService: StoryPlaybackService? = null
    private var isBound = false

    private val homeViewModel: HomeViewModel by viewModels()
    private val recordViewModel: RecordVoiceViewModel by viewModels()
    private val playerViewModel: SleepPlayerViewModel by viewModels()
    private var pendingStoryToPlay: Triple<com.bedtime.dreamtales.data.local.entity.Story, String, Boolean>? = null
    private var widgetPendingStoryId by mutableStateOf<Long?>(null)
    private var appNavController: NavHostController? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? StoryPlaybackService.PlaybackBinder
            if (binder != null) {
                playbackService = binder.getService()
                playerViewModel.attachService(binder.getService())
                isBound = true
                pendingStoryToPlay?.let { (story, path, autoPlay) ->
                    playbackService?.playStoryAudio(
                        storyId = story.id,
                        storyTitle = story.title,
                        storyContent = com.bedtime.dreamtales.core.StorySpeechHelper.buildFullSpokenStoryText(story.title, story.content),
                        localFilePath = path,
                        autoPlay = autoPlay
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

        // 开屏前先把窗口底色切成当前主题色，避免深色主题冷启动时闪白
        applyInitialWindowTheme()

        widgetPendingStoryId = extractWidgetStoryId(intent)
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
                appNavController = navController

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
                            onPlayStory = { story, localFilePath, autoPlay ->
                                // 普通点播进入待命模式；桌面小组件一键伴读则自动开播
                                val currentService = playbackService
                                if (currentService != null) {
                                    currentService.playStoryAudio(
                                        storyId = story.id,
                                        storyTitle = story.title,
                                        storyContent = com.bedtime.dreamtales.core.StorySpeechHelper.buildFullSpokenStoryText(story.title, story.content),
                                        localFilePath = localFilePath,
                                        autoPlay = autoPlay
                                    )
                                } else {
                                    pendingStoryToPlay = Triple(story, localFilePath, autoPlay)
                                }
                                navController.navigate(Screen.SleepPlayer.route)
                            },
                            pendingWidgetStoryId = widgetPendingStoryId,
                            onWidgetStoryConsumed = { widgetPendingStoryId = null }
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val storyId = extractWidgetStoryId(intent)
        if (storyId != null) {
            widgetPendingStoryId = storyId
            appNavController?.navigate(Screen.Home.route) {
                launchSingleTop = true
                popUpTo(Screen.Home.route) { inclusive = false }
            }
        }
    }

    private fun applyInitialWindowTheme() {
        val mode = AppConfig.getInstance(this).getThemeMode()
        val isDark = when (mode) {
            "day" -> false
            "system" -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            else -> true
        }
        val background = ContextCompat.getColor(
            this,
            if (isDark) R.color.background_dark else R.color.background_paper
        )
        window.setBackgroundDrawable(ColorDrawable(background))
        window.statusBarColor = background
        window.navigationBarColor = background
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
    }

    private fun extractWidgetStoryId(intent: Intent?): Long? {
        if (intent == null) return null
        return WidgetProtocol.extractPlayStoryId(
            intent.action,
            intent.getLongExtra(DreamTalesWidgetProvider.EXTRA_STORY_ID, 0L)
        )
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
