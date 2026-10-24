package io.github.hypercar.headunit

import io.github.hypercar.settings.SettingsStore

/**
 * 车机「档案」：把**品牌/型号特有的行为差异**集中到一个对象里。
 *
 * 为什么需要它：这套协议在不同车机上差异不小，而且大多是实测出来的——
 * BMW MGU-55 从不发 0x4301、不回 0xEA03；WinPlay 则校验 deviceID、端口固定 7000。
 * 以前这些差异散落在 `Iap2SenderSession`/`HyperCarRepo` 的常量和 if 里，
 * 每加一个品牌都要动主流程。现在：**新增品牌 = 新增一个档案文件 + 注册表一行**，
 * 会话层与传输层只读档案，不再出现品牌名。
 *
 * 约定：字段一律带默认值（默认取「真 iPhone 实测行为」），
 * 这样新加档案时只需覆盖确实不同的那几项。
 */
data class HeadUnitProfile(
    /** 稳定 id（写进 SharedPreferences，改名会丢设置）。 */
    val id: String,
    /** 显示名（设置页、引导卡）。 */
    val label: String,
    /** 型号补充说明（如 MGU-86 / MGU-55 / AirPlay 1）。 */
    val model: String,
    /** 一句话备注：这个档案凭什么这么配（写进设置页帮助文本）。 */
    val notes: String,

    /**
     * 是否经过**实车首手验证**。
     *
     * false 的档案（如奥迪）只有二手结论支撑，设置页会打「未实测」角标：
     * 免得用户以为是"官方支持"，也提醒后续贡献者优先补实测。
     */
    val verified: Boolean = true,

    /** 接收端会逐字符比对 AirPlay SETUP 的 deviceID 与本机蓝牙 MAC（WinPlay 会，BMW 不会）。 */
    val validatesDeviceId: Boolean = false,

    /** 发 0x4300 CarPlayAvailability（真 iPhone 从不发；仅车机声明可收时才值得发）。 */
    val sendCarPlayAvailability: Boolean = false,

    /** 开 0xEA00 EA 会话（真 iPhone 一个都不发，车机也从不回 0xEA03）。 */
    val openEaSessions: Boolean = false,

    /** 发 0x4E0E 设备传输标识（真 iPhone 不发）。 */
    val sendTransportIdentifier: Boolean = false,

    /** 车机发 0xAE00 StartPowerUpdates 时回 0xAE01（真 iPhone 必回 3 条）。 */
    val replyPowerUpdates: Boolean = true,

    /** 端点端口策略：true = 先查 mDNS `_airplay._tcp`，false = 直接用 [fallbackAirplayPorts]。 */
    val preferMdnsAirplay: Boolean = true,

    /** mDNS 拿不到（或不用 mDNS）时的兜底端口，按顺序试连。 */
    val fallbackAirplayPorts: List<Int> = listOf(7000, 7001, 7100),

    /** 接收端 HID 像素空间（"WxH"）；null = 用 /info 回报或协商尺寸。 */
    val touchDisplay: String? = null,

    /**
     * 是否进入「只保持 iAP2 链路」模式（联调/盒子角色）。
     *
     * true = 手机只维持 iAP2（AirPlay 由 PC 侧 `hypercar-hub` 推送）。
     * 原来设置里有个独立开关，现在**由车机档案联动**：选 WinPlay 自动打开，
     * 选真车自动关闭——少一个容易忘的开关。
     */
    val keepIap2AliveOnly: Boolean = false,

    /** 首次连接引导里这个车机额外要提示的一句；null = 不提。 */
    val pairingHint: String? = null,
)

/**
 * 内置档案表。新增品牌：在这里加一个 `val`，并在 [all] 里注册。
 */
object HeadUnitProfiles {

    /** 宝马 iD8.5（MGU-86 / MGU-55）：真车实测过的那一套。 */
    val BMW_ID85 = HeadUnitProfile(
        id = "bmw_id85",
        label = "BMW iD8.5",
        model = "MGU-86 / MGU-55",
        notes = "真车实测：车机从不发 0x4301、从不回 0xEA03；端点靠 ctrl 邀请 + mDNS，失败要保活 iAP2。",
        validatesDeviceId = false,
        sendCarPlayAvailability = false,
        openEaSessions = false,
        sendTransportIdentifier = false,
        replyPowerUpdates = true,
        preferMdnsAirplay = true,
        fallbackAirplayPorts = listOf(7000, 7001, 7100),
        touchDisplay = null,
        pairingHint = "首次要在 iDrive 里把这个设备选成 CarPlay 手机（车机会弹确认）",
    )

    /** WinPlay（Windows 上的 AirPlay 接收端，联调/盒子用）。 */
    val WINPLAY = HeadUnitProfile(
        id = "winplay",
        label = "WinPlay",
        model = "PC AirPlay 接收端",
        notes = "接收端会校验 deviceID（SETUP 必须带本机真实蓝牙 MAC）；控制面固定 7000，/info 只认 GET。",
        validatesDeviceId = true,
        sendCarPlayAvailability = false,
        openEaSessions = false,
        sendTransportIdentifier = false,
        replyPowerUpdates = true,
        preferMdnsAirplay = false,
        fallbackAirplayPorts = listOf(7000),
        keepIap2AliveOnly = true,
        // WinPlay 的 /info 固定报 0x0，HID 坐标按它自己的窗口像素算（实测值）
        touchDisplay = "2732x794",
        pairingHint = "先在 WinPlay 点「开始接收」，再让手机连（它不监听时连不上）",
    )

    /**
     * 奥迪 MIB3 / MIB3 3315 —— **未实车验证**（只有 cardeck 与历史日志的二手结论）。
     *
     * 已知（均来自代码注释里的实测记录）：
     *  - 车机**会发** 0x4301，但可能晚到 2 分 35 秒（MIB3 3310 实测）；
     *  - 控制消息会话 id = 1；0x4E0E 接受但从不 ACK；
     *  - 触摸报告是 5 字节单指（Rust 侧已按长度自适应，无需配置）；
     *  - 0x1D01 可收清单可能只有 5 项、不含 0x5702（清单只能证明"有"）。
     *
     * 未知：是否需要 EA 会话、`/info` 是否只认 POST、AirPlay 端口、
     * 是否校验 deviceID、是否广播 `_airplay._tcp`。首次上车请按
     * `docs/HEADUNIT-PROFILES.md` 的「症状 → 字段」表逐项校准后把 verified 改 true。
     */
    val AUDI_MIB3 = HeadUnitProfile(
        id = "audi_mib3",
        label = "Audi MIB3",
        model = "MIB3 / 3315",
        notes = "未实车验证：车机会发 0x4301（可能晚到 2 分钟以上），控制会话 id=1，触摸为 5 字节单指。",
        verified = false,
        validatesDeviceId = false,
        sendCarPlayAvailability = false,
        openEaSessions = false,
        sendTransportIdentifier = false,
        replyPowerUpdates = true,
        preferMdnsAirplay = true,
        fallbackAirplayPorts = listOf(7000, 7001, 7100),
        touchDisplay = null,
        pairingHint = "首次要在 MMI 里选「Apple CarPlay」并确认这台手机（车机会弹提示）",
    )
    /** 全部内置档案（设置页按此顺序展示）。 */
    val all: List<HeadUnitProfile> = listOf(BMW_ID85, WINPLAY, AUDI_MIB3)

    /** 按 id 取档案；未知/空值回落到宝马（默认车型）。 */
    fun byId(id: String?): HeadUnitProfile =
        all.firstOrNull { it.id == id } ?: BMW_ID85

    /** 当前设置里选中的档案。 */
    fun current(): HeadUnitProfile = byId(SettingsStore.headUnitProfileId)
}



