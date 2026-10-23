package io.github.hypercar.iap2

import java.io.IOException
import java.security.SecureRandom
import java.util.Date
import java.util.TimeZone
import java.util.UUID

/**
 * 会话层看到的链路通道（由 [Iap2Bluetooth] 用 RFCOMM + [Iap2LinkEngine] 实现；
 * 抽成接口便于单测注入 mock 时序）。
 */
interface Iap2LinkChannel {
    /** 等待链路层协商完成（NORMAL）。 */
    fun awaitReady(timeoutMillis: Long): Boolean

    /** 对端协商的最大控制载荷（已扣除帧头与校验和）；协商前为 null。 */
    fun peerMaxControlPayload(): Int?

    /** 排入一个完整控制载荷；链路已死返回 false。[awaitAck]=false 为发后不管。 */
    fun sendControl(
        bytes: ByteArray,
        awaitAck: Boolean = true,
    ): Boolean

    /** 取下一个控制载荷；超时或链路结束返回 null。 */
    fun recvControl(timeoutMillis: Long): ByteArray?

    val isAlive: Boolean

    fun close()
}

/**
 * iAP2 发送端（iPhone 角色）控制会话：走通无线 CarPlay 的 bring-up 序列，
 * 产出 [CarPlayEndpoint]（热点凭据 + AirPlay 端点）交给上层。
 *
 * 序列（BMW iD8.5/MGU-86 实测次序，见 §9j）：
 *   0x1D00 → 0x1D01 → 0x1D02（识别）
 *   0xAA00 → 0xAA01 → 0xAA02 → 0xAA03 → 0xAA05（MFi，跳过证书/签名校验）
 *   **0xAA05 之后立即突发设备状态问候** 0x4E0C/0x4E0A/0x4E0B/0x4E09（§9j 核心修复：
 *   真 iPhone 就是这个次序；旧实现把问候排在 0x5703 等待之后，BMW 等问候、我们等
 *   0x5703，5.4s 后车机判 accessory 未就绪拆链）
 *   0xEA00（EA 会话：param1 必须是**协议名字符串**——§9j 第三证据）
 *   0x5702 → 0x5703（按能力清单，拿热点 SSID/密码/信道）
 *   0x4300 → 0x4301（按能力清单，拿 AirPlay 端口/公钥/地址）
 *   进入保活泵：0x4301 是生命周期事件，重复到达都回调 onEndpoint
 */
class Iap2SenderSession(
    private val channel: Iap2LinkChannel,
    private val log: (String) -> Unit,
    /** 本机真实蓝牙 MAC（0x4300/0x4E0E 的传输标识、0x4E0C UUID 派生源；真车会校验）。 */
    private val bluetoothMac: String = "02:00:00:00:00:00",
    /** 本机蓝牙名（0x4E09 设备信息）。 */
    private val deviceName: String = "iPhone",
    /** 每次拿到/更新端点即回调（0x5703 与 0x4301 各触发一次，保活期 0x4301 重发也触发）。 */
    private val onEndpoint: (CarPlayEndpoint) -> Unit,
    /** 仅用于 MFi 挑战长度的按车机持久化记忆；null = 只用进程内内存表（纯 JVM 单测）。 */
    private val context: android.content.Context? = null,
) {
    private val parser = Iap2Csm.Parser()

    /** 刚解析、尚未分类的帧（到达顺序）。 */
    private val receivedFrames = ArrayDeque<Iap2Csm.Frame>()

    /**
     * [expectOrNull] 等待期间取到的「非预期」帧暂存区；[recvFrame] 优先从这里取。
     *
     * **必须与 [receivedFrames] 分开**：两者若混用，expectOrNull 把非目标帧塞回
     * 它自己下一轮优先读取的队列，就会同一帧反复取出—记录—塞回形成活锁
     * （cardeck 2026-10-18 实测：车机 bring-up 期间发 0x5000，日志 ~1000 行/秒刷屏
     * 直到等待超时）。
     */
    private val pendingFrames = ArrayDeque<Iap2Csm.Frame>()

    /** 暂存区上限：车机订阅类消息可能在长等待期间持续到达，无上限会吃内存。 */
    private val maxPendingFrames = 256
    private var pendingOverflowLogged = false

    private val random = SecureRandom()

    /** 0x5703 解析结果（保活期车机重发 0x5703 时需重新应答/回调）。 */
    @Volatile
    private var wifi: Iap2Messages.WifiConfiguration? = null

    /** 0x4301 是否已产出过端点（区分「首达」与「重发」日志）。 */
    private var endpointEmitted = false

    /** 0x4301 原始体是否已记录（每轮会话一次，避免周期补发刷屏）。 */
    private var startSessionRawLogged = false

    /** 最近一次晚到 0x4301 的端点指纹（车机周期重发相同内容时不重复打详情）。 */
    private var lastLateStartSessionSignature: Int? = null

    /** 设备状态问候是否已发过（一轮一次；force=true 才重发，§9j 救急重发用）。 */
    private var greetingsSent = false

    private companion object {
        /** MFi 挑战阶梯（长度, 首次试探窗口）：DiPlay/Audi/BMW 双端实测顺序。 */
        private val MFI_ATTEMPTS = listOf(32 to 3_000L, 20 to 6_000L, 16 to 3_000L)

        /**
         * 已知「静默过」的档位重试窗口。首次试探保留原窗口（可能是慢车机），
         * 但已经失败过一次的档位没必要再等满 6 秒——真 iPhone 应答在 100ms 级。
         */
        const val MFI_SILENT_RETRY_WINDOW_MS = 1_500L

        /** 等 0x4301 的短窗口（声明会发的车机毫秒级应答；没声明的直接跳过）。 */
        const val START_SESSION_WAIT_NANOS = 3_000_000_000L

        /**
         * 没有 0x5703（新协议车机：AP 信息只在 0x4301 里）时的等待窗口。
         * 这类车机必须先把热点信息给手机，手机才可能入网——窗口太短等于直接判死。
         */
        const val START_SESSION_WAIT_NEW_PROTOCOL_NANOS = 8_000_000_000L

        /** 0x4301 原始体日志上限（Audi 实测 261B，完整记录；异常大的只截前段）。 */
        const val START_SESSION_RAW_LOG_BYTES = 1_024

        /** 0x5703 分段等待：1.5s 未到先重发问候，再 1.3s 未到重发一次 0x5702（§9j 处置 3/4）。 */
        const val WIFI_RESCUE_GREETING_MILLIS = 1_500L
        const val WIFI_RESCUE_RETRY_MILLIS = 1_300L
    }

    /**
     * 跑完整个 bring-up，返回（可能不完整的）端点。
     *
     * @throws IOException 识别/链路超时，或「既无 0x5703 也无 0x4301」（一点 AP 信息都没有）。
     */
    fun run(timeoutMillis: Long = 60_000L): CarPlayEndpoint {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        if (!channel.awaitReady(remainingMillis(deadline))) {
            throw IOException("iAP2 链路协商超时")
        }
        log("iap2 link ready peerMaxControl=${channel.peerMaxControlPayload()}")

        // ---- 识别 ----
        send(Iap2MessageIds.START_IDENTIFICATION)
        val identFrame = expect(Iap2MessageIds.IDENTIFICATION_INFORMATION, deadline)
        val ident = Iap2Messages.parseIdentification(identFrame.body)
        logIdentification(ident, identFrame)
        send(Iap2MessageIds.IDENTIFICATION_ACCEPTED)
        log("iap2 tx=0x1d02 identification-accepted")

        // ---- MFi（跳过校验：0xAA05 自报成功）----
        val identityText = listOfNotNull(ident.name, ident.model, ident.manufacturer).joinToString(" ").lowercase()
        send(Iap2MessageIds.MFI_REQUEST_CERTIFICATE)
        val certificate = expect(Iap2MessageIds.MFI_CERTIFICATE, deadline)
        log("iap2 rx=0xaa01 certificate bytes=${certificate.body.size}（跳过校验）")
        runMfiChallenge(deadline, receiverKey = MfiLengthMemory.receiverKey(identityText))
        send(Iap2MessageIds.MFI_AUTHENTICATION_SUCCEEDED)
        log("iap2 tx=0xaa05 authentication-succeeded（自报）")

        // ---- 设备状态问候：必须**紧贴** 0xAA05 突发（§9j 核心修复）----
        // 真 iPhone 在 0xAA05 之后立刻发 0x4E0C/0x4E0A/0x4E0B/0x4E09，然后才轮到
        // 订阅/请求。旧实现排在 0x5703 等待之后：BMW 等问候、我们等 0x5703，
        // 两轮实测稳定 5.37~5.45s 后车机拆 RFCOMM（另一台 MGU-86 五轮全败同签名）。
        sendDeviceStateGreetings()

        // ---- EA 会话（MFi 完成后发；认证前发会噎死 DiPlay）----
        // BMW iD8.5 档案 B 声明 com.bmw.carplay protocol=8；档案 A 声明 de.bmw.a4a.bt。
        // param1 必须是**协议名字符串**：旧代码发成 u16 协议号，车机不回 0xEA03、
        // CarPlay 数据通道不打开（§9j 第三证据，对所有车机都存在的错误）。
        for (protocol in ident.eaProtocols) {
            sendFireAndForget(Iap2MessageIds.EA_OPEN_SESSION, Iap2Messages.eaOpenSession(protocol.protocolId, protocol.name))
            log("iap2 tx=0xea00 startEASession protocol=${protocol.protocolId} name=${protocol.name}")
        }
        if (ident.eaProtocols.isNotEmpty() && ident.declaresSend(Iap2MessageIds.EA_SESSION_STATUS) != false) {
            // 探 800ms 的 0xEA03 并记日志——回没回一眼可见（§9j 处置 1）
            val status = expectOrNull(Iap2MessageIds.EA_SESSION_STATUS, minOf(deadline, System.nanoTime() + 800_000_000L))
            if (status != null) {
                log("iap2 rx=0xea03 EA 会话已打开（${status.body.size}B）")
            } else {
                log("iap2 0xea03 未到（EA 会话未确认）——继续 WiFi 交换")
            }
        }

        // ---- WiFi 配置交换（按能力清单分派两代协议）----
        // 旧协议（0x5702 → 0x5703）：手机请求，车机回 AP 明文——BMW 档案 B/Audi 走这条。
        // 新协议（0x4300 → 0x4301）：**AP 信息由 0x4301 自带**——不声明 0x57xx 的车机走这条。
        val declaresReceiveWifi = ident.declaresReceive(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION)
        val declaresSendWifi = ident.declaresSend(Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION)
        val legacyWifiExchange = declaresReceiveWifi != false && declaresSendWifi != false
        val wifiFromExchange: Iap2Messages.WifiConfiguration? =
            if (!legacyWifiExchange) {
                log(
                    "iap2 车机未声明支持 0x5702/0x5703（收=${declaresReceiveWifi ?: "未知"} " +
                        "发=${declaresSendWifi ?: "未知"}）——跳过旧协议，AP 信息由 0x4301 提供",
                )
                null
            } else {
                send(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION)
                // 0x5703 分段等待（§9j 处置 3/4/5）：1.5s 未到强制重发问候 →
                // 再 1.3s 未到重发一次 0x5702 → 之后按原窗口继续等。
                var parsed = expectOrNull(Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION, rescueDeadline(deadline, WIFI_RESCUE_GREETING_MILLIS))
                if (parsed == null) {
                    sendDeviceStateGreetings(force = true)
                    log("iap2 0x5703 1.5s 未到——强制重发设备状态问候，继续等待")
                    parsed = expectOrNull(Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION, rescueDeadline(deadline, WIFI_RESCUE_RETRY_MILLIS))
                }
                if (parsed == null) {
                    send(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION)
                    log("iap2 0x5702 重发一次（首次仅链路 ACK）")
                    parsed = expectOrNull(Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION, deadline)
                }
                if (parsed == null) {
                    log("iap2 0x5703 未在窗口内到达——改走 0x4300/0x4301（新版协议自带 AP 信息）")
                    null
                } else {
                    val wifiConfiguration = Iap2Messages.parseWifiConfiguration(parsed.body)
                    log(
                        "iap2 rx=0x5703 ssid=${wifiConfiguration.ssid} channel=${wifiConfiguration.channel} " +
                            "security=${wifiConfiguration.securityType} " +
                            "psk=${if (wifiConfiguration.passphrase.isEmpty()) "(空)" else "***"}",
                    )
                    this.wifi = wifiConfiguration
                    // 凭据即刻回调：上层据此绑车机网络 + mDNS，0x4301 到达前就能入网
                    onEndpoint(endpointOf(null, wifiConfiguration))
                    wifiConfiguration
                }
            }

        // ---- CarPlay 会话启动 ----
        // 0x4300 发后不管：Audi 实测车机曾对它停止 ACK（其余包毫秒级 ACK），
        // 计入重传清单会让看门狗在 +20s 杀掉整条蓝牙链路。
        sendFireAndForget(Iap2MessageIds.CARPLAY_AVAILABILITY, Iap2Messages.carPlayAvailability(bluetoothMac))
        val declaresStartSession = ident.declaresSend(Iap2MessageIds.CARPLAY_START_SESSION)
        // 没拿到 0x5703（新协议车机）时 0x4301 是**唯一** AP 来源——窗口给足；
        // 已有 0x5703 的旧协议车机保持 3 秒短窗口（应答都在毫秒级）。
        val startSessionWindow =
            if (wifiFromExchange == null) START_SESSION_WAIT_NEW_PROTOCOL_NANOS else START_SESSION_WAIT_NANOS
        val startSession =
            if (declaresStartSession == false) {
                log("iap2 车机未声明会发 0x4301——跳过等待（上层可用 mDNS/网关兜底）")
                null
            } else {
                expectOrNull(Iap2MessageIds.CARPLAY_START_SESSION, minOf(deadline, System.nanoTime() + startSessionWindow))
            }

        val endpoint =
            if (startSession != null) {
                emitStartSession(startSession, phase1 = true)
            } else {
                // 不能断言「该车机不发」：Audi MMI 9310 声明会发、也确实发，只是晚到
                // 2 分 35 秒（cardeck 2026-10-18 日志）。措辞保留「稍后可能补发」的预期。
                log(
                    if (declaresStartSession == true) {
                        "iap2 0x4301 在 ${startSessionWindow / 1_000_000_000}s 窗口内未到" +
                            "（车机声明会发——可能稍后补发，保活期间收到会重新回调端点）"
                    } else {
                        "iap2 0x4301 未到（车机未声明会发）"
                    },
                )
                val wifiOnly = this.wifi ?: wifiFromExchange
                if (wifiOnly == null) {
                    // 既没有 0x5703、也没有 0x4301 = 一点 AP 信息都没有，继续等只会空转
                    throw IOException("未拿到车机热点凭据：该车机既不支持 0x5703，0x4301 也未提供 AP 信息")
                }
                endpointOf(null, wifiOnly)
            }
        log("iap2 Phase 1 完成：拿到热点凭据与（可能的）AirPlay 端点")
        return endpoint
    }

    /**
     * Phase 2 过渡：bring-up 完成后保持链路打开，持续处理配件发来的控制消息，
     * 直到链路断开。真实无线 CarPlay 中这条控制通道与 AirPlay 会话并存于整个连接生命周期。
     *
     * 心跳（裸 ACK 保活）由链路层 [Iap2LinkEngine] 在 NORMAL 态按对端 ackTimeout 自动发。
     */
    fun pumpIncomingForever() {
        log("SESSION 保活中（链路保持打开，持续处理配件消息）")
        // 同一消息 ID 连续到达时限流：车机进入媒体播放后会周期性发 NowPlaying 更新，
        // 逐条打日志会把文件日志撑爆（文件超限被删 = 丢掉真正有用的上下文）。
        var lastLoggedId = -1
        var repeatCount = 0
        while (true) {
            val frame = recvFrame(30_000L)
            if (frame == null) {
                if (!channel.isAlive) break
                continue
            }
            // 生命周期消息必须先于日志限流处理：0x4301 可能作为「车机刚打开 CarPlay
            // 窗口」的晚信号在 Phase 1 之后才到（Audi MMI 9310 晚 2 分 35 秒）。若跟着
            // 限流逻辑走，它要么被当成 keep-alive 只记长度，要么因与上一条同 ID 被
            // continue 掉——两种都等于丢弃（车机等 15 秒无人连入 AirPlay 就拆 RFCOMM）。
            when (frame.messageId) {
                Iap2MessageIds.CARPLAY_START_SESSION -> handleLateStartSession(frame)
                Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION -> handleLateWifiConfiguration(frame)
                else -> Unit
            }
            if (frame.messageId == lastLoggedId) {
                repeatCount++
                if (repeatCount % 100 != 0) continue
            } else {
                if (repeatCount >= 99) {
                    log("iap2 上一条 0x${lastLoggedId.toString(16)} 重复 ${repeatCount + 1} 次（同类已限流）")
                }
                lastLoggedId = frame.messageId
                repeatCount = 0
            }
            log(
                "iap2 rx=0x${frame.messageId.toString(16).padStart(4, '0')} " +
                    "(${Iap2MessageIds.nameOf(frame.messageId)}) keep-alive bytes=${frame.body.size}",
            )
        }
        log("SESSION 保活结束（链路断开）")
    }

    /** 保活期收到 0x4301：解析成端点回调（重复到达也回调，上层自行忽略冗余）。 */
    private fun handleLateStartSession(frame: Iap2Csm.Frame) {
        val endpoint = emitStartSession(frame, phase1 = false)
        if (endpoint.ssid.isNullOrEmpty()) {
            log("iap2 rx=0x4301（晚到）——无线组与缓存里都没有 SSID，仍回调端点交上层处理")
        }
    }

    /** 保活期车机重发 0x5703：更新缓存并回调（入网状态可能已变化）。 */
    private fun handleLateWifiConfiguration(frame: Iap2Csm.Frame) {
        val parsed = Iap2Messages.parseWifiConfiguration(frame.body)
        log(
            "iap2 rx=0x5703（保活期重发）ssid=${parsed.ssid} channel=${parsed.channel} " +
                "psk=${if (parsed.passphrase.isEmpty()) "(空)" else "***"}",
        )
        wifi = parsed
        onEndpoint(endpointOf(lastStartSession, parsed))
    }

    /** 解析 0x4301 → [CarPlayEndpoint] → 回调；返回解析结果。 */
    private var lastStartSession: Iap2Messages.StartSession? = null

    private fun emitStartSession(
        frame: Iap2Csm.Frame,
        phase1: Boolean,
    ): CarPlayEndpoint {
        logStartSessionRaw(frame)
        val parsed = Iap2Messages.parseStartSession(frame.body)
        lastStartSession = parsed
        // 新协议车机：0x4301 无线组是唯一的 AP 来源——解析出来就缓存，供端点合成
        if (wifi == null && parsed.wirelessAp != null) {
            wifi = parsed.wirelessAp
            log("iap2 0x4301 自带 AP：ssid=${parsed.wirelessAp.ssid}——已缓存")
        }
        val endpoint = endpointOf(parsed, wifi)
        if (endpointEmitted && endpoint.hashCode() == lastLateStartSessionSignature) {
            // 车机周期性重发同一份 0x4301：详情只在端点变化时打，避免刷日志；
            // 但回调照发（契约：0x4301 是生命周期事件，重复到达都回调）。
            onEndpoint(endpoint)
            return endpoint
        }
        endpointEmitted = true
        lastLateStartSessionSignature = endpoint.hashCode()
        log(
            "iap2 rx=0x4301${if (phase1) "" else "（晚到）"} airPlayPort=${endpoint.airPlayPort} " +
                "device=${endpoint.deviceIdentifier} publicKey=${endpoint.publicKeyHex?.take(16)}… " +
                "srcvers=${endpoint.sourceVersion} ips=${endpoint.ipAddresses}",
        )
        onEndpoint(endpoint)
        return endpoint
    }

    /** 0x4301 原始体只记一次：车机间端点字段差异（有无 IPv4/端口/公钥）只能靠它定谳。 */
    private fun logStartSessionRaw(frame: Iap2Csm.Frame) {
        if (startSessionRawLogged) return
        startSessionRawLogged = true
        val body = frame.body
        val shown = if (body.size <= START_SESSION_RAW_LOG_BYTES) body else body.copyOf(START_SESSION_RAW_LOG_BYTES)
        val truncated = if (body.size > shown.size) "（前 ${shown.size}/${body.size} 字节）" else ""
        log("iap2 0x4301 原始体$truncated=" + shown.joinToString("") { "%02x".format(it) })
    }

    /** 由 0x4301（可为 null）+ 0x5703 缓存合成对外端点。 */
    private fun endpointOf(
        startSession: Iap2Messages.StartSession?,
        wifi: Iap2Messages.WifiConfiguration?,
    ): CarPlayEndpoint =
        CarPlayEndpoint(
            ssid = wifi?.ssid ?: startSession?.wirelessAp?.ssid,
            passphrase = wifi?.passphrase ?: startSession?.wirelessAp?.passphrase,
            airPlayPort = startSession?.airPlayPort ?: 0,
            deviceIdentifier = startSession?.deviceIdentifier,
            publicKeyHex = startSession?.publicKeyHex,
            sourceVersion = startSession?.sourceVersion,
            ipAddresses = startSession?.ipAddresses ?: emptyList(),
            // 网关不在 iAP2 消息里；上层入网后从 DHCP/链路信息取
            gateway = null,
        )

    // ---- MFi 挑战 ----

    /**
     * MFi 挑战应答：按「对端可接受的长度」依次尝试 32B(3s) → 20B(6s) → 16B(3s)，
     * 每次失败（0xAA04 / 静默超时 / 断链）换下一档；[MfiLengthMemory] 命中则首选记忆档。
     *
     * 全部无应答时不抛异常直接返回——我们跳过签名校验（0xAA05 自报成功），由后续
     * 0x5702/0x4301 是否获得应答判定对端是否放行（真车实测无应答后仍可推进）。
     */
    private fun runMfiChallenge(
        deadline: Long,
        receiverKey: String,
    ) {
        val baseline = MFI_ATTEMPTS.map { it.first }
        val windowOf = MFI_ATTEMPTS.toMap()
        val memory = MfiLengthMemory.load(context, receiverKey)
        val order = MfiLengthMemory.orderAttempts(baseline, memory)
        if (!memory.isEmpty) {
            log(
                "iap2 MFi 按车机记忆（$receiverKey）：${MfiLengthMemory.describe(memory)}" +
                    " → 本轮顺序 ${order.joinToString("/")}B",
            )
        }
        for ((position, size) in order.withIndex()) {
            // 已知「静默过」的档位缩短窗口：它已经失败过一次，没必要再等满
            val baseWindow = windowOf[size] ?: MFI_ATTEMPTS.last().second
            val windowMillis =
                if (size in memory.silent && size != memory.succeeded) {
                    minOf(baseWindow, MFI_SILENT_RETRY_WINDOW_MS)
                } else {
                    baseWindow
                }
            val challenge = ByteArray(size).also(random::nextBytes)
            send(Iap2MessageIds.MFI_REQUEST_CHALLENGE, Iap2Csm.param(0, challenge))
            log("iap2 tx=0xaa02 challenge=${size}B（第 ${position + 1} 次，窗口 ${windowMillis}ms）")
            val window = minOf(System.nanoTime() + windowMillis * 1_000_000L, deadline)
            var gotResponse = false
            var gotFailure = false
            while (true) {
                val remaining = (window - System.nanoTime()) / 1_000_000L
                if (remaining <= 0) break
                val frame = recvFrame(remaining)
                if (frame == null) {
                    if (!channel.isAlive) {
                        log("iap2 MFi 挑战期间链路断开（${size}B，对端不接受该长度）——记入该车机被拒档位")
                        MfiLengthMemory.recordRejected(context, receiverKey, size)
                        return
                    }
                    continue
                }
                when (frame.messageId) {
                    Iap2MessageIds.MFI_RESPONSE -> {
                        log("iap2 rx=0xaa03 signature bytes=${frame.body.size}（跳过校验）")
                        gotResponse = true
                    }

                    Iap2MessageIds.MFI_AUTHENTICATION_FAILED -> {
                        log("iap2 rx=0xaa04 挑战 ${size}B 被拒")
                        gotFailure = true
                    }

                    else -> log(
                        "iap2 rx=0x${frame.messageId.toString(16).padStart(4, '0')} " +
                            "(${Iap2MessageIds.nameOf(frame.messageId)})",
                    )
                }
                if (gotResponse || gotFailure) break
            }
            if (gotResponse) {
                MfiLengthMemory.recordSuccess(context, receiverKey, size)
                return
            }
            if (gotFailure) {
                MfiLengthMemory.recordRejected(context, receiverKey, size)
            } else {
                MfiLengthMemory.recordSilent(context, receiverKey, size)
            }
            if (position < order.size - 1) {
                log("iap2 MFi ${size}B 无应答，改用 ${order[position + 1]}B 重试")
            } else {
                log("iap2 MFi 挑战全部无应答——跳过校验直接发 0xaa05（由后续消息判定车机是否放行）")
            }
        }
    }

    // ---- 设备状态问候 ----

    /**
     * 设备状态问候：0x4E0C 无线会话标识（UUID 大写）/ 0x4E0A 语言 / 0x4E0B 设备时间 /
     * 0x4E09 设备名。一轮一次（[force]=true 才重发：0x5703 分段等待里的救急，§9j 处置 3）。
     */
    private fun sendDeviceStateGreetings(force: Boolean = false) {
        if (greetingsSent && !force) return
        greetingsSent = true
        val timezoneMinutes = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
        val daylightSaving = if (TimeZone.getDefault().inDaylightTime(Date())) 1 else 0
        send(
            Iap2MessageIds.DEVICE_UUID_UPDATE,
            Iap2Csm.string(0, deviceUuid()),
        )
        send(
            Iap2MessageIds.DEVICE_LANGUAGE_UPDATE,
            Iap2Csm.string(0, "zh"),
        )
        send(
            Iap2MessageIds.DEVICE_TIME_UPDATE,
            Iap2Messages.deviceTime(System.currentTimeMillis() / 1_000L, timezoneMinutes, daylightSaving),
        )
        send(
            Iap2MessageIds.DEVICE_INFORMATION_UPDATE,
            Iap2Csm.string(0, deviceName),
        )
        log("iap2 tx=0x4e0c/0x4e0a/0x4e0b/0x4e09 设备状态问候（name=$deviceName mac=$bluetoothMac）")
    }

    /** 设备 UUID：由蓝牙 MAC 确定性派生，跨会话稳定（车机按它标识设备；大写输出）。 */
    private fun deviceUuid(): String =
        UUID
            .nameUUIDFromBytes(bluetoothMac.toByteArray())
            .toString()
            .uppercase()

    // ---- 帧收发 ----

    /** 分段等待用的截止时刻：取「整体 deadline」与「再等 millis 毫秒」的较小值。 */
    private fun rescueDeadline(
        deadlineNanos: Long,
        millis: Long,
    ): Long = minOf(deadlineNanos, System.nanoTime() + millis * 1_000_000L)

    private fun send(
        messageId: Int,
        vararg body: ByteArray,
    ) {
        val joined =
            when (body.size) {
                0 -> ByteArray(0)
                1 -> body[0]
                else -> body.reduce { acc, bytes -> acc + bytes }
            }
        sendOrThrow(Iap2Csm.encode(messageId, joined), messageId, awaitAck = true)
    }

    /** [send] 的发后不管变体：链路不因对端不 ACK 此消息而重传/死亡（0x4300/0xEA00/0x4E0E 用）。 */
    private fun sendFireAndForget(
        messageId: Int,
        body: ByteArray,
    ) {
        sendOrThrow(Iap2Csm.encode(messageId, body), messageId, awaitAck = false)
    }

    /** 按对端协商的 maxLength 分片发送；链路关闭时抛 IOException（会话层据此收尾）。 */
    private fun sendOrThrow(
        frame: ByteArray,
        messageId: Int,
        awaitAck: Boolean,
    ) {
        val limit = channel.peerMaxControlPayload() ?: (frame.size - 1).coerceAtLeast(1)
        var offset = 0
        while (offset < frame.size) {
            val end = minOf(offset + limit, frame.size)
            if (!channel.sendControl(frame.copyOfRange(offset, end), awaitAck)) {
                throw IOException("iAP2 链路在发送 0x${messageId.toString(16)} 时关闭")
            }
            offset = end
        }
        log(
            "iap2 tx=0x${messageId.toString(16).padStart(4, '0')}" +
                if (awaitAck) "" else "（发后不管）",
        )
    }

    private fun expect(
        messageId: Int,
        deadline: Long,
    ): Iap2Csm.Frame =
        expectOrNull(messageId, deadline)
            ?: throw IOException("等待 0x${messageId.toString(16)} 超时")

    /**
     * 同 [expect]，但超时返回 null（用于可选消息）。非预期帧进暂存区供后续消费。
     *
     * 只从 [receivedFrames] 读新帧，**不重读自己刚暂存的帧**：旧实现两者共用一个
     * 队列，非预期帧被塞回后立刻又被本函数取出，形成活锁（见 pendingFrames 注释）。
     * 目标帧若已在暂存区（早到），先从这里取，保证不漏。
     */
    private fun expectOrNull(
        messageId: Int,
        deadlineNanos: Long,
    ): Iap2Csm.Frame? {
        pendingFrames.firstOrNull { it.messageId == messageId }?.let {
            pendingFrames.remove(it)
            return it
        }
        while (true) {
            val remaining = (deadlineNanos - System.nanoTime()) / 1_000_000L
            if (remaining <= 0) return null
            val frame = recvNewFrame(remaining)
            if (frame == null) {
                if (!channel.isAlive) return null
                continue
            }
            if (frame.messageId == messageId) return frame
            log(
                "iap2 rx=0x${frame.messageId.toString(16).padStart(4, '0')} " +
                    "(${Iap2MessageIds.nameOf(frame.messageId)})，非预期，暂存待后续消费",
            )
            pendingFrames += frame
            if (pendingFrames.size > maxPendingFrames) {
                pendingFrames.removeFirst()
                if (!pendingOverflowLogged) {
                    pendingOverflowLogged = true
                    log("iap2 非预期帧暂存区超过 $maxPendingFrames 条——丢弃最旧的（车机订阅类消息过多）")
                }
            }
        }
    }

    /**
     * 通用取帧（MFi 挑战 / 保活泵）：优先消费暂存的非预期帧
     * （保持到达顺序：暂存 > 新解析），再读新帧。
     */
    private fun recvFrame(timeoutMillis: Long): Iap2Csm.Frame? {
        pendingFrames.firstOrNull()?.let {
            pendingFrames.removeFirst()
            return it
        }
        return recvNewFrame(timeoutMillis)
    }

    /** 只取新帧（先已解析队列、再读 socket）；绝不返回暂存区的帧。 */
    private fun recvNewFrame(timeoutMillis: Long): Iap2Csm.Frame? {
        receivedFrames.firstOrNull()?.let {
            receivedFrames.removeFirst()
            return it
        }
        if (timeoutMillis <= 0) return null
        val bytes = channel.recvControl(timeoutMillis) ?: return null
        val frames = parser.offer(bytes)
        if (frames.isEmpty()) return null
        frames.forEachIndexed { index, frame -> if (index > 0) receivedFrames += frame }
        return frames.first()
    }

    // ---- 日志 ----

    private fun logIdentification(
        ident: Iap2Messages.Identification,
        frame: Iap2Csm.Frame,
    ) {
        log("iap2 rx=0x1d01 车机身份 name=${ident.name} model=${ident.model} manufacturer=${ident.manufacturer}")
        ident.sendIds?.let { sends ->
            log("iap2 0x1d01 车机发送清单(${sends.size})=" + sends.sorted().joinToString(" ") { "0x%04x".format(it) })
            log(
                "iap2 0x1d01 可选消息声明：StartHID(0x6800)=" +
                    (ident.declaresSend(Iap2MessageIds.START_HID)?.let { if (it) "会发" else "不发" } ?: "未知") +
                    " CarPlayStartSession(0x4301)=" +
                    (ident.declaresSend(Iap2MessageIds.CARPLAY_START_SESSION)?.let { if (it) "会发" else "不发" } ?: "未知"),
            )
        }
        ident.receiveIds?.let { receives ->
            log("iap2 0x1d01 车机可收清单(${receives.size})=" + receives.sorted().joinToString(" ") { "0x%04x".format(it) })
            log(
                "iap2 0x1d01 协议代际：请求 WiFi 配置(0x5702)=" +
                    (ident.declaresReceive(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION)?.let { if (it) "可收" else "不收" } ?: "未知") +
                    " 回 WiFi 配置(0x5703)=" +
                    (ident.declaresSend(Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION)?.let { if (it) "会发" else "不发" } ?: "未知"),
            )
        }
        log("iap2 0x1d01 原始体=" + frame.body.joinToString("") { "%02x".format(it) })
    }

    private fun remainingMillis(deadlineNanos: Long): Long {
        val remaining = (deadlineNanos - System.nanoTime()) / 1_000_000L
        return remaining.coerceIn(0, 60_000L)
    }
}
