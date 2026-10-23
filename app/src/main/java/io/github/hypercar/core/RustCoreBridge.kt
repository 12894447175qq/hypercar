package io.github.hypercar.core

/**
 * Rust 核心层 JNI 桥（libhypercar_core.so）。
 *
 * 与「参考文档」的分工差异（详见 docs/NEGOTIATION-GAPS.md）：
 *  - 参考文档：Kotlin 完成 AirPlay 全部协商后把「已连通的 FD + 密钥」交给 Rust；
 *  - 本项目：协商整体下沉 Rust（/info、pair-verify、RTSP SETUP、事件通道、三条心跳），
 *    Kotlin 只负责「拿到绑定车机网络的 socket FD」并通过 [SocketProvider] 按需供给。
 *    这样协商状态机只有一份实现，秒连路径少一次跨语言往返。
 */
object RustCoreBridge {

    init {
        System.loadLibrary("hypercar_core")
        nativeInitLogger()
    }

    // ---- CarLife 原生硬按键码（宝马 iDrive 旋钮/方向盘快捷键 → CarLife 0x68008）----
    const val CL_KEY_HOME = 0x01
    const val CL_KEY_BACK = 0x02
    const val CL_KEY_NEXT_SONG = 0x0E
    const val CL_KEY_PREV_SONG = 0x0F
    const val CL_KEY_PLAY_PAUSE = 0x10
    const val CL_KEY_KNOB_LEFT = 0x1C
    const val CL_KEY_KNOB_RIGHT = 0x1D
    const val CL_KEY_KNOB_SELECT = 0x1E

    /**
     * 触摸动作（**CarLife 语义**：0=DOWN/1=UP/2=MOVE，不是 Android MotionEvent 的 1/2/3）。
     * CTRL 通道载荷为裸 BE 结构 `[u32 action][u32 x][u32 y]`。
     */
    const val TOUCH_DOWN = 0
    const val TOUCH_UP = 1
    const val TOUCH_MOVE = 2

    /**
     * socket 供给器：Rust 协商过程中需要新开「绑定车机网络」的套接字
     * （事件通道、视频 dataPort、音频 dataPort/controlPort、keepAlive 端口）。
     * Kotlin 实现必须走 carNetwork.socketFactory / carNetwork.bindSocket，
     * 保证不走手机流量；返回 Linux FD（已 detach），失败返回 -1。
     */
    interface SocketProvider {
        fun openTcpFd(host: String, port: Int, timeoutMs: Int): Int
        fun openUdpFd(host: String, port: Int): Int
    }

    /** Rust → Kotlin 状态/日志回调（在 Rust 工作线程调用，实现方需自行切主线程）。 */
    interface EventListener {
        /** 状态迁移：[state] 为机器码，[detail] 为人话（见 engine.rs 的 emit 调用）。 */
        fun onState(state: String, detail: String)

        fun onLog(line: String)

        /** 统计心跳（约 1s 一次）：fps/frames/kbps/音频闪避。 */
        fun onStats(fps: Int, frames: Long, kbps: Long, audioPlaying: Boolean, ducking: Boolean)
    }

    @Volatile
    private var listener: EventListener? = null

    fun setListener(l: EventListener?) {
        listener = l
        nativeSetListener(l)
    }

    fun currentListener(): EventListener? = listener

    external fun nativeInitLogger()

    external fun nativeSetListener(listener: EventListener?)

    /**
     * 启动全链路网关。
     *
     * @param configJson 引擎配置（见 rust-core/src/engine.rs 的 EngineConfig::from_json）：
     *   device_id（本机真实蓝牙 MAC）、car_ip、airplay_port、width/height/fps（CarLife 编码分辨率）、
     *   identity_path（ed25519 长期身份落盘路径）、enable_audio、keep_alive_stats…
     * @param controlFd Kotlin 已 connect 到 车机 AirPlay 端口（carNetwork 绑定）的 TCP FD（已 detach）
     * @param socketProvider 后续新端口的 socket 供给
     */
    external fun nativeStart(configJson: String, controlFd: Int, socketProvider: SocketProvider): Boolean

    external fun nativeStop()

    /** AirPlay 事件通道解出的车机触摸 → CarLife 0x68001（坐标已在 Rust 侧换算）。 */
    external fun nativeSendTouch(action: Int, x: Int, y: Int)

    /** iDrive 旋钮/方向盘媒体键 → CarLife 0x68008 硬按键。 */
    external fun nativeSendHardKey(carlifeKeyCode: Int)

    /** Rust 侧是否在运行（诊断）。 */
    external fun nativeIsRunning(): Boolean

    /**
     * 构造宝马 HUD/仪表导航报文（iAP2 0x5200 TLV），返回完整报文字节。
     * 由 iAP2 会话层在事件通道 iAPSendMessage 或蓝牙控制会话里发送。
     */
    external fun nativeBuildIap2NavPacket(
        maneuverType: Int,
        currentStreet: String,
        nextStreet: String,
        distToManeuverM: Int,
        remainTimeS: Int,
        remainDistM: Int
    ): ByteArray
}
