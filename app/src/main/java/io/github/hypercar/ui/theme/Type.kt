package io.github.hypercar.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字体规范：
 * - 标题：系统无衬线，大字重 + 正 letterSpacing（科技、克制）。
 * - 大数字 / 等宽场景（fps、时长、码率、日志）：[FontFamily.Monospace]，数字不跳动。
 */
val HyperCarTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 52.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 58.sp,
        letterSpacing = (-0.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 40.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 46.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 32.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 38.sp,
    ),
    headlineLarge = TextStyle(
        fontSize = 28.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 36.sp,
        letterSpacing = 0.2.sp,
    ),
    headlineMedium = TextStyle(
        fontSize = 24.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 32.sp,
        letterSpacing = 0.2.sp,
    ),
    headlineSmall = TextStyle(
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 28.sp,
        letterSpacing = 0.3.sp,
    ),
    titleLarge = TextStyle(
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 30.sp,
        letterSpacing = 0.4.sp,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 22.sp,
        letterSpacing = 0.3.sp,
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp,
    ),
    bodyLarge = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyMedium = TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 19.sp,
        letterSpacing = 0.1.sp,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 16.sp,
        letterSpacing = 0.1.sp,
    ),
    labelLarge = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 20.sp,
        letterSpacing = 0.8.sp,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 16.sp,
        letterSpacing = 0.6.sp,
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 15.sp,
        letterSpacing = 0.4.sp,
    ),
)

/** 统计芯片里的等宽指标值（fps、码率、时长）。 */
val MonoMetric = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    fontWeight = FontWeight.Medium,
    lineHeight = 18.sp,
    letterSpacing = 0.2.sp,
)

/** 英雄卡里的等宽时长（mm:ss）。 */
val MonoDuration = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 18.sp,
    fontWeight = FontWeight.Bold,
    lineHeight = 24.sp,
    letterSpacing = 0.6.sp,
)

/** 日志行（诊断页 / 首页预览）。 */
val MonoLogLine = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
    lineHeight = 16.sp,
)
