# HyperCar 架构说明

> CarDeck + 本机 CarLife **零转码**网关：砍掉耗电的 VirtualDisplay 与 EGL 双屏合成，
> 让本机的百度 CarLife / 荣耀车联 / 小米 CarWith（CarLife 兼容）连回 **127.0.0.1**，
> H.264 裸流经 Rust 核心层零转码直推 **宝马 X6（2023）ID8.5** 的 AirPlay 接收端。
>
> 硬件环境：手机 = Android 17 / HyperOS 4；车机 = BMW iD8.5（MGU-86）。

## 1. 为什么砍掉 VirtualDisplay / EGL

cardeck 的老链路：手机自绘车机桌面（4 块 Shizuku 虚拟屏）→ EGL 合成 → MediaCodec
编码 → ChaCha20 封帧推车机。这条链路的固有成本：

- EGL 合成循环 + 多虚拟屏**持续烧 GPU/CPU**（车机熄火后手机照样发热）；
- 虚拟屏/IME/方向覆盖需要一整套 Shizuku shell 维护（cardeck AGENTS 里一半的坑位
  都在这条链上）；
- 自绘桌面永远追不上 CarLife 生态里 App 的适配质量。

hypercar 的替代：**本机 CarLife 当内容源**。手机上的 CarLife/CarWith 本来就产出
H.264 Annex B 裸流 + PCM，与 AirPlay 推流所需的东西**只差一次封帧**——于是：

```
┌─────────────── 同一台手机 ───────────────┐
│ CarLife / CarWith（发送端，官方 app）      │
│      ↕ TCP 127.0.0.1（CarLife 回环）      │
│ hypercar_core (Rust)                      │
│   ├─ CarLife HU 服务端（7240/8240/9240…） │
│   ├─ H.264 NAL 拆分 → avcC → 128B 头封帧  │
│   ├─ PCM 重采样 48k → 闪避混音 → RTP      │
│   └─ AirPlay 协商 + 推流（ChaCha20-Poly） │
│      ↕ 车机热点 Wi-Fi（Shizuku 静默入网）  │
└──────────────────→ 宝马 iD8.5 屏 ─────────┘
```

零转码 = 无 MediaCodec、无 VirtualDisplay、无 EGL；CPU 只剩封帧与对称加密。

## 2. 分层

```
Kotlin（薄壳 + 系统集成）
├── session/HyperCarRepo        会话总管：秒连时序、状态快照（StateFlow→Compose）
├── iap2/                       蓝牙 iAP2 协商：RFCOMM → 识别/MFi/0x5703/0x4301
├── net/WifiJoiner              Shizuku 静默连车机热点 + bindProcessToNetwork
├── net/BluetoothMac            真实蓝牙 MAC（SETUP deviceID / 0x4300 用）
├── shizuku/ShizukuTools        shell 执行器（主线程硬守卫）
├── autowake/                   ACL 广播唤醒 + FGS(connectedDevice)——上车秒连第一环
└── ui/                         Compose 深空黑科技风 UI（状态监控 + 设置 + 诊断）

Rust（rust-core，全部协商 + 数据面）
├── lib.rs                      JNI 入口、运行时、引擎生命周期、回调（onState/onLog/onStats）
├── engine.rs                   编排：CarLife 事件分发 → 视频泵/音频泵 → 统计
├── carlife.rs                  CarLife HU 服务端（标准端口 + 7241~7244 回退）
├── airplay.rs                  AirPlay 会话：/info→pair-verify→SETUP→RECORD→心跳→HID
├── rtsp.rs                     RTSP 通道：1422 MTU 密文帧、CSeq 异步派发、入向 200
├── screen.rs                   屏幕流：VideoConfig/VideoFrame/keepAlive（128B 头）
├── audio_out.rs                RTP 音频：352 SPF、SSRC=0、AAD=4..12、大端 PCM
├── timing.rs                   NTP over RTCP 计时服务（pt 0xD2→0xD3）
├── crypto.rs / tlv8.rs / bplist.rs / srp6a.rs / pairing.rs
│                               HKDF/ChaCha/X25519/Ed25519、TLV8、bplist(根在字节8)、
│                               SRP-6a(3072)、pair-setup/pair-verify 客户端
└── iap2_nav.rs                 宝马 HUD 0x5200 导航报文构造器
```

## 3. 上车秒连时序

```
拉开车门 → 车机蓝牙 ACL 连上手机
  → CarBluetoothReceiver（清单静态，进程可被冷启动）
  → HyperCarService.startForeground(connectedDevice)（SYSTEM_ALERT_WINDOW 豁免）
  → AutoStart.handler → HyperCarRepo.connect
  → Iap2Coordinator：RFCOMM（车机回连为主）→ 链路协商 → 0x1D01 → MFi 阶梯
    → 0xAA05 后立即问候（BMW §9j）→ 0x5702 → 0x5703（热点凭据）→ 0x4301（AirPlay 端点）
  → WifiJoiner：cmd wifi connect-network（0 弹窗）→ 轮询 status → requestNetwork 绑定
  → Socket(车机IP:7000) connect → detachFd → RustCoreBridge.nativeStart
  → Rust：GET /info → pair-verify → modesChanged → SETUP(/setup) → 事件通道
    → 屏幕流 SETUP → 音频流 SETUP → RECORD → 三条心跳
  → onState("connected") → 广播唤醒本机 CarLife（StartWireless）
  → CarLife 连 127.0.0.1:7240… → 版本/鉴权握手 → 0x10007 下发 1920×720@60
  → H.264 裸流泵入 ScreenSender；PCM 泵入混音器；车机 HID 触摸回注 CarLife
下车：ACL 断开 → ACTION_STOP → nativeStop + iap2.stop + 网络释放
```

断开链路的两个硬约束（2026-10-23 线上刷屏故障的教训）：

- **ACL 断开 ≠ 一定是下车**。iAP2 的「只配对、不连接」会主动断车机 HFP/A2DP
  （个别 ROM 退到设备级断开），系统随即广播 `ACTION_ACL_DISCONNECTED`。`BtProfiles`
  在真断开前打「自己断开」标记（6s 宽限），`CarBluetoothReceiver` 见到标记就不按
  下车处理——否则每次建链都被自己的广播拆掉。
- **拆除必须幂等、且 ACTION_STOP 只回灌一次**。`HyperCarRepo.runDisconnect` 用
  `sessionLive` CAS 保证只真正拆一次；`HyperCarService` 只在 `isRunning` 由 true 变
  false 的那次转交 handler(false)。两条缺一条就会形成
  `handler(false) → startService(STOP) → onStartCommand → handler(false)` 自激回环：
  每秒数百次 nativeStop，日志只剩「车机蓝牙 断开 / 会话已断开」两行，且下车后仍在刷。
  日志层另有一道限流兜底（同一行 10s 内最多 4 次，其后折叠计数）。

## 4. 数据面细节

- **视频**：CarLife 帧（len/ts/id + AnnexB）→ NAL 切分 → SPS/PPS 变化发
  VideoConfig（avcC + Encrypted flag + w/h f32）→ VCL 打 4B BE 长度前缀序列 →
  ChaCha20-Poly1305（AAD=128B 头、nonce=帧计数）→ TCP；每 400ms 节流补 keepAlive 帧。
- **音频**：媒体（44.1k/48k 立体声）与 TTS（16k 单声道）各自线性重采样到 48k 立体声，
  按 352 帧出包；TTS 活跃时媒体增益 0.25（400ms 尾音保持）；RTP 封包（PT=100、
  SSRC=0、AAD=头 4..12、独立 nonce 计数、尾部 8B nonce、PCM 大端）；每秒同步包。
- **触摸**：车机 hidSendReport（事件通道）→ 显示空间→CarLife 编码空间缩放 →
  0x68001；iDrive 旋钮/媒体键 → 0x68008 硬按键。

## 5. 状态机（UI 视角）

`Idle → Negotiating(步骤) → Connected(车名+时长) →（车机断开/热点消失）→ Idle`
失败态 `Failed(原因)` 带自动重试语义；`CarLifeState: Idle → Waiting → Active`。
所有状态由 Rust 回调 + iAP2/WiFi 层事件汇入 `HyperCarRepo.snapshots`。

## 6. 与 cardeck 的关系

- 继承：iAP2 协议栈与全部真车实测坑位（docs/PROTOCOL-NOTES §3/§4/§5/§9h/§9j）、
  Shizuku 静默入网、ACL 秒连链路、AirPlay 线格式。
- 删除：VirtualDisplay/虚拟屏、EGL 合成器、MediaCodec 编码、触摸三坐标路由、
  隐形启动器、暗幕/真息屏（无屏可暗）、iAP2 隧道（MIB3 专属，BMW 不需要）。
- 新增：CarLife HU 回环服务端、Rust 协商层、TTS 闪避混音、HUD 导航报文。

## 7. 崩溃取证（为什么日志必须落盘）

内存环形缓冲的日志在进程一死就清零——2026-10-23 20:30 连上 AirPlay 端口后 App 秒崩，
用户「崩溃前快速点了导出」导出的也只是崩溃前的旧内容，现场全丢。现在的取证链：

- `HyperCarRepo.log()` 每行同时追加到 `filesDir/hypercar-session.log` 并 flush（>1.5MB 转存 `.1`）；
  重启时把上一轮最后 120 行带回环形缓冲，标一条「上次运行（进程重启/崩溃前）」。
- Java 未捕获异常走 `installCrashHandler()` 写盘完整栈。
- Rust 侧 `[profile.release]` **不用 `panic = "abort"`**：否则任何一个 `unwrap` 踩空都会让
  进程直接消失且无痕。现在 panic 由 tokio 任务边界捕获，`lib.rs` 的看门狗把它报成
  「引擎任务异常退出」；`nativeInitLogger` 装的 panic hook 再把 `PANIC:` 行打到 logcat。
- 启动时（有 Shizuku 即可）`captureCrashArtifacts()` 用 shell 读 `logcat -s HyperCar-Core`
  与 crash 缓冲区，把 `PANIC`/`Fatal signal` 行抓进日志，免 adb。

