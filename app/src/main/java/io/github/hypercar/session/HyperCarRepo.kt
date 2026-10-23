package io.github.hypercar.session

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import io.github.hypercar.autowake.AutoStart
import io.github.hypercar.autowake.HyperCarService
import io.github.hypercar.core.AudioStats
import io.github.hypercar.core.CarLifeState
import io.github.hypercar.core.LinkState
import io.github.hypercar.core.RustCoreBridge
import io.github.hypercar.core.SessionSnapshot
import io.github.hypercar.core.VideoStats
import io.github.hypercar.iap2.CarPlayEndpoint
import io.github.hypercar.iap2.Iap2Coordinator
import io.github.hypercar.net.BluetoothMac
import io.github.hypercar.net.SpecifierPermissions
import io.github.hypercar.net.WifiJoiner
import io.github.hypercar.settings.SettingsStore
import io.github.hypercar.shizuku.ShizukuTools
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 会话总管：上车秒连的完整链路。
 *
 * 时序（与 docs/AUTO-CONNECT.md 的 cardeck 链路同源，协商已下沉 Rust）：
 *  车机蓝牙 ACL 连上 → （清单静态接收器冷启动进程）→ HyperCarService FGS
 *    → [AutoStart.handler] → [connect]
 *    → iAP2 协商（0x5703 热点凭据 / 0x4301 AirPlay 端点）
 *    → Shizuku 静默连车机热点 + bindProcessToNetwork
 *    → 连 AirPlay 控制端口 → detachFd 移交 Rust → Rust 内完成全部 AirPlay 协商
 *    → 广播唤醒本机 CarLife 连 127.0.0.1
 */
object HyperCarRepo {

    private val _snapshots = MutableStateFlow(SessionSnapshot())
    val snapshots: StateFlow<SessionSnapshot> = _snapshots.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var initialized = false

    @Volatile
    private var iap2: Iap2Coordinator? = null

    private var wifiJoiner: WifiJoiner? = null

    /** AirPlay 会话是否已建立（冗余端点去重，cardeck「推流中忽略 0x5703/0x4301」教训）。 */
    @Volatile
    private var streaming = false

    @Volatile
    private var connecting = false

    /** 在途连接协程：下车/停止时必须取消，否则它会一直等 iAP2 端点（`connecting` 卡 true）。 */
    @Volatile
    private var connectJob: Job? = null

    /**
     * 会话资源是否还活着（蓝牙协商/WiFi 绑定/Rust 引擎）。拆除用它做幂等闸门：
     * 重复的 ACL 断开广播、service 二次转交都只会真正拆一次。
     */
    private val sessionLive = AtomicBoolean(false)

    /** 持有移交 Rust 的网络句柄（推流期间不能释放）。 */
    @Volatile
    private var boundNetwork: Network? = null

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
            val app = context.applicationContext
            appCtx = app
            // 恢复日志记录开关（诊断页切换过则沿用）
            logRecording = SettingsStore.debugLogEnabled
            _snapshots.value = _snapshots.value.copy(logRecording = logRecording)
            // ACL 广播唤醒入口（receiver → handler）：优先经 FGS 拿前台豁免与持锁，
            // 服务起来后再触发真正会话（handler 幂等，service 二次进入走直连分支）
            AutoStart.handler = { ctx, device, connected ->
                val app = ctx.applicationContext
                if (connected) {
                    if (!HyperCarService.isRunning) {
                        runCatching {
                            app.startForegroundService(HyperCarService.startIntent(app, device))
                        }.onFailure {
                            // FGS 被系统拦（未授悬浮窗等）：降级直接连接（进程可能被回收）
                            log("AUTO: FGS 启动被拦（${it.message}）——降级直接拉起会话")
                            connect(app)
                        }
                    } else {
                        connect(app)
                    }
                } else {
                    // 收摊路径必须幂等，且**只在服务确实在跑时**投 ACTION_STOP：
                    // 服务收到 ACTION_STOP 会反向调本 handler(false)（见 HyperCarService），
                    // 若这里无条件再投 ACTION_STOP，就形成
                    //   handler(false) → startService(STOP) → onStartCommand(STOP) → handler(false) …
                    // 的自激回环——实测每秒数百轮，只有「车机蓝牙 断开 + 会话已断开」两种日志，
                    // 且车机不在场也照刷（进程自己把自己钉在前台）。
                    if (HyperCarService.isRunning) {
                        runCatching { app.startService(HyperCarService.stopIntent(app, device)) }
                    }
                    disconnect()
                }
                logAclEvent(device, connected)
            }
            // Rust 回调 → 快照（Rust 工作线程回调，直接改 StateFlow 是安全的）
            RustCoreBridge.setListener(rustListener)
        }
    }

    /**
     * ACL 事件日志：同一次事件会被 receiver 与 service 各转交一次，去重后只记一行；
     * 车机若在极短时间内反复重建 ACL，也不会把日志刷满（见 [log] 的限流兜底）。
     */
    private fun logAclEvent(device: BluetoothDevice, connected: Boolean) {
        val name = runCatching { device.name }.getOrNull() ?: "未知设备"
        val address = runCatching { device.address }.getOrNull() ?: "?"
        val key = "$address/$connected"
        val now = SystemClock.elapsedRealtime()
        if (key == lastAclEventKey && now - lastAclEventAt < ACL_EVENT_LOG_DEDUPE_MILLIS) {
            lastAclEventAt = now
            return
        }
        lastAclEventKey = key
        lastAclEventAt = now
        log("AUTO: 车机蓝牙 ${if (connected) "连上" else "断开"}（$name）")
    }

    /** handler 只在主线程（receiver/service 都是主线程）调用，无需加锁。 */
    private var lastAclEventKey: String? = null
    private var lastAclEventAt = 0L

    // ---------------------------------------------------------------- 对 UI

    fun connect(context: Context) {
        init(context)
        if (connecting || streaming) {
            log("CONNECT: 已在连接/已连接，忽略重复触发")
            return
        }
        connecting = true
        sessionLive.set(true)
        push(link = LinkState.Negotiating("检查 Shizuku"))
        connectJob?.cancel()
        connectJob = scope.launch { runConnect(context.applicationContext) }
    }

    fun disconnect() {
        // 先掐在途连接：否则它会一直阻塞在「等 iAP2 端点」上，`connecting` 恒 true，
        // 之后再上车也进不来（表现为「连了一次就再也连不上」）。
        // 拆除等它在途协程真正退出后再动资源（join），避免「拆到一半新连接又建起来」；
        // join 有上限——连接可能正卡在 WiFi 入网轮询这类不可中断的等待里。
        val pending = connectJob
        connectJob = null
        pending?.cancel()
        scope.launch {
            withTimeoutOrNull(JOIN_TIMEOUT_MILLIS) { pending?.join() }
            runDisconnect("用户/系统断开")
        }
    }

    fun clearLogs() {
        _snapshots.value = _snapshots.value.copy(recentLogs = emptyList())
    }

    // ---------------------------------------------------------------- 连接主流程

    private suspend fun runConnect(context: Context) {
        try {
            // Shizuku 从「必须」降级为「可选增强」：没有它照样能连，只是两条路径降级——
            //  WiFi 走 WifiNetworkSpecifier（热点首连弹一次系统确认框）+ deviceID 用稳定伪 MAC。
            if (!ShizukuTools.isReady) {
                // 降级模式预检：权限缺失/定位服务关闭时提前失败并给出可操作原因，
                // 不要等 requestNetwork 静默 onUnavailable 才让用户一头雾水。
                if (!precheckDegradedMode(context)) return
                val first = !SettingsStore.degradedFirstConnectDone
                log(
                    "SHIZUKU: 未就绪——降级模式" +
                        if (first) "（热点首连需在系统确认框点「连接」，deviceID 用稳定伪 MAC）"
                        else "（热点已批准过，应静默；deviceID 用稳定伪 MAC）"
                )
            }
            push(link = LinkState.Negotiating("解析本机蓝牙身份"))
            val deviceMac = BluetoothMac.resolveOrStable()

            // ---- iAP2 协商（蓝牙侧）----
            push(link = LinkState.Negotiating("等待车机蓝牙 iAP2 协商"))
            val coordinator = Iap2Coordinator(
                context,
                targetMac = { SettingsStore.targetCarMac },
                deviceMacProvider = { BluetoothMac.resolveOrStable() },
                log = ::log,
            )
            iap2 = coordinator

            // 等「可用」端点（有 IP），而不是等第一个回调：iAP2 两条消息分工不同——
            // 0x5703 只带热点凭据（无 IP/端口），0x4301 才带 AirPlay 端口与车机地址，
            // 谁先到由车机决定。WinPlay 实测 0x5703 先到、0x4301 晚 143ms：
            // 旧实现取首个回调就继续，于是在 19:46:57.019 立刻判「端点无可用 IP」失败，
            // 而真正可用的 ips=[192.168.137.1] 在下一条消息里被当成「冗余端点」丢掉。
            val lastSeen = AtomicReference<CarPlayEndpoint?>(null)
            val endpoint = awaitUsableEndpoint(coordinator, lastSeen)
            if (endpoint == null) {
                val seen = lastSeen.get()
                fail(
                    if (seen == null) {
                        "等待车机 iAP2 端点超时（${ENDPOINT_TIMEOUT_MILLIS / 1000}s）——车机未开放无线 CarPlay 窗口？"
                    } else {
                        "车机未给出可用 IP（ssid=${seen.ssid ?: "-"} port=${seen.airPlayPort} " +
                            "ips=${seen.ipAddresses}）——检查车机热点是否开启"
                    },
                )
                return
            }

            // ---- 静默入车机热点 + 绑网 ----
            push(link = LinkState.Negotiating("静默加入车机热点"))
            val host = pickHost(endpoint)
            if (host == null) {
                // awaitUsableEndpoint 已保证有 IP，这里只是防御
                fail("端点无可用 IP（0x5703 有凭据、0x4301 未给出地址）")
                return
            }
            val network = joinAndBind(context, endpoint)

            // ---- 连 AirPlay 控制端口并移交 FD ----
            push(link = LinkState.Negotiating("连接车机 AirPlay 端口"))
            val controlFd = withContext(Dispatchers.IO) {
                runCatching {
                    val socket = Socket()
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(host, endpoint.airPlayPort.takeIf { it > 0 } ?: 7000), 5_000)
                    ParcelFileDescriptor.fromSocket(socket).detachFd()
                }.getOrNull()
            }
            if (controlFd == null || controlFd < 0) {
                fail("AirPlay 控制端口连接失败 $host:${endpoint.airPlayPort}")
                return
            }

            // ---- 启动 Rust 引擎（协商在 Rust 内继续）----
            push(link = LinkState.Negotiating("Rust 核心层协商 AirPlay 会话"))
            val identityPath = context.filesDir.resolve("controller_identity.txt").absolutePath
            val json = SettingsStore.engineConfigJson(host, endpoint.airPlayPort.takeIf { it > 0 } ?: 7000, deviceMac, identityPath)
            val started = RustCoreBridge.nativeStart(json, controlFd, socketProvider)
            if (!started) {
                fail("核心层启动失败（查看日志）")
                return
            }
            // 拆除与新连接的竞争兜底：拆除已经把资源收走时（sessionLive=false）别再挂
            // streaming，并把刚起的引擎停掉——否则会留下没人管的僵尸会话。
            if (!sessionLive.get()) {
                runCatching { RustCoreBridge.nativeStop() }
                log("SESSION: 连接结果已过期（拆除途中），丢弃本次会话")
                return
            }
            streaming = true
            // CarLife 唤醒放在 onState("connected") 里做（AirPlay RECORD 完成后），
            // 避免 CarLife 视频帧在屏幕流就绪前被丢弃。
        } catch (cancelled: CancellationException) {
            // disconnect() 掐掉在途连接：正常取消，不算失败（也不要把 Failed 写进快照）
            throw cancelled
        } catch (t: Throwable) {
            fail("会话异常: ${t.message}")
        } finally {
            connecting = false
        }
    }

    /**
     * 会话拆除（幂等）：只有真正还活着的会话才拆一次。
     *
     * 幂等闸门不可省：ACL 断开广播 + service 的 ACTION_STOP 转交会让本方法被重复调用，
     * 每调一次都 nativeStop + 重建快照 + 记一行日志，历史版本正是在这里配合
     * ACTION_STOP 回灌，把「断开」刷成了每秒几百条。
     */
    private suspend fun runDisconnect(reason: String) {
        if (!sessionLive.compareAndSet(true, false)) return
        push(link = LinkState.Stopping)
        releaseSessionResources()
        log("SESSION: 已断开（$reason）")
        _snapshots.update { s ->
            s.copy(
                link = LinkState.Idle,
                carLife = CarLifeState.Idle,
                video = VideoStats(),
                audio = AudioStats(),
                shizukuReady = ShizukuTools.isReady,
                logRecording = logRecording,
            )
        }
    }

    /**
     * 释放 iap2 / Rust / WiFi 绑定（不碰 UI 状态位；拆除与失败路径共用）。
     * 必须把 iAP2 停掉：只要它在跑，出站循环就每 3s 断一次车机的音频配置文件，
     * 既打扰车机，也会不断制造「ACL 断开」事件。
     */
    private fun releaseSessionResources() {
        streaming = false
        runCatching { RustCoreBridge.nativeStop() }
        runCatching { iap2?.stop() }
        iap2 = null
        runCatching { wifiJoiner?.release() }
        wifiJoiner = null
        boundNetwork = null
    }

    // ---------------------------------------------------------------- WiFi

    private suspend fun joinAndBind(context: Context, endpoint: CarPlayEndpoint): Network? {
        val joiner = WifiJoiner(context) {
            // 车机熄火热点消失：结束会话，回到待机
            log("WIFI: 车机热点消失（熄火/离开）");
            scope.launch { runDisconnect("热点消失") }
        }
        wifiJoiner = joiner

        val ssid = endpoint.ssid
        val psk = endpoint.passphrase
        val network: Network? = if (!ssid.isNullOrBlank() && !psk.isNullOrBlank()) {
            when (val result = joiner.joinCarHotspotSilently(ssid, psk)) {
                is WifiJoiner.JoinResult.Joined -> {
                    // 降级模式成功连上 = 系统确认框已批准（或此前批准过），记首连完成
                    markDegradedFirstConnectIfNeeded()
                    result.network
                }
                WifiJoiner.JoinResult.Unsupported -> {
                    log("WIFI: 静默入网不支持（WEP/ROM 限制），尝试直接绑定当前网络")
                    null
                }
                WifiJoiner.JoinResult.Failed -> {
                    log("WIFI: 静默入网失败，尝试直接绑定当前网络")
                    null
                }
            }
        } else {
            log("WIFI: 端点无热点凭据（可能已在车机网络），直接绑定当前网络")
            null
        }
        val effective = network ?: currentDefaultNetwork(context)
        if (effective != null) {
            joiner.bindProcess(effective)
            boundNetwork = effective
            log("WIFI: ✓ 已绑定车机网络（进程级）")
        }
        return effective
    }

    /** 兜底：拿当前默认网络（连上车机热点但静默路径失败时，Android 可能仍选蜂窝）。 */
    private fun currentDefaultNetwork(context: Context): Network? = runCatching {
        context.getSystemService(ConnectivityManager::class.java)?.activeNetwork
    }.getOrNull()

    // ---------------------------------------------------------------- socket 供给（移交 Rust）

    private val socketProvider = object : RustCoreBridge.SocketProvider {
        override fun openTcpFd(host: String, port: Int, timeoutMs: Int): Int = runCatching {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), timeoutMs.coerceAtLeast(1_000))
            ParcelFileDescriptor.fromSocket(socket).detachFd()
        }.getOrElse { -1 }

        override fun openUdpFd(host: String, port: Int): Int = runCatching {
            val udp = DatagramSocket()
            udp.connect(InetSocketAddress(host, port))
            ParcelFileDescriptor.fromDatagramSocket(udp).detachFd()
        }.getOrElse { -1 }
    }

    // ---------------------------------------------------------------- Rust 回调

    private val rustListener = object : RustCoreBridge.EventListener {
        override fun onState(state: String, detail: String) {
            log("CORE: [$state] $detail")
            when (state) {
                "negotiating" -> push(link = LinkState.Negotiating(detail))
                "connected" -> {
                    streaming = true
                    push(
                        link = LinkState.Connected(
                            carName = SettingsStore.targetCarName ?: "BMW ID8.5",
                            sinceMillis = System.currentTimeMillis(),
                        ),
                        carLife = CarLifeState.Waiting(),
                    )
                    wakeLocalCarLife()
                }
                "carlife_waiting" -> push(carLife = CarLifeState.Waiting())
                // Rust 侧 CarLife 14 步握手完成（VIDEO_ENCODER_START 已发）
                "carlife_active" -> push(carLife = CarLifeState.Active())
                "failed" -> fail(detail)
                "ended" -> {
                    scope.launch { runDisconnect(detail) }
                }
                "stopping" -> { /* runDisconnect 内处理 */ }
            }
        }

        override fun onLog(line: String) = log(line)

        override fun onStats(fps: Int, frames: Long, kbps: Long, audioPlaying: Boolean, ducking: Boolean) {
            // 用 update（CAS）而不是读-改-写：每秒一次，与 log() 的读-改-写并发会吞掉日志行。
            _snapshots.update { s ->
                s.copy(
                    video = s.video.copy(fps = fps, frames = frames, kbps = kbps),
                    audio = s.audio.copy(playing = audioPlaying, ducking = ducking),
                )
            }
        }
    }

    /** 唤醒本机 CarLife / CarWith·CarLife / 荣耀车联，令其连 127.0.0.1 回环网关。 */
    private fun wakeLocalCarLife() {
        val pkg = SettingsStore.carlifePackage
        runCatching {
            val intent = android.content.Intent("com.baidu.carlife.Action.StartWireless").apply {
                setPackage(pkg)
                addFlags(android.content.Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            _snapshots.value = _snapshots.value.copy(carLife = CarLifeState.Waiting())
            // Android 14+ 静态注册限制：本广播由目标 app 清单接收（CarLife 系一直如此）
            runCatching { appContext()?.sendBroadcast(intent) }
                .onFailure { log("WAKE: 广播发送失败 - ${it.message}") }
            log("WAKE: 已广播唤醒 $pkg（连接 127.0.0.1:7240）")
        }
    }

    @Volatile
    private var appCtx: android.content.Context? = null

    private fun appContext(): android.content.Context? = appCtx

    // ---------------------------------------------------------------- 工具

    private fun pickHost(endpoint: CarPlayEndpoint): String? {
        // IPv4 优先 → 其余地址；全空再试网关（cardeck EndpointHosts 同序）
        val ips = endpoint.ipAddresses.filter { it.isNotBlank() }
        return ips.firstOrNull { it.contains('.') && !it.contains(':') }
            ?: ips.firstOrNull()
            ?: endpoint.gateway?.takeIf { it.isNotBlank() }
    }

    /**
     * 等一个真正可用的端点（有 IP），期间把 0x5703（热点凭据）与 0x4301（AirPlay 端口/地址）
     * 两次回调合并成一份，并每 [ENDPOINT_PROGRESS_MILLIS] 报一次进度。
     *
     * 为什么不能取第一个回调：两条消息分工不同，0x5703 只有 ssid/psk（没有 IP/端口），
     * 0x4301 才带端口与车机地址，先后顺序由车机决定。WinPlay 实测 0x5703 先到、
     * 0x4301 晚 143ms（真车同理）。取首个回调 = 拿凭据那一份去要 IP，必然立刻失败。
     *
     * 用无界 Channel 收集回调而不是首个回调 resume：0x5703/0x4301 可能连续到达，一条都不能丢；
     * 等待结束（或超时）后 close，晚到的回调 trySend 失败即静默丢弃。
     */
    private suspend fun awaitUsableEndpoint(
        coordinator: Iap2Coordinator,
        lastSeen: AtomicReference<CarPlayEndpoint?>,
    ): CarPlayEndpoint? = withTimeoutOrNull(ENDPOINT_TIMEOUT_MILLIS) {
        val callbacks = Channel<CarPlayEndpoint>(Channel.UNLIMITED)
        coordinator.start { endpoint -> callbacks.trySend(endpoint) }
        try {
            var usable: CarPlayEndpoint? = null
            var ticks = 0
            var partialKey: String? = null
            while (usable == null) {
                val next = withTimeoutOrNull(ENDPOINT_PROGRESS_MILLIS) { callbacks.receive() }
                if (next == null) {
                    ticks++
                    val seen = lastSeen.get()
                    val waited = ticks * ENDPOINT_PROGRESS_MILLIS / 1000
                    log(
                        if (seen == null) {
                            "SESSION: 仍在等车机端点（还没收到 0x5703/0x4301，已等 ${waited}s）"
                        } else {
                            "SESSION: 仍在等车机地址（ssid=${seen.ssid ?: "-"} port=${seen.airPlayPort} " +
                                "ips=${seen.ipAddresses}，已等 ${waited}s）"
                        },
                    )
                    continue
                }
                val merged = mergeEndpoint(lastSeen.get(), next)
                lastSeen.set(merged)
                if (pickHost(merged) != null) {
                    usable = merged
                    continue
                }
                val key = "${merged.ssid}/${merged.airPlayPort}"
                if (key != partialKey) {
                    partialKey = key
                    log(
                        "SESSION: 端点缺 IP（0x5703 只给热点凭据 ssid=${merged.ssid ?: "-"}）" +
                            "——继续等 0x4301 的 AirPlay 地址",
                    )
                }
            }
            log("SESSION: 端点就绪 ips=${usable?.ipAddresses} port=${usable?.airPlayPort}")
            usable
        } finally {
            callbacks.close()
        }
    }

    /** 合并两次端点回调：0x5703 给热点凭据、0x4301 给端口与地址，字段各自补齐。 */
    private fun mergeEndpoint(old: CarPlayEndpoint?, incoming: CarPlayEndpoint): CarPlayEndpoint {
        if (old == null) return incoming
        return incoming.copy(
            ssid = incoming.ssid ?: old.ssid,
            passphrase = incoming.passphrase ?: old.passphrase,
            airPlayPort = if (incoming.airPlayPort > 0) incoming.airPlayPort else old.airPlayPort,
            deviceIdentifier = incoming.deviceIdentifier ?: old.deviceIdentifier,
            publicKeyHex = incoming.publicKeyHex ?: old.publicKeyHex,
            sourceVersion = incoming.sourceVersion ?: old.sourceVersion,
            ipAddresses = incoming.ipAddresses.ifEmpty { old.ipAddresses },
            gateway = incoming.gateway ?: old.gateway,
        )
    }

    private fun fail(reason: String) {
        log("SESSION 失败: $reason")
        // 失败即停蓝牙/WiFi 侧（保留 Failed 状态给 UI）：否则 iAP2 出站循环会继续每 3s
        // 断一次车机音频配置文件，还占着 RFCOMM 监听，下一次上车也会被旧会话拖住。
        releaseSessionResources()
        _snapshots.value = _snapshots.value.copy(
            link = LinkState.Failed(reason),
            carLife = CarLifeState.Idle,
        )
    }

    private fun push(link: LinkState? = null, carLife: CarLifeState? = null) {
        _snapshots.update { s ->
            s.copy(
                link = link ?: s.link,
                carLife = carLife ?: s.carLife,
                shizukuReady = ShizukuTools.isReady,
                degradedPermissionGranted = degradedPermissionGranted(),
                degradedLocationOn = degradedLocationOn(),
                degradedFirstConnectDone = SettingsStore.degradedFirstConnectDone,
            )
        }
    }

    // ---------------------------------------------------------------- 降级模式引导

    /**
     * 系统侧状态变化后刷新快照（Shizuku 授权成功、从系统设置返回等）。
     * 与连接流程无关，只重算 shizukuReady / 降级预检等派生字段。
     */
    fun refreshSystemState() {
        push()
    }

    /** 降级模式下连热点的运行时权限是否已授予（有 Shizuku 时不需要，恒 true）。 */
    private fun degradedPermissionGranted(): Boolean {        if (ShizukuTools.isReady) return true
        val ctx = appCtx ?: return true
        return runCatching { SpecifierPermissions.isGranted(ctx) }.getOrDefault(false)
    }

    /** 系统定位服务是否开启（仅 12/12L 相关；13+ 用 NEARBY_WIFI_DEVICES，恒 true）。 */
    private fun degradedLocationOn(): Boolean {
        if (ShizukuTools.isReady) return true
        val ctx = appCtx ?: return true
        return runCatching { SpecifierPermissions.isLocationEnabled(ctx) }.getOrDefault(true)
    }

    /**
     * 由 UI 调：发起降级模式所需的运行时权限申请。
     * 走标准 ActivityResult 由 Activity 侧发起（这里只决定申请哪些权限）。
     */
    fun degradedPermissionsToRequest(): Array<String> = SpecifierPermissions.requiredPermissions()

    /** 权限申请结果回调（UI 侧 ActivityResult 后调用），刷新快照让引导卡消失。 */
    fun onDegradedPermissionResult() {
        log("PERM: 权限申请结果 refreshed（granted=${degradedPermissionGranted()}）")
        push()
    }

    /**
     * 降级模式连接前预检：权限缺失/定位服务关闭时**提前失败**并给出可操作原因，
     * 而不是等 WifiNetworkSpecifier 的 requestNetwork 静默 onUnavailable 才懵。
     */
    private fun precheckDegradedMode(context: Context): Boolean {
        if (ShizukuTools.isReady) return true
        val reason = SpecifierPermissions.degradeBlockReason(context)
        if (reason != null) {
            fail(reason)
            return false
        }
        return true
    }

    /** 降级模式成功连上车机热点后：标记首连已完成（系统确认框已批准，后续静默）。 */
    private fun markDegradedFirstConnectIfNeeded() {
        if (ShizukuTools.isReady) return
        if (!SettingsStore.degradedFirstConnectDone) {
            SettingsStore.degradedFirstConnectDone = true
            log("PERM: ✓ 降级模式首连完成——此后上车自动连接将静默（不再弹确认框）")
            push()
        }
    }

    /**
     * 环形日志（[LOG_KEEP_LINES] 行）+ 快照更新。任意线程可调。行前缀带 HH:mm:ss.SSS 时间戳（上车对表用）。
     *
     * **刷屏兜底**：同一行文本在 [LOG_RATE_WINDOW_MILLIS] 内超过 [LOG_RATE_MAX] 次就不再落盘
     * （logcat 也一起省掉），等下一次有非重复行进来时补一条「已折叠 N 条」。事件回环的历史
     * 故障就是 0.6s 灌了 300 行；有了限流，环形缓冲能把真正的上下文留下来。
     */
    fun log(line: String) {
        val now = SystemClock.elapsedRealtime()
        var folded = 0
        var overBudget = false
        synchronized(logRateLock) {
            val state = logRates.getOrPut(line) { LogRate(now, 0) }
            if (now - state.windowStart >= LOG_RATE_WINDOW_MILLIS) {
                state.windowStart = now
                state.count = 0
            }
            if (state.count >= LOG_RATE_MAX) {
                suppressedLogs++
                overBudget = true
            } else {
                state.count++
                if (logRates.size > LOG_RATE_MAX_KEYS) logRates.clear() // 防长生日志键膨胀
                folded = suppressedLogs
                suppressedLogs = 0
            }
        }
        if (overBudget) return
        android.util.Log.i("HyperCar", line)
        if (!logRecording) return // 记录开关关闭：丢弃（logcat 仍写，adb 可查）
        val stamp = logTimeFormat.get().format(java.util.Date())
        _snapshots.update { s ->
            val extra =
                if (folded > 0) {
                    listOf("$stamp  LOG: ⚠ 已折叠 $folded 条重复日志（同一行 ${LOG_RATE_WINDOW_MILLIS / 1000}s 内超过 $LOG_RATE_MAX 次）")
                } else {
                    emptyList()
                }
            s.copy(recentLogs = (s.recentLogs + extra + "$stamp  $line").takeLast(LOG_KEEP_LINES))
        }
    }

    /** 单行日志的限流状态（仅 [logRateLock] 内访问）。 */
    private class LogRate(var windowStart: Long, var count: Int)

    private val logRateLock = Any()
    private val logRates = HashMap<String, LogRate>()
    private var suppressedLogs = 0

    private val logTimeFormat = object : ThreadLocal<java.text.SimpleDateFormat>() {
        override fun initialValue(): java.text.SimpleDateFormat =
            java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
    }

    /** 调试日志记录开关（持久化到 SettingsStore；关闭不丢既有内容）。 */
    @Volatile
    var logRecording: Boolean = true
        private set

    fun setLogRecording(enabled: Boolean) {
        logRecording = enabled
        SettingsStore.debugLogEnabled = enabled
        _snapshots.value = _snapshots.value.copy(logRecording = enabled)
        log("LOG: 日志记录已${if (enabled) "开启" else "暂停"}")
    }

    /**
     * 导出日志到 `下载/HyperCar/`（MediaStore，免存储权限，文件管理器/微信可见）。
     * 完成后路径写进快照 [SessionSnapshot.lastExportPath] 并落一条日志（诊断页最上方可见）。
     * 必须在后台线程调用（内部已切 IO）。
     */
    fun exportLogs() {
        scope.launch {
            val logs = _snapshots.value.recentLogs
            if (logs.isEmpty()) {
                log("LOG: 无日志可导出")
                return@launch
            }
            val result = withContext(Dispatchers.IO) { writeDownloadsFile(logs) }
            result.onSuccess { path ->
                _snapshots.value = _snapshots.value.copy(lastExportPath = path)
                log("LOG: ✓ 日志已导出 $path（${logs.size} 行）")
            }.onFailure { e ->
                log("LOG: 导出失败 - ${e.message}")
            }
        }
    }

    /** 写 `Download/HyperCar/hypercar-log-<时间戳>.txt`；Android 10+ 走 MediaStore 免权限。 */
    private fun writeDownloadsFile(logs: List<String>): Result<String> = runCatching {
        val ctx = requireNotNull(appCtx) { "Repo 未初始化" }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val name = "hypercar-log-$stamp.txt"
        val text = buildString {
            appendLine("# HyperCar 调试日志  导出于 $stamp")
            appendLine("# 设备 ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE}")
            appendLine()
            logs.forEach { appendLine(it) }
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(
                    android.provider.MediaStore.Downloads.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS + "/HyperCar",
                )
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = requireNotNull(
                resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values),
            ) { "MediaStore 插入失败" }
            resolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                ?: error("输出流打开失败")
            values.clear()
            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "/storage/emulated/0/Download/HyperCar/$name"
        } else {
            // Android 9 及以下：需存储权限路径（manifest 未声明时此分支仅在旧设备出现）
            val dir = java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS,
                ),
                "HyperCar",
            )
            dir.mkdirs()
            val file = java.io.File(dir, name)
            file.writeText(text, Charsets.UTF_8)
            file.absolutePath
        }
    }

    // ---------------------------------------------------------------- 常量
    // 注意：本类是 standalone object，**不能**用 companion object（Kotlin 不允许
    // 「companion 声明在 object 里」），常量直接作为 object 成员即可。

    /** 等车机 iAP2 端点的上限：超时失败而不是无限挂着（挂着会让后续自动连接全被挡）。 */
    private const val ENDPOINT_TIMEOUT_MILLIS = 180_000L

    /** 同一次 ACL 事件会被 receiver 与 service 各转交一次：这段时间内只记一行。 */
    private const val ACL_EVENT_LOG_DEDUPE_MILLIS = 1_500L

    /** 等端点期间的进度日志间隔（车机不开 iAP2 窗口时不至于静默 3 分钟）。 */
    private const val ENDPOINT_PROGRESS_MILLIS = 15_000L

    /** 停止时等在途连接协程退出的上限（超过就先拆，连接侧另有 sessionLive 兜底）。 */
    private const val JOIN_TIMEOUT_MILLIS = 5_000L

    /** 环形日志保留行数（诊断页展示 + 导出）。 */
    private const val LOG_KEEP_LINES = 300

    /** 单行日志限流：窗口内最多落盘次数。 */
    private const val LOG_RATE_MAX = 4
    private const val LOG_RATE_WINDOW_MILLIS = 10_000L

    /** 限流表容量上限（超过就清空重建，避免长跑后无限增长）。 */
    private const val LOG_RATE_MAX_KEYS = 256
}
