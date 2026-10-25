# 第三方分析核对（2026-10-25，`E:\问题汇总.txt`）

> 逐条对照代码验证，结论分「真 / 部分真 / 不成立」。改动已落工作区，编译通过
> （`:app:compileDebugKotlin` + `cargo check --target aarch64-linux-android`）。

## 结论总表

| # | 分析结论 | 判定 | 处理 |
|---|---|---|---|
| 1 | `lib.rs` `emit_stats` 的 JNI 签名多了一个 `I` | **真**（关键） | 已改为 `(IJJZZ)V` + `with_env` 清 Pending Exception |
| 2 | `airplay.rs` `block_in_place` 会死锁 | **不成立**（有风险但不构成本项目的死锁） | 未改；注释说明理由 |
| 3 | `Iap2Bluetooth` 没把当前车机档案传给 `Iap2SenderSession` | **真** | 已补 `profile = HeadUnitProfiles.current()` |
| 4 | 单机 CarLife 的「谁监听 7240」悖论 | **真（已完整实现）** | 唤醒在前 + 探测优先：探测命中 → `md_host=127.0.0.1` 主动拨号；未命中 → HU 监听，等 CarLife 连入 |
| 5 | Android 14+ 后台录音缺 FGS `microphone` 类型 | **真** | 已补权限 + `foregroundServiceType="connectedDevice\|microphone"` + startForeground 组合类型（带降级） |
| 6 | 进程被绑到无外网的车机热点导致「更新车型/检查更新」必失败 | **真** | 已新增 `net/InternetRoute`，`RemoteProfiles` 与 `UpdateChecker` 都改走它 |
| 7 | `iap2_nav.rs` 帧头/字节序与 `Iap2Csm` 不一致 | **真**（但该函数目前无调用方，属潜在 bug） | 已按 CSM 规范重写：`0x4040` 头 + 大端 TLV + NUL 结尾字符串 |

## 1. `onStats` JNI 签名（真，已修）

- Kotlin：`onStats(fps: Int, frames: Long, kbps: Long, audioPlaying: Boolean, ducking: Boolean)` → `(IJJZZ)V`（5 个参数）
- Rust 旧值：`"(IJJIZZ)V"`（6 个类型）→ `GetMethodID` 必失败，`onStats` 一次都调用不到，
  首页帧率/帧数/码率/音频芯片全空；且失败会在该 tokio 线程上留下 **Pending Java Exception**，
  后续同一线程的 `emit_log` / `openTcpFd` 等 JNI 调用都会被污染。
- 修正：签名改 `(IJJZZ)V`；`with_env` 统一在调用后 `exception_check → exception_describe → exception_clear`。

## 2. `block_in_place`（不成立，未改）

- 代码确实用 `tokio::task::block_in_place(|| Handle::current().block_on(...))` 桥接同步的
  `PairSetupClient/PairVerifyClient`。这是 **tokio 官方支持的桥接方式**：`block_in_place`
  会把当前 worker 让出去执行其它任务，不会自锁。
- 前提是运行在多线程 runtime（本项目 `TOKIO_RT` 是 4 worker 的多线程 runtime）；若改到
  current-thread runtime 或 `spawn_blocking` 里才会 panic。当前调用链不满足该前提。
- 重构收益低、面大（要改 `TlvTransport` + `PairSetupClient` + `PairVerifyClient` + 调用点），
  故保持现状并在代码注释里写明约束。

## 3. 车机档案没有传进 iAP2 会话（真，已修）

`Iap2Bluetooth.runSession` 构造 `Iap2SenderSession` 时漏了 `profile`，构造函数默认值永远是
`HeadUnitProfiles.BMW_ID85`。后果：设置页切成「通用(0.27) / WinPlay / Audi MIB3」或远程下发
新档案后，`0x4300` / `0xEA00` / `0x4E0E` / 档案 A 退场等差异**全部不生效**（表面看是"切了没反应"）。
已补显式传参。

## 4. 单机 CarLife 的端口归属（部分真，已做兜底）

- 代码事实：`CarLifeHub::start()` 用 `bind_tcp(7240..9340)` 监听（Android 默认绑 `127.0.0.1`），
  `CarLifeHub::connect(md_host)` 才走主动拨号；`engineConfigJson` 原来**没有** `md_host` 字段。
- 因此若本机 CarLife（小米 CarLife / CarWith 的「车辆热点」模式）要自己 bind 7240，就会撞端口。
- **已完整实现「唤醒在前 + 探测优先」**（2026-10-25）：
  1. `runConnect` 在连 AirPlay 控制端口**之前**就 `wakeLocalCarLife()`（CarLife 启动要 1~3s，
     正好和连 AirPlay 端口并行）；
  2. 拿到控制 FD 后、启引擎前 `probeLocalCarLifeMd()`：每 200ms 试连 `127.0.0.1:7240`，
     最多 2.5s。命中 → 引擎配置带 `md_host:"127.0.0.1"`，Rust 走 `CarLifeHub::connect`
     **主动拨号**（我们当车机、CarLife 当内容源）；未命中 → 不带 `md_host`，仍是 HU 监听；
  3. 主动拨号模式下，`onState("connected")` **不再**补发唤醒广播（会打断已连上的 CarLife 会话）；
     HU 监听模式保留原来的「连上后唤醒」，兼容客户端型 CarLife；
  4. Rust 侧 `CarLifeHub::start()` 的 `Address already in use` 兜底仍在（防「探测漏判/竞态」）。
  5. **UDP 7999 发现广播也补了**（`carlife.rs::spawn_discovery_beacon`）：HU 监听模式下，
     每 1s（前 12 次）→ 每 5s 往 `127.0.0.1:7999` 与 `255.255.255.255:7999` 发一条可读文本
     `127.0.0.1:7240\0`；同时尽力 bind 7999 收包，收到就把前 64B 打成 hex 记日志。
     需要说明的是：**没有公开资料证明 CarLife 用 7999 做发现**（Limo/xcertplay 的实测结论是
     「无线 CarLife 没有发现协议，只是把六条 TCP 端口打开等车机连」，见 `SubnetScanner.kt`），
     所以这条广播是"尽力而为"：对不认识它的版本无害，等真抓到 MD 的广播包再按真实格式改。

## 5. Android 14+ 后台供麦（真，已修）

- 事实：`HyperCarService` 原来只有 `foregroundServiceType="connectedDevice"`；而连接成功后
  `startVoiceMic()` 会一直开 `AudioRecord`（语音助手供麦）。Android 14 起，仅 connectedDevice
  类型的 FGS **不允许后台录音**（静默 0 数据或 SecurityException）。
- 修正：manifest 补 `FOREGROUND_SERVICE_MICROPHONE` 权限与 `connectedDevice|microphone` 类型；
  `startForegroundSafe()` 在 `RECORD_AUDIO` 已授权时用组合类型，失败自动退回 connectedDevice。
- **未做**「按需开麦」（收到 CarLife VR 开始/结束指令才录音）：需要先定谳 CarLife 的 VR 触发
  报文，否则可能把"语音助手没麦"改成"偶发没麦"。当前仍为会话期间常开（顶部会有麦克风指示点）。

## 6. 双网并发时的联网（真，已修）

`RemoteProfiles.httpGet` 与 `UpdateChecker.fetchLatest` 原来直接用 `URL.openConnection()`——
会话期间进程被 `bindProcessToNetwork(车机热点)`，热点无外网（Direct AP 连网关都是 0.0.0.0），
「从 GitHub 更新车型列表 / 检查更新」必然失败。新增 `net/InternetRoute`：优先挑
`NET_CAPABILITY_VALIDATED` 的网络，其次蜂窝，用 `Network.openConnection(URL)` 建连接
（不受进程级绑定影响），找不到才回退默认。`openControlFd` 的「全败恢复默认绑定」逻辑本来就对，
未改。

## 7. `iap2_nav.rs` 的帧格式（真，已修）

旧实现：4 字节头 `[u16 BE 总长][u16 BE msgId]` + **Little-Endian** TLV、字符串不带 NUL。
与 `Iap2Csm` 的 `0x4040 | u16 BE 总长 | u16 BE msgId` + 大端 TLV + NUL 字符串不一致。
已重写。注意 `nativeBuildIap2NavPacket` 目前在 Kotlin 侧**没有调用方**（HUD 导航尚未接入），
所以它是潜在 bug 而非现网故障；接 HUD 前务必用真车校验参数 ID 语义。
