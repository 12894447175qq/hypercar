package io.github.hypercar.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hypercar.perm.AppPermissions
import io.github.hypercar.session.HyperCarRepo
import io.github.hypercar.settings.SettingsStore
import io.github.hypercar.shizuku.ShizukuState
import io.github.hypercar.shizuku.ShizukuTools
import io.github.hypercar.ui.components.CapsuleButton
import io.github.hypercar.ui.components.GlassPanel
import io.github.hypercar.ui.components.SectionLabel
import io.github.hypercar.ui.theme.AmberWarning
import io.github.hypercar.ui.theme.CoralError
import io.github.hypercar.ui.theme.ElectricCyan
import io.github.hypercar.ui.theme.MintGreen
import io.github.hypercar.ui.theme.StrokeGlass
import io.github.hypercar.ui.theme.SurfaceInset
import io.github.hypercar.ui.theme.TextPrimary
import io.github.hypercar.ui.theme.TextSecondary
import io.github.hypercar.update.UpdateCenter
import io.github.hypercar.update.UpdateUiState
import kotlinx.coroutines.launch

/**
 * 分辨率预设（**兜底用**）。实际分辨率以车机 `/info` 回报为准，手机回报实际
 * 编码尺寸后还会再覆盖一次；只有两者都拿不到时才落到这里。宽度均为偶数。
 */
private data class ResolutionPreset(val label: String, val width: Int, val height: Int)

private val ResolutionPresets = listOf(
    ResolutionPreset("1920×720", 1920, 720),
    ResolutionPreset("1440×480", 1440, 480),
    ResolutionPreset("1280×480", 1280, 480),
)

private val FpsOptions = listOf(30, 60)

private val CarLifePackagePresets = listOf(
    "百度 CarLife" to "com.baidu.carlife",
    "小米 CarWith" to "com.xiaomi.carlink",
    "荣耀车联" to "com.hihonor.carlink",
)

/**
 * 设置页：全部直读直写 [SettingsStore]，改动即时生效（apply() 异步落盘，读路径内存可见）。
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TargetCarSection()
        ResolutionSection()
        BehaviorSection()
        WakePackageSection()
        PermissionSettingsSection()
        ShizukuSection()
        AboutSection()
        Spacer(modifier = Modifier.height(8.dp))
    }
}

/* ---------- Shizuku ---------- */

/**
 * Shizuku 入口。
 *
 * 之前只有一个**只读**状态芯片（`StatChips.ShizukuChip`），而 `requestPermissionIfNeeded`
 * 从未被调用——用户看到"未启用 Shizuku"却没有任何地方可以授权。这里按四种状态给出
 * 对应的下一步动作：安装 → 打开 → 授权 → 已就绪。
 */
@Composable
private fun ShizukuSection() {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val state = remember(version) { ShizukuTools.state(context) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) version++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val (statusText, statusColor) = when (state) {
        ShizukuState.Ready -> "已就绪" to MintGreen
        ShizukuState.NotGranted -> "已运行 · 待授权" to AmberWarning
        ShizukuState.NotRunning -> "已安装 · 未启动" to AmberWarning
        ShizukuState.NotInstalled -> "未安装" to TextSecondary
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(text = "Shizuku（可选增强）")
        GlassPanel(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(statusColor),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = state.label(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = state.hint(),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(12.dp))

            when (state) {
                ShizukuState.NotInstalled -> CapsuleButton(
                    text = "安装 Shizuku",
                    onClick = { ShizukuTools.openShizukuSite(context) },
                    modifier = Modifier.fillMaxWidth(),
                    filled = true,
                )

                ShizukuState.NotRunning -> CapsuleButton(
                    text = "打开 Shizuku 并启动服务",
                    onClick = {
                        if (!ShizukuTools.openShizukuApp(context)) {
                            ShizukuTools.openShizukuSite(context)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    filled = true,
                )

                ShizukuState.NotGranted -> CapsuleButton(
                    text = "请求授权",
                    onClick = {
                        ShizukuTools.requestPermissionIfNeeded { _ ->
                            // 无论允许/拒绝都刷新：授权框关闭后状态可能已变
                            version++
                            HyperCarRepo.refreshSystemState()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    filled = true,
                )

                ShizukuState.Ready -> CapsuleButton(
                    text = "重新检查",
                    onClick = {
                        version++
                        HyperCarRepo.refreshSystemState()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    filled = false,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "不启用也能用：降级模式用系统 WiFi 授权框连热点（每个热点首次弹一次），" +
                    "且拿不到真实蓝牙 MAC。启用后连热点零弹窗、可读真实 MAC。",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary.copy(alpha = 0.75f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun ShizukuState.label(): String = when (this) {
    ShizukuState.Ready -> "Shizuku 已授权"
    ShizukuState.NotGranted -> "Shizuku 服务在运行"
    ShizukuState.NotRunning -> "Shizuku 已安装但服务未启动"
    ShizukuState.NotInstalled -> "未检测到 Shizuku"
}

private fun ShizukuState.hint(): String = when (this) {
    ShizukuState.Ready -> "可以静默连接车机热点，并读取本机真实蓝牙 MAC。"
    ShizukuState.NotGranted ->
        "点下方按钮会弹出 Shizuku 的授权框；若没弹，请先在 Shizuku 里确认服务已启动。"
    ShizukuState.NotRunning ->
        "需要先打开 Shizuku 并启动服务（无线调试配对，或 root 启动）。"
    ShizukuState.NotInstalled ->
        "Shizuku 是可选的：装上后能免除每次连热点的系统确认框。不装也能正常使用。"
}

/* ---------- 权限与系统设置 ---------- */

/**
 * 设置页的权限入口（与首页的「权限检查」卡互补）：
 * 首页卡片在必需项齐备后会隐藏/可收起，这里**常驻**，方便随时回来复查或改系统开关。
 */
@Composable
private fun PermissionSettingsSection() {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val summary = remember(version) { AppPermissions.summary(context) }
    val missing = remember(version) { AppPermissions.missingRequired(context) }

    // 从系统设置页返回后自动刷新状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) version++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { version++ }

    val essentialsOk = missing.isEmpty()
    val missingNames = buildList {
        if (!summary.nearby) add("附近设备")
        if (summary.locationRequired && !summary.location) add("定位")
        if (summary.notificationRequired && !summary.notification) add("通知")
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(text = "权限与系统设置")
        GlassPanel(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (essentialsOk) "必需权限已就绪" else "缺 ${missingNames.size} 项必需权限",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (essentialsOk) MintGreen else AmberWarning,
                    )
                    Text(
                        text = if (essentialsOk) {
                            "附近设备与通知已授予"
                        } else {
                            missingNames.joinToString("、") + " 未授予——对应功能会静默失效"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = "重新检查",
                    style = MaterialTheme.typography.labelMedium,
                    color = ElectricCyan,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { version++ }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }

            if (!essentialsOk) {
                Spacer(modifier = Modifier.height(10.dp))
                CapsuleButton(
                    text = "补授缺失权限",
                    onClick = {
                        val all = missing.flatMap { AppPermissions.perms(it).toList() }.distinct()
                        if (all.isNotEmpty()) launcher.launch(all.toTypedArray())
                    },
                    modifier = Modifier.fillMaxWidth(),
                    filled = true,
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider(color = StrokeGlass.copy(alpha = 0.5f))

            InfoRow(
                label = "自启动",
                value = when (summary.autostart) {
                    true -> "已允许"
                    false -> "已禁止"
                    null -> "去设置"
                },
                onClick = {
                    if (!AppPermissions.openAutostartSettings(context)) {
                        AppPermissions.openAppDetails(context)
                    }
                },
            )
            InfoRow(
                label = "电池优化白名单",
                value = if (summary.batteryWhitelisted) "已加入" else "去设置",
                onClick = { AppPermissions.requestIgnoreBatteryOptimizations(context) },
            )
            InfoRow(
                label = "悬浮窗（后台启动豁免）",
                value = if (summary.overlay) "已允许" else "去设置",
                onClick = { AppPermissions.openOverlaySettings(context) },
            )
            InfoRow(
                label = "系统权限页",
                value = "打开",
                onClick = { AppPermissions.openAppDetails(context) },
            )

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "自启动、电池白名单属系统专有设置，部分系统不提供查询接口，" +
                    "显示「去设置」不代表未开启。",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary.copy(alpha = 0.75f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/* ---------- 关于 / 更新 ---------- */

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by UpdateCenter.state.collectAsStateWithLifecycle()
    val checking = state is UpdateUiState.Checking

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(text = "关于")
        GlassPanel(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        ) {
            InfoRow(label = "当前版本", value = UpdateCenter.currentVersion)
            InfoRow(label = "版本号（versionCode）", value = UpdateCenter.currentBuild.toString())
            InfoRow(label = "作者", value = "fw867")
            InfoRow(
                label = "开源仓库",
                value = "github.com/fw867/hypercar",
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/fw867/hypercar"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
            )

            Spacer(modifier = Modifier.height(10.dp))
            when (val s = state) {
                is UpdateUiState.Available -> {
                    Text(
                        text = "发现新版本 ${s.info.version}（当前 ${UpdateCenter.currentVersion}）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MintGreen,
                    )
                    if (s.info.notes.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = s.info.notes.take(400),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    CapsuleButton(
                        text = if (s.info.apkUrl != null) "下载新版本" else "打开 Release 页面",
                        onClick = {
                            val url = s.info.apkUrl ?: s.info.pageUrl
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        filled = true,
                        containerColor = MintGreen,
                    )
                }

                is UpdateUiState.UpToDate -> Text(
                    text = "已是最新版本（${s.latest}）",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )

                is UpdateUiState.Failed -> Text(
                    text = "检查失败：${s.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = CoralError,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )

                UpdateUiState.Checking -> Text(
                    text = "正在检查…",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )

                UpdateUiState.Idle -> Text(
                    text = "更新来自 GitHub Releases；每次推送由 CI 自动构建正式签名包",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            CapsuleButton(
                text = if (checking) "检查中…" else "检查更新",
                onClick = { scope.launch { UpdateCenter.check(context) } },
                modifier = Modifier.fillMaxWidth(),
                enabled = !checking,
                filled = false,
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = if (onClick != null) ElectricCyan else TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/* ---------- 目标车机 ---------- */

@Composable
private fun TargetCarSection() {
    var targetName by rememberSaveable {
        mutableStateOf(SettingsStore.targetCarName ?: "自动发现")
    }
    var dialogVisible by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(text = "目标车机")
        GlassPanel(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = targetName,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (targetName == "自动发现") TextSecondary else TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "仅列出已配对设备 · 未配置时来者不拒",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                CapsuleButton(
                    text = "选择",
                    onClick = { dialogVisible = true },
                    modifier = Modifier.height(44.dp),
                    filled = false,
                    contentColor = ElectricCyan,
                    borderColor = ElectricCyan.copy(alpha = 0.6f),
                )
            }
        }
    }

    if (dialogVisible) {
        TargetCarDialog(
            initialMac = SettingsStore.targetCarMac.orEmpty(),
            initialName = SettingsStore.targetCarName.orEmpty(),
            onDismiss = { dialogVisible = false },
            onCleared = {
                SettingsStore.targetCarMac = null
                SettingsStore.targetCarName = null
                targetName = "自动发现"
                dialogVisible = false
            },
            onSaved = { mac, name ->
                SettingsStore.targetCarMac = mac.ifBlank { null }
                SettingsStore.targetCarName = name.ifBlank { null }
                targetName = name.ifBlank { "自动发现" }
                dialogVisible = false
            },
        )
    }
}

/** 目标车机手动配置弹窗：玻璃面板自定义 Dialog（不用 Material 默认弹窗底色）。 */
@Composable
private fun TargetCarDialog(
    initialMac: String,
    initialName: String,
    onDismiss: () -> Unit,
    onCleared: () -> Unit,
    onSaved: (String, String) -> Unit,
) {
    var mac by rememberSaveable { mutableStateOf(initialMac) }
    var name by rememberSaveable { mutableStateOf(initialName) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xB302040A))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
                .padding(20.dp)
                .navigationBarsPadding(),
            contentAlignment = Alignment.Center,
        ) {
            GlassPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    // 吞掉面板内点击，避免误触关闭
                    .pointerInput(Unit) { detectTapGestures { } },
                contentPadding = PaddingValues(20.dp),
            ) {
                Text(
                    text = "目标车机",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                )
                Text(
                    text = "简化实现：手动输入蓝牙 MAC 与名称（与系统蓝牙设置一致）",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                )
                DialogTextField(
                    value = mac,
                    onValueChange = { mac = it },
                    label = "蓝牙 MAC（AA:BB:CC:DD:EE:FF）",
                    keyboardType = KeyboardType.Ascii,
                )
                Spacer(modifier = Modifier.height(12.dp))
                DialogTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "车机名称（如 BMW X6 ID8.5）",
                    keyboardType = KeyboardType.Text,
                )
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CapsuleButton(
                        text = "清除",
                        onClick = onCleared,
                        modifier = Modifier.height(44.dp),
                        filled = false,
                        contentColor = AmberWarning,
                        borderColor = AmberWarning.copy(alpha = 0.5f),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    CapsuleButton(
                        text = "取消",
                        onClick = onDismiss,
                        modifier = Modifier.height(44.dp),
                        filled = false,
                        contentColor = TextSecondary,
                        borderColor = StrokeGlass,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    CapsuleButton(
                        text = "保存",
                        onClick = { onSaved(mac.trim(), name.trim()) },
                        modifier = Modifier.height(44.dp),
                        filled = true,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DialogTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = RoundedCornerShape(14.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = SurfaceInset,
            unfocusedContainerColor = SurfaceInset,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            cursorColor = ElectricCyan,
            focusedLabelColor = ElectricCyan,
            unfocusedLabelColor = TextSecondary,
            focusedIndicatorColor = ElectricCyan,
            unfocusedIndicatorColor = StrokeGlass,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/* ---------- 分辨率 / 帧率 ---------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResolutionSection() {
    var selectedIndex by rememberSaveable {
        mutableStateOf(
            ResolutionPresets.indexOfFirst {
                it.width == SettingsStore.carlifeWidth && it.height == SettingsStore.carlifeHeight
            }.coerceAtLeast(0),
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(text = "CarLife 编码分辨率（兜底值）")
        GlassPanel(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Text(
                text = "实际分辨率以车机 /info 回报为准——连上后会自动改成车机原生尺寸，" +
                    "手机在编码器初始化完成后回报的实际值会再次覆盖它。" +
                    "这里只在这两者都拿不到时使用；下发前按偶数对齐。",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Spacer(modifier = Modifier.height(12.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ResolutionPresets.forEachIndexed { index, preset ->
                    SegmentedButton(
                        selected = selectedIndex == index,
                        onClick = {
                            selectedIndex = index
                            SettingsStore.carlifeWidth = preset.width
                            SettingsStore.carlifeHeight = preset.height
                        },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ResolutionPresets.size,
                        ),
                        label = {
                            Text(
                                text = preset.label,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                            )
                        },
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            FpsSegment()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FpsSegment() {
    var fps by rememberSaveable { mutableStateOf(SettingsStore.carlifeFps.coerceIn(30, 60)) }
    Text(
        text = "帧率",
        style = MaterialTheme.typography.bodySmall,
        color = TextSecondary,
    )
    Spacer(modifier = Modifier.height(8.dp))
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        FpsOptions.forEachIndexed { index, option ->
            SegmentedButton(
                selected = fps == option,
                onClick = {
                    fps = option
                    SettingsStore.carlifeFps = option
                },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = FpsOptions.size),
                label = {
                    Text(
                        text = "$option fps",
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
            )
        }
    }
}

/* ---------- 开关组 ---------- */

@Composable
private fun BehaviorSection() {
    var autoConnect by rememberSaveable { mutableStateOf(SettingsStore.autoConnect) }
    var enableAudio by rememberSaveable { mutableStateOf(SettingsStore.enableAudio) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(text = "行为")
        GlassPanel(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
        ) {
            SwitchRow(
                title = "上车自动连接",
                subtitle = "车机蓝牙 ACL 连上手机即唤醒协商",
                checked = autoConnect,
                onCheckedChange = {
                    autoConnect = it
                    SettingsStore.autoConnect = it
                },
            )
            HorizontalDivider(color = StrokeGlass.copy(alpha = 0.5f))
            SwitchRow(
                title = "音频转发",
                subtitle = "CarLife 音轨直通 AirPlay 通道",
                checked = enableAudio,
                onCheckedChange = {
                    enableAudio = it
                    SettingsStore.enableAudio = it
                },
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .heightIn(min = 56.dp)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = ElectricCyan,
                checkedTrackColor = ElectricCyan.copy(alpha = 0.28f),
                checkedBorderColor = ElectricCyan,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = SurfaceInset,
                uncheckedBorderColor = StrokeGlass,
            ),
        )
    }
}

/* ---------- 唤醒包名 ---------- */

@Composable
private fun WakePackageSection() {
    var pkg by rememberSaveable { mutableStateOf(SettingsStore.carlifePackage) }
    var menuExpanded by remember { mutableStateOf(false) }
    val isPreset = CarLifePackagePresets.any { it.second == pkg }
    val presetLabel = CarLifePackagePresets.firstOrNull { it.second == pkg }?.first

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(text = "CarLife 唤醒包名")
        GlassPanel(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Box {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(SurfaceInset)
                        .border(1.dp, StrokeGlass, RoundedCornerShape(14.dp))
                        .clickable { menuExpanded = true }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = presetLabel ?: "自定义",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (isPreset) TextPrimary else MintGreen,
                        )
                        Text(
                            text = pkg,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.ArrowDropDown,
                        contentDescription = "选择包名",
                        tint = ElectricCyan,
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier
                        .background(Color(0xF2101826))
                        .border(1.dp, StrokeGlass, RoundedCornerShape(16.dp)),
                ) {
                    CarLifePackagePresets.forEach { (label, packageName) ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(label, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        packageName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary,
                                    )
                                }
                            },
                            trailingIcon = {
                                if (pkg == packageName) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = "当前",
                                        tint = ElectricCyan,
                                    )
                                }
                            },
                            onClick = {
                                pkg = packageName
                                SettingsStore.carlifePackage = packageName
                                menuExpanded = false
                            },
                        )
                    }
                    HorizontalDivider(color = StrokeGlass.copy(alpha = 0.6f))
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "自定义…",
                                style = MaterialTheme.typography.titleSmall,
                                color = ElectricCyan,
                            )
                        },
                        onClick = {
                            pkg = ""
                            SettingsStore.carlifePackage = ""
                            menuExpanded = false
                        },
                    )
                }
            }
            if (!isPreset) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = pkg,
                    onValueChange = {
                        pkg = it
                        SettingsStore.carlifePackage = it
                    },
                    label = {
                        Text("自定义包名", style = MaterialTheme.typography.labelSmall)
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    shape = RoundedCornerShape(14.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = SurfaceInset,
                        unfocusedContainerColor = SurfaceInset,
                        focusedTextColor = MintGreen,
                        unfocusedTextColor = MintGreen,
                        cursorColor = ElectricCyan,
                        focusedLabelColor = ElectricCyan,
                        unfocusedLabelColor = TextSecondary,
                        focusedIndicatorColor = ElectricCyan,
                        unfocusedIndicatorColor = StrokeGlass,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
