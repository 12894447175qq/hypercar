# HyperCar

**CarDeck + 本机 CarLife 零转码网关** —— 让 HyperOS 4 手机（Android 17）上的百度
CarLife / 小米 CarWith 连回本机 `127.0.0.1`，H.264 裸流经 **Rust 核心层**
零转码直推 **宝马（iD8.5 / MGU-86）** 的 AirPlay 接收端。

```
CarLife(手机) ──127.0.0.1──▶ hypercar_core(Rust) ──车机热点 Wi-Fi──▶ 宝马 iD8.5
        H.264/PCM 进           封帧/加密/协商出              AirPlay 会话
```

## 特性

- **砍掉 VirtualDisplay / EGL 双屏合成**：不再自绘车机桌面，无 MediaCodec、无虚拟屏，
  手机不发热；本机 CarLife 就是内容源，AirPlay 只差一次封帧。
- **协商整体下沉 Rust**（/info → pair-verify → RTSP SETUP → RECORD → 三条心跳），
  Kotlin 只交「绑定车机网络的 socket FD」——协商状态机一份实现，见
  [docs/NEGOTIATION-GAPS.md](docs/NEGOTIATION-GAPS.md)。
- **上车秒连**：车机蓝牙 ACL 连上 → 清单静态接收器冷启动进程 → FGS(connectedDevice)
  → iAP2 协商（含 BMW §9j 全部修复）→ Shizuku 静默连车机热点 → Rust 自动协商 →
  广播唤醒本机 CarLife。全程手机在口袋里。
- **零转码数据面**：H.264 Annex B 拆 NAL → avcC/VideoConfig → ChaCha20-Poly1305 封帧；
  PCM 重采样 48k + TTS 闪避混音 → RTP（352 SPF / SSRC=0 / 大端 PCM）。
- **双向控制**：车机触摸回注 CarLife（0x68001）、iDrive 旋钮/方向盘键 → 硬按键
  （0x68008）、宝马 HUD 导航报文（iAP2 0x5200）。
- **现代 UI**：Jetpack Compose 深空黑 + 电光青 + 玻璃拟态，状态大卡 + 实时统计 +
  免 adb 诊断日志。

## 目录

```
app/          Android 应用（Kotlin + Compose）：薄壳、iAP2 协商、WiFi/秒连、UI
rust-core/    Rust 核心层（cdylib）：AirPlay 协商 + CarLife 回环 + 零转码推流
docs/         架构、协商对比分析、编译指南
```

## 快速开始

见 [docs/BUILD.md](docs/BUILD.md)。TL;DR：

```bash
cd rust-core && cargo ndk -t arm64-v8a -o ../app/src/main/jniLibs build --release
cd .. && ./gradlew :app:assembleDebug
```

## 合规

- 基于 [shaonialife/cardeck](https://github.com/shaonialife/cardeck) 的协议研究成果重构
  （其协议笔记与坑位记录来自真车实测）。
- 「CarLife」为百度商标，本项目仅个人研究用途；CarLife 接收端协议参考了
  apollo-DuerOS（Apache 2.0）与 CarLifeWirelessBox（MIT）的公开资料。
