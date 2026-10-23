package io.github.hypercar.perm

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 首启权限引导的**唯一权威清单**。
 *
 * 为什么要单独一份：此前只在降级连接时申请「附近设备/定位」，而
 * `BLUETOOTH_CONNECT` 从未被申请——但 iAP2 RFCOMM、上车 ACL 广播唤醒
 * 全都依赖它，等于秒连链路在用户手动授权前根本起不来。
 * HyperOS / MIUI 对侧载应用还会把通知等默认拒绝，必须主动申请并给出状态。
 *
 * 分版本说明：
 *  - **Nearby（附近设备）**：Android 12+ 的 `BLUETOOTH_CONNECT/SCAN` 与 13+ 的
 *    `NEARBY_WIFI_DEVICES` 同属系统「附近设备」组，一次弹窗即可覆盖。
 *  - **Location（定位）**：仅 Android 12/12L 的 `WifiNetworkSpecifier` 需要；
 *    13+ 因为有 `neverForLocation` 的附近设备权限，不再需要定位。
 *  - **Notification（通知）**：13+ 默认拒绝；前台服务通知被隐藏时部分 ROM 会回收服务。
 *
 * 特殊权限（不在运行时弹窗，只能深链系统页）：
 *  - `SYSTEM_ALERT_WINDOW`：Android 12+ 后台启动前台服务的一条豁免（上车自动唤醒要用）。
 *  - 电池优化白名单：避免熄屏后被判低电耗限流。
 *  - 自启动（HyperOS/MIUI 专有）：不授权则杀进程后收不到蓝牙广播。
 */
enum class PermGroup {
    /** 蓝牙 + Wi-Fi 附近设备（连接车机热点 / iAP2 / 上车唤醒）。 */
    Nearby,

    /** 定位（仅 Android 12/12L 的 Wi-Fi specifier 需要）。 */
    Location,

    /** 通知（前台服务常驻通知）。 */
    Notification,
}

/** 一次性快照，供 UI 判断显示哪些行、是否有待办。 */
data class PermissionSummary(
    val nearby: Boolean,
    val location: Boolean,
    val locationRequired: Boolean,
    val notification: Boolean,
    val notificationRequired: Boolean,
    val overlay: Boolean,
    val batteryWhitelisted: Boolean,
) {
    /** 所有"必需"项是否齐备（特殊权限不算必需，只提示）。 */
    val requiredOk: Boolean
        get() = nearby &&
            (!locationRequired || location) &&
            (!notificationRequired || notification)

    /** 是否还有任何可优化项（用于决定卡片是否显示）。 */
    val hasTodo: Boolean
        get() = !requiredOk || !overlay || !batteryWhitelisted
}

object AppPermissions {

    /** 某组在**当前系统版本**下实际需要申请的运行时权限。 */
    fun perms(group: PermGroup): Array<String> = when (group) {
        PermGroup.Nearby -> buildList {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }.toTypedArray()

        PermGroup.Location -> arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        PermGroup.Notification ->
            if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
    }

    /** 当前版本下**必需**的运行时权限组。 */
    fun requiredGroups(): List<PermGroup> = buildList {
        add(PermGroup.Nearby)
        if (Build.VERSION.SDK_INT <= 32) add(PermGroup.Location)
        if (Build.VERSION.SDK_INT >= 33) add(PermGroup.Notification)
    }

    /** 缺失的必需组（用于一键申请与连接前预检）。 */
    fun missingRequired(context: Context): List<PermGroup> =
        requiredGroups().filterNot { isGranted(context, it) }

    /** 首启自动申请只发起一次（避免每次进前台都弹窗骚扰）。 */
    fun wasPrompted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PROMPTED, false)

    fun markPrompted(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PROMPTED, true).apply()
    }

    private const val PREFS = "hypercar_permissions"
    private const val KEY_PROMPTED = "onboarding_prompted"

    fun isGranted(context: Context, group: PermGroup): Boolean {
        val list = perms(group)
        if (list.isEmpty()) return true
        return list.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** 定位服务是否开启（仅 12/12L 的 specifier 需要）。 */
    fun isLocationServiceOn(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33) return true
        return runCatching {
            context.getSystemService(android.location.LocationManager::class.java)?.isLocationEnabled == true
        }.getOrDefault(false)
    }

    fun hasOverlay(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 23) Settings.canDrawOverlays(context) else true

    fun isBatteryWhitelisted(context: Context): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    }.getOrDefault(false)

    fun summary(context: Context): PermissionSummary = PermissionSummary(
        nearby = isGranted(context, PermGroup.Nearby),
        location = isGranted(context, PermGroup.Location),
        locationRequired = Build.VERSION.SDK_INT <= 32,
        notification = isGranted(context, PermGroup.Notification),
        notificationRequired = Build.VERSION.SDK_INT >= 33,
        overlay = hasOverlay(context),
        batteryWhitelisted = isBatteryWhitelisted(context),
    )

    // ------------------------------------------------------------ 深链

    /** 应用详情页（权限被永久拒绝时唯一的恢复入口）。 */
    fun openAppDetails(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** 悬浮窗（显示在其他应用上层）授权页。 */
    fun openOverlaySettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.fromParts("package", context.packageName, null),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { openAppDetails(context) }
    }

    /**
     * 电池优化白名单。优先弹系统"是否允许后台运行"对话框
     * （`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`，manifest 已声明权限），
     * 部分 ROM 不认则退回列表页。
     */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        val direct = runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.fromParts("package", context.packageName, null),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false)
        if (direct) return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { openAppDetails(context) }
    }

    fun openLocationSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * 自启动管理页（HyperOS / MIUI 专有，非 AOSP 标准 API）。
     * 返回 false 表示没找到对应页面，调用方应退回应用详情页。
     */
    fun openAutostartSettings(context: Context): Boolean {
        val candidates = listOf(
            // HyperOS / MIUI
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
            // HyperOS 新版入口
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.powercenter.autostart.AutoStartManagementActivity",
            ),
            // 华为 / 荣耀
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
            // ColorOS / realme
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            ),
        )
        for (component in candidates) {
            val ok = runCatching {
                context.startActivity(
                    Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                true
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }
}
