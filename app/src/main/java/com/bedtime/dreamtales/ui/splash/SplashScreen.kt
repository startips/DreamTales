package com.bedtime.dreamtales.ui.splash

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bedtime.dreamtales.core.theme.NightBackground
import kotlinx.coroutines.delay

/**
 * 沉浸式睡前开屏宣传动效界面。
 * 全面自适应日间温润绘本（羊皮纸米黄☀️）与夜间伴读（静谧深邃蓝黑🌙）双模式。
 * 优雅的朝阳/金月升起、柔光微晕呼吸与温和标题浮现，1.8秒后平滑进入文库主页。
 */
@Composable
fun SplashScreen(
    onSplashFinished: () -> Unit
) {
    var animationStarted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        animationStarted = true
        delay(1800L)
        onSplashFinished()
    }

    val isDark = MaterialTheme.colorScheme.background == NightBackground

    // 中心天体升起平滑弹簧动效
    val celestialOffsetY by animateFloatAsState(
        targetValue = if (animationStarted) 0f else 60f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "celestialOffset"
    )

    // 文字渐显动画
    val contentAlpha by animateFloatAsState(
        targetValue = if (animationStarted) 1f else 0f,
        animationSpec = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
        label = "contentAlpha"
    )

    // 光晕柔和呼吸动效
    val infiniteTransition = rememberInfiniteTransition(label = "haloPulse")
    val haloScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "haloScale"
    )

    val primaryColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // 点击屏幕任意位置可直接跳过开屏
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                onSplashFinished()
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 中心天体与呼吸光晕（夜间暖月 🌙，日间朝阳 ☀️）
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(160.dp)
                    .offset(y = celestialOffsetY.dp)
            ) {
                // 外层柔光微光圈
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .scale(haloScale)
                        .background(primaryColor.copy(alpha = if (isDark) 0.08f else 0.12f), CircleShape)
                )

                // 中层暖光圈
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .background(primaryColor.copy(alpha = if (isDark) 0.15f else 0.18f), CircleShape)
                )

                // 核心天体 Emoji
                Text(
                    text = if (isDark) "🌙" else "☀️",
                    fontSize = 52.sp
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // 标题与入眠标语
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.alpha(contentAlpha)
            ) {
                Text(
                    text = "DreamTales",
                    color = primaryColor,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (isDark) "经典伴读 · 温柔伴宝入梦" else "经典伴读 · 温暖绘本时光",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    letterSpacing = 1.sp
                )
            }
        }

        // 底部轻微跳过提示
        Text(
            text = "轻触任意处跳过",
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        )
    }
}
