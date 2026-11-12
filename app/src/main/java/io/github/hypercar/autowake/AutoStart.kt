package io.github.hypercar.autowake

import android.bluetooth.BluetoothDevice
import android.content.Context

/**
 * 会话层注入的「车机蓝牙事件 → 会话」回调（由 Application 初始化时接线）。
 *
 * **契约（会话层实现本回调时必须遵守，避免 receiver↔service 循环）**：
 *  - connected=true：调用 `HyperCarRepo.connect`，由其去重并等待前台保活服务就绪。
 *    系统拒绝后台启动时报告失败，不绕过限制。
 *  - connected=false：仅关闭失效的 RFCOMM 链路，不等于用户停止投屏。
 *    Wi-Fi 丢失、AirPlay 心跳/通道失败由会话层处理，协商超时同样有界。
 *    不投递 ACTION_STOP，不取消来源绑定、诊断或自动恢复。
 *  - 重复广播必须幂等；连接重活一律放后台线程。保活服务不回灌蓝牙事件。
 */
object AutoStart {
    @Volatile
    var handler: ((Context, BluetoothDevice, Boolean) -> Unit)? = null
}
