package io.github.hypercar.session

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
import io.github.hypercar.net.CarPlayCtrlAdvertiser
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
import kotlinx.coroutines.isActive
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

    /**
     * `_carplay-ctrl._tcp` 广播器（真车邀请驱动流程的核心：车机在 WiFi 上发现它才下发
     * 0x4301）。生命周期与本会话一致：拿到 0x5703 → 绑网 → 注册；拆除时注销。
     */
    private var ctrlAdvertiser: CarPlayCtrlAdvertiser? = null

    /** 本轮已经静默加入过的车机热点（避免「先拿凭据入网」与「端点到了再入网」重复连接）。 */
    @Volatile
    private var joinedSsid: String? = null

    @Volatile
    private var joinedNetwork: Network? = null

    /** AirPlay 会话是否已建立（冗余端点去重，cardeck「推流中忽略 0x5703/0x4301」教训）。 */
    @Volatile
    private var streaming = false

    /** 本轮连接的计时基准（毫秒）；日志里统一打成 `+Nms`，便于定位慢在哪一步。 */
    @Volatile
    private var sessionStageStart: Long = 0L

    /** 相对 [sessionStageStart] 的毫秒数，用于阶段日志前缀。 */
    private fun stageMs(): Long =
        if (sessionStageStart == 0L) 0L else System.currentTimeMillis() - sessionStageStart

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
            // 落盘日志 + 崩溃兜底：环形缓冲只在内存里，进程一死（原生崩溃 / 被 OOM 杀）
            // 现场就全没了——2026-10-23 20:30 那次崩溃就是这么丢的。这里把日志逐行
            // 追加到 filesDir/hypercar-session.log（每行 flush），重启后先把上一轮的尾部
            // 带回环形缓冲（放在启动标记之前，顺序才读得懂），崩溃前现场仍可导出。
            restoreDiskLogTail()
            installCrashHandler()
            appendDiskLog("===== HyperCar 启动 =====")
            scope.launch { captureCrashArtifacts() }
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
        // 阶段计时基准：之后每条 CONNECT 日志都带 +Nms，一眼看出卡在哪一步
        sessionStageStart = System.currentTimeMillis()
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
            // deviceID 不是「随便一个唯一值」：按 deviceID 过滤的接收端会逐字符比对。
            // WinPlay 就是拿它和「目标蓝牙设备」（Windows 里配对的经典蓝牙地址）比，
            // 不一致直接对会话级 SETUP 回 403（cpStack.ts 的 deviceFilter）。
            // 所以这里明确区分「真实 MAC」和「伪 MAC」，伪 MAC 时把后果写进日志。
            val realMac = BluetoothMac.resolve()
            val deviceMac = realMac ?: BluetoothMac.resolveOrStable()
            if (realMac == null) {
                log(
                    "BT: ⚠ 未解析到真实蓝牙 MAC，本次用伪 ID $deviceMac——" +
                        "按 deviceID 过滤的接收端（WinPlay / 部分车机）会直接 403；" +
                        "装 Shizuku 或到设置里「手动指定蓝牙 MAC」填真实地址即可",
                )
            } else {
                log("BT: 使用真实蓝牙 MAC $deviceMac")
            }

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
            //
            // **真车死锁的解药**（2026-10-24 对照 cardeck 定谳）：0x5703 一到就立刻
            // 绑 WiFi + 广播 `_carplay-ctrl._tcp`——车机是在 WiFi 上发现该服务后
            // `GET /ctrl-int/1/connect` 邀请手机，**邀请成功才下发 0x4301**。
            // 若等 0x4301 才去入网，双方互等（旧实现就是这样，真车上永远拿不到端点）。
            val advertiser = CarPlayCtrlAdvertiser(context, { deviceMac }, ::log)
            ctrlAdvertiser = advertiser
            val lastSeen = AtomicReference<CarPlayEndpoint?>(null)
            val endpoint =
                awaitUsableEndpoint(context, coordinator, lastSeen, advertiser)
                    ?: gatewayFallbackEndpoint(context, lastSeen.get())
            if (endpoint == null) {
                val seen = lastSeen.get()
                fail(
                    if (seen == null) {
                        "等车机无线 CarPlay 端点超时（${ENDPOINT_TIMEOUT_MILLIS / 1000}s）——" +
                            "车机没下发 0x5703/0x4301。请在车机上进入「移动设备 → 本机 → Apple CarPlay」" +
                            "点一次（车机只在打开 CarPlay 窗口后才下发热点凭据与 AirPlay 端点）；" +
                            "若已知车机热点密码，也可先把手机手动连上该热点，本机会用网关 7000 端口兜底"
                    } else {
                        "车机未给出可用 IP（ssid=${seen.ssid ?: "-"} port=${seen.airPlayPort} " +
                            "ips=${seen.ipAddresses}）——检查车机热点是否开启"
                    },
                )
                return
            }

            // ---- 联调开关：只保 iAP2 链路，不起 AirPlay ----
            // 目的：WinPlay 这类接收端的「接收会话」与蓝牙 iAP2 强绑定（链路一断就整进程退出），
            // 而 CarLife MD 只在车机侧（PC）才连得上。于是让手机只当 iAP2 保活端，
            // AirPlay 由 PC 侧 hypercar-hub 去推，两边就不会抢同一条 AirPlay 会话。
            // 现场 2026-10-23 22:37：手机引擎与 PC hub 同时连 17000，WinPlay 画面被后者顶掉。
            if (SettingsStore.iap2KeepAliveOnly) {
                log("联调: 只保持 iAP2 链路（不启动 AirPlay 引擎）——端点 ips=${endpoint.ipAddresses} port=${endpoint.airPlayPort}")
                // 不 fail、不 return 前关闭协调器：iAP2 链路由 coordinator 自己的保活定时器维持，
                // 这样接收端（WinPlay）的 BT 桥接不会因链路关闭而退出。
                push(
                    link = LinkState.Connected(
                        carName = SettingsStore.targetCarName ?: "BMW ID8.5",
                        sinceMillis = System.currentTimeMillis(),
                    ),
                    carLife = CarLifeState.Idle,
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
            // 端点定谳：把「凭什么地址」摊开写清楚（真车排查全靠这一行）
            log(
                "CONNECT +${stageMs()}ms 端点确定：$host:${endpoint.airPlayPort}" +
                    " ssid=${endpoint.ssid ?: "-"}" +
                    " 有密码=${!endpoint.passphrase.isNullOrBlank()}" +
                    " ips=${endpoint.ipAddresses}" +
                    " gateway=${endpoint.gateway ?: "-"}" +
                    " devId=${endpoint.deviceIdentifier ?: "-"}" +
                    " srcvers=${endpoint.sourceVersion ?: "-"}",
            )
            val network = joinAndBind(context, endpoint)

            // ---- 连 AirPlay 控制端口并移交 FD ----
            push(link = LinkState.Negotiating("连接车机 AirPlay 端口"))
            val port = endpoint.airPlayPort.takeIf { it > 0 } ?: 7000
            val controlFd = openControlFd(context, host, port)
            if (controlFd == null || controlFd < 0) {
                fail("AirPlay 控制端口连接失败 $host:$port（默认网络/Wi-Fi 网络都试过了）")
                return
            }

            // ---- 启动 Rust 引擎（协商在 Rust 内继续）----
            push(link = LinkState.Negotiating("Rust 核心层协商 AirPlay 会话"))
            val identityPath = context.filesDir.resolve("controller_identity.txt").absolutePath
            val json = SettingsStore.engineConfigJson(host, endpoint.airPlayPort.takeIf { it > 0 } ?: 7000, deviceMac, identityPath)
            // 下发配置原样打一行（不含密钥）：分辨率/帧率/md_host/voice 一目了然，
            // 免得再靠猜「这次到底请求了多大尺寸、是监听还是拨号」
            log("CORE +${stageMs()}ms 引擎配置：$json")
            // 前后各记一行：原生崩溃（Rust panic 越过 JNI → SIGABRT）会直接把进程带走，
            // 这两行能区分「崩在 nativeStart 里」还是「崩在引擎跑起来之后」。
            log("CORE: 调用 nativeStart（$host:$port fd=$controlFd）")
            val started = RustCoreBridge.nativeStart(json, controlFd, socketProvider)
            log("CORE: nativeStart 返回 $started")
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
        stopVoiceMic()
        runCatching { RustCoreBridge.nativeStop() }
        runCatching { iap2?.stop() }
        iap2 = null
        runCatching { ctrlAdvertiser?.stop() }
        ctrlAdvertiser = null
        runCatching { wifiJoiner?.release() }
        wifiJoiner = null
        joinedSsid = null
        joinedNetwork = null
        boundNetwork = null
    }

    // ---------------------------------------------------------------- 语音助手麦克风
    //
    // CarLife 的语音识别要求「车机」侧提供麦克风数据（VR 通道 VR_MIC_DATA 0x00058001）。
    // 本机 HU 就用手机自己的麦克风：AudioRecord(16k mono) → nativeSendMic → carlife::inject_mic。
    // 现场 2026-10-24：不给麦克风时手机端报「麦克风有问题」。

    @Volatile
    private var micRecord: android.media.AudioRecord? = null
    private var micJob: Job? = null

    private fun startVoiceMic() {
        if (micRecord != null) return
        val ctx = appContext() ?: return
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                ctx,
                android.Manifest.permission.RECORD_AUDIO,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            log("VOICE: 缺少录音权限（RECORD_AUDIO）——语音助手会报麦克风异常，去设置授权后重连")
            return
        }
        val rate = 16_000
        val minBuf = android.media.AudioRecord.getMinBufferSize(
            rate,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
        )
        // 至少 80ms 缓冲：20ms 一帧读，留点余量防采集中断
        val bufSize = maxOf(minBuf, rate / 50 * 2 * 4)
        val rec = runCatching {
            android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
                rate,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
            )
        }.getOrNull()
        if (rec == null || rec.state != android.media.AudioRecord.STATE_INITIALIZED) {
            log("VOICE: AudioRecord 初始化失败（state=${rec?.state}）")
            runCatching { rec?.release() }
            return
        }
        val started = runCatching { rec.startRecording() }.isSuccess
        if (!started) {
            log("VOICE: startRecording 失败")
            runCatching { rec.release() }
            return
        }
        micRecord = rec
        micJob = scope.launch(Dispatchers.IO) {
            val chunk = ShortArray(320) // 20ms @16kHz
            while (isActive) {
                val n = runCatching { rec.read(chunk, 0, chunk.size) }.getOrDefault(0)
                if (n > 0) {
                    val pcm = if (n == chunk.size) chunk else chunk.copyOf(n)
                    runCatching { RustCoreBridge.nativeSendMic(pcm) }
                }
            }
        }
        log("VOICE: 手机麦克风已接入 CarLife VR 通道（16kHz 单声道，20ms/帧）")
    }

    private fun stopVoiceMic() {
        micJob?.cancel()
        micJob = null
        micRecord?.let { r ->
            runCatching { r.stop() }
            runCatching { r.release() }
        }
        micRecord = null
    }

    // ---------------------------------------------------------------- WiFi

    private suspend fun joinAndBind(context: Context, endpoint: CarPlayEndpoint): Network? {
        val ssid = endpoint.ssid
        val psk = endpoint.passphrase
        // 幂等：先拿 0x5703 入过网、端点到了又走一遍（0x4301 晚到）时不要重复 connect-network
        if (!ssid.isNullOrBlank() && ssid == joinedSsid && joinedNetwork != null) {
            log("WIFI: 本轮已加入热点 $ssid——复用网络，不重复连接")
            return joinedNetwork
        }
        // 静默网关试探已经把进程绑到该 WiFi 了（无密码、只知道网关）——别再用
        // 「当前默认网络」把它顶掉（热点无外网时默认网络常常是蜂窝）
        if (!ssid.isNullOrBlank() && psk.isNullOrBlank() && boundNetwork != null && endpoint.gateway != null) {
            log("WIFI: 已由静默试探绑定 \"$ssid\" 的网络——复用，不重新选网")
            joinedSsid = ssid
            joinedNetwork = boundNetwork
            return boundNetwork
        }
        val joiner = WifiJoiner(context) {
            // 车机熄火热点消失：结束会话，回到待机
            log("WIFI: 车机热点消失（熄火/离开）");
            scope.launch { runDisconnect("热点消失") }
        }
        wifiJoiner = joiner

        val network: Network? = if (!ssid.isNullOrBlank() && !psk.isNullOrBlank()) {
            log("WIFI: 尝试静默加入热点 $ssid（Wi-Fi 开关=${if (joiner.isWifiEnabled()) "开" else "关"}）")
            when (val result = joiner.joinCarHotspotSilently(ssid, psk)) {
                is WifiJoiner.JoinResult.Joined -> {
                    // 降级模式成功连上 = 系统确认框已批准（或此前批准过），记首连完成
                    markDegradedFirstConnectIfNeeded()
                    result.network
                }
                WifiJoiner.JoinResult.Unsupported -> {
                    log("WIFI: 静默入网不支持（${joiner.lastFailureReason ?: "WEP/ROM 限制"}），尝试复用当前网络")
                    null
                }
                WifiJoiner.JoinResult.Failed -> {
                    log("WIFI: 静默入网失败（${joiner.lastFailureReason ?: "原因未知"}）")
                    log("WIFI: → 若手机已手动连上该热点，会继续用下面挑出来的网络试连；否则请在手机上手动连接")
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
            if (!ssid.isNullOrBlank() && network != null) {
                joinedSsid = ssid
                joinedNetwork = effective
            }
            // 绑定后把「实际拿到的网络长什么样」打全：IP/网关/DNS/接口名。
            // 真车排查最常缺的就是这几个值（例如 DIRECT- 链路没有网关、拿到 169.254 自分配）
            log(
                "WIFI +${stageMs()}ms ✓ 已绑定网络（进程级）：${describeNetwork(context, effective)}" +
                    if (network == null) "——注意：这是兜底网络，不一定是车机热点" else "",
            )
        } else {
            log("WIFI +${stageMs()}ms 没有可绑定的网络（Wi-Fi 未连接/未授权）")
        }
        return effective
    }

    /** 把某个 [Network] 的链路属性压成一行（IP/前缀/网关/DNS/接口），供日志排查。 */
    private suspend fun describeNetwork(context: Context, network: Network): String = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val lp = cm?.getLinkProperties(network)
        val caps = cm?.getNetworkCapabilities(network)
        val v4 = lp?.linkAddresses?.filter { it.address is java.net.Inet4Address }
            ?.joinToString(",") { "${it.address.hostAddress}/${it.prefixLength}" }
        val v6 = lp?.linkAddresses?.count { it.address is java.net.Inet6Address } ?: 0
        val gw = lp?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress
        val dns = lp?.dnsServers?.mapNotNull { it.hostAddress }?.joinToString(",")
        "if=${lp?.interfaceName ?: "?"} ipv4=[${v4 ?: "-"}] ipv6=${v6} gw=${gw ?: "-"} dns=[${dns ?: "-"}]" +
            " ssid=${currentWifiSsid(context) ?: "-"}" +
            " validated=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: false}" +
            " internet=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false}"
    }.getOrElse { "网络属性读取失败（${it.message}）" }

    /** 兜底：拿当前默认网络（连上车机热点但静默路径失败时，Android 可能仍选蜂窝）。 */
    private fun currentDefaultNetwork(context: Context): Network? = runCatching {
        context.getSystemService(ConnectivityManager::class.java)?.activeNetwork
    }.getOrNull()

    /**
     * 「网关兜底」：iAP2 没给出可连的 AirPlay 地址时，拿当前 Wi-Fi 的默认网关 + 7000 端口当端点。
     *
     * 两种场景都要覆盖：
     *  - **已拿到 0x5703、也已入网**，但车机没下发 0x4301（真车等 ctrl 邀请超时/邀请失败）——
     *    车机的 AirPlay 接收端通常就在热点网内以 7000 端口提供 RTSP 控制面；
     *  - **一个凭据都没拿到**，但用户手动连了车机热点。
     * 失败不比现状更差：照旧报错退出（只多花几次 2.5s 超时）。端口 7000 是 AirPlay 惯例值。
     */
    private fun gatewayFallbackEndpoint(
        context: Context,
        seen: CarPlayEndpoint?,
    ): CarPlayEndpoint? {
        val gateway = wifiGateway(context) ?: return null
        log(
            "SESSION: 车机未给可用 AirPlay 地址" +
                (if (seen?.ssid.isNullOrEmpty()) "" else "（已拿到热点凭据 ssid=${seen?.ssid}）") +
                "——尝试网关兜底 $gateway:7000",
        )
        return CarPlayEndpoint(
            ssid = seen?.ssid,
            passphrase = seen?.passphrase,
            airPlayPort = 7000,
            deviceIdentifier = seen?.deviceIdentifier,
            publicKeyHex = seen?.publicKeyHex,
            sourceVersion = seen?.sourceVersion,
            ipAddresses = listOf(gateway),
            gateway = gateway,
        )
    }

    /** 当前所有 Wi-Fi 网络里第一个「有默认路由」的网关地址（IPv4 优先）。 */
    private fun wifiGateway(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return runCatching {
            cm.allNetworks
                .asSequence()
                .filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
                .mapNotNull { network -> cm.getLinkProperties(network)?.routes?.firstOrNull { it.isDefaultRoute }?.gateway }
                .mapNotNull { it.hostAddress }
                .firstOrNull { it.contains('.') && !it.contains(':') }
        }.getOrNull()
    }

    /**
     * 当前连接的 Wi-Fi SSID。
     *
     * 先读 `WifiManager.connectionInfo.ssid`（零成本，5s 一次的轮询不能每次都起 shell 进程）；
     * 缺定位权限时它会返回 `<unknown ssid>`（HyperOS 常见），此时退回 Shizuku 的
     * `cmd wifi status`（与 WifiJoiner 同源的解析，一直可读）。
     */
    private suspend fun currentWifiSsid(context: Context): String? {
        val fromApi =
            runCatching {
                (context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager)
                    ?.connectionInfo
                    ?.ssid
                    ?.trim('"')
                    ?.takeIf { it.isNotBlank() && !it.contains("unknown", ignoreCase = true) }
            }.getOrNull()
        if (fromApi != null) return fromApi
        if (!ShizukuTools.isReady) return null
        val out = runCatching { ShizukuTools.exec("cmd wifi status", 3_000) }.getOrNull()?.out
        val match = out?.let { Regex("connected\\s+to\\s+\"([^\"]*)\"").find(it) }
        return match?.groupValues?.get(1)?.ifBlank { null }
    }

    /**
     * **静默网关试探**：把当前 Wi-Fi 的默认网关当成车机热点，试一次 `网关:7000`（AirPlay 控制面）。
     *
     * 为什么需要（用户 2026-10-24 要求；cardeck PR #19 在凯迪拉克 CT5 上的同款兜底）：
     * 新协议车机（GM/Cinemo、BMW iD9 同族）声明会发 0x4301，但可能晚发或等前置条件，
     * 车主手动把手机连上「全新CT5」后其实就能直连——cardeck 那边是弹窗确认，本仓按
     * 「直接静默试一次」处理：成功即当端点继续，失败就继续等 0x4301（不影响保活）。
     *
     * 只在「等待期间出现新 SSID」或「该 SSID 正是 iAP2 给的车机热点」时各试一次：
     * 手机上原本就有的 WiFi（家里/公司，可能有 Apple TV 占着 7000）不会被误当车机。
     *
     * @return 试探成功时的合成端点（`airPlayPort=7000`、ip=网关）；失败返回 null
     */
    private fun probeGatewayAsCarHotspot(
        context: Context,
        ssid: String,
    ): CarPlayEndpoint? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val previous = boundNetwork
        for (network in wifiNetworks(context)) {
            val gateway =
                cm.getLinkProperties(network)
                    ?.routes
                    ?.firstOrNull { it.isDefaultRoute }
                    ?.gateway
                    ?.hostAddress
                    ?: continue
            if (!gateway.contains('.') || gateway.contains(':')) continue
            val reachable =
                runCatching {
                    cm.bindProcessToNetwork(network)
                    java.net.Socket().use { socket ->
                        socket.connect(
                            java.net.InetSocketAddress(gateway, GATEWAY_FALLBACK_PORT),
                            GATEWAY_PROBE_TIMEOUT_MILLIS.toInt(),
                        )
                    }
                }.isSuccess
            if (reachable) {
                boundNetwork = network
                log("WIFI: ✓ \"$ssid\" 的网关 $gateway:$GATEWAY_FALLBACK_PORT 可连——直接当车机热点继续（不等 0x4301）")
                return CarPlayEndpoint(
                    ssid = ssid,
                    passphrase = null,
                    airPlayPort = GATEWAY_FALLBACK_PORT,
                    deviceIdentifier = null,
                    publicKeyHex = null,
                    sourceVersion = null,
                    ipAddresses = listOf(gateway),
                    gateway = gateway,
                )
            }
            log("WIFI: \"$ssid\" 的网关 $gateway:$GATEWAY_FALLBACK_PORT 无响应——继续等车机 0x4301")
        }
        // 全部失败：把进程网络恢复原状（别一直绑在一块无外网的 WiFi 上）
        runCatching { cm.bindProcessToNetwork(previous) }
        return null
    }

    /**
     * 连车机 AirPlay 控制端口（TCP），按候选网络逐个试，成功返回已 detach 的 FD。
     *
     * 为什么不能「绑定默认网络后连一次」：手机手动连上热点后，系统默认网络仍可能是
     * 移动数据（热点无外网，HyperOS 会主动切走），这时连 192.168.137.1 必然超时
     * （2026-10-23 20:06、20:08 两次现场都是 5s 超时）。这里把
     * 「已绑定的网络 → 手机上所有 Wi-Fi 网络 → 进程默认网络」都试一遍，哪条通走哪条。
     */
    private suspend fun openControlFd(
        context: Context,
        host: String,
        port: Int,
    ): Int? = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val candidates = buildList<Network?> {
            boundNetwork?.let { add(it) }
            addAll(wifiNetworks(context))
            add(null)
        }.distinct()
        for (network in candidates) {
            val label = if (network == null) "进程默认网络" else "Wi-Fi 网络 #$network"
            if (network != null) runCatching { cm?.bindProcessToNetwork(network) }
            val startedAt = System.currentTimeMillis()
            var lastError: String? = null
            val fd = runCatching {
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), CONTROL_CONNECT_TIMEOUT_MILLIS.toInt())
                keepAliveForRust(socket)
                ParcelFileDescriptor.fromSocket(socket).detachFd()
            }.onFailure { lastError = it.javaClass.simpleName + ": " + it.message }.getOrNull()
            val cost = System.currentTimeMillis() - startedAt
            if (fd != null && fd >= 0) {
                if (network != null) {
                    boundNetwork = network
                    // 同步给 joiner：它的 onLost 只认自己记录的网络，不同步就解不掉绑定
                    wifiJoiner?.bindProcess(network)
                }
                log("NET +${stageMs()}ms ✓ $host:$port 已连通（$label，耗时 ${cost}ms，fd=$fd）")
                return@withContext fd
            }
            log("NET +${stageMs()}ms ✗ 经 $label 连 $host:$port 失败（${cost}ms，${lastError ?: "未知原因"}）")
        }
        // 全败：恢复默认绑定，别让进程一直卡在一块无外网的网络上
        runCatching { cm?.bindProcessToNetwork(null) }
        boundNetwork = null
        null
    }

    /** 手机当前挂着的 Wi-Fi 网络（很可能就是用户手动连上的车机热点）。 */
    private fun wifiNetworks(context: Context): List<Network> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        return runCatching {
            cm.allNetworks.filter { network ->
                cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        }.getOrDefault(emptyList())
    }

    // ---------------------------------------------------------------- socket 供给（移交 Rust）

    /**
     * 交给 Rust 的 socket 宿主（Socket / DatagramSocket）**必须保持可达**。
     *
     * `detachFd()` 只是把 fd 从 PFD 里摘出来交给 Rust；Java 的 Socket 对象仍然持有同一个
     * fd，是它背后的 `FileDescriptor` 负责在 GC 时 close。若此时 Socket 已不可达，GC 会在
     * Rust 还在用的时候把 fd 关掉（推流中途断流、引擎 EBADF）。这里把宿主对象留一份强引用：
     * 实际 fd 由 Rust 持有并在 nativeStop 时关闭，Java 这边只是不再去 close 它。
     * 每个会话只留几个小对象，只增不减（不 close、不回收，避免重复 close 到被复用的 fd 号）。
     *
     * 注：不能用 `android.system.Os.dup(int)` 做副本——该重载不是公开 API（只有
     * `dup(FileDescriptor)`），编译期就过不了。
     */
    private val socketKeepAlive = java.util.Collections.synchronizedList(mutableListOf<Any>())

    private fun keepAliveForRust(owner: Any) {
        socketKeepAlive.add(owner)
    }

    private val socketProvider = object : RustCoreBridge.SocketProvider {
        override fun openTcpFd(host: String, port: Int, timeoutMs: Int): Int = runCatching {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), timeoutMs.coerceAtLeast(1_000))
            keepAliveForRust(socket)
            ParcelFileDescriptor.fromSocket(socket).detachFd()
        }.getOrElse { -1 }

        override fun openUdpFd(host: String, port: Int): Int = runCatching {
            val udp = DatagramSocket()
            udp.connect(InetSocketAddress(host, port))
            keepAliveForRust(udp)
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
                    // 语音助手要靠车机侧供麦克风；CarLife 连上后再开采集，
                    // 避免没会话时空录（也会占着麦克风影响其他应用）
                    startVoiceMic()
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

    /**
     * CarLife 系 app 的候选包名（按唤醒优先级）。
     *
     * `com.baidu.carlife.xiaomi` 是 HyperOS 预装的**小米定制版 CarLife**——现场
     * 2026-10-23 实测：手机上只有它和 CarWith，设置里默认的 `com.baidu.carlife`
     * 根本没装，于是广播静默丢弃、7240 永远等不到接入。
     */
    private val carLifePackageCandidates = listOf(
        "com.baidu.carlife",
        "com.baidu.carlife.xiaomi",
        "com.miui.carlink",
        "com.xiaomi.carlink",
        "com.hihonor.carlink",
    )

    /** 实际可用的唤醒包名：设置里配的优先；没装就自动换一个装了的，并把换的结果写进日志。 */
    private fun resolveCarLifePackage(): String {
        val configured = SettingsStore.carlifePackage
        val ctx = appContext() ?: return configured
        fun installed(p: String) = runCatching { ctx.packageManager.getPackageInfo(p, 0) }.isSuccess
        if (installed(configured)) return configured
        val fallback = carLifePackageCandidates.firstOrNull { it != configured && installed(it) }
        if (fallback != null) {
            log("WAKE: ⚠ 唤醒包名 $configured 未安装，自动改用 $fallback（建议到设置里改过来）")
            return fallback
        }
        log("WAKE: ⚠ 设备上没有 CarLife 系 app（候选：${carLifePackageCandidates.joinToString(", ")}）")
        return configured
    }

    /**
     * 唤醒本机 CarLife / CarWith·CarLife / 荣耀车联，令其连 127.0.0.1 回环网关。
     *
     * 三级递进（现场 2026-10-23 22:00：应用内广播发出去后，7240 一个连接都没有）：
     *  ① 应用内广播 `com.baidu.carlife.Action.StartWireless`——最轻，但 Android 14+
     *     对「非导出接收器」会静默丢弃，且包名不对时也毫无反馈；
     *  ② Shizuku 已就绪时用 shell 再广播一次（`am broadcast` 以 shell 身份投递，
     *     不受导出限制）；
     *  ③ 仍未接入就把 app 直接拉起来（`monkey -c LAUNCHER`），让它自己走无线连接。
     * 顺带把设备上「CarLife/CarLink 系」的包名列进日志——包名配错时一眼可见。
     */
    private fun wakeLocalCarLife() {
        val pkg = resolveCarLifePackage()
        _snapshots.value = _snapshots.value.copy(carLife = CarLifeState.Waiting())
        runCatching {
            val intent = android.content.Intent("com.baidu.carlife.Action.StartWireless").apply {
                setPackage(pkg)
                addFlags(android.content.Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            runCatching { appContext()?.sendBroadcast(intent) }
                .onFailure { log("WAKE: 广播发送失败 - ${it.message}") }
            log("WAKE: ① 应用内广播 StartWireless → $pkg")
        }
        if (!ShizukuTools.isReady) {
            log("WAKE: 无 Shizuku，跳过 shell 兜底（②③）——若一直 Waiting，检查设置里的唤醒包名")
            return
        }
        scope.launch {
            // 包名到底存不存在、还有哪些 CarLife 系 app
            val listed = runCatching {
                ShizukuTools.exec(
                    "pm list packages | grep -iE 'carlife|carlink|carwith|carlife'",
                    6_000,
                )
            }.getOrNull()
            val packages = listed?.out
                ?.lineSequence()
                ?.map { it.removePrefix("package:").trim() }
                ?.filter { it.isNotEmpty() }
                ?.distinct()
                ?.toList()
                .orEmpty()
            log("WAKE: 设备上 CarLife 系包 = ${if (packages.isEmpty()) "（没找到）" else packages.joinToString(", ")}")
            if (packages.isNotEmpty() && pkg !in packages) {
                log("WAKE: ⚠ 设置里的 $pkg 不在上面列表里——很可能是包名不对（改成实际那个）")
            }

            // ② shell 广播：以 shell 身份投递，绕开 Android 14+ 非导出接收器限制
            val bcast = runCatching {
                ShizukuTools.exec(
                    "am broadcast -a com.baidu.carlife.Action.StartWireless -p $pkg --user 0",
                    6_000,
                )
            }.getOrNull()
            log("WAKE: ② shell 广播 exit=${bcast?.code} ${bcast?.out?.replace("\n", " ")?.take(160).orEmpty()}")

            // ③ 直接拉起 app（很多人肉点的那一步）
            val launch = runCatching {
                ShizukuTools.exec("monkey -p $pkg -c android.intent.category.LAUNCHER 1", 8_000)
            }.getOrNull()
            log("WAKE: ③ 拉起 $pkg exit=${launch?.code} ${launch?.out?.lineSequence()?.firstOrNull().orEmpty().take(160)}")
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
     * **拿到 0x5703 就立刻入网 + 广播 `_carplay-ctrl._tcp`**（不等 0x4301）：
     * 真车（Audi MIB3 / BMW）是「在 WiFi 上发现手机 ctrl 服务 → `GET /ctrl-int/1/connect`
     * 邀请 → 邀请成功才下发 0x4301」。若等 0x4301 才入网，两边互等，永远拿不到端点
     * （cardeck 在奥迪上实测的死锁；hypercar 旧流程同样只有 0x4301 一条来源）。
     *
     * 用无界 Channel 收集回调而不是首个回调 resume：0x5703/0x4301 可能连续到达，一条都不能丢；
     * 等待结束（或超时）后 close，晚到的回调 trySend 失败即静默丢弃。
     */
    private suspend fun awaitUsableEndpoint(
        context: Context,
        coordinator: Iap2Coordinator,
        lastSeen: AtomicReference<CarPlayEndpoint?>,
        advertiser: CarPlayCtrlAdvertiser,
    ): CarPlayEndpoint? = withTimeoutOrNull(ENDPOINT_TIMEOUT_MILLIS) {
        val callbacks = Channel<CarPlayEndpoint>(Channel.UNLIMITED)
        coordinator.start { endpoint -> callbacks.trySend(endpoint) }
        try {
            var usable: CarPlayEndpoint? = null
            var ticks = 0
            var partialKey: String? = null
            var wifiJoined = false
            // 静默网关试探：只在「等待期间出现新 WiFi」或「当前 WiFi 正是 iAP2 给的车机热点」
            // 时各试一次（每个 SSID 一次），避免对家里已有的 WiFi（可能有 Apple TV 占着 7000）误连。
            var observedSsid: String? = null
            val probedSsid = HashSet<String>()
            while (usable == null) {
                val next = withTimeoutOrNull(ENDPOINT_TICK_MILLIS) { callbacks.receive() }
                if (next == null) {
                    ticks++
                    // ① 车机已经来邀请过（GET /ctrl-int/1/connect）→ 直接用邀请方 IP 当端点。
                    //    BMW MGU-55 实测：清单里声明「0x4301 不发」，等它是死等；而邀请一旦
                    //    到达，TCP 对端地址就是车机在热点网段上的地址，AirPlay 控制面在 7000。
                    val inviteIp = advertiser.lastInvitePeerIp
                    if (inviteIp != null) {
                        val base = lastSeen.get()
                        log("SESSION: ✓ 车机已邀请（$inviteIp）——用它当 AirPlay 端点 :7000")
                        usable = CarPlayEndpoint(
                            ssid = base?.ssid,
                            passphrase = base?.passphrase,
                            airPlayPort = 7000,
                            deviceIdentifier = base?.deviceIdentifier,
                            publicKeyHex = base?.publicKeyHex,
                            sourceVersion = base?.sourceVersion,
                            ipAddresses = listOf(inviteIp),
                            gateway = inviteIp,
                        )
                        continue
                    }
                    // ① 静默网关试探：手机刚连上（或本来就该连）车机热点时，不用等 0x4301——
                    //    车机的 AirPlay 控制面通常就在热点网关的 7000 端口。
                    val ssid = currentWifiSsid(context)
                    if (!ssid.isNullOrBlank() && ssid !in probedSsid) {
                        val changed = observedSsid != null && observedSsid != ssid
                        val isCarHotspot = ssid == lastSeen.get()?.ssid
                        if (changed || isCarHotspot) {
                            probedSsid.add(ssid)
                            log("WIFI: 检测到 Wi-Fi \"$ssid\"——静默试一次「把网关当车机热点」（:7000）")
                            val probed = probeGatewayAsCarHotspot(context, ssid)
                            if (probed != null) {
                                usable = probed
                                continue
                            }
                        }
                    }
                    if (ssid != null) observedSsid = ssid
                    // ② 每 15s 报一次进度（tick 是 5s，探测要跟得上换网速度）
                    if (ticks % TICKS_PER_PROGRESS == 0) {
                        val seen = lastSeen.get()
                        val waited = ticks * ENDPOINT_TICK_MILLIS / 1000
                        log(
                            if (seen == null) {
                                "SESSION: 仍在等车机端点（还没收到 0x5703/0x4301，已等 ${waited}s）" +
                                    "——链路保持中，请在车机上点一次「Apple CarPlay/手机」"
                            } else {
                                "SESSION: 仍在等车机地址（ssid=${seen.ssid ?: "-"} port=${seen.airPlayPort} " +
                                    "ips=${seen.ipAddresses}，已等 ${waited}s）"
                            },
                        )
                    }
                    continue
                }
                val merged = mergeEndpoint(lastSeen.get(), next)
                lastSeen.set(merged)
                // 0x5703 只给 ssid/psk：先入网 + 广播，车机才可能邀请并下发 0x4301
                if (!wifiJoined && !merged.ssid.isNullOrBlank()) {
                    wifiJoined = true
                    log("SESSION: 先拿到热点凭据（0x5703）——立即入网 + 广播 _carplay-ctrl，再等 0x4301")
                    joinAndBind(context, merged)
                    val ssid = merged.ssid
                    if (ssid != null) {
                        advertiser.ensureForNetwork(ssid, force = true, network = boundNetwork)
                    }
                }
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
        appendDiskLog("$stamp  $line")
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

    // ---------------------------------------------------------------- 落盘日志（崩溃后仍可查）

    /**
     * 磁盘日志写入器（懒打开）。只在 [diskLogLock] 内访问。
     *
     * 为什么要落盘：`recentLogs` 是纯内存环形缓冲，进程一死就清零；2026-10-23 20:30
     * 的崩溃现场（连上 AirPlay 端口之后）就是这么丢的——用户「崩溃前快速点了导出」，
     * 导出的也只是崩溃前的旧内容。落盘后重启会把上一轮的尾部带回环形缓冲。
     */
    private var diskLogWriter: java.io.BufferedWriter? = null
    private val diskLogLock = Any()

    /** 打开（必要时先把上一份转成 .1）内部日志文件；失败返回 null，不影响界面日志。 */
    private fun diskLog(): java.io.BufferedWriter? {
        synchronized(diskLogLock) {
            diskLogWriter?.let { return it }
            val dir = appCtx?.filesDir ?: return null
            val writer = runCatching {
                val file = java.io.File(dir, DISK_LOG_NAME)
                if (file.length() > DISK_LOG_MAX_BYTES) {
                    val old = java.io.File(dir, "$DISK_LOG_NAME.1")
                    old.delete()
                    file.renameTo(old)
                }
                java.io.FileOutputStream(file, true).bufferedWriter()
            }.getOrNull() ?: return null
            diskLogWriter = writer
            return writer
        }
    }

    /** 追加一行到磁盘日志并立即 flush：原生崩溃（SIGSEGV/SIGABRT）时最后几行也不会丢。 */
    private fun appendDiskLog(line: String) {
        val writer = diskLog() ?: return
        synchronized(diskLogLock) {
            runCatching {
                writer.write(line)
                writer.newLine()
                writer.flush()
            }
        }
    }

    /** 把上一次运行（崩溃/被系统杀）的最后若干行带回环形缓冲，供崩溃后导出。 */
    private fun restoreDiskLogTail() {
        val dir = appCtx?.filesDir ?: return
        val file = java.io.File(dir, DISK_LOG_NAME)
        if (!file.exists() || file.length() == 0L) return
        val tail = runCatching { file.readLines().takeLast(DISK_LOG_TAIL_LINES) }.getOrDefault(emptyList())
        if (tail.isEmpty()) return
        _snapshots.update { s ->
            val header = "===== 上次运行（进程重启/崩溃前）最后 ${tail.size} 行 ====="
            s.copy(recentLogs = (s.recentLogs + header + tail).takeLast(LOG_KEEP_LINES))
        }
    }

    /** Java 层未捕获异常写盘（原生崩溃靠 [appendDiskLog] 的逐行 flush 保命）。 */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                appendDiskLog("!!! CRASH（线程 ${thread.name}）：${error.javaClass.name}: ${error.message}")
                error.stackTraceToString().lineSequence().take(80).forEach { appendDiskLog("    $it") }
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    /**
     * 抓上一次运行的崩溃线索（只有 Shizuku 就绪时能读 logcat）。
     *
     * Rust 侧 panic 会经 `nativeInitLogger` 装的 panic hook 打到 logcat（tag HyperCar-Core），
     * 系统崩溃记录在 crash 缓冲区，原生 abort 的证据（`Fatal signal`/`Abort message`）在
     * DEBUG/libc 里。抓回来写进日志后，不用 adb 也能看到「谁崩的、崩在哪」。
     *
     * 注意别用 `logcat -d -t 600` 这种「全量尾巴」：这台机器 logcat 每秒上千行，
     * 600 行只覆盖半秒，崩溃那一瞬很容易被冲掉（2026-10-23 20:44 就是这样漏掉的）。
     * 改成按 tag 精确抓、窗口放大，并只留真正像崩溃的行。指纹文件去重。
     */
    private suspend fun captureCrashArtifacts() {
        if (!ShizukuTools.isReady) return
        val dump = runCatching {
            ShizukuTools.exec(
                "logcat -d -s HyperCar-Core:V -t 600; " +
                    "logcat -b crash -d -t 150; " +
                    "logcat -d -t 5000 -s DEBUG:V libc:V",
                8_000,
            )
        }.getOrNull() ?: return
        val interesting = listOf(
            "PANIC",
            "panicked at",
            "Fatal signal",
            "Abort message",
            "libhypercar_core",
            "tombstone",
            "Process: io.github.hypercar",
            "ANR in io.github.hypercar",
        )
        val lines = (dump.out + "\n" + dump.err)
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && interesting.any { key -> it.contains(key) } }
            .distinct()
            .toList()
            .takeLast(40)
        if (lines.isEmpty()) return
        val dir = appCtx?.filesDir ?: return
        val marker = java.io.File(dir, "crash-capture.txt")
        val fingerprint = lines.joinToString("\n").hashCode().toString()
        if (runCatching { marker.readText() }.getOrNull() == fingerprint) return
        runCatching { marker.writeText(fingerprint) }
        log("CRASH: 上一次运行的崩溃线索（${lines.size} 行）")
        lines.forEach { log("CRASH| $it") }
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

    /**
     * 等车机 iAP2 端点的上限。
     *
     * **5 分钟**（cardeck 0.1.14 / PR #19 定谳）：0x4301 是「活事件」，新协议车机
     * （GM/Cinemo、BMW iD9 同族）声明会发但可能**晚发或等前置条件**——
     * 奥迪 MMI 9310 实测晚 2 分 35 秒、凯迪拉克 CT5 更久；旧实现 8s 拆链时这两类车
     * 永远等不到（拆链还会把车机侧的等待计时反复清零）。窗口内链路保持、每 15s 报进度。
     */
    private const val ENDPOINT_TIMEOUT_MILLIS = 300_000L

    /** 同一次 ACL 事件会被 receiver 与 service 各转交一次：这段时间内只记一行。 */
    private const val ACL_EVENT_LOG_DEDUPE_MILLIS = 1_500L

    /** 等端点期间的进度日志间隔（车机不开 iAP2 窗口时不至于静默 5 分钟）。 */
    private const val ENDPOINT_PROGRESS_MILLIS = 15_000L

    /**
     * 等端点时的轮询粒度（比进度日志细）：静默网关试探要跟得上「用户手动连车机热点」
     * 的换网速度，5s 一次 / 进度日志每 3 个 tick（15s）报一次。
     */
    private const val ENDPOINT_TICK_MILLIS = 5_000L
    private const val TICKS_PER_PROGRESS = (ENDPOINT_PROGRESS_MILLIS / ENDPOINT_TICK_MILLIS).toInt()

    /** 网关兜底端口（AirPlay 控制面惯例值）与静默试探的单次连接超时。 */
    private const val GATEWAY_FALLBACK_PORT = 7000
    private const val GATEWAY_PROBE_TIMEOUT_MILLIS = 1_200L

    /** 停止时等在途连接协程退出的上限（超过就先拆，连接侧另有 sessionLive 兜底）。 */
    private const val JOIN_TIMEOUT_MILLIS = 5_000L

    /** 单个候选网络上连 AirPlay 控制端口的超时（多网络轮试，单次不能给太久）。 */
    private const val CONTROL_CONNECT_TIMEOUT_MILLIS = 2_500L

    /** 环形日志保留行数（诊断页展示 + 导出）。 */
    private const val LOG_KEEP_LINES = 300

    /** 单行日志限流：窗口内最多落盘次数。 */
    private const val LOG_RATE_MAX = 4
    private const val LOG_RATE_WINDOW_MILLIS = 10_000L

    /** 限流表容量上限（超过就清空重建，避免长跑后无限增长）。 */
    private const val LOG_RATE_MAX_KEYS = 256

    /** 落盘日志文件名（filesDir 下，不需要任何存储权限）。 */
    private const val DISK_LOG_NAME = "hypercar-session.log"

    /** 落盘日志超过这个大小就转存成 `.1`（只留一份上一代）。 */
    private const val DISK_LOG_MAX_BYTES = 1_500_000L

    /** 重启时从落盘日志带回环形缓冲的行数（够覆盖崩溃前的一段现场）。 */
    private const val DISK_LOG_TAIL_LINES = 120
}

