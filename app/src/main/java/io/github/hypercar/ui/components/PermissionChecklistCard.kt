package io.github.hypercar.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.hypercar.perm.PermGroup
import io.github.hypercar.perm.PermissionSummary
import io.github.hypercar.ui.theme.AmberWarning
import io.github.hypercar.ui.theme.ElectricCyan
import io.github.hypercar.ui.theme.MintGreen
import io.github.hypercar.ui.theme.TextPrimary
import io.github.hypercar.ui.theme.TextSecondary

/**
 * 首启权限检查清单。
 *
 * 背景：HyperOS / MIUI 对**侧载安装**的应用默认拒绝通知、定位等权限，且不会主动
 * 提示；此前应用只申请「附近设备」，导致蓝牙（iAP2 / 上车唤醒）一直处于未授权状态，
 * 用户看不到任何异常。这里把所有与功能相关的项列出来，逐项给可操作入口。
 *
 * 分两类：
 *  - **必需**（附近设备 / 定位 12-12L / 通知 13+）：不授权则对应功能直接不可用；
 *  - **建议**（后台启动、电池白名单、自启动）：只影响"上车自动唤醒"的可靠性。
 */
@Composable
fun PermissionChecklistCard(
    summary: PermissionSummary,
    locationServiceOn: Boolean,
    onRequest: (PermGroup) -> Unit,
    onRequestAll: () -> Unit,
    onOpenOverlay: () -> Unit,
    onBattery: () -> Unit,
    onAutostart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val requiredMissing = buildList {
        if (!summary.nearby) add(PermGroup.Nearby)
        if (summary.locationRequired && !summary.location) add(PermGroup.Location)
        if (summary.notificationRequired && !summary.notification) add(PermGroup.Notification)
    }.size
    val optionalMissing =
        (if (!summary.overlay) 1 else 0) + (if (!summary.batteryWhitelisted) 1 else 0)
    val totalMissing = requiredMissing + optionalMissing
    if (totalMissing == 0) return // 全部就绪：不占首页空间

    GlassPanel(
        modifier = modifier.fillMaxWidth(),
        glow = requiredMissing > 0,
        glowColor = if (requiredMissing > 0) AmberWarning else ElectricCyan,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Shield,
                contentDescription = null,
                tint = if (requiredMissing > 0) AmberWarning else ElectricCyan,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.size(10.dp))
            Text(
                text = "权限检查",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (requiredMissing > 0) "缺 $requiredMissing 项必需" else "建议 $optionalMissing 项",
                style = MaterialTheme.typography.labelSmall,
                color = if (requiredMissing > 0) AmberWarning else TextSecondary,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "系统对侧载应用默认拒绝通知/定位等权限，需手动授予；否则对应功能会静默失效。",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(12.dp))

        PermRow(
            title = "附近设备（蓝牙 + Wi-Fi）",
            detail = "连接车机热点、iAP2 配对、上车自动唤醒",
            granted = summary.nearby,
            required = true,
            onClick = { onRequest(PermGroup.Nearby) },
        )
        if (summary.locationRequired) {
            PermRow(
                title = "定位",
                detail = if (summary.location && !locationServiceOn) {
                    "已授权，但系统定位服务未开启——Android 12 仍连不上热点"
                } else {
                    "Android 12/12L 连车机热点需要"
                },
                granted = summary.location && locationServiceOn,
                required = true,
                onClick = { onRequest(PermGroup.Location) },
            )
        }
        if (summary.notificationRequired) {
            PermRow(
                title = "通知",
                detail = "前台服务常驻通知；被隐藏时部分系统会回收服务",
                granted = summary.notification,
                required = true,
                onClick = { onRequest(PermGroup.Notification) },
            )
        }
        PermRow(
            title = "后台启动（显示在其他应用上层）",
            detail = "Android 12+ 后台拉起前台服务的一条豁免，上车唤醒依赖它",
            granted = summary.overlay,
            required = false,
            onClick = onOpenOverlay,
        )
        PermRow(
            title = "电池优化白名单",
            detail = "避免熄屏后被视为低电耗限流",
            granted = summary.batteryWhitelisted,
            required = false,
            onClick = onBattery,
        )
        PermRow(
            title = "自启动（HyperOS / MIUI）",
            detail = "系统专有开关；不开启则杀进程后收不到蓝牙广播",
            granted = false,
            required = false,
            actionText = "去设置",
            alwaysAction = true,
            onClick = onAutostart,
        )

        if (requiredMissing > 0) {
            Spacer(modifier = Modifier.height(14.dp))
            CapsuleButton(
                text = "一键授予必需权限",
                onClick = onRequestAll,
                modifier = Modifier.fillMaxWidth(),
                filled = true,
            )
        }
    }
}

@Composable
private fun PermRow(
    title: String,
    detail: String,
    granted: Boolean,
    required: Boolean,
    onClick: () -> Unit,
    actionText: String = "去授予",
    alwaysAction: Boolean = false,
) {
    val showAction = alwaysAction || !granted
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .then(if (showAction) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (granted) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = MintGreen,
                modifier = Modifier.size(15.dp),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(15.dp)
                    .clip(CircleShape)
                    .background((if (required) AmberWarning else TextSecondary).copy(alpha = 0.35f)),
            )
        }
        Spacer(modifier = Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (granted) TextSecondary else TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary.copy(alpha = 0.75f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showAction) {
            Text(
                text = actionText,
                style = MaterialTheme.typography.labelMedium,
                color = ElectricCyan,
                modifier = Modifier.padding(start = 10.dp),
                maxLines = 1,
            )
        }
    }
}
