package io.github.hypercar.ui.screens

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import io.github.hypercar.settings.SettingsStore
import io.github.hypercar.ui.components.CapsuleButton
import io.github.hypercar.ui.components.GlassPanel
import io.github.hypercar.ui.components.SectionLabel
import io.github.hypercar.ui.theme.AmberWarning
import io.github.hypercar.ui.theme.ElectricCyan
import io.github.hypercar.ui.theme.MintGreen
import io.github.hypercar.ui.theme.StrokeGlass
import io.github.hypercar.ui.theme.SurfaceInset
import io.github.hypercar.ui.theme.TextPrimary
import io.github.hypercar.ui.theme.TextSecondary

/** 分辨率预设（宝马 iD8.5 曲面屏 16:6 带鱼比；宽度均为偶数）。 */
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
        Spacer(modifier = Modifier.height(8.dp))
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
        SectionLabel(text = "CarLife 编码分辨率")
        GlassPanel(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Text(
                text = "下发 0x10007 视频编码初始化；偶数像素，越界值自动钳制",
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
    var dimWhileConnected by rememberSaveable { mutableStateOf(SettingsStore.dimWhileConnected) }

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
            HorizontalDivider(color = StrokeGlass.copy(alpha = 0.5f))
            SwitchRow(
                title = "连接时降亮度",
                subtitle = "推流期间压低面板亮度省电",
                checked = dimWhileConnected,
                onCheckedChange = {
                    dimWhileConnected = it
                    SettingsStore.dimWhileConnected = it
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
