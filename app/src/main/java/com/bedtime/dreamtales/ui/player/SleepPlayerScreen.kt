package com.bedtime.dreamtales.ui.player

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bedtime.dreamtales.core.StorySpeechHelper
import com.bedtime.dreamtales.core.audio.AmbientMix
import com.bedtime.dreamtales.core.constants.AppConstants
import com.bedtime.dreamtales.core.image.rememberCoverBitmap
import com.bedtime.dreamtales.core.playback.PlaybackSpeedPolicy
import com.bedtime.dreamtales.core.story.StoryPageSplitter
import com.bedtime.dreamtales.core.theme.DeepNightScrim
import com.bedtime.dreamtales.data.local.entity.StoryPage
import com.bedtime.dreamtales.service.FlipSensorManager
import com.bedtime.dreamtales.service.StoryPlaybackState
import kotlin.math.max

/**
 * 沉浸式睡前伴读播放界面。
 * 支持：待命就绪（点击后开播）、歌词式绘本跟读滚动（声画节奏同步高亮）、倒计时表盘切换与双指手势盲操。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepPlayerScreen(
    viewModel: SleepPlayerViewModel,
    flipSensorManager: FlipSensorManager,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val playbackState by viewModel.playbackState.collectAsState()
    val storyPages by viewModel.storyPages.collectAsState()
    val storyPagesState by viewModel.storyPagesState.collectAsState()
    val isFaceDown by flipSensorManager.isFaceDown.collectAsState()
    // pointerInput 是长驻手势协程，必须用 rememberUpdatedState 读取最新播放状态
    val latestPlaybackState by rememberUpdatedState(playbackState)

    var activeTab by remember { mutableIntStateOf(0) } // 0: 🖼️ 绘本插图, 1: 📖 绘本跟读, 2: 📚 连环画
    var showAmbientSheet by remember { mutableStateOf(false) }
    var showTimerDialog by remember { mutableStateOf(false) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var coverGenerationError by remember { mutableStateOf<String?>(null) }

    // 原生无权限相册图片选择器（免敏感权限、零打扰）
    val pickMediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.setCoverFromUri(
                uri = uri,
                onSuccess = {
                    Toast.makeText(context, "绘本封面已更新！", Toast.LENGTH_SHORT).show()
                },
                onError = { err ->
                    Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    // 监听翻转扣屏感应：跟随页面生命周期启停。
    // 之前只在首次组合时启动，MainActivity.onPause 停掉传感器后回到前台不会重启，
    // 会导致下拉通知栏/切后台再回来后「翻转扣屏」彻底失效。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    flipSensorManager.startListening()
                    FlipSensorManager.applyWindowBrightness(
                        activity,
                        flipSensorManager.isFaceDown.value
                    )
                }
                Lifecycle.Event.ON_PAUSE -> {
                    flipSensorManager.stopListening()
                    FlipSensorManager.applyWindowBrightness(activity, false)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // 首次进入页面时如果已处于 RESUMED，直接启动
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            flipSensorManager.startListening()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            flipSensorManager.stopListening()
            FlipSensorManager.applyWindowBrightness(activity, false)
        }
    }

    LaunchedEffect(isFaceDown) {
        FlipSensorManager.applyWindowBrightness(activity, isFaceDown)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // 双指轻点屏幕任意位置触发播放/暂停（盲操无惊扰）
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var twoFingersDetected = false
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.size >= 2) {
                            twoFingersDetected = true
                        }
                        if (event.changes.all { !it.pressed }) {
                            if (twoFingersDetected) {
                                val snapshot = latestPlaybackState
                                if (snapshot.isAmbientOnly && snapshot.ambientMix.isEmpty() && !snapshot.isPlaying) {
                                    Toast.makeText(context, "先选择至少一条声景", Toast.LENGTH_SHORT).show()
                                } else {
                                    viewModel.togglePlayPause()
                                }
                            }
                            break
                        }
                    }
                }
            }
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            text = if (playbackState.isAmbientOnly) "月眠声景" else "沉浸式睡前伴读",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回文库",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
            }
        ) { innerPadding ->
            if (playbackState.isAmbientOnly) {
                AmbientOnlyPlayerContent(
                    modifier = Modifier.padding(innerPadding),
                    playbackState = playbackState,
                    onOpenAmbient = { showAmbientSheet = true },
                    onOpenTimer = { showTimerDialog = true },
                    onTogglePlayPause = {
                        // 空音轨时按钮不再「静默无效」，给家长明确提示
                        if (playbackState.ambientMix.isEmpty() && !playbackState.isPlaying) {
                            Toast.makeText(context, "先点「助眠背景」选择至少一条声景", Toast.LENGTH_SHORT).show()
                        } else {
                            viewModel.togglePlayPause()
                        }
                    }
                )
            } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // 顶部故事标题与状态提示
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Text(
                        text = playbackState.storyTitle.ifEmpty { "精选睡前故事" },
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (playbackState.isPlaying) "🌙 正在安抚伴读" else "伴读就绪 · 待命中",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // 视图切换药丸开关：⏱️ 睡眠表盘 vs 📖 绘本歌词
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = (activeTab == 0),
                            onClick = { activeTab = 0 },
                            label = { Text("🖼️ 绘本插图", style = MaterialTheme.typography.labelSmall) },
                            shape = CircleShape,
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                labelColor = MaterialTheme.colorScheme.onSurface,
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            border = null)

                        Spacer(modifier = Modifier.width(10.dp))

                        FilterChip(
                            selected = (activeTab == 1),
                            onClick = { activeTab = 1 },
                            label = { Text("📖 绘本跟读", style = MaterialTheme.typography.labelSmall) },
                            shape = CircleShape,
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                labelColor = MaterialTheme.colorScheme.onSurface,
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            border = null)

                        Spacer(modifier = Modifier.width(10.dp))

                        FilterChip(
                            selected = (activeTab == 2),
                            onClick = { activeTab = 2 },
                            label = { Text("📚 连环画", style = MaterialTheme.typography.labelSmall) },
                            shape = CircleShape,
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                labelColor = MaterialTheme.colorScheme.onSurface,
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            border = null)
                    }
                }

                // 中央动态区域：绘本专属插画 或 绘本歌词滚动
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedContent(
                        targetState = activeTab,
                        transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
                        label = "centerView"
                    ) { tab ->
                        if (tab == 0) {
                            StoryIllustrationCard(
                                storyTitle = playbackState.storyTitle,
                                coverImagePath = playbackState.coverImagePath,
                                isGeneratingCover = playbackState.isGeneratingCover,
                                onPickFromGallery = {
                                    pickMediaLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                onGenerateAiCover = {
                                    viewModel.generateCoverIllustration(
                                        onSuccess = {
                                            Toast.makeText(context, "绘本插画绘制完成！", Toast.LENGTH_SHORT).show()
                                        },
                                        onError = { err ->
                                            coverGenerationError = err
                                        }
                                    )
                                },
                                onClearCover = {
                                    viewModel.clearCover {
                                        Toast.makeText(context, "已恢复默认绘本封面", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        } else if (tab == 1) {
                            StoryLyricsComponent(
                                content = playbackState.storyContent,
                                currentPositionMs = playbackState.currentPositionMs,
                                totalDurationMs = playbackState.totalDurationMs,
                                onSeekTo = { pos -> viewModel.seekTo(pos) }
                            )
                        } else {
                            StoryPagesComponent(
                                pages = storyPages,
                                currentPositionMs = playbackState.currentPositionMs,
                                totalDurationMs = playbackState.totalDurationMs,
                                pagesState = storyPagesState,
                                onGenerate = { viewModel.generateStoryPages() },
                                onResetState = { viewModel.resetStoryPagesState() }
                            )
                        }
                    }
                }

                // 下部：系统音量、助眠白噪音、睡眠定时胶囊、进度条与播控大按钮
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // 真实系统硬件音量、助眠白噪音、伴读睡眠定时（胶囊三剑客）
                    val ambientMix = playbackState.ambientMix
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. 真实系统音量胶囊
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val volPercent = (playbackState.currentVolume * 100).toInt()
                                Text(
                                    text = if (volPercent > 0) "🔊 $volPercent%" else "🔇 静音",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        // 2. 助眠背景白噪音多轨混音胶囊
                        Surface(
                            shape = CircleShape,
                            color = if (ambientMix.isNotEmpty()) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.clickable { showAmbientSheet = true }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (ambientMix.isNotEmpty()) {
                                        val ambientEmojis = ambientMix.keys
                                            .mapNotNull { AmbientMix.trackInfo(it)?.emoji }
                                            .take(2)
                                            .joinToString("")
                                        "$ambientEmojis ${ambientMix.size}轨"
                                    } else {
                                        "🌿 助眠背景"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (ambientMix.isNotEmpty()) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (ambientMix.isNotEmpty()) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 12.sp
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "▾",
                                    fontSize = 10.sp,
                                    color = if (ambientMix.isNotEmpty()) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // 3. 伴读睡眠定时选择胶囊（轻巧优雅，替代原来屏幕中心大表盘）
                        val isFading = playbackState.isTimerActive && playbackState.remainingTimerSeconds in 1..300
                        Surface(
                            shape = CircleShape,
                            color = if (isFading) MaterialTheme.colorScheme.primaryContainer
                            else if (playbackState.isTimerActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.clickable { showTimerDialog = true }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val timerLabel = when {
                                    isFading -> {
                                        val min = playbackState.remainingTimerSeconds / 60
                                        val sec = playbackState.remainingTimerSeconds % 60
                                        "⏱️ ${min}:${String.format("%02d", sec)} 渐降"
                                    }
                                    playbackState.isTimerActive -> {
                                        val remMin = (playbackState.remainingTimerSeconds + 59) / 60
                                        "⏱️ ${remMin}分"
                                    }
                                    else -> "⏱️ 定时"
                                }
                                Text(
                                    text = timerLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isFading || playbackState.isTimerActive) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (isFading || playbackState.isTimerActive) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 12.sp
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "▾",
                                    fontSize = 10.sp,
                                    color = if (isFading || playbackState.isTimerActive) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (playbackState.isTimerActive && playbackState.remainingTimerSeconds <= 300) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "🌙 已进入最后5分钟：对数声学衰减与语速渐降中",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // 进度条与播放时间（支持平滑拖拽防抖与松手精准寻址，避免低端机型频繁 seek 越界或冲顶）
                    var isDragging by remember(playbackState.storyId) { mutableStateOf(false) }
                    var dragFraction by remember(playbackState.storyId) { mutableFloatStateOf(0f) }

                    val currentFraction = if (playbackState.totalDurationMs > 0) {
                        (playbackState.currentPositionMs.toFloat() / playbackState.totalDurationMs).coerceIn(0f, 1f)
                    } else 0f

                    val displayFraction = if (isDragging) dragFraction else currentFraction
                    val displayPositionMs = if (isDragging && playbackState.totalDurationMs > 0) {
                        (dragFraction * playbackState.totalDurationMs).toLong()
                    } else {
                        playbackState.currentPositionMs
                    }

                    Column(modifier = Modifier.fillMaxWidth()) {
                        Slider(
                            value = displayFraction,
                            onValueChange = { fraction ->
                                isDragging = true
                                dragFraction = fraction.coerceIn(0f, 1f)
                            },
                            onValueChangeFinished = {
                                if (playbackState.totalDurationMs > 0) {
                                    val newPos = (dragFraction * playbackState.totalDurationMs).toLong().coerceIn(0L, playbackState.totalDurationMs)
                                    viewModel.seekTo(newPos)
                                }
                                isDragging = false
                            },
                            enabled = playbackState.totalDurationMs > 0,
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = formatTime(displayPositionMs / 1000),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = formatTime(playbackState.totalDurationMs / 1000),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // 连播模式 + 播放速度
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.clickable { viewModel.cyclePlaybackMode() }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${playbackState.playbackMode.iconEmoji} ${playbackState.playbackMode.title}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "切换 ▾",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 10.sp
                                )
                            }
                        }

                        Box {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.clickable { showSpeedMenu = true }
                            ) {
                                Text(
                                    text = "${playbackState.basePlaybackSpeed}x ▾",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = showSpeedMenu,
                                onDismissRequest = { showSpeedMenu = false }
                            ) {
                                PlaybackSpeedPolicy.OPTIONS.forEach { speed ->
                                    val selected = kotlin.math.abs(speed - playbackState.basePlaybackSpeed) < 0.01f
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = when (speed) {
                                                    0.8f -> "0.8x · 更慢"
                                                    0.9f -> "0.9x · 稍慢"
                                                    else -> "1.0x · 标准"
                                                },
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (selected) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurface
                                            )
                                        },
                                        onClick = {
                                            viewModel.setPlaybackSpeed(speed)
                                            showSpeedMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 上一首 + 大播放/暂停按键 + 下一首
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalIconButton(
                            onClick = { viewModel.playPrevious() },
                            modifier = Modifier.size(46.dp),
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipPrevious,
                                contentDescription = "上一首",
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Button(
                            onClick = { viewModel.togglePlayPause() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            shape = CircleShape,
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp, pressedElevation = 0.dp),
                            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 14.dp)
                        ) {
                            Icon(
                                imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (playbackState.isPlaying) "暂停伴读" else "开始伴读",
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (playbackState.isPlaying) "暂停伴读" else "开始伴读",
                                style = MaterialTheme.typography.labelLarge,
                                fontSize = 16.sp
                            )
                        }

                        FilledTonalIconButton(
                            onClick = { viewModel.playNext() },
                            modifier = Modifier.size(46.dp),
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = "下一首",
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
            }
        }

        // 助眠白噪音混音弹窗
        if (showAmbientSheet) {
            AmbientSoundDialog(
                currentMix = playbackState.ambientMix,
                userPresets = viewModel.getAmbientPresets(),
                onMixChanged = { mix, persist -> viewModel.setAmbientMix(mix, persist) },
                onSavePreset = { name, mix -> viewModel.saveAmbientPreset(name, mix) },
                onDismiss = { showAmbientSheet = false }
            )
        }

        // 伴读睡眠定时选择弹窗
        if (showTimerDialog) {
            SleepTimerDialog(
                selectedMinutes = playbackState.selectedTimerMinutes,
                isTimerActive = playbackState.isTimerActive,
                onSelectMinutes = { minutes -> viewModel.startTimer(minutes) },
                onCancelTimer = { viewModel.cancelTimer() },
                onDismiss = { showTimerDialog = false }
            )
        }

        // 绘本生图错误/配额提示弹窗
        if (coverGenerationError != null) {
            AlertDialog(
                onDismissRequest = { coverGenerationError = null },
                shape = MaterialTheme.shapes.extraLarge,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "🎨", fontSize = 22.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "绘本生图提示",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                text = {
                    Text(
                        text = coverGenerationError!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 22.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { coverGenerationError = null },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text("我知道了", style = MaterialTheme.typography.labelLarge)
                    }
                }
            )
        }

        // 翻转扣屏感应触发时的全屏透明拦截防误触遮罩
        if (isFaceDown) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DeepNightScrim)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = true)
                        }
                    }
            )
        }
    }
}

/**
 * 纯声景播放界面：不显示故事正文、插图和进度，只保留声景、定时和播控。
 */
@Composable
private fun AmbientOnlyPlayerContent(
    modifier: Modifier,
    playbackState: StoryPlaybackState,
    onOpenAmbient: () -> Unit,
    onOpenTimer: () -> Unit,
    onTogglePlayPause: () -> Unit
) {
    val mix = playbackState.ambientMix
    val trackText = mix.keys
        .mapNotNull { id ->
            AmbientMix.trackInfo(id)?.let { "${it.emoji} ${it.title}" }
        }
        .joinToString(" · ")
    val timerLabel = when {
        playbackState.isTimerActive && playbackState.remainingTimerSeconds > 0 -> {
            val minutes = (playbackState.remainingTimerSeconds + 59) / 60
            "⏱️ ${minutes} 分后停止"
        }
        else -> "⏱️ 设置定时"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.weight(1f))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text(text = "🌿", fontSize = 56.sp)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "月眠声景",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (playbackState.isPlaying) "自然声正在轻轻流淌" else "声景已暂停",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(20.dp))
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    text = trackText.ifBlank { "还没有选择声景，点下方「助眠背景」添加" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    modifier = Modifier.clickable { onOpenAmbient() }
                ) {
                    Text(
                        text = "🌿 助眠背景 ▾",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                }

                Surface(
                    shape = CircleShape,
                    color = if (playbackState.isTimerActive) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    modifier = Modifier.clickable { onOpenTimer() }
                ) {
                    Text(
                        text = timerLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (playbackState.isTimerActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (playbackState.isTimerActive) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                }
            }

            if (playbackState.isTimerActive && playbackState.remainingTimerSeconds <= 300) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "🌙 已进入最后 5 分钟，声音正在慢慢变轻",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Button(
                onClick = onTogglePlayPause,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = CircleShape,
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp, pressedElevation = 0.dp),
                contentPadding = PaddingValues(horizontal = 36.dp, vertical = 15.dp)
            ) {
                Icon(
                    imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playbackState.isPlaying) "暂停声景" else "播放声景",
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (playbackState.isPlaying) "暂停声景" else "继续声景",
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = 16.sp
                )
            }

            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}

/**
 * 分段多图连环画：3~4 页绘本，随音频进度自动翻页，也支持手动前后翻页。
 */
@Composable
private fun StoryPagesComponent(
    pages: List<StoryPage>,
    currentPositionMs: Long,
    totalDurationMs: Long,
    pagesState: StoryPagesUiState,
    onGenerate: () -> Unit,
    onResetState: () -> Unit
) {
    val sortedPages = remember(pages) { pages.sortedBy { it.pageIndex } }

    if (sortedPages.isEmpty()) {
        val generating = pagesState as? StoryPagesUiState.Generating
        val error = (pagesState as? StoryPagesUiState.Error)?.message
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(20.dp)
            ) {
                if (generating != null) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.size(38.dp),
                        strokeWidth = 3.dp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "🪄 正在绘制连环画… ${generating.progress}%",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "正在为每一页故事绘制专属插画，请稍候",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(text = "📚", fontSize = 38.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "把故事变成连环画",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "AI 会把正文分成 3 页，并分别为每一页绘制睡前插画。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    if (error != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "⚠️ $error",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            onResetState()
                            onGenerate()
                        },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text("🪄 生成 3 页连环画", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        return
    }

    val drafts = remember(sortedPages) {
        StoryPageSplitter.withFractions(sortedPages.map { it.text })
    }
    val progress = if (totalDurationMs > 0) {
        (currentPositionMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val audioPageIndex = StoryPageSplitter.activePageIndex(progress, drafts)

    var manualIndex by remember(sortedPages) { mutableIntStateOf(audioPageIndex) }
    LaunchedEffect(audioPageIndex) {
        manualIndex = audioPageIndex
    }
    val currentIndex = manualIndex.coerceIn(0, sortedPages.lastIndex)

    Column(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = currentIndex,
            transitionSpec = { fadeIn(tween(320)) togetherWith fadeOut(tween(220)) },
            label = "storyPage",
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { index ->
            val page = sortedPages.getOrNull(index) ?: return@AnimatedContent
            val bitmap = rememberCoverBitmap(path = page.imagePath, maxDimensionPx = 768)

            Column(modifier = Modifier.fillMaxSize()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = "连环画第 ${index + 1} 页",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text(text = "🌙", fontSize = 34.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = page.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 22.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalButton(
                onClick = { manualIndex = (currentIndex - 1).coerceAtLeast(0) },
                enabled = currentIndex > 0,
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text("‹ 上一页", style = MaterialTheme.typography.labelSmall)
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "第 ${currentIndex + 1} / ${sortedPages.size} 页",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 3.dp)
                ) {
                    sortedPages.indices.forEach { dotIndex ->
                        Box(
                            modifier = Modifier
                                .size(if (dotIndex == currentIndex) 6.dp else 4.dp)
                                .clip(CircleShape)
                                .background(
                                    if (dotIndex == currentIndex) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant
                                )
                        )
                    }
                }
            }

            FilledTonalButton(
                onClick = { manualIndex = (currentIndex + 1).coerceAtMost(sortedPages.lastIndex) },
                enabled = currentIndex < sortedPages.lastIndex,
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text("下一页 ›", style = MaterialTheme.typography.labelSmall)
            }
        }

        if (pagesState is StoryPagesUiState.Error) {
            Text(
                text = "⚠️ ${pagesState.message}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 2.dp)
            )
        } else if (pagesState is StoryPagesUiState.Generating) {
            Text(
                text = "🪄 正在重新绘制连环画… ${pagesState.progress}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp)
            )
        } else {
            FilledTonalButton(
                onClick = {
                    onResetState()
                    onGenerate()
                },
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 2.dp)
            ) {
                Text(
                    "🪄 重新绘制",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 绘本伴读歌词式逐句滚动高亮组件。
 * 按照标点与节奏自动分句，当前句温润高亮并居中滚动，点击任意句可定点跳转播放。
 */
@Composable
private fun StoryLyricsComponent(
    content: String,
    currentPositionMs: Long,
    totalDurationMs: Long,
    onSeekTo: (Long) -> Unit
) {
    if (content.isBlank()) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(
                text = "📖 绘本伴读文字准备中...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val sentenceList = remember(content) {
        val parsed = StorySpeechHelper.parseStorySentences(content)
        StorySpeechHelper.calculateLyricIntervals(parsed)
    }

    val progressFraction = if (totalDurationMs > 0) (currentPositionMs.toFloat() / totalDurationMs).coerceIn(0f, 1f) else 0f
    val activeIndex = remember(progressFraction, sentenceList) {
        StorySpeechHelper.getActiveSentenceIndex(progressFraction, sentenceList)
    }

    val listState = rememberLazyListState()

    LaunchedEffect(activeIndex) {
        if (sentenceList.isNotEmpty() && activeIndex in sentenceList.indices) {
            listState.animateScrollToItem(max(0, activeIndex - 1))
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        itemsIndexed(sentenceList, key = { index, _ -> index }) { index, item ->
            val isCurrent = (index == activeIndex)
            val isPast = (index < activeIndex)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        else Color.Transparent
                    )
                    .clickable {
                        val seekTarget = (item.startFraction * totalDurationMs).toLong()
                        onSeekTo(seekTarget)
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = item.text,
                    style = if (isCurrent) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                    color = when {
                        isCurrent -> MaterialTheme.colorScheme.primary
                        isPast -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                    },
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    lineHeight = if (isCurrent) 26.sp else 22.sp
                )
            }
        }
    }
}

/**
 * 睡前绘本专属插画展示与智能绘制组件 (Local-First 高清渲染，支持相册自定义与AI生图)
 */
@Composable
private fun StoryIllustrationCard(
    storyTitle: String,
    coverImagePath: String?,
    isGeneratingCover: Boolean,
    onPickFromGallery: () -> Unit,
    onGenerateAiCover: () -> Unit,
    onClearCover: () -> Unit
) {
    var showCoverMenu by remember { mutableStateOf(false) }

    val bitmap = rememberCoverBitmap(path = coverImagePath, maxDimensionPx = 1024)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            when {
                isGeneratingCover -> {
                    // 生成中动效与微光提示
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.size(44.dp),
                            strokeWidth = 3.dp
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "🎨 正在为《${storyTitle.ifEmpty { "故事" }}》编织睡前插画...",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "基于 gemini-3.1-flash-image 高清绘本模型",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                bitmap != null -> {
                    // 已有绘本插画展示
                    Image(
                        bitmap = bitmap,
                        contentDescription = "故事绘本插画",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )

                    // 右上角浮层：操作菜单微胶囊
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = DeepNightScrim.copy(alpha = 0.7f),
                            modifier = Modifier.clickable { showCoverMenu = true }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = "📷 封面 ▾", fontSize = 11.sp, color = Color.White)
                            }
                        }

                        DropdownMenu(
                            expanded = showCoverMenu,
                            onDismissRequest = { showCoverMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("📷 从相册更换封面") },
                                onClick = {
                                    showCoverMenu = false
                                    onPickFromGallery()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("🪄 AI 重新生成") },
                                onClick = {
                                    showCoverMenu = false
                                    onGenerateAiCover()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("🗑️ 清除自定义封面") },
                                onClick = {
                                    showCoverMenu = false
                                    onClearCover()
                                }
                            )
                        }
                    }
                }

                else -> {
                    // 尚未设置封面时的温馨艺术占位景致
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp)
                    ) {
                        Text(text = "🌙", fontSize = 42.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = storyTitle.ifEmpty { "精选睡前童话" },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "为宝贝配上亲子照片、手绘涂鸦或实体绘本封面：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = onPickFromGallery,
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 7.dp)
                            ) {
                                Text(text = "📷 相册选图", style = MaterialTheme.typography.labelLarge)
                            }

                            FilledTonalButton(
                                onClick = onGenerateAiCover,
                                shape = CircleShape,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    text = "🪄 AI 生图",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return String.format("%02d:%02d", m, s)
}
