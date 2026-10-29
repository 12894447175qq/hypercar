package io.github.hypercar.iap2

import java.security.SecureRandom
import java.util.TimeZone
import java.util.UUID

/** AirPlay 事件入站、控制通道出站的独立 iAP2 会话；与蓝牙会话分开计时和清理。 */
class VirtualIap2Session(
    private val send: (ByteArray) -> Boolean,
    private val log: (String) -> Unit,
    preferredChallengeBytes: Int = 20,
    private val now: () -> Long = { System.nanoTime() / 1_000_000L },
    private val epoch: () -> Long = { System.currentTimeMillis() },
    private val onControlReady: () -> Unit = {},
) : AutoCloseable {
    private val engine = Iap2LinkEngine()
    private val parser = Iap2Csm.Parser()
    private val early = ArrayDeque<ByteArray>()
    private val lengths = listOf(preferredChallengeBytes, 20, 32, 16).distinct()
    private val random = SecureRandom()
    private val seen = mutableSetOf<Int>()
    private var open = true
    private var armed = false
    private var armedAt = 0L
    private var initiated = false
    private var identified = false
    private var csmStarted = false
    private var requestedCert = false
    private var challengeIndex = 0
    private var challengeAt: Long? = null
    private var authenticated = false
    private var authFailed = false
    private var rxPackets = 0L
    private var rxBytes = 0L
    private var txPackets = 0L
    private var lastRxAt: Long? = null
    private var lastReportAt = 0L
    private var reportedState: Iap2LinkEngine.State? = null
    private var earlyBytes = 0
    private var detectMarkers = 0
    private var readyNotified = false

    init {
        engine.onDetectionMarker = { clock ->
            if (open && armed && engine.state == Iap2LinkEngine.State.NEGOTIATING &&
                engine.peerSynchronization == null && !initiated) {
                detectMarkers++
                log("IAPLINK: peer_detect count=$detectMarkers elapsedMs=${clock - armedAt}")
                if (detectMarkers >= 2) {
                    initiated = true
                    engine.initiate(clock)
                    log("IAPLINK: 连续两次 DETECT，立即补发本方同步表（CarDeck 协商顺序）")
                }
            }
        }
    }
    @Synchronized fun isOpen(): Boolean = open

    @Synchronized fun arm() {
        if (!open || armed) return
        armed = true
        armedAt = now()
        engine.startResponder(armedAt)
        drain()
        while (early.isNotEmpty() && open) feedNow(early.removeFirst())
        earlyBytes = 0
        log("IAPLINK: 主屏就绪，控制通道已发 DETECT，等待车机 SYN")
    }

    @Synchronized fun feed(bytes: ByteArray) {
        if (!open || bytes.isEmpty()) return
        if (!armed) {
            if (earlyBytes + bytes.size <= 262144 && early.size < 16) {
                early.add(bytes.copyOf())
                earlyBytes += bytes.size
            } else {
                log("IAPLINK: 主屏就绪前数据超限，停止本轮虚拟链路")
                close()
            }
            return
        }
        feedNow(bytes)
    }

    private fun feedNow(bytes: ByteArray) {
        rxPackets++
        rxBytes += bytes.size
        lastRxAt = now()
        engine.feed(bytes, now())
        drain()
    }

    @Synchronized fun tick() {
        if (!open || !armed) return
        val clock = now()
        if (!initiated && engine.peerSynchronization == null && clock - armedAt >= 6_000) {
            initiated = true
            engine.initiate(clock)
            log("IAPLINK: DETECT 后未收到 SYN，补发本方同步表一次")
        }
        if (engine.state != Iap2LinkEngine.State.NORMAL && clock - armedAt >= 20_000) {
            report("timeout", force = true)
            log("IAPLINK: 虚拟链路协商超时；停止重复发送，主屏链路由其自身状态管理")
            close()
            return
        }
        engine.advanceTime(clock)
        challengeAt?.let {
            if (!authenticated && clock - it >= 3_000) nextChallenge("应答超时")
        }
        drain()
    }

    private fun drain() {
        report("progress")
        flush()
        if (!open) return
        if (!csmStarted && engine.state == Iap2LinkEngine.State.NORMAL) {
            csmStarted = true
            csm(Iap2MessageIds.START_IDENTIFICATION)
        }
        while (open) {
            when (val event = engine.pollEvent() ?: break) {
                is Iap2LinkEngine.Event.Control -> parser.offer(event.bytes).forEach(::handle)
                is Iap2LinkEngine.Event.Dead -> { log("IAPLINK: 虚拟链路结束 ${event.reason}"); close() }
            }
        }
        flush()
        if (open && authenticated && !readyNotified) {
            readyNotified = true
            // Queue after all authentication/device-state messages. Native code
            // acknowledges those transactions before releasing optional audio.
            onControlReady()
        }
    }

    private fun flush() {
        val bytes = engine.takeOutput()
        if (bytes.isNotEmpty()) txPackets++
        if (bytes.isNotEmpty() && open && !send(bytes)) {
            log("IAPLINK: 控制出站队列不可用，停止本轮虚拟链路")
            close()
        }
    }

    private fun report(cause: String, force: Boolean = false) {
        val clock = now()
        if (!force && reportedState == engine.state && clock - lastReportAt < 5_000) return
        reportedState = engine.state
        lastReportAt = clock
        log("IAPLINK: progress cause=$cause state=${engine.state} peerSync=${engine.peerSynchronization != null} csmStarted=$csmStarted authenticated=$authenticated rxPackets=$rxPackets rxBytes=$rxBytes txPackets=$txPackets lastRxAgeMs=${lastRxAt?.let { clock - it }} elapsedMs=${clock - armedAt}")
    }

    private fun csm(id: Int, body: ByteArray = byteArrayOf()) {
        if (!open || !engine.sendControl(Iap2Csm.encode(id, body), now())) return
        log("IAPLINK: tx=0x${id.toString(16)} bytes=${body.size}")
    }

    private fun challenge() {
        if (authenticated || authFailed || challengeAt != null) return
        if (challengeIndex >= lengths.size) {
            authFailed = true
            log("IAPLINK: MFi 所有挑战长度均无有效应答；保留诊断，未标记认证完成")
            return
        }
        val size = lengths[challengeIndex]
        val bytes = ByteArray(size).also(random::nextBytes)
        challengeAt = now()
        csm(Iap2MessageIds.MFI_REQUEST_CHALLENGE, Iap2Csm.param(0, bytes))
        log("IAPLINK: MFi challengeLength=$size")
    }

    private fun nextChallenge(reason: String) {
        if (authenticated || authFailed) return
        log("IAPLINK: MFi length=${lengths.getOrNull(challengeIndex)} $reason")
        challengeIndex++
        challengeAt = null
        challenge()
    }

    private fun handle(frame: Iap2Csm.Frame) {
        if (!open) return
        val id = frame.messageId
        if (seen.add(id)) log("IAPLINK: rx=0x${id.toString(16)} bytes=${frame.body.size}")
        when (id) {
            Iap2MessageIds.IDENTIFICATION_INFORMATION -> if (!identified) {
                identified = true
                csm(Iap2MessageIds.IDENTIFICATION_ACCEPTED)
                csm(Iap2MessageIds.MFI_REQUEST_CERTIFICATE)
                requestedCert = true
            }
            Iap2MessageIds.MFI_EXTENDED_INITIATE -> if (requestedCert && !authenticated) {
                csm(Iap2MessageIds.MFI_REQUEST_CERTIFICATE)
            }
            Iap2MessageIds.MFI_CERTIFICATE -> if (requestedCert) challenge()
            Iap2MessageIds.MFI_AUTHENTICATION_FAILED -> if (challengeAt != null) nextChallenge("车机拒绝")
            Iap2MessageIds.MFI_RESPONSE -> if (challengeAt != null && frame.body.isNotEmpty() && !authenticated) {
                // 与已有蓝牙实现相同：这里只证明车机回了签名，不宣称验证了证书链。
                authenticated = true
                challengeAt = null
                csm(Iap2MessageIds.MFI_AUTHENTICATION_SUCCEEDED)
                sendDeviceState()
                log("IAPLINK: 车机已返回挑战应答，控制会话已推进；未执行证书链验证")
            }
            Iap2MessageIds.START_POWER_UPDATES -> Iap2Messages.powerUpdates().forEach { csm(Iap2MessageIds.POWER_UPDATE, it) }
            Iap2MessageIds.START_NOW_PLAYING_UPDATES -> csm(0x5001) // 当前网关没有媒体元数据。
            Iap2MessageIds.START_COMMUNICATIONS_UPDATES -> csm(0x4158)
            Iap2MessageIds.START_CALL_STATE_UPDATES -> csm(0x4155, Iap2Csm.u8(2, 0))
            0x4170 -> csm(0x4171, Iap2Csm.u8(0, 0) + Iap2Csm.u16(2, 0) + Iap2Csm.u8(5, 0) + Iap2Csm.u16(7, 0))
            // HID/EA/定位等数据的链路 ACK 已由 Iap2LinkEngine 发送。
            // 不把未知 HID 位图映射为按键，避免误触；现有 AirPlay hidSendReport 路径保留。
        }
    }

    private fun sendDeviceState() {
        csm(Iap2MessageIds.DEVICE_UUID_UPDATE, Iap2Csm.string(0, UUID.randomUUID().toString().uppercase()))
        csm(0xA100, Iap2Csm.param(6, byteArrayOf()))
        csm(Iap2MessageIds.DEVICE_LANGUAGE_UPDATE, Iap2Csm.string(0, "zh"))
        val wall = epoch()
        val zone = TimeZone.getDefault()
        csm(Iap2MessageIds.DEVICE_TIME_UPDATE, Iap2Messages.deviceTime(wall / 1000, zone.getOffset(wall) / 60000, if (zone.inDaylightTime(java.util.Date(wall))) 1 else 0))
        csm(Iap2MessageIds.DEVICE_INFORMATION_UPDATE, Iap2Csm.string(0, "Hypercar"))
    }

    @Synchronized override fun close() {
        if (!open) return
        report("close", force = true)
        open = false
        early.clear()
        earlyBytes = 0
        engine.feedEof()
        engine.takeOutput()
    }
}
