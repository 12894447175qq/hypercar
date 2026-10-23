package io.github.hypercar.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.hypercar.core.SessionSnapshot
import io.github.hypercar.ui.components.GlassPanel
import io.github.hypercar.ui.components.StatChips
import io.github.hypercar.ui.components.StatusHeroCard
import io.github.hypercar.ui.theme.MonoLogLine
import io.github.hypercar.ui.theme.TextPrimary
import io.github.hypercar.ui.theme.TextSecondary

/**
 * 首页：英雄大卡 + 统计芯片 + 最近日志预览（最近 5 行，等宽小字，自动滚到底部）。
 * 横屏（BoxWithConstraints）切换为双栏：左英雄卡 + 芯片，右日志。
 */
@Composable
fun HomeScreen(
    snapshot: SessionSnapshot,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        if (landscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    StatusHeroCard(
                        link = snapshot.link,
                        onStartClick = onStart,
                        onStopClick = onStop,
                    )
                    StatChips(snapshot = snapshot)
                }
                LogPreviewCard(
                    logs = snapshot.recentLogs,
                    onOpenDiagnostics = onOpenDiagnostics,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                StatusHeroCard(
                    link = snapshot.link,
                    onStartClick = onStart,
                    onStopClick = onStop,
                )
                StatChips(snapshot = snapshot)
                LogPreviewCard(
                    logs = snapshot.recentLogs,
                    onOpenDiagnostics = onOpenDiagnostics,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * 最近日志预览：最近 5 行等宽小字，reverseLayout 让最新行贴底并自动跟随。
 */
@Composable
private fun LogPreviewCard(
    logs: List<String>,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val recent = remember(logs) { logs.takeLast(5) }
    val listState = rememberLazyListState()
    LaunchedEffect(recent.size) {
        if (recent.isNotEmpty()) listState.scrollToItem(0)
    }
    GlassPanel(
        modifier = modifier.clickable(onClick = onOpenDiagnostics),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 12.dp,
            bottom = 12.dp,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "最近日志",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "全部 →",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (recent.isEmpty()) {
            Text(
                text = "暂无日志，连接一次车机后这里会显示链路轨迹",
                style = MonoLogLine,
                color = TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                items(recent.reversed()) { line ->
                    LogLineRow(line = line)
                }
            }
        }
    }
}

/** 单行日志：等宽 11sp，按级别（E/W/I/D）着色。解析逻辑见 [parseLogLine]。 */
@Composable
internal fun LogLineRow(line: String, modifier: Modifier = Modifier) {
    val parsed = remember(line) { parseLogLine(line) }
    val color = logLevelColor(parsed.level)
    Row(modifier = modifier.fillMaxWidth()) {
        Text(
            text = levelTag(parsed.level),
            style = MonoLogLine,
            color = color,
            modifier = Modifier.padding(end = 6.dp),
        )
        Text(
            text = parsed.text,
            style = MonoLogLine,
            color = if (parsed.level == 'E') color else TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
