# HyperCar

**CarPlay + CarLife 零转码网关** —— 让 HyperOS 手机（Android 17）上的 CarLife / 小米
CarWith 产出的 H.264 与 PCM，经 **Rust 核心层**零转码直推 AirPlay 接收端
（宝马 iD8.5 / WinPlay 等）。

支持两种部署形态，协议逻辑同一份：

```
① 本机 HU（真车）
   CarLife/CarWith(手机) ──127.0.0.1──▶ hypercar_core(Rust, 在手机里) ──车机热点──▶ 车机 AirPlay

② 盒子角色（PC 联调 / 车机端）
   CarLife/CarWith(手机) ◀──Wi-Fi── hypercar-hub(PC，当车机) ──127.0.0.1──▶ WinPlay
                                   同上协议栈              AirPlay 会话
```

> 方向不是写死的：**Android 同机**时 HU 监听 `127.0.0.1:7240…`、MD 连入；
> **盒子角色**（小米 CarWith「车辆热点 → 开始连接」实测）则是 **MD 在监听**、
> HU 逐个端口拨过去。两条路径分别对应 `CarLifeHub::start` / `CarLifeHub::connect`，
> 见 [docs/CARLIFE-PROTOCOL.md](docs/CARLIFE-PROTOCOL.md)。

## 特性

- **砍掉 VirtualDisplay / EGL 双屏合成**：不自绘车机桌面、无 MediaCodec、无虚拟屏，
  手机不发热；CarLife 就是内容源，AirPlay 只差一次封帧。
- **协商整体下沉 Rust**（/info → pair-verify → RTSP SETUP → RECORD → 三条心跳），
  Kotlin 只交「绑定车机网络的 socket FD」——协商状态机一份实现，见
  [docs/NEGOTIATION-GAPS.md](docs/NEGOTIATION-GAPS.md)。
- **上车秒连**：车机蓝牙 ACL 连上 → 清单静态接收器冷启动进程 → FGS(connectedDevice)
  → iAP2 协商 → 静默加入车机热点（Shizuku 可用时零弹窗）→ Rust 自动协商。
- **零转码数据面**：H.264 Annex B 拆 NAL → avcC/VideoConfig → ChaCha20-Poly1305 封帧；
  PCM 重采样 48k + TTS 闪避混音 → RTP（352 SPF / SSRC=0 / 大端 PCM）。
- **双向控制**：车机触摸 → `CarlifeTouchAction` **protobuf**（0x68001）、
  iDrive 旋钮/方向盘键 → 硬按键（0x68008）、宝马 HUD 导航报文（iAP2 0x5200）。
- **语音助手麦克风**：CarLife 的语音识别要求「车机」供麦，本机 HU 用手机麦克风
  采集经 VR 通道（`VR_MIC_DATA 0x58001`）推给 MD；也可在车机端设置里切到
  「手机麦克风录音」由 CarLife 自己录。
- **可调编码分辨率**：设置页直接填宽/高（默认 2560×744），手机回报实际尺寸后自动跟随。
- **按车机型号配置**：品牌差异收敛在「车机档案」里（内置 BMW iD8.5 / WinPlay），
  设置页可切换；新增品牌见 [docs/HEADUNIT-PROFILES.md](docs/HEADUNIT-PROFILES.md)。
- **现代 UI**：Compose 深空黑 + 电光青 + 玻璃拟态，状态大卡 + 实时统计 +
  免 adb 诊断日志，内置「首次连接」引导。

## 目录

```
app/          Android 应用（Kotlin + Compose）：薄壳、iAP2 协商、WiFi/秒连、UI
rust-core/    Rust 核心层（cdylib）：AirPlay 协商 + CarLife 网关 + 零转码推流
rust-core/hub/ PC 侧可执行 HU（独立 crate，复用同一套协议源码，不含 JNI）
tools/        本地构建脚本（build-local.ps1 / .cmd）
docs/         架构、CarLife 协议、协商对比、编译指南、**车机档案（新增品牌）**
```

## 快速开始

见 [docs/BUILD.md](docs/BUILD.md)。本机已装好工具链时：

```powershell
# 手机 APK（Release，自动跑 cargo ndk + Gradle）
pwsh -File tools\build-local.ps1 -Release -BuildNumber <N>
# 只改 Kotlin 时
pwsh -File tools\build-local.ps1 -Release -SkipRust
```

PC 侧 HU（联调/盒子角色）：

```powershell
cd rust-core\hub
cargo build --release
# 需要 WinPlay「开始接收」后它才连得上 17000
.\target\release\hypercar-hub.exe --device-id <手机蓝牙MAC> `
    --airplay 127.0.0.1:17000 --md-host <手机IP> `
    --touch-display 2732x794 --voice 0
```

> `--device-id` 必须等于接收端「目标蓝牙设备」的 MAC（WinPlay 会逐字符比对，
> 不一致直接 403）；`--touch-display` 是接收端的 HID 像素空间（它 `/info` 报 0×0
> 时用）；`--voice 0` 表示 HU 不提供麦克风（PC 没麦克风来源）。

## 已知限制

- **首次连接必须手动点一次**：CarWith → CarLife 连接 → 无线连接 → 车辆热点 →
  选车机蓝牙 → 下一步 → 开始连接。之后同一台车会自动回连（App 内有引导卡）。
- **MIUI 会把后台录音静音**：CarWith 的 `RECORD_AUDIO` 被锁在 `foreground` 模式，
  语音助手在后台录音时拿到全静音数据（不报错但不响应）。真车请用「车机麦克风」
  让本 App 供麦（它有前台服务）。
- **接收端与 HU 连接强绑定**：WinPlay 在控制连接断开时会结束整个接收会话，
  所以顺序是「先开始接收 → 再起 hub」，hub 重启需要重新开始接收。

## 合规

- 基于 [shaonialife/cardeck](https://github.com/shaonialife/cardeck) 的协议研究成果重构
  （其协议笔记与坑位记录来自真车实测）。
- 「CarLife」为百度商标，本项目仅个人研究用途；CarLife 接收端协议参考了
  apollo-DuerOS（Apache 2.0）与 CarLifeWirelessBox（MIT）的公开资料。

