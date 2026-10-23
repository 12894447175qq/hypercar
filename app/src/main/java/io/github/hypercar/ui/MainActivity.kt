package io.github.hypercar.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hypercar.core.LinkState
import io.github.hypercar.core.SessionSnapshot
import io.github.hypercar.session.HyperCarRepo
import io.github.hypercar.ui.screens.DiagnosticsScreen
import io.github.hypercar.ui.screens.HomeScreen
import io.github.hypercar.ui.screens.SettingsScreen
import io.github.hypercar.ui.theme.AmberWarning
import io.github.hypercar.ui.theme.CoralError
import io.github.hypercar.ui.theme.DeepSpace
import io.github.hypercar.ui.theme.ElectricCyan
import io.github.hypercar.ui.theme.HyperCarBackground
import io.github.hypercar.ui.theme.HyperCarTheme
import io.github.hypercar.ui.theme.MintGreen
import io.github.hypercar.ui.theme.SurfaceNavBar
import io.github.hypercar.ui.theme.TextPrimary
import io.github.hypercar.ui.theme.TextSecondary

/** 单 Activity：底部三 Tab（主页 / 设置 / 诊断），state-based 切换，无需 navigation-compose。 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 沉浸式：edge-to-edge + 透明状态栏，深空背景直铺到系统栏后面
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setContent {
            HyperCarTheme {
                MainContent()
            }
        }
    }
}

private enum class AppTab(val title: String, val icon: ImageVector) {
    Home("主页", Icons.Rounded.Home),
    Settings("设置", Icons.Rounded.Settings),
    Diagnostics("诊断", Icons.Rounded.BugReport),
}

@Composable
private fun MainContent() {
    val snapshot by HyperCarRepo.snapshots.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    val tabs = AppTab.entries
    val current = tabs[tabIndex.coerceIn(tabs.indices)]

    // 无 Shizuku 降级模式：申请连车机热点所需的运行时权限（12/12L=定位，13+=附近设备）。
    // 结果回来后刷新 Repo 快照，引导卡随之消失/推进。
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        HyperCarRepo.onDegradedPermissionResult()
    }

    Scaffold(
        containerColor = DeepSpace,
        topBar = { StatusStrip(link = snapshot.link) },
        bottomBar = {
            NavigationBar(
                containerColor = SurfaceNavBar,
                tonalElevation = 0.dp,
            ) {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = tabIndex == index,
                        onClick = { tabIndex = index },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.title,
                                modifier = Modifier.size(22.dp),
                            )
                        },
                        label = {
                            Text(
                                text = tab.title,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = ElectricCyan,
                            selectedTextColor = ElectricCyan,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary,
                            indicatorColor = ElectricCyan.copy(alpha = 0.14f),
                        ),
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            HyperCarBackground()
            Crossfade(
                targetState = current,
                animationSpec = tween(300),
                label = "tab-crossfade",
            ) { tab ->
                when (tab) {
                    AppTab.Home -> HomeScreen(
                        snapshot = snapshot,
                        onStart = { HyperCarRepo.connect(context) },
                        onStop = { HyperCarRepo.disconnect() },
                        onOpenDiagnostics = { tabIndex = AppTab.Diagnostics.ordinal },
                        onRequestDegradedPermission = {
                            permissionLauncher.launch(HyperCarRepo.degradedPermissionsToRequest())
                        },
                    )

                    AppTab.Settings -> SettingsScreen()

                    AppTab.Diagnostics -> DiagnosticsScreen(
                        logs = snapshot.recentLogs,
                        recording = snapshot.logRecording,
                        lastExportPath = snapshot.lastExportPath,
                        onToggleRecording = { HyperCarRepo.setLogRecording(it) },
                        onClearLogs = { HyperCarRepo.clearLogs() },
                        onExport = { HyperCarRepo.exportLogs() },
                    )
                }
            }
        }
    }
}

/**
 * 顶部窄条：连接态色点 + HyperCar 字标 + 车名缩写。
 */
@Composable
private fun StatusStrip(link: LinkState, modifier: Modifier = Modifier) {
    val dotColor = when (link) {
        is LinkState.Connected -> MintGreen
        is LinkState.Negotiating -> ElectricCyan
        is LinkState.Failed -> CoralError
        is LinkState.Stopping -> AmberWarning
        is LinkState.Idle -> TextSecondary
    }
    val carAbbreviation = (link as? LinkState.Connected)?.carName
        ?.uppercase()
        ?.take(4)
        ?: when (link) {
            is LinkState.Negotiating -> "协商中"
            is LinkState.Failed -> "连接失败"
            is LinkState.Stopping -> "断开中"
            else -> "待机"
        }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(DeepSpace)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(dotColor, CircleShape),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "HyperCar",
            style = MaterialTheme.typography.titleSmall.copy(
                letterSpacing = 2.sp,
                fontFamily = FontFamily.Monospace,
            ),
            color = TextPrimary,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = carAbbreviation,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
