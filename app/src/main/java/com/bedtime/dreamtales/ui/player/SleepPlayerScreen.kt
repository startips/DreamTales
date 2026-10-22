package com.bedtime.dreamtales.ui.player

import android.app.Activity
import android.graphics.BitmapFactory
import android.widget.Toast
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
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bedtime.dreamtales.core.StorySpeechHelper
import com.bedtime.dreamtales.core.constants.AppConstants
import com.bedtime.dreamtales.core.theme.DeepNightScrim
import com.bedtime.dreamtales.service.AmbientSound
import com.bedtime.dreamtales.service.FlipSensorManager
import java.io.File
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
    val isFaceDown by flipSensorManager.isFaceDown.collectAsState()

    var activeTab by remember { mutableIntStateOf(0) } // 0: 🖼️ 绘本插图, 1: 📖 绘本跟读歌词
    var showAmbientSheet by remember { mutableStateOf(false) }
    var showTimerDialog by remember { mutableStateOf(false) }

    // 监听翻转扣屏感应：仅当正面朝下扣屏时熄屏；手持时恢复系统自然亮度
    LaunchedEffect(isFaceDown) {
        FlipSensorManager.applyWindowBrightness(activity, isFaceDown)
    }

    // 离开播放页时恢复常规亮度
    DisposableEffect(Unit) {
        onDispose {
            activity?.window?.let { window ->
                val params = window.attributes
                params.screenBrightness = -1.0f
                window.attributes = params
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // 双指轻点屏幕任意位置触发播放/暂停（盲操无惊扰）
            .pointerInput(Unit) {
                awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    var twoFingersDetected = false
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.size >= 2) {
                            twoFingersDetected = true
                        }
                        if (event.changes.all { !it.pressed }) {
                            if (twoFingersDetected) {
                                viewModel.togglePlayPause()
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
                            text = "沉浸式睡前伴读",
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
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = (activeTab == 0),
                                borderColor = MaterialTheme.colorScheme.outlineVariant,
                                selectedBorderColor = MaterialTheme.colorScheme.primary
                            )
                        )

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
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = (activeTab == 1),
                                borderColor = MaterialTheme.colorScheme.outlineVariant,
                                selectedBorderColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }

                // 中央动态区域：绘本专属插画 或 绘本歌词滚动
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
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
                                onGenerateCover = {
                                    viewModel.generateCoverIllustration(
                                        onSuccess = {
                                            Toast.makeText(context, "绘本插画绘制完成！", Toast.LENGTH_SHORT).show()
                                        },
                                        onError = { err ->
                                            Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                                        }
                                    )
                                }
                            )
                        } else {
                            StoryLyricsComponent(
                                content = playbackState.storyContent,
                                currentPositionMs = playbackState.currentPositionMs,
                                totalDurationMs = playbackState.totalDurationMs,
                                onSeekTo = { pos -> viewModel.seekTo(pos) }
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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. 真实系统音量胶囊
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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

                        // 2. 助眠背景白噪音混音胶囊
                        Surface(
                            shape = CircleShape,
                            color = if (playbackState.ambientSound != AmbientSound.OFF) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(
                                1.dp,
                                if (playbackState.ambientSound != AmbientSound.OFF) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier.clickable { showAmbientSheet = true }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (playbackState.ambientSound != AmbientSound.OFF) "${playbackState.ambientSound.iconEmoji} ${playbackState.ambientSound.title}"
                                    else "🌿 助眠背景",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (playbackState.ambientSound != AmbientSound.OFF) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (playbackState.ambientSound != AmbientSound.OFF) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 12.sp
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "▾",
                                    fontSize = 10.sp,
                                    color = if (playbackState.ambientSound != AmbientSound.OFF) MaterialTheme.colorScheme.primary
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
                            border = BorderStroke(
                                1.dp,
                                if (isFading) MaterialTheme.colorScheme.primary
                                else if (playbackState.isTimerActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.outlineVariant
                            ),
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

                    // 进度条与播放时间
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Slider(
                            value = if (playbackState.totalDurationMs > 0) {
                                (playbackState.currentPositionMs.toFloat() / playbackState.totalDurationMs).coerceIn(0f, 1f)
                            } else 0f,
                            onValueChange = { fraction ->
                                val newPos = (fraction * playbackState.totalDurationMs).toLong()
                                viewModel.seekTo(newPos)
                            },
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
                                text = formatTime(playbackState.currentPositionMs / 1000),
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

                    // 连播模式切换药丸
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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

        // 助眠白噪音混音弹窗
        if (showAmbientSheet) {
            AmbientSoundDialog(
                currentSound = playbackState.ambientSound,
                currentVolume = playbackState.ambientVolume,
                onSoundSelected = { sound -> viewModel.setAmbientSound(sound) },
                onVolumeChanged = { vol -> viewModel.setAmbientVolume(vol) },
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
        itemsIndexed(sentenceList) { index, item ->
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
 * 睡前绘本专属插画展示与智能绘制组件 (Local-First 高清渲染)
 */
@Composable
private fun StoryIllustrationCard(
    storyTitle: String,
    coverImagePath: String?,
    isGeneratingCover: Boolean,
    onGenerateCover: () -> Unit
) {
    val bitmap = remember(coverImagePath) {
        if (!coverImagePath.isNullOrBlank()) {
            val file = File(coverImagePath)
            if (file.exists() && file.length() > 0) {
                try {
                    BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                } catch (e: Exception) {
                    null
                }
            } else null
        } else null
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        border = BorderStroke(
            1.dp,
            if (bitmap != null) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.outlineVariant
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

                    // 右上角浮层：重新生成小按钮
                    Surface(
                        shape = CircleShape,
                        color = DeepNightScrim.copy(alpha = 0.65f),
                        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp)
                            .clickable { onGenerateCover() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "🪄 重新绘制", fontSize = 11.sp, color = Color.White)
                        }
                    }
                }

                else -> {
                    // 尚未生成插图时的温馨艺术占位景致
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp)
                    ) {
                        Text(text = "🌙", fontSize = 42.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = storyTitle.ifEmpty { "精选睡前童话" },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "暂无绘本插画 · 可一键调用大模型智能绘制",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        FilledTonalButton(
                            onClick = onGenerateCover,
                            shape = CircleShape,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)
                        ) {
                            Text(text = "🪄 生成绘本插图", style = MaterialTheme.typography.labelLarge)
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
