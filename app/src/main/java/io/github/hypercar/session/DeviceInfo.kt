package io.github.hypercar.session

import android.content.Context
import android.os.Build

/**
 * 环境自述：**设备型号 + 系统版本（含 ROM 显示号）+ SDK + HyperCar 版本号**。
 *
 * 为什么单独抽出来：排查日志时第一个问题永远是「哪台设备、什么系统、哪个版本」。
 * 以前的导出头只有 `Xiaomi M154FF / Android 17`——看不出 HyperOS 版本、编译号
 * （很多坑和 ROM 版本强相关），也看不出 APK 版本（用户说「更新后就不行了」时要靠它对
 * 提交）。这段同时写进两处：导出的日志头 + 启动时的第一行日志。
 */
object DeviceInfo {

    /** 设备 + 系统一行（不含 App 版本，便于两行分别排版）。 */
    fun systemSummary(): String =
        buildString {
            append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            append("（device=").append(Build.DEVICE)
            if (Build.PRODUCT.isNotBlank() && Build.PRODUCT != Build.DEVICE) {
                append("、product=").append(Build.PRODUCT)
            }
            append("）")
            append("｜Android ").append(Build.VERSION.RELEASE)
            append("（SDK ").append(Build.VERSION.SDK_INT).append("）")
            // Build.DISPLAY 在 Xiaomi/HyperOS 上是 "OS3.0.xxx.WOCMIXM" 这类 ROM 显示号；
            // INCREMENTAL 是编译号（常带日期），两者合起来能唯一定位一次系统更新。
            append("｜系统版本 ").append(Build.DISPLAY.ifBlank { "?" })
            if (Build.VERSION.INCREMENTAL.isNotBlank() && Build.VERSION.INCREMENTAL != Build.DISPLAY) {
                append("（编译 ").append(Build.VERSION.INCREMENTAL).append("）")
            }
        }

    /** HyperCar 版本号：`0.1.0.32（versionCode 10032）`。 */
    fun appVersion(context: Context): String =
        runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            val code =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pi.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    pi.versionCode.toLong()
                }
            "${pi.versionName ?: "?"}（versionCode $code）"
        }.getOrDefault("未知（无法读取 PackageInfo）")

    /** 一行完整自述（启动日志用）：`HyperCar 0.1.0.32（versionCode 10032）｜Xiaomi …`。 */
    fun fullSummary(context: Context): String = "HyperCar ${appVersion(context)}｜${systemSummary()}"
}
