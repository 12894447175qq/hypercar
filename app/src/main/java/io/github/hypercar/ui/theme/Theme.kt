package io.github.hypercar.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 渐变集合：全局通过 [LocalGradients] 提供，组件用它保持视觉一致。 */
@Immutable
data class GradientSpecs(
    /** 主强调渐变（实心主按钮等）。 */
    val accent: Brush,
    /** 待机态英雄卡渐变。 */
    val heroIdle: Brush,
    /** 协商态英雄卡渐变。 */
    val heroNegotiating: Brush,
    /** 已连接英雄卡渐变。 */
    val heroConnected: Brush,
    /** 失败态英雄卡渐变。 */
    val heroFailed: Brush,
    /** 屏幕底色渐变。 */
    val screenBase: Brush,
)

/** 玻璃拟态参数：全局通过 [LocalGlass] 提供。 */
@Immutable
data class GlassSpecs(
    /** 面板半透填充。 */
    val panelBrush: Brush,
    /** 1px 描边基色。 */
    val strokeColor: Color,
    /** 描边宽度。 */
    val strokeWidth: Dp = 1.dp,
    /** 圆角。 */
    val cornerRadius: Dp = 20.dp,
    /** 辉光颜色（可选发光）。 */
    val glowColor: Color,
    /** 遮罩色（弹窗）。 */
    val scrimColor: Color,
)

private val DefaultGradients = GradientSpecs(
    accent = Brush.horizontalGradient(
        listOf(ElectricCyan, ElectricCyan.copy(alpha = 0.80f)),
    ),
    heroIdle = Brush.linearGradient(
        listOf(ElectricCyan, NebulaViolet),
        start = Offset(0f, 0f),
        end = Offset.Infinite,
    ),
    heroNegotiating = Brush.linearGradient(
        listOf(NebulaViolet, ElectricCyan),
        start = Offset(0f, 0f),
        end = Offset.Infinite,
    ),
    heroConnected = Brush.linearGradient(
        listOf(MintGreen, ElectricCyan),
        start = Offset(0f, 0f),
        end = Offset.Infinite,
    ),
    heroFailed = Brush.linearGradient(
        listOf(CoralError, AmberWarning),
        start = Offset(0f, 0f),
        end = Offset.Infinite,
    ),
    screenBase = Brush.verticalGradient(
        listOf(Color(0xFF080C14), DeepSpace, Color(0xFF04060A)),
    ),
)

private val DefaultGlass = GlassSpecs(
    panelBrush = Brush.verticalGradient(
        listOf(Color(0xB3101826), Color(0xD90A1019)),
    ),
    strokeColor = StrokeGlass,
    glowColor = ElectricCyan,
    scrimColor = ScrimDeepSpace,
)

val LocalGradients = staticCompositionLocalOf { DefaultGradients }
val LocalGlass = staticCompositionLocalOf { DefaultGlass }

/** HyperCar 全局主题：Material3 dark 全套映射 + 自定义渐变 / 玻璃参数。 */
@Composable
fun HyperCarTheme(
    gradients: GradientSpecs = DefaultGradients,
    glass: GlassSpecs = DefaultGlass,
    content: @Composable () -> Unit,
) {
    val colorScheme = darkColorScheme(
        primary = ElectricCyan,
        onPrimary = InkBlack,
        primaryContainer = ElectricCyan.copy(alpha = 0.16f),
        onPrimaryContainer = ElectricCyan,
        secondary = NebulaViolet,
        onSecondary = InkBlack,
        secondaryContainer = NebulaViolet.copy(alpha = 0.22f),
        onSecondaryContainer = NebulaViolet,
        tertiary = MintGreen,
        onTertiary = InkBlack,
        tertiaryContainer = MintGreen.copy(alpha = 0.16f),
        onTertiaryContainer = MintGreen,
        error = CoralError,
        onError = InkBlack,
        errorContainer = CoralError.copy(alpha = 0.18f),
        onErrorContainer = CoralError,
        background = DeepSpace,
        onBackground = TextPrimary,
        surface = DeepSpace,
        onSurface = TextPrimary,
        surfaceVariant = Color(0xFF131C2B),
        onSurfaceVariant = TextSecondary,
        surfaceTint = ElectricCyan,
        inverseSurface = TextPrimary,
        inverseOnSurface = DeepSpace,
        inversePrimary = Color(0xFF006B7D),
        outline = StrokeGlass,
        outlineVariant = Color(0xFF16202E),
        scrim = Color.Black,
        surfaceContainerLowest = Color(0xFF04060A),
        surfaceContainerLow = Color(0xFF080D15),
        surfaceContainer = Color(0xFF0B111C),
        surfaceContainerHigh = Color(0xFF101826),
        surfaceContainerHighest = Color(0xFF16202F),
    )

    CompositionLocalProvider(
        LocalGradients provides gradients,
        LocalGlass provides glass,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = HyperCarTypography,
            content = content,
        )
    }
}

/**
 * 深空背景：竖向基色渐变 + 右上青辉光 + 左下紫辉光。
 * 无动画（车机场景避免分心），纯静态氛围层。
 */
@Composable
fun HyperCarBackground(modifier: Modifier = Modifier) {
    val base = LocalGradients.current.screenBase
    Canvas(modifier = modifier.fillMaxSize()) {
        drawBackground(base)
    }
}

private fun DrawScope.drawBackground(base: Brush) {
    val cyanGlow = Brush.radialGradient(
        listOf(ElectricCyan.copy(alpha = 0.10f), Color.Transparent),
        center = Offset(size.width * 0.92f, -size.height * 0.05f),
        radius = size.width * 0.85f,
    )
    val violetGlow = Brush.radialGradient(
        listOf(NebulaViolet.copy(alpha = 0.13f), Color.Transparent),
        center = Offset(-size.width * 0.10f, size.height * 1.05f),
        radius = size.width * 0.95f,
    )
    drawRect(base)
    drawRect(cyanGlow)
    drawRect(violetGlow)
}
