package io.github.hypercar.session

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.ParcelFileDescriptor
import io.github.hypercar.autowake.AutoStart
import io.github.hypercar.autowake.HyperCarService
import io.github.hypercar.core.RustCoreBridge
import io.github.hypercar.core.CarLifeState
import io.github.hypercar.core.LinkState
import io.github.hypercar.core.SessionSnapshot
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                if (connected) {
                    if (!HyperCarService.isRunning) {
                        runCatching {
                            ctx.startForegroundService(HyperCarService.startIntent(ctx, device))
                        }.onFailure {
                            // FGS 被系统拦（未授悬浮窗等）：降级直接连接（进程可能被回收）
                            log("AUTO: FGS 启动被拦（${it.message}）——降级直接拉起会话")
                            scope.launch { connect(ctx.applicationContext) }
                        }
                    } else {
                        scope.launch { connect(ctx.applicationContext) }
                    }
                } else {
                    runCatching { ctx.startService(HyperCarService.stopIntent(ctx, device)) }
                    scope.launch { disconnect() }
                }
                val name = runCatching { device.name }.getOrNull() ?: "未知设备"
                log("AUTO: 车机蓝牙 ${if (connected) "连上" else "断开"}（$name）")
            }
            // Rust 回调 → 快照（Rust 工作线程回调，直接改 StateFlow 是安全的）
            RustCoreBridge.setListener(rustListener)
        }
    }

    // ---------------------------------------------------------------- 对 UI

    fun connect(context: Context) {
        init(context)
        if (connecting || streaming) {
            log("CONNECT: 已在连接/已连接，忽略重复触发")
            return
        }
        connecting = true
        push(link = LinkState.Negotiating("检查 Shizuku"))
        scope.launch { runConnect(context.applicationContext) }
    }

    fun disconnect() {
        scope.launch { runDisconnect("用户/系统断开") }
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

            val endpoint = kotlinx.coroutines.suspendCancellableCoroutine<CarPlayEndpoint> { cont ->
                coordinator.start { ep ->
                    if (cont.isActive) {
                        // 首个端点即继续；后续冗余端点由 onRedundantEndpoint 处理
                        cont.resume(ep) {}
                    } else {
                        runCatching { log("SESSION: 冗余端点被忽略 ssid=${ep.ssid}") }
                    }
                }
            }

            // ---- 静默入车机热点 + 绑网 ----
            push(link = LinkState.Negotiating("静默加入车机热点"))
            val host = pickHost(endpoint)
            if (host == null) {
                fail("端点无可用 IP（0x4301/mDNS 均未给出地址）")
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
            streaming = true
            // CarLife 唤醒放在 onState("connected") 里做（AirPlay RECORD 完成后），
            // 避免 CarLife 视频帧在屏幕流就绪前被丢弃。
        } catch (t: Throwable) {
            fail("会话异常: ${t.message}")
        } finally {
            connecting = false
        }
    }

    private suspend fun runDisconnect(reason: String) {
        push(link = LinkState.Stopping)
        streaming = false
        runCatching { RustCoreBridge.nativeStop() }
        runCatching { iap2?.stop() }
        iap2 = null
        runCatching { wifiJoiner?.release() }
        boundNetwork = null
        log("SESSION: 已断开（$reason）")
        _snapshots.value = SessionSnapshot(
            link = LinkState.Idle,
            carLife = CarLifeState.Idle,
            shizukuReady = ShizukuTools.isReady,
            logRecording = logRecording,
            lastExportPath = _snapshots.value.lastExportPath,
            recentLogs = _snapshots.value.recentLogs,
        )
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
            val s = _snapshots.value
            _snapshots.value = s.copy(
                video = s.video.copy(fps = fps, frames = frames, kbps = kbps),
                audio = s.audio.copy(playing = audioPlaying, ducking = ducking),
            )
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

    private fun fail(reason: String) {
        log("SESSION 失败: $reason")
        streaming = false
        _snapshots.value = _snapshots.value.copy(
            link = LinkState.Failed(reason),
            carLife = CarLifeState.Idle,
        )
    }

    private fun push(link: LinkState? = null, carLife: CarLifeState? = null) {
        val s = _snapshots.value
        _snapshots.value = s.copy(
            link = link ?: s.link,
            carLife = carLife ?: s.carLife,
            shizukuReady = ShizukuTools.isReady,
            degradedPermissionGranted = degradedPermissionGranted(),
            degradedLocationOn = degradedLocationOn(),
            degradedFirstConnectDone = SettingsStore.degradedFirstConnectDone,
        )
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

    /** 环形日志（200 行）+ 快照更新。任意线程可调。行前缀带 HH:mm:ss.SSS 时间戳（上车对表用）。 */
    fun log(line: String) {
        android.util.Log.i("HyperCar", line)
        if (!logRecording) return // 记录开关关闭：丢弃（logcat 仍写，adb 可查）
        val stamp = logTimeFormat.get().format(java.util.Date())
        val s = _snapshots.value
        val logs = (s.recentLogs + "$stamp  $line").takeLast(300)
        _snapshots.value = s.copy(recentLogs = logs)
    }

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
}
