package io.github.hypercar.core

/**
 * 会话状态模型 —— UI（Compose）与 Orchestrator 之间的唯一契约。
 *
 * 本项目砍掉了 cardeck 的 VirtualDisplay / EGL 合成链路：手机不再自绘车机桌面，
 * 而是让本机 CarLife/CarWith/车联 连回 127.0.0.1，Rust 核心层零转码直推宝马 AirPlay。
 * 因此状态模型只有三条链路：蓝牙 iAP2 协商 → AirPlay 会话 → CarLife 回环。
 */
sealed interface LinkState {
    /** 未连接（首页大卡：待机态）。 */
    data object Idle : LinkState

    /** 协商进行中，[step] 为当前步骤的人话描述（秒连链路的可视化）。 */
    data class Negotiating(val step: String) : LinkState

    /** 全链路已建立：[carName] 车机名，[sinceMillis] 建立时刻。 */
    data class Connected(val carName: String, val sinceMillis: Long) : LinkState

    /** 失败：[reason] 人话原因，[recoverable] 是否可自动重试。 */
    data class Failed(val reason: String, val recoverable: Boolean = true) : LinkState

    data object Stopping : LinkState
}

/** 本机 CarLife 回环接入状态（Rust 核心层回报）。 */
sealed interface CarLifeState {
    data object Idle : CarLifeState

    /** 网关监听就绪，正在等 CarLife/CarWith 接入 127.0.0.1。 */
    data class Waiting(val port: Int = 7240) : CarLifeState

    /** CarLife 已接入并在推流。 */
    data class Active(val appLabel: String = "CarLife") : CarLifeState
}

/** 视频通路统计（零转码：帧率即 CarLife 编码帧率）。 */
data class VideoStats(
    val fps: Int = 0,
    val frames: Long = 0,
    val kbps: Long = 0,
    val resolution: String = "",
)

/** 音频通路统计。 */
data class AudioStats(
    val playing: Boolean = false,
    val ducking: Boolean = false,
)

/** UI 快照：Repo 每次变更推送给 Compose。 */
data class SessionSnapshot(
    val link: LinkState = LinkState.Idle,
    val carLife: CarLifeState = CarLifeState.Idle,
    val video: VideoStats = VideoStats(),
    val audio: AudioStats = AudioStats(),
    val shizukuReady: Boolean = false,
    val targetCar: String? = null,
    /** 调试日志记录开关（关 = 暂停写入环形缓冲，不丢既有内容）。 */
    val logRecording: Boolean = true,
    /** 最近一次导出的绝对路径（null = 尚未导出）。 */
    val lastExportPath: String? = null,
    /** 最近日志（环形缓冲，供「诊断」页展示，无需 adb）。 */
    val recentLogs: List<String> = emptyList(),
)
