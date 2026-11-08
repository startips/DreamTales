package com.bedtime.dreamtales.core.theme

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * 深度暖暗色彩方案（默认·夜间伴读）
 */
val DreamTalesDarkColorScheme = darkColorScheme(
    primary = WarmMoonYellow,
    onPrimary = WarmMoonYellowDark,
    primaryContainer = WarmMoonYellowContainer,
    onPrimaryContainer = WarmMoonYellowOnContainer,
    secondary = WarmMoonYellowDim,
    onSecondary = NightBackground,
    secondaryContainer = NightSurfaceContainerHigh,
    onSecondaryContainer = WarmTextSecondary,
    tertiary = WarmMoonYellowDim,
    onTertiary = NightBackground,
    tertiaryContainer = WarmMoonYellowContainer,
    onTertiaryContainer = WarmMoonYellowOnContainer,
    background = NightBackground,
    onBackground = WarmTextPrimary,
    surface = NightSurface,
    onSurface = WarmTextPrimary,
    surfaceVariant = NightSurfaceVariant,
    onSurfaceVariant = WarmTextSecondary,
    surfaceContainerLowest = NightSurfaceContainerLowest,
    surfaceContainerLow = NightSurfaceContainerLow,
    surfaceContainer = NightSurfaceContainer,
    surfaceContainerHigh = NightSurfaceContainerHigh,
    surfaceContainerHighest = NightSurfaceContainerHighest,
    surfaceDim = NightSurfaceDim,
    surfaceBright = NightSurfaceBright,
    outline = NightCardBorder,
    outlineVariant = NightBorderHighlight,
    error = WarmAlertRed,
    onError = NightBackground,
    errorContainer = WarmAlertRedContainer,
    onErrorContainer = WarmAlertRed
)

/**
 * 日间温润绘本色彩方案（羊皮纸护眼米黄色）
 */
val DreamTalesLightColorScheme = lightColorScheme(
    primary = PaperPrimary,
    onPrimary = PaperOnPrimary,
    primaryContainer = PaperPrimaryContainer,
    onPrimaryContainer = PaperOnPrimaryContainer,
    secondary = PaperSecondary,
    onSecondary = PaperOnSecondary,
    secondaryContainer = PaperSecondaryContainer,
    onSecondaryContainer = PaperOnSecondaryContainer,
    tertiary = PaperSecondary,
    onTertiary = PaperOnSecondary,
    tertiaryContainer = PaperPrimaryContainer,
    onTertiaryContainer = PaperOnPrimaryContainer,
    background = PaperBackground,
    onBackground = PaperTextPrimary,
    surface = PaperSurface,
    onSurface = PaperTextPrimary,
    surfaceVariant = PaperSurfaceVariant,
    onSurfaceVariant = PaperTextSecondary,
    surfaceContainerLowest = PaperSurfaceContainerLowest,
    surfaceContainerLow = PaperSurfaceContainerLow,
    surfaceContainer = PaperSurfaceContainer,
    surfaceContainerHigh = PaperSurfaceContainerHigh,
    surfaceContainerHighest = PaperSurfaceContainerHighest,
    surfaceDim = PaperSurfaceDim,
    surfaceBright = PaperSurfaceBright,
    outline = PaperOutline,
    outlineVariant = PaperOutlineVariant,
    error = WarmAlertRed,
    onError = PaperSurface,
    errorContainer = PaperPrimaryContainer,
    onErrorContainer = WarmAlertRed
)

/**
 * Material Design 3 标准 Shape 比例尺
 */
val DreamTalesShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun DreamTalesTheme(
    themeMode: String = "night", // "night", "day", "system"
    content: @Composable () -> Unit
) {
    val systemInDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        "day" -> false
        "system" -> systemInDark
        else -> true // default "night"
    }

    val colorScheme = if (isDark) DreamTalesDarkColorScheme else DreamTalesLightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        // 用 SideEffect 在组合结果应用时立即同步窗口底色/系统栏，比 LaunchedEffect 少一帧延迟。
        // 用 lastAppliedTheme 去重，避免每次重组都重新设置 Drawable。
        val lastAppliedTheme = remember { longArrayOf(Long.MIN_VALUE) }
        SideEffect {
            val backgroundArgb = colorScheme.background.toArgb()
            val themeKey = (backgroundArgb.toLong() shl 1) or if (isDark) 1L else 0L
            if (lastAppliedTheme[0] != themeKey) {
                lastAppliedTheme[0] = themeKey
                val window = (view.context as? Activity)?.window
                if (window != null) {
                    // 主题切换/冷启动时同步窗口底色，避免 Compose 绘制前露出浅色背景
                    window.setBackgroundDrawable(ColorDrawable(backgroundArgb))
                    window.statusBarColor = backgroundArgb
                    window.navigationBarColor = backgroundArgb
                    val controller = WindowCompat.getInsetsController(window, view)
                    // 浅色背景使用深色图标，深色背景使用浅色图标
                    controller.isAppearanceLightStatusBars = !isDark
                    controller.isAppearanceLightNavigationBars = !isDark
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = DreamTalesTypography,
        shapes = DreamTalesShapes,
        content = content
    )
}
