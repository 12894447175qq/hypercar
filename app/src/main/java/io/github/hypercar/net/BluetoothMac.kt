package io.github.hypercar.net

import android.os.SystemClock
import io.github.hypercar.shizuku.ShizukuTools
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 本机真实蓝牙 MAC 解析（无线 CarPlay/CarLife 协议身份必需）。
 *
 * Android 11+ 应用层调 `BluetoothAdapter.getAddress()` 只会拿到占位 `02:00:00:00:00:00`；
 * 车机会校验协议里的 bluetoothTransportIdentifier 是否与 RFCOMM 对端一致，占位值会被静默丢弃
 * （现象：链路层照常 ACK，上层永远等不到后续消息）。这里用 Shizuku shell 读系统安全设置
 * （shell 身份免 root）：`content query --uri content://settings/secure/bluetooth_address`。
 *
 * 结果**进程内缓存**；解析失败进入 10s 静默窗口（窗口内直接返回 null，避免每次连接都阻塞在
 * shell 查询上）；`force=true` 跳过缓存与静默窗口立即重查（占位 MAC 需要能立刻重解析）。
 */
object BluetoothMac {
    /** Android 应用层可见的占位 MAC——解析必须得到与之不同的真值才算成功。 */
    const val PLACEHOLDER_MAC = "02:00:00:00:00:00"

    private val MAC_REGEX = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
    private const val QUIET_MILLIS = 10_000L

    @Volatile
    private var cached: String? = null

    @Volatile
    private var lastAttemptAt = 0L

    /** 用协程友好的 Mutex 而非 synchronized：下面要挂起等 shell，不能持 JVM 锁跨挂起点。 */
    private val mutex = Mutex()

    /**
     * 解析真实蓝牙 MAC（大写冒号格式）。
     *
     * @param force 忽略缓存与 10s 静默窗口立即重查。
     * @return 真实 MAC；解析失败或处于静默窗口内返回 null。
     */
    suspend fun resolve(force: Boolean = false): String? {
        cached?.let { return it }
        if (!force && SystemClock.uptimeMillis() - lastAttemptAt < QUIET_MILLIS) return null
        return mutex.withLock {
            cached?.let { return it }
            lastAttemptAt = SystemClock.uptimeMillis()
            val mac = query()
            if (mac != null) cached = mac
            mac
        }
    }

    private suspend fun query(): String? {
        val r = ShizukuTools.exec("content query --uri content://settings/secure/bluetooth_address", 4_000)
        if (!r.ok) return null
        return MAC_REGEX.find(r.out)?.value?.uppercase()?.takeIf { it != PLACEHOLDER_MAC }
    }
}
