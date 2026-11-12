package com.bedtime.dreamtales.ui.player

import android.app.Activity
import android.view.Window
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.bedtime.dreamtales.core.playback.ChildLockPolicy
import com.bedtime.dreamtales.core.theme.DeepNightScrim
import com.bedtime.dreamtales.service.StoryPlaybackState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 播放页儿童锁遮罩：
 * - 拦截系统返回键与边缘侧滑返回，隐藏一切播控/切歌/退出按钮；
 * - 暂时收起系统状态栏与导航栏，离开或解锁时必定恢复；
 * - 轻触屏幕任意位置浮现低亮度「长按 2.5 秒解锁」胶囊（带环形进度反馈），3 秒无操作自动淡出。
 */
@Composable
fun ChildLockOverlay(
    playbackState: StoryPlaybackState,
    onUnlock: () -> Unit
) {
    val view = LocalView.current
    val activity = view.context as? Activity
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val currentOnUnlock by rememberUpdatedState(onUnlock)

    var hintVisible by remember { mutableStateOf(true) }
    var hintRequest by remember { mutableIntStateOf(1) }
    var isHolding by remember { mutableStateOf(false) }
    var holdProgress by remember { mutableFloatStateOf(0f) }

    BackHandler(enabled = true) {
        hintVisible = true
        hintRequest++
    }

    // 锁定期间临时隐藏系统状态栏与导航栏；解锁或退出页面时恢复
    DisposableEffect(activity, view) {
        val window: Window? = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.let {
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            it.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    LaunchedEffect(hintRequest, isHolding) {
        if (!isHolding && hintVisible) {
            delay(ChildLockPolicy.HINT_AUTO_HIDE_MS)
            hintVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepNightScrim.copy(alpha = 0.90f))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.all { !it.pressed }) {
                            hintVisible = true
                            hintRequest++
                            break
                        }
                    }
                }
            }
    ) {
        // 中央极简柔暗守护状态
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (playbackState.isAmbientOnly) "🌿" else playbackState.storyEmoji.ifBlank { "🌙" },
                fontSize = 44.sp
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = playbackState.storyTitle.ifBlank { "月眠伴读" },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (playbackState.isPlaying) "🔒 儿童锁已开启 · 正在轻声陪伴" else "🔒 儿童锁已开启 · 已暂停",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                textAlign = TextAlign.Center
            )
            if (playbackState.isTimerActive && playbackState.remainingTimerSeconds > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                val min = playbackState.remainingTimerSeconds / 60
                val sec = playbackState.remainingTimerSeconds % 60
                Text(
                    text = "⏱️ ${min}:${String.format("%02d", sec)} 后轻柔停止",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                )
            }
        }

        // 底部低亮度长按解锁胶囊
        AnimatedVisibility(
            visible = hintVisible || isHolding,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 52.dp)
        ) {
            val primaryColor = MaterialTheme.colorScheme.primary
            val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                modifier = Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = true)
                        isHolding = true
                        hintVisible = true
                        holdProgress = 0f
                        val startTime = System.currentTimeMillis()
                        var unlocked = false

                        val progressJob = scope.launch {
                            while (isActive && isHolding) {
                                val elapsed = System.currentTimeMillis() - startTime
                                holdProgress = ChildLockPolicy.progressFraction(elapsed)
                                if (ChildLockPolicy.isUnlocked(elapsed)) {
                                    unlocked = true
                                    isHolding = false
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    currentOnUnlock()
                                    break
                                }
                                delay(16L)
                            }
                        }

                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                            if (event.changes.all { !it.pressed }) {
                                break
                            }
                        }

                        progressJob.cancel()
                        if (!unlocked) {
                            isHolding = false
                            holdProgress = 0f
                            hintRequest++
                        }
                    }
                }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier.size(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val strokeWidth = 3.dp.toPx()
                            drawArc(
                                color = trackColor,
                                startAngle = 0f,
                                sweepAngle = 360f,
                                useCenter = false,
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                            )
                            if (holdProgress > 0f) {
                                drawArc(
                                    color = primaryColor,
                                    startAngle = -90f,
                                    sweepAngle = 360f * holdProgress,
                                    useCenter = false,
                                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "长按解锁",
                            tint = primaryColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = if (isHolding) "继续按住以解锁…" else "长按 2.5 秒解锁",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "锁定期间已屏蔽返回与误触操作",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
