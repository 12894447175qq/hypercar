package io.github.hypercar.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * HyperCar 调色板 —— 深空黑 + 电光青 + 玻璃拟态。
 * 全局唯一取色来源：组件一律引用这里的命名色，禁止在业务 UI 里散落十六进制。
 */

/** 全局背景（深空黑，略偏蓝）。 */
val DeepSpace = Color(0xFF05070C)

/** 玻璃面板半透底色（#101826 @ 70%）。 */
val PanelGlass = Color(0xB3101826)

/** 玻璃描边（1px）。 */
val StrokeGlass = Color(0xFF1E2A3A)

/** 主色：电光青（主按钮、焦点、选中态）。 */
val ElectricCyan = Color(0xFF00E5FF)

/** 辅色：星云紫（渐变另一端、次强调）。 */
val NebulaViolet = Color(0xFF7C4DFF)

/** 成功 / 已连接。 */
val MintGreen = Color(0xFF00FFA3)

/** 警告。 */
val AmberWarning = Color(0xFFFFB300)

/** 错误 / 断开。 */
val CoralError = Color(0xFFFF5252)

/** 主文字。 */
val TextPrimary = Color(0xFFE8F1F8)

/** 次级文字。 */
val TextSecondary = Color(0xFF7C8DA0)

/* ---------- 派生色（仅供主题内部 / 细节使用） ---------- */

/** 纯黑（onXxx 到亮底元素上时使用，本项目几乎全深色，保留备用）。 */
val InkBlack = Color(0xFF02040A)

/** 输入框 / 下拉等“内嵌面”。 */
val SurfaceInset = Color(0xFF0B1220)

/** 底部导航栏底色。 */
val SurfaceNavBar = Color(0xF2080D16)

/** 弹窗遮罩（深空黑 70%）。 */
val ScrimDeepSpace = Color(0xB303060B)
