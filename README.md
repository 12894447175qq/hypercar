# HyperCar

**CarLife → AirPlay 零转码网关**：把手机上 CarLife 系应用（百度 CarLife、小米 CarWith、
荣耀车联等）产出的 H.264 与 PCM，经 **Rust 核心层**零转码直推任意 AirPlay 接收端
（宝马车机、Audi MIB3、WinPlay、其它 AirPlay 1/2 接收端）。


- **手机侧**只要求它跑的是 CarLife 协议栈（iAP2 + 那 6 个标准端口），官方 CarLife、
  小米 CarWith 的 CarLife 通道、荣耀车联都属于这一类；CarWith 只是其中一个「入口」。
- **车机侧**只要求它是 AirPlay 接收端；不同车机的行为差异（是否校验 deviceID、
  发不发 `0x4301`、端口怎么找、触摸报告几字节/指……）全部收敛在
  [车机档案](#车机档案按型号适配)里，加型号不改主流程。

## 两种部署形态（协议逻辑同一份）

```
① 本机 HU（真车 / 手机自己当车机）
   CarLife(CarWith) ──127.0.0.1──▶ hypercar_core(Rust, 在手机里) ──车机热点──▶ 车机 AirPlay
                                        同一套协议栈                      （BMW / Audi / …）

② 盒子角色（PC 联调 / 没有真车时）
   CarLife(CarWith) ◀──Wi-Fi── hypercar-hub(PC，当车机) ──127.0.0.1──▶ WinPlay
                                        同一套协议栈              （任意 AirPlay 接收端）
```

> 连接方向不是写死的：**同机**时 HU 监听 `127.0.0.1:7240…`、MD 连入；
> **盒子角色**（小米 CarWith「车辆热点 → 开始连接」实测）反过来是 **MD 监听**、
> HU 逐个端口拨过去。两条路径分别对应 `CarLifeHub::start` / `CarLifeHub::connect`，
> 见 [docs/CARLIFE-PROTOCOL.md](docs/CARLIFE-PROTOCOL.md)。

## 车机档案（按型号适配）

车机侧的差异用「档案」表达，设置页可切换，内置四种：

| 档案 | 状态 | 关键差异 |
|---|---|---|
| **BMW iD8.5**（MGU-86 / MGU-55） | 真车实测 | 车机从不发 `0x4301`、从不回 `0xEA03`；端点靠 ctrl 邀请 + mDNS；失败要保活 iAP2 |
| **通用（0.27）**（任意车机 / 未知车型） | 0.27 宝马实测 + cardeck 通用序列 | `0x4300`/`0xEA00`/`0x4E0E` 全发、出站前断 HFP/A2DP、不主动拆链；端点 mDNS 优先、拿不到退 7000；`0xAE00` 回 3 条 `0xAE01`。不知道选哪个就先选它 |
| **WinPlay**（PC AirPlay 接收端） | 实测 | 校验 `deviceID`（SETUP 必须带真实蓝牙 MAC）；控制面固定 7000；同时进入「只保 iAP2」模式 |
| **Audi MIB3**（3315） | **未实测** | 会发 `0x4301`（可能晚到 2 分钟以上）；控制会话 id=1；触摸为 5 字节单指 |

加新品牌有两条路：

1. **改配置（推荐，不用发版）**：仓库 [`profiles/headunits.json`](profiles/headunits.json) 是车型下发文件，
   补一条 JSON + push，用户在手机上点「车机型号 → 从 GitHub 更新车型列表」就能拿到新车型。
   远程同 id 会覆盖内置档案，所以内置档案配错了也能这样修。
2. **改代码**：加一个 `HeadUnitProfile` 常量 + 注册表一行（改协议行为时才需要）。

字段含义、实测校准步骤、症状→字段对照、远程下发格式见
[docs/HEADUNIT-PROFILES.md](docs/HEADUNIT-PROFILES.md)（§2 加档案 · §5 远程下发）。

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
  旋钮/方向盘键 → 硬按键（0x68008）、宝马 HUD 导航报文（iAP2 0x5200）。
  触摸报告两种布局（每指 6 字节 / 奥迪 5 字节）按长度自适应。
- **语音助手麦克风**：CarLife 的语音识别要求「车机」供麦，本机 HU 用手机麦克风
  采集经 VR 通道（`VR_MIC_DATA 0x58001`）推给 MD；也可在车机端设置里切到
  「手机麦克风录音」由 CarLife 自己录。
- **可调编码分辨率**：设置页直接填宽/高（默认 2560×744），手机回报实际尺寸后自动跟随。
- **按车机型号配置**：设置页切换档案，切换时自动把「目标车机」重置为自动发现，
  避免带着上一台车机的名字连不上。
- **协议对齐真机**：用真 iPhone 的 HCI 抓包逐条对照（iAP2 报文清单、`/info` 方法、
  链路存活），差异都在文档里可追溯。
- **现代 UI**：Compose 深空黑 + 电光青 + 玻璃拟态，状态大卡 + 2×2 状态芯片 +
  实时步骤 + 免 adb 诊断日志，内置「首次连接」引导。

## 目录

```
app/           Android 应用（Kotlin + Compose）：薄壳、iAP2 协商、WiFi/秒连、UI
rust-core/     Rust 核心层（cdylib）：AirPlay 协商 + CarLife 网关 + 零转码推流
rust-core/hub/ PC 侧可执行 HU（独立 crate，复用同一套协议源码，不含 JNI）
               双击即开原生 Win32 参数面板（GUI）；带参数启动仍是命令行
tools/         本地构建脚本 + 预编译的联调工具（hypercar-hub.exe）
profiles/      车型下发配置（headunits.json）：新增车机改这里，不用发版
docs/          架构、CarLife 协议、协商对比、编译指南、车机档案、**iPhone 抓包手册**
```

## 文档

| 文档 | 讲什么 |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 整体架构、数据面/控制面分工 |
| [docs/CARLIFE-PROTOCOL.md](docs/CARLIFE-PROTOCOL.md) | CarLife 六个端口、握手阶段、消息 ID |
| [docs/NEGOTIATION-GAPS.md](docs/NEGOTIATION-GAPS.md) | AirPlay 协商差异、真车实测、真 iPhone 对照 |
| [docs/CARDECK-ALIGNMENT.md](docs/CARDECK-ALIGNMENT.md) | 与 cardeck 参考实现的差异对照（身份模型/配对/蓝牙/iAP2/AirPlay） |
| [docs/HEADUNIT-PROFILES.md](docs/HEADUNIT-PROFILES.md) | 车机档案字段、**新增品牌四步走**、**远程下发（改 JSON 不发版）** |
| [docs/IPHONE-HCI-CAPTURE.md](docs/IPHONE-HCI-CAPTURE.md) | **iPhone HCI 抓包手册**（描述文件 → sysdiagnose → .pklg） |
| [docs/BUILD.md](docs/BUILD.md) | 编译环境、本地出包、CI 标记 |

## 快速开始

见 [docs/BUILD.md](docs/BUILD.md)。本机已装好工具链时：

```powershell
# 一次出两样：手机 APK + PC 侧 hypercar-hub.exe（自动覆盖 tools\）
pwsh -File tools\build-local.ps1 -Release -BuildNumber <N>
# 只改 Kotlin（跳过所有 Rust 构建）
pwsh -File tools\build-local.ps1 -Release -SkipRust
```

PC 侧联调工具：

```
双击 tools\hypercar-hub.exe
  → 原生窗口：自动经 adb 填 device-id / 手机 IP，可改参数、看实时日志
  → 点「启动」跑 headless 子进程（无控制台窗口），日志流回窗口
```

也可以纯命令行（参数全部可省略，会自动检测/用上次缓存）：

```powershell
# WinPlay 需先点「开始接收」，它才监听 17000
.\tools\hypercar-hub.exe --airplay 127.0.0.1:17000 --touch-display 2732x794 --voice 0
```

> - `--device-id` 省略时按 adb → 缓存取值；**必须等于接收端「目标蓝牙设备」的 MAC**
>   （WinPlay 逐字符比对，不一致直接 403）。
> - `--touch-display` 是接收端的 HID 像素空间（它 `/info` 报 `0x0` 时用）。
> - `--voice 0` = HU 不提供麦克风（PC 上没有麦克风来源）。
> - 配置/身份缓存在 `%LOCALAPPDATA%\hypercar-hub\`，**不会写进仓库**。

## 已知限制

- **首次连接必须手动点一次**（CarWith 入口）：CarWith → CarLife 连接 → 无线连接 →
  车辆热点 → 选车机蓝牙 → 下一步 → 开始连接。之后同一台车会自动回连（App 内有引导卡）。
- **部分 ROM 会把后台录音静音**：小米 HyperOS 把 CarWith 的 `RECORD_AUDIO` 锁在
  `foreground` 模式，语音助手在后台录音时拿到全静音数据（不报错但不响应）。
  真车请用「车机麦克风」让本 App 供麦（它跑前台服务）。
- **接收端与 HU 连接强绑定**（WinPlay 这类 PC 接收端）：控制连接一断它就结束接收，
  所以顺序是「先开始接收 → 再起 hub」，hub 重启需要重新点一次接收。
- **Audi MIB3 档案未实车验证**：只按历史记录配了已知差异，首次上车需按
  [docs/HEADUNIT-PROFILES.md](docs/HEADUNIT-PROFILES.md) 的症状表逐项校准。

## 合规

- 基于 [shaonialife/cardeck](https://github.com/shaonialife/cardeck) 的协议研究成果重构
  （其协议笔记与坑位记录来自真车实测）。
- 「CarLife」为百度商标，本项目仅个人研究用途；CarLife 接收端协议参考了
  apollo-DuerOS（Apache 2.0）与 CarLifeWirelessBox（MIT）的公开资料。




