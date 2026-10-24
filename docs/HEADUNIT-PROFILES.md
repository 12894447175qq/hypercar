# 车机档案（HeadUnitProfile）：新增品牌怎么做

这套协议在不同车机上的差异**不小**，而且大多是实测出来的（宝马从不发 0x4301、WinPlay 校验
deviceID……）。为了让"加一个品牌"不去动主流程，所有差异都收敛在一处：

```
app/src/main/java/io/github/hypercar/headunit/HeadUnitProfile.kt   ← 档案定义 + 内置档案表
```

会话层（`Iap2SenderSession`）、连接层（`HyperCarRepo`）只读档案，代码里**不出现品牌名**。

## 1. 字段含义

| 字段 | 作用 | 怎么判断该填什么 |
|---|---|---|
| `id` | 持久化键（写进 SharedPreferences） | 小写下划线；**定下就别改**，改了用户的选择会丢 |
| `label` / `model` | 设置页与引导卡显示 | 用户认得出的名字 |
| `notes` | 设置页里这个档案的一句话说明 | 写"凭什么这么配"（实测结论） |
| `verified` | 是否经过**实车首手验证**；`false` 会在设置页打「未实测」角标 | 自己没上过实车就别写 `true` |
| `validatesDeviceId` | 接收端会逐字符比对 AirPlay `SETUP` 的 `deviceID` 与本机蓝牙 MAC | 连不上且日志出现 `403`/`deviceID` 相关拒绝 → `true` |
| `sendCarPlayAvailability` | 是否发 `0x4300` CarPlayAvailability | 真 iPhone **从不发**；只有车机在 `0x1D01` 里声明可收 `0x4300` 且实测需要时才开 |
| `openEaSessions` | 是否开 `0xEA00` EA 会话 | 真 iPhone 一个都不发；车机若不回 `0xEA03`，保持 `false` |
| `sendTransportIdentifier` | 是否发 `0x4E0E` 传输标识 | 真 iPhone 不发；默认 `false` |
| `replyPowerUpdates` | 车机发 `0xAE00` 时是否回 `0xAE01` | 真 iPhone **必回**（3 条）；默认 `true` |
| `preferMdnsAirplay` | 端口策略：`true` = 先查 mDNS `_airplay._tcp` | 车机广播该服务（抓包/Bonjour 浏览可见）→ `true`；普通接收端（WinPlay）→ `false` |
| `fallbackAirplayPorts` | mDNS 拿不到时的试连顺序 | AirPlay 惯例 `7000`，其次 `7001` / `7100` |
| `touchDisplay` | 接收端 HID 像素空间（`"WxH"`） | 车机 `/info` 报 `0x0` 而坐标总是偏 → 填接收端真实像素 |
| `pairingHint` | 首次连接引导里这台车额外的提示 | 例如"首次要在 iDrive 里选成 CarPlay 手机" |

> 默认值全部取「真 iPhone 实测行为」。新档案**只覆盖确实不同**的项。

## 2. 新增一个品牌：四步

**① 加档案**

在 `HeadUnitProfiles` 里加一个 `val`：

```kotlin
/** 某车机（型号 X）：一句话说明凭什么这么配。 */
val AUDI_MIB3 = HeadUnitProfile(
    id = "audi_mib3",
    label = "Audi MIB3",
    model = "MIB3 / 8W",
    notes = "车机声明会发 0x4301 但可能晚到 2 分钟以上；端口 7000。",
    validatesDeviceId = false,
    sendCarPlayAvailability = true,   // 这台车声明可收 0x4300（示例）
    openEaSessions = false,
    sendTransportIdentifier = false,
    replyPowerUpdates = true,
    preferMdnsAirplay = true,
    fallbackAirplayPorts = listOf(7000, 7001, 7100),
    touchDisplay = null,
    pairingHint = "首次要在车机的 CarPlay 列表里确认这台手机",
)
```

**② 注册**

```kotlin
val all: List<HeadUnitProfile> = listOf(BMW_ID85, WINPLAY, AUDI_MIB3)
```

设置页与引导卡会自动多出一项（UI 遍历 `all`，不需要改 UI）。

**③ 实测一轮并抓日志**

在设置里切到新档案 → 连接 → 导出日志。重点看这几行判断字段对不对：

| 日志 | 说明 |
|---|---|
| `iap2 跳过 0x4300 … 档案 <id> 不要求` | `sendCarPlayAvailability=false` 生效 |
| `iap2 tx=0x4300 … 档案 <id> 要求且车机可收` | 打开了且车机声明可收 |
| `iap2 跳过 0xea00 EA 会话（档案 <id> …）` | `openEaSessions=false` 生效 |
| `iap2 rx=0xae00 StartPowerUpdates → 回 3 条 0xae01` | `replyPowerUpdates=true` 生效 |
| `MDNS: ✓ … → <ip>:<port>` / `候选端口=[…]` | `preferMdnsAirplay` / `fallbackAirplayPorts` 生效 |
| `AIRPLAY: ✗ GET /info → [RTSP/1.0 455 …]` | 端口或 `/info` 方法不对（先换端口，再看是否需要 POST） |

**③.5 内置的未验证档案**

`Audi MIB3`（`id=audi_mib3`）目前 `verified=false`：只有二手结论支撑

- 车机会发 `0x4301`，但可能晚到 2 分 35 秒（MIB3 3310 实测）；
- 控制消息会话 id = 1；`0x4E0E` 接受但从不 ACK；`0x4300` 会被停止 ACK；
- 触摸报告是 5 字节单指（Rust 侧按长度自适应，无需配置）；
- `0x1D01` 可收清单可能只有 5 项、不含 `0x5702`。

未知项：EA 会话是否需要、`/info` 是否只认 POST、AirPlay 端口、是否校验 deviceID、
是否广播 `_airplay._tcp`。首次上车按第 3 节逐项校准后把 `verified` 改 `true`。

**④ 把结论写回 `notes`**

`notes` 是给下一个开发者/用户看的，写清"为什么是这个值"，避免以后再踩一遍。

## 3. 症状 → 该改哪个字段

| 现象 | 先查 |
|---|---|
| 车机上**没有 CarPlay 图标** | 不是档案问题：先确认 AirPlay 失败后我们没拆 iAP2（看 `保留 iAP2 链路` 日志） |
| 能连上但**触摸点不准** | `touchDisplay`（接收端 `_info` 报 0×0 时必须给） |
| 连不上且日志提示 `403` | `validatesDeviceId` + 设置里手动填真实蓝牙 MAC |
| 车机从不发 `0x4301`、一直等端点 | `preferMdnsAirplay=true`（用 ctrl 邀请 / mDNS 找端点） |
| 车机不回 `0xEA03`、EA 会话无意义 | `openEaSessions=false` |
| 连接后车机反复问电源状态 | `replyPowerUpdates=true` |
| 端口 7000 回 `455` | `fallbackAirplayPorts` 加 `7001`/`7100`，并确认 mDNS 是否可用 |

## 4. 约定

- **不要**在 `Iap2SenderSession` / `HyperCarRepo` 里写 `if (brand == "xxx")`——差异属于档案。
- 真要引入**结构不同**的流程（例如某车机不走 AirPlay 而走 EA 隧道），
  再加一个 `HeadUnitFlow` 接口实现，并让档案引用它；不要推翻现有档案字段。
- 档案默认值 = 真 iPhone 行为。偏离默认值时，只写"确实不同"的那几项，其余留空。
- 新增档案不需要改 Rust 侧；除非差异涉及数据面（例如视频封装格式）。

