package io.github.hypercar.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.hypercar.core.AudioStats
import io.github.hypercar.core.CarLifeState
import io.github.hypercar.core.SessionSnapshot
import io.github.hypercar.core.VideoStats
import io.github.hypercar.ui.theme.AmberWarning
import io.github.hypercar.ui.theme.ElectricCyan
import io.github.hypercar.ui.theme.MintGreen
import io.github.hypercar.ui.theme.MonoMetric
import io.github.hypercar.ui.theme.StrokeGlass
import io.github.hypercar.ui.theme.SurfaceInset
import io.github.hypercar.ui.theme.TextSecondary
import java.util.Locale

/**
 * 统计芯片行：视频 / CarLife / 音频 / Shizuku。
 * 单行横向滚动（窄屏也能全部看到），每枚芯片最小高度 52dp 保证触达一致。
 */
@Composable
fun StatChips(
    snapshot: SessionSnapshot,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        VideoChip(snapshot.video)
        CarLifeChip(snapshot.carLife)
        AudioChip(snapshot.audio)
        ShizukuChip(snapshot.shizukuReady)
    }
}

/* ---------- 单枚芯片 ---------- */

@Composable
private fun StatChip(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .heightIn(min = 52.dp)
            .widthIn(min = ChipMinWidth)
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceInset.copy(alpha = 0.65f))
            .border(1.dp, StrokeGlass, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
        } else {
            StatusDot(color = accent)
            Spacer(modifier = Modifier.width(8.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                maxLines = 1,
            )
            Text(
                text = value,
                style = MonoMetric,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/* ---------- 各链路状态 → 芯片 ---------- */

@Composable
private fun VideoChip(video: VideoStats) {
    val idle = video.fps <= 0
    val accent = if (idle) TextSecondary else ElectricCyan
    val value = if (idle) {
        "未推流"
    } else {
        buildString {
            append(video.fps).append(" fps")
            if (video.resolution.isNotEmpty()) {
                append(" · ").append(video.resolution)
            }
            if (video.kbps > 0) {
                append(" · ").append(formatBitrate(video.kbps))
            }
        }
    }
    StatChip(
        label = "视频",
        value = value,
        accent = accent,
        leadingIcon = Icons.Rounded.Videocam,
    )
}

@Composable
private fun CarLifeChip(carLife: CarLifeState) {
    when (carLife) {
        is CarLifeState.Idle -> StatChip(
            label = "CarLife",
            value = "未接入",
            accent = TextSecondary,
            leadingIcon = Icons.Rounded.GraphicEq,
        )

        is CarLifeState.Waiting -> StatChip(
            label = "CarLife",
            value = "等待接入 :${carLife.port}",
            accent = AmberWarning,
            leadingIcon = Icons.Rounded.GraphicEq,
        )

        is CarLifeState.Active -> StatChip(
            label = "CarLife",
            value = "已接入 · ${carLife.appLabel}",
            accent = MintGreen,
            leadingIcon = Icons.Rounded.GraphicEq,
        )
    }
}

@Composable
private fun AudioChip(audio: AudioStats) {
    val (accent, value) = when {
        audio.ducking -> AmberWarning to "导航闪避中"
        audio.playing -> MintGreen to "播放中"
        else -> TextSecondary to "待机"
    }
    StatChip(
        label = "音频",
        value = value,
        accent = accent,
        leadingIcon = Icons.Rounded.Navigation,
    )
}

@Composable
private fun ShizukuChip(ready: Boolean) {
    // Shizuku 已降级为「可选增强」：没有它也能连车（热点首连弹一次框 + 稳定伪 MAC），
    // 有则体验最优（0 弹窗 + 真实蓝牙 MAC）。故未就绪时用中性灰、文案标「可选」，不报警色。
    StatChip(
        label = "Shizuku",
        value = if (ready) "增强已启用" else "可选未启用",
        accent = if (ready) MintGreen else TextSecondary,
        leadingIcon = Icons.Rounded.Security,
    )
}

/* ---------- 工具 ---------- */

private fun formatBitrate(kbps: Long): String = if (kbps >= 1000L) {
    String.format(Locale.US, "%.1f Mbps", kbps / 1000.0)
} else {
    "$kbps kbps"
}

/** 芯片最小宽度，保证短文案芯片不至于太窄。 */
private val ChipMinWidth = 110.dp
