package io.github.hypercar.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.util.Log
import io.github.hypercar.shizuku.ShizukuTools
import io.github.hypercar.shizuku.shellQuote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 静默加入车机热点并把进程网络绑上去（cardeck `docs/WIFI-JOIN.md` 的坑全部内化）。
 *
 * 两步式：
 *  1. Shizuku `cmd wifi connect-network`——**0 弹窗**地让系统连上热点（热点进系统已保存网络）。
 *  2. **不带 specifier** 的 `requestNetwork(TRANSPORT_WIFI)` 捕获 `Network` 句柄 → [bindProcess]。
 *
 * 必须绑 `bindProcessToNetwork` 的原因：mDNS `NsdManager.registerService` 没有 Network 参数
 * （API 36 才有 `AdvertisingRequest` 重载），广播跟随进程默认网络；不绑就从蜂窝发出去，车机
 * 永远发现不了手机（0x4301/邀请不到）。它只影响本进程，虚拟屏里的导航/音乐是独立进程不受影响。
 *
 * 三个内化的坑：
 *  - `connect-network` 的 **exit=0 不代表已连上**（命令只把连接投给 WifiService 就返回）→
 *    必须轮询 `cmd wifi status` 等 SSID 匹配（断旧网→扫描→关联→DHCP 实测 2~10s，5GHz 更久）。
 *  - SSID 校验用 `cmd wifi status` 而**不是** `WifiInfo`（后者在缺定位权限时返回 `<unknown ssid>`）。
 *  - `onLost` 必须处理（车机熄火热点消失），否则会话层停在「已连接」的假态。
 */
class WifiJoiner(
    private val context: Context,
    onLost: () -> Unit,
) {
    /** 构造参数转存：避免与 NetworkCallback.onLost 回调方法名冲突。 */
    private val lostCallback: () -> Unit = onLost

    /** 入网结果。 */
    sealed interface JoinResult {
        /** 已连上并拿到 [network] 句柄（尚未绑进程，调用 [bindProcess] 后 socket/mDNS 才走它）。 */
        data class Joined(val network: Network) : JoinResult

        /** 失败（connect-network 被拒 / 轮询超时 / requestNetwork 未回调）。 */
        data object Failed : JoinResult

        /** 该安全类型无法静默表达（WEP）——调用方走兜底路径。 */
        data object Unsupported : JoinResult
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** 当前持有的网络回调（join 时注册，供 onLost 使用）。 */
    private val callback = AtomicReference<ConnectivityManager.NetworkCallback?>(null)

    /** 当前已绑定的网络（bindProcess 记录，供快路径与 onLost 解绑）。 */
    private val boundNetwork = AtomicReference<Network?>(null)

    /** 串行化 bind/release（网络回调线程与调用方线程可能并发）。 */
    private val bindLock = Any()

    /** 当前绑定网络；未绑定返回 null。 */
    val currentNetwork: Network? get() = boundNetwork.get()

    /**
     * 静默加入车机热点（0 弹窗）。阻塞到连上并拿到 Network 句柄，或失败。可取消。
     *
     * 步骤：① 打开 WiFi → ② `cmd wifi connect-network` → ③ 轮询 `cmd wifi status` 等 SSID
     * 匹配（15s，可取消）→ ④ 不带 specifier 的 `requestNetwork` 取句柄（15s，onUnavailable 即失败）。
     *
     * @param ssid 热点名（可能含空格/中文，内部用 shellQuote 转义）。
     * @param psk  热点密码；空=开放热点；WEP 形态返回 [JoinResult.Unsupported]。
     */
    suspend fun joinCarHotspotSilently(ssid: String, psk: String): JoinResult =
        withContext(Dispatchers.IO) {
            // WEP 无法用 cmd wifi connect-network 的 wpa2/开放表达——交给调用方兜底
            if (isWepKey(psk)) return@withContext JoinResult.Unsupported

            // 清掉旧回调/旧绑定，干净起步
            release()

            // ① 确保 WiFi 开关打开（仅 Shizuku 可用时生效）
            ShizukuTools.exec("cmd wifi set-wifi-enabled enabled")

            // ② connect-network：exit=0 仅表示「已投递给 WifiService」，不代表已连上
            val connectCmd =
                if (psk.isBlank()) {
                    "cmd wifi connect-network ${shellQuote(ssid)} open"
                } else {
                    "cmd wifi connect-network ${shellQuote(ssid)} wpa2 ${shellQuote(psk)}"
                }
            ShizukuTools.exec(connectCmd)

            // ③ 轮询 cmd wifi status 直到 SSID 匹配（500ms × 15s，可取消）
            if (!awaitSsid(ssid, JOIN_WAIT_MS)) {
                Log.i(TAG, "connect-network 后 ${JOIN_WAIT_MS}ms 内未连上 \"$ssid\"")
                return@withContext JoinResult.Failed
            }

            // ④ 不带 specifier 的 requestNetwork 取 Network 句柄（不会弹任何确认框）
            val network = awaitNetworkHandle(HANDLE_WAIT_MS)
                ?: return@withContext JoinResult.Failed

            JoinResult.Joined(network)
        }

    /**
     * 把进程 socket 绑到 [network]（mDNS/裸 socket 跟随进程默认网络，必须显式绑）。
     * 同一热点重复绑定走快路径：句柄未变时直接返回 true，不再重复 bind（避免无谓解绑/重绑抖动）。
     */
    fun bindProcess(network: Network): Boolean = synchronized(bindLock) {
        if (boundNetwork.get() == network) return true // 快路径：已绑同一网络
        val ok = connectivityManager.bindProcessToNetwork(network)
        if (ok) boundNetwork.set(network)
        ok
    }

    /** 解绑进程网络并注销回调。幂等，可重复调用。 */
    fun release() {
        callback.getAndSet(null)?.let {
            runCatching { connectivityManager.unregisterNetworkCallback(it) }
        }
        synchronized(bindLock) {
            if (boundNetwork.getAndSet(null) != null) {
                connectivityManager.bindProcessToNetwork(null)
            }
        }
    }

    /** WEP 密钥形态（5/10/13/26 位）——静默路径无法表达，返回 true 让调用方走兜底。 */
    private fun isWepKey(psk: String): Boolean = psk.length in setOf(5, 10, 13, 26)

    /** 轮询 `cmd wifi status` 直到出现目标 SSID；[timeoutMs] 内未匹配返回 false。可取消。 */
    private suspend fun awaitSsid(ssid: String, timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            currentCoroutineContext().ensureActive() // 协程取消即时打断等待
            val status = ShizukuTools.exec("cmd wifi status", 4_000)
            if (statusShowsSsid(status.out, ssid)) return true
            delay(500)
        }
        return false
    }

    /** 解析 `cmd wifi status` 是否已连在 [ssid]（统一走 shell 查询，不依赖定位权限/WifiInfo）。 */
    private fun statusShowsSsid(status: String, ssid: String): Boolean {
        if (status.isBlank()) return false
        val m = Regex("connected\\s+to\\s+\"([^\"]*)\"").find(status)
        if (m != null) return m.groupValues[1] == ssid
        return status.contains("\"$ssid\"") // 兜底：输出里出现带引号的 SSID
    }

    /** 用不带 specifier 的 requestNetwork 捕获当前 WiFi 的 Network 句柄；超时/onUnavailable 返回 null。 */
    private suspend fun awaitNetworkHandle(timeoutMs: Long): Network? {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // 车机热点无外网，别让它被拆
            .build()
        val latch = CountDownLatch(1)
        val result = AtomicReference<Network?>(null)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                result.set(network)
                latch.countDown()
            }

            override fun onUnavailable() {
                latch.countDown()
            }

            override fun onLost(network: Network) {
                // 车机熄火/离开范围：热点消失 → 解绑并通知会话层
                if (boundNetwork.compareAndSet(network, null)) {
                    connectivityManager.bindProcessToNetwork(null)
                }
                lostCallback()
            }
        }
        callback.set(cb)
        try {
            connectivityManager.requestNetwork(request, cb)
        } catch (t: Throwable) {
            callback.compareAndSet(cb, null)
            Log.i(TAG, "requestNetwork 失败：${t.message}")
            return null
        }
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            if (latch.await(200, TimeUnit.MILLISECONDS)) break
        }
        val network = result.get()
        if (network == null) {
            release() // 未拿到句柄：注销回调，别泄漏
            return null
        }
        return network
    }

    private companion object {
        const val TAG = "WifiJoiner"
        const val JOIN_WAIT_MS = 15_000L
        const val HANDLE_WAIT_MS = 15_000L
    }
}
