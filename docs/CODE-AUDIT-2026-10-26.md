# 第三方分析核对（2026-10-26，`E:\修复建议.txt`）

> 逐条对照当前代码（HEAD `54d36b5`）验证。结论分「真 / 合理推测 / 无据」，
> 属实的已修，未改的写明原因。编译：`:app:compileDebugKotlin` + `cargo check --target aarch64-linux-android`。

## 结论总表

| # | 建议内容 | 判定 | 处理 |
|---|---|---|---|
| 1 | `Socket().connect()` 预探测会「毒死」CarLife 的单连接 accept，导致 8240 无帧 | **真**（代码里确实有两处 connect 探测，8240 那处最致命） | 改为 **bind 冲突零接触探测**（`isLocalPortListening`），7240/8240 都不再产生 TCP 握手 |
| 2 | 缺 `VIDEO_ENCODER_START` 补发：前台/亮屏后要重发，首帧未到要周期性催 | **真**（原代码 tick 5s 只打日志、`SCREEN_ON/FOREGROUND` 只打日志） | tick 改 1.5s，首帧前最多补发 12 次；收到 `SCREEN_ON/FOREGROUND` 立刻补发一次 |
| 3 | 2560×744 里 744 非 16 倍数 → 手机 MediaCodec 静默失败 | **部分成立**（744/16=46.5 属实；"宽度上限 1920" 已被现场否定——CarWith 自己的分辨率设置最大支持 **2560×2560**） | 只做 **16 对齐**、保留原生宽度（`2560×744 → 2560×752`）；宽高上限提到 2560 仅作异常值防御 |
| 4 | `HARD_KEY` 应是 protobuf `CarlifeCarHardKeyCode{keycode=1}`，且旧键值错位 | **合理推测**（触摸同源已证 protobuf；键值表未验证） | 改 protobuf；键值按资料重排（BACK 0x02→0x11、旋钮 0x1C/1D/1E→0x1B/1C/1D），文档标注待真车校准 |
| 5 | 断开要清理本机 CarLife（半死空壳占着 7240，下次不回 Phase 1） | **合理**（现场确有"空壳"日志） | 断链时发 `StopWireless/Disconnect` 广播；有 Shizuku 再 `am force-stop` 引擎包（**不杀 `com.miui.carlink`**） |
| 6 | 重写 HID 旋钮/媒体键解析（位图 + 步进 + 边沿过滤） | **无据**（报告布局是猜的） | 未改：先把 `EVENT: HID 报告 …` 原始字节抓出来再定 |
| 7 | 特性配置加 `FOCUS_UI` / `instrument` | **无据**（未知 key 可能让 MD 的特性配置校验失败） | 未改 |
| 8 | 打通 CarLife 导航 → `0x5201` → AirPlay `iAPSendMessage` HUD 链路 | **未证实**（消息 ID/字段、iAP2 订阅方向都需真车验证） | 未改；`iap2_nav` 目前仍无调用方 |

## 1. 零接触端口探测（真，已修）

原代码两处 `Socket().connect()`：

- `probeLocalCarLifeMd()`：每 200ms 连一次 7240；
- `runConnect` 里 `mediaOk` 检查又连了一次 **8240**。

CarLife 的 `ServerSocket.accept()` 是一次性的：连上即 close 会被当作「车机连上又断开」，
CMD/VIDEO 线程各自回到初始态；于是 Rust 真正拨进 8240 的那条连接拿不到编码器投递队列，
表现正是「握手全过、音频正常、视频 0 字节」。

改为 bind 冲突探测（**没有任何 TCP 握手**）：

```kotlin
java.net.ServerSocket().use { ss ->
    ss.reuseAddress = false
    ss.bind(java.net.InetSocketAddress("127.0.0.1", port), 1)
}
// 抛 BindException ⇒ 有进程在 LISTEN
```

`probeCarLifeOnce()`、`probeLocalCarLifeMd()`、8240 校验三处都已换成它。

## 2. 编码器 START 补发（真，已修）

- 收到 `CMD_SCREEN_ON (0x10018)` / `CMD_FOREGROUND (0x1001B)` 且已发过 START →
  **立刻补发** `CMD_VIDEO_ENCODER_START (0x18009)`（CarLife 的 Activity 被 `am start`
  推回前台、Surface 才真正就绪，后台时收到的 START 会被吞掉）。
- tick 从 5s 降到 **1.5s**：START 已发但还没收到首帧时，每拍补发一次，最多 12 次
  （≈18s），首帧到达立即停止（`VIDEO_FIRST_FRAME_RECEIVED` 原子标志，在 8240 的
  `VIDEO_DATA` 分支置位，每次会话开始时清零）。

## 3. 编码分辨率规整（已实现）

```rust
const CARLIFE_ENCODER_MAX_WIDTH: u32 = 2560;    // 仅防御异常值
const CARLIFE_ENCODER_MAX_HEIGHT: u32 = 2560;
// 2560×744 → 2560×752（只做 16 对齐，保留 CarWith 的原生宽度）
```

- `2560×744` 的 744 不是 16 的倍数（46.5 个宏块）属实；部分机型确实会因此 configure 失败。
- **「宽必须 ≤1920」不成立**：CarWith 自己的分辨率设置里最大支持 **2560×2560**（2026-10-26
  现场确认），说明手机侧能直接出 2560 宽的流；压到 1920 只是白丢清晰度。所以现在只做
  16 对齐，宽高上限 2560 纯属异常值防御。
- 规整后同步 `encoder_size` 并 `EncoderResolution` 事件，保证 AirPlay 画布与触摸坐标
  跟真实码流一致（否则车机会缩放、触摸会整体偏移）。
- 如果 CarWith 界面里用户自选了别的分辨率，以 `VIDEO_ENCODER_INIT_DONE` 回报的**实际值**为准
  （代码已经以它覆盖画布）。

## 4. 硬按键改 protobuf（已实现，键值待校准）

`CTRL_HARD_KEY 0x68008` 载荷改为 `proto::varint(1, keycode)`，与触摸
（`CarlifeTouchAction`，已验证）保持一致。键值表见 `docs/CARLIFE-PROTOCOL.md` §6，
旧值里 `BACK=0x02`（其实是电话键）等错误一并纠正，`RustCoreBridge.CL_KEY_*` 同步更新。

## 5. 断链清理 CarLife（已实现）

`releaseSessionResources()` 调 `stopLocalCarLife(wasDialMode)`：

1. 发 `com.baidu.carlife.Action.StopWireless` / `Disconnect` 广播（无 Shizuku 也能收尾）；
2. 有 Shizuku 时 `am force-stop com.baidu.carlife.xiaomi` / `com.baidu.carlife`
   （拨号模式先等 250ms，让 Rust 的 `VIDEO_ENCODER_PAUSE + FIN` 先送到）；
3. **不杀 `com.miui.carlink`**——CarWith 外壳管着系统级连接状态。

Rust 侧 `handle_cmd_client` 在 stop 时先发 `CMD_VIDEO_ENCODER_PAUSE (0x1800A)` 再
`shutdown()` 写半部（标准 FIN），避免留下半开连接。

## 未采纳的三项及原因

- **HID 旋钮解析重写**：报告布局（`[buttons, step_i8]`、bit0/1/2 语义）完全是推测，
  按猜的映射会把普通按键误判成旋钮步进（一次误转会连发 5 个方向键）。等真车日志里
  `EVENT: HID 报告 #n xB …` 抓到样本再改。
- **特性配置加 `FOCUS_UI` / `instrument`**：CarLife 的特性配置是"声明即生效"的白名单，
  塞未知 key 有让 MD 校验失败、握手直接卡住的风险；没有抓包证据不做。
- **HUD 导航链路**：`0x5201`/`iAPSendMessage` 的消息 ID 与字段都需要真车验证，
  且要先确认车机 0x5200 订阅的真实语义；`iap2_nav` 目前没有调用方，改了也无法验证。
