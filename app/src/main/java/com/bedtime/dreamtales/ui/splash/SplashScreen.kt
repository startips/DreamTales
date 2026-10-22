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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bedtime.dreamtales.core.theme.NightBackground
import com.bedtime.dreamtales.core.theme.WarmMoonYellow
import com.bedtime.dreamtales.core.theme.WarmTextSecondary
import kotlinx.coroutines.delay

/**
 * 沉浸式睡前开屏宣传动效界面。
 * 唯美静谧的月亮升起、柔光微星呼吸与温和标题浮现，1.8秒后平滑进入文库主页。
 */
@Composable
fun SplashScreen(
    onSplashFinished: () -> Unit
) {
    var animationStarted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        animationStarted = true
        delay(2000L)
        onSplashFinished()
    }

    // 月亮升起平滑弹簧
    val moonOffsetY by animateFloatAsState(
        targetValue = if (animationStarted) 0f else 60f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "moonOffset"
    )

    // 文字渐显动画
    val contentAlpha by animateFloatAsState(
        targetValue = if (animationStarted) 1f else 0f,
        animationSpec = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
        label = "contentAlpha"
    )

    // 光晕柔和呼吸
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NightBackground)
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
            // 中心月亮与呼吸光晕
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(160.dp)
                    .offset(y = moonOffsetY.dp)
            ) {
                // 外层柔光微光圈
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .scale(haloScale)
                        .background(WarmMoonYellow.copy(alpha = 0.08f), CircleShape)
                )

                // 中层暖光圈
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .background(WarmMoonYellow.copy(alpha = 0.15f), CircleShape)
                )

                // 核心月亮 Emoji
                Text(
                    text = "🌙",
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
                    color = WarmMoonYellow,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "经典伴读 · 温柔伴宝入梦",
                    color = WarmTextSecondary,
                    fontSize = 14.sp,
                    letterSpacing = 1.sp
                )
            }
        }

        // 底部轻微跳过提示
        Text(
            text = "轻触任意处跳过",
            color = WarmTextSecondary.copy(alpha = 0.4f),
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
                .alpha(contentAlpha)
        )
    }
}
