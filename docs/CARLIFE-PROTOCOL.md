# CarLife HU 侧协议（HyperCar 网关）

> 依据：**pitate7777777/CarLifeWirelessBox**（MIT，实测兼容小米 CarWith 的 CarLife 连接）
> 的 HU 实现与 `docs/DEVELOPMENT.md`、`docs/通讯协议分析报告.md`，与 apollo-DuerOS 同源。
> 本文是 `rust-core/src/carlife.rs` 的实现依据——**改代码前先对照本文**。

## 1. 角色与端口

HyperCar 扮演 **HU（车机）**，本机 CarLife / CarWith(CarLife 兼容) / 荣耀车联 扮演 **MD（手机）**，
由 MD 主动连回 `127.0.0.1`：

| 端口 | 通道 | 帧头 |
|---|---|---|
| 7240 | CMD（握手/控制） | **8 字节** |
| 8240 | VIDEO（H.264 裸流） | 12 字节 |
| 9240 | MEDIA（媒体 PCM） | 12 字节 |
| 9241 | TTS（导航语音 PCM） | 12 字节 |
| 9242 | VR（语音识别，暂不消费） | 12 字节 |
| 9340 | CTRL（触摸/硬按键） | **8 字节** |

> ⚠️ 不存在 9440 端口；7241~7244 也不存在（早期资料猜测，已从实现移除）。

## 2. 两种帧头（最容易踩的坑）

**CMD / CTRL — 8 字节**

```
[u16 BE payload_len][u16 reserved=0][u32 BE service_type][payload]
```
- `payload_len` **不含头**；
- 写/读都用大端。

**VIDEO / MEDIA / TTS / VR — 12 字节**

```
[u32 BE payload_len][u32 BE timestamp_ms][u32 BE service_type][raw payload]
```

> 早期实现（含参考文档）把所有通道都写成 12 字节头 —— MD 侧会把握手消息当垃圾，
> 表现为「连上但握手不动 / 一直转圈」。

## 3. 消息 ID 域

- `0x0001_8xxx` = CMD，**HU → MD**
- `0x0001_0xxx` = CMD，**MD → HU**
- `0x0002_xxxx` = VIDEO、`0x0003_xxxx` = MEDIA、`0x0004_xxxx` = TTS、`0x0006_xxxx` = CTRL

| 消息 | ID | 方向 |
|---|---|---|
| HU_PROTOCOL_VERSION | 0x00018001 | HU→MD |
| VERSION_MATCH_STATUS | 0x00010002 | MD→HU |
| HU_STATISTIC_INFO | 0x00018027 | HU→MD |
| MD_INFO | 0x00010004 | MD→HU |
| HU_INFO | 0x00018003 | HU→MD |
| MD_FEATURE_CONFIG_REQUEST | 0x00010051 | MD→HU |
| HU_FEATURE_CONFIG_RESPONSE | 0x00018052 | HU→MD |
| HU_AUTHEN_REQUEST | 0x00018048 | HU→MD |
| MD_AUTHEN_RESPONSE | 0x00010049 | MD→HU |
| HU_AUTHEN_RESULT | 0x0001804A | HU→MD |
| MD_AUTHEN_RESULT | 0x0001004B | MD→HU |
| VIDEO_ENCODER_INIT | 0x00018007 | HU→MD |
| VIDEO_ENCODER_INIT_DONE | 0x00010008 | MD→HU |
| VIDEO_ENCODER_START | 0x00018009 | HU→MD |
| MD_CAR_DATA_SUBSCRIBE | 0x00010031 | MD→HU |
| HU_CAR_DATA_SUBSCRIBE_DONE | 0x00018032 | HU→MD |
| SCREEN_ON / FOREGROUND | 0x00010018 / 0x0001001B | MD→HU |
| MD_MODULE_STATUS | 0x00010026 | MD→HU |
| MD_EXIT | 0x00010059 | MD→HU |

媒体通道内：VIDEO_DATA `0x20001`、VIDEO_HEARTBEAT `0x20002`、MEDIA_INIT `0x30001`、
MEDIA_STOP `0x30002`、MEDIA_DATA `0x30006`、TTS_INIT `0x40001`、TTS_STOP `0x40002`、
TTS_DATA `0x40003`。CTRL 内：TOUCH_ACTION `0x68001`、HARD_KEY `0x68008`。

## 4. 握手 14 步（事件驱动）

```
Phase 1    HU→MD  HU_PROTOCOL_VERSION (0x18001)   版本 3.2
           MD→HU  VERSION_MATCH_STATUS (0x10002)
Phase 1.5  HU→MD  HU_STATISTIC_INFO (0x18027)
Phase 2    MD→HU  MD_INFO (0x10004)
           HU→MD  HU_INFO (0x18003)
Phase 3    MD→HU  MD_FEATURE_CONFIG_REQUEST (0x10051)
           HU→MD  HU_FEATURE_CONFIG_RESPONSE (0x18052)
Phase 4    HU→MD  HU_AUTHEN_REQUEST (0x18048, random_value=UUID)
           MD→HU  MD_AUTHEN_RESPONSE (0x10049, encrypt_value)
           HU→MD  HU_AUTHEN_RESULT (0x1804A, authen_result=true)
           MD→HU  MD_AUTHEN_RESULT (0x1004B)
Phase 5    HU→MD  VIDEO_ENCODER_INIT (0x18007, width/height/frameRate)
           MD→HU  VIDEO_ENCODER_INIT_DONE (0x10008)
Phase 6    HU→MD  VIDEO_ENCODER_START (0x18009)   ← 此后 MD 才开始推流
```

要点：
- **必须等 `VIDEO_ENCODER_INIT_DONE` 再发 START**；提前发 START 或提前起读循环，MD 会 reset 连接。
- 认证是「绕过式」：HU 收到响应后一律回 `authen_result=true`，不做真实 SRP/加密校验
  （个人回环网关无信任边界；CarLifeWirelessBox 同款做法）。
- `MD_CAR_DATA_SUBSCRIBE` 可能在握手任意时刻到达，回一个 `cnt=0` 的车辆信息列表即可。
- `SCREEN_ON` / `FOREGROUND` 是 CarWith 握手期通知，仅记日志、维持读超时。

## 5. 载荷格式

| 消息 | protobuf / 结构 |
|---|---|
| HU_PROTOCOL_VERSION | `{1: major(uint32), 2: minor(uint32)}` |
| HU_STATISTIC_INFO | `{1:cuid,2:version_name,3:version_code,4:channel,5:connect_count,6:success_count,7:connect_time}` |
| HU_INFO | `CarlifeDeviceInfo{1:device_type=2,2:os_type=1,3:os_version,4:manufacturer,5:model,6:device_id,8:device_name,…}` |
| HU_FEATURE_CONFIG_RESPONSE | `CarlifeFeatureConfigList{1:cnt, 2:repeated{1:key,2:value}, 3:bt_audio_support, 4:bt_name, 5:bt_mac}` |
| HU_AUTHEN_REQUEST | `{1: random_value(string)}` |
| HU_AUTHEN_RESULT | `{1: authen_result(bool)}` |
| VIDEO_ENCODER_INIT / DONE | `{1: width(int32), 2: height(int32), 3: frameRate(int32)}` |
| HU_CAR_DATA_SUBSCRIBE_DONE | `{1: cnt(int32) = 0}` |
| VIDEO_DATA 载荷 | 裸 H.264 AnnexB（无 protobuf 包装） |
| MEDIA/TTS 载荷 | 裸 PCM（无 protobuf 包装） |

**CTRL 触摸载荷是 protobuf `CarlifeTouchAction`，不是裸结构**：

```proto
// app/src/main/proto/carlife_ctrl.proto（CarLifeWirelessBox）
message CarlifeTouchAction {
    int32 action = 1;   // DOWN=0, UP=1, MOVE=2（就是 MotionEvent 的 0/1/2）
    int32 x = 2;        // 视频编码坐标空间（不是车机显示空间）
    int32 y = 3;
    int32 pointerx = 4; // 单指填 0
    int32 pointery = 5;
}
```

编码：全部 `int32` → varint，tag 依次 `0x08/0x10/0x18/0x20/0x28`。
参考实现 `MainActivity.forwardTouchEvent` 里明确标注「⚠️ CarWith 期望 protobuf 格式，
不是原始字节！」——早期我们用 `[u32 BE action][u32 BE x][u32 BE y]`，手机按 protobuf
解不出来，表现为「触摸完全无响应」，且日志上只能看到报文已送达。

`HARD_KEY 0x68008` 仍是裸 `[u32 BE keycode]`。

## 7. 触摸坐标的显示空间（接收端报 0×0 时）

CarPlay 接收端的 HID 报告用的是**它自己屏幕的像素空间**，而我们发往 CarLife 的是
**视频编码空间**，中间必须按接收端显示尺寸缩放：

```
x_编码 = x_hid * 编码宽 / 显示宽
```

`/info` 的 `displays[0]` 给不出尺寸时（WinPlay 报 `0x0`）必须回落到我们协商的屏幕尺寸，
**不能**用 `max(1)` 当分母——那样 `x_hid * 2560 / 1` 会溢出后被 clamp 到右/下边界，
表现是「每一下触摸都落在右下角」。

## 8. 音频发包节拍（RTP 时钟不能漂）

接收端按 RTP 时间戳排播，`late` / `discontinuity` 持续增长就是时钟落后于墙钟。
本项目曾用 `tokio::time::interval` + `MissedTickBehavior::Delay`，且与「收 CarLife PCM」
共用同一个 `select!`：漏一拍就**永久丢一拍**，实测发包率只有 68/s（应 48000/352≈136/s），
听感是断续 + 爆音。

修法（对齐 cardeck：它的节奏由 `AudioRecord.read()` 阻塞读天然保证）：

- `MissedTickBehavior::Burst`——漏的拍立刻补发（仍受混音队列 ~0.5s 上限约束）
- 每秒打一行 `AUDIO: 发包 N/s（应 136/s） 队列 media=Xms` 作为自测
- 混音必须按**交错立体声**取（每帧 L、R 两个样本），否则只消费一半数据 = 沙沙 + 断续

## 9. 仍待真机确认的点


「谁监听、谁拨号」取决于 MD 侧的入口，**不能写死成一种**：

| 场景 | 监听方 | 代码路径 |
|---|---|---|
| Android 同机（HU 与 CarLife 同设备） | **HU** 监听 `127.0.0.1:7240…`，MD 连入 | `CarLifeHub::start` |
| 盒子角色（小米 CarWith「车辆热点 → 开始连接」） | **MD** 监听 `7240…`，HU 拨过去 | `CarLifeHub::connect(md_host)` |

现场实测（2026-10-23 22:56，Xiaomi ****（型号略）+ CarWith 4.0.16 + CarLife 8.8.5）：
点「开始连接」后手机 `com.baidu.carlife.xiaomi`（uid 10345）开始 **LISTEN**
`7240/8240/9240/9241/9242/9340`，等待车机连入；PC 侧拨过去后 14 步握手一次跑通，
首帧 `NAL=[7,8,5]`。这与参考实现 `CarLifeWirelessBox` 的
`HuRole.connect(phoneBIp, port)` 方向一致。

> 早期版本只实现了「监听」方向，导致在盒子里场景下 hub 在 PC 上白等：
> CarLife 也在等，双方都不发起连接。

## 7. 仍待真机确认的点

1. **MEDIA/TTS 初始化载荷**：HU 侧不解析（转发给桥接层），故 `sample_rate/channels` 的
   protobuf 字段号未实测。当前实现按 `CarlifeAudioStart{3:sample_rate,4:channels}` 解析，
   失败回落 48kHz/立体声——若真车语音音调异常，从这里查。
2. **MD 是否跳过 MD_AUTHEN_RESPONSE 直发 MD_AUTHEN_RESULT**：资料称可能发生；
   实现里两者都会推进到「发编码器初始化」，不会卡死。
3. **版本号 3.2** 是 CarLifeWirelessBox 的实测默认值；若某版 CarLife 拒绝，可在
   `PROTOCOL_MAJOR/MINOR` 调整。

### 5.1 HU 侧没有「断开/退出」命令（2026-10-25 核实）

现场问题：手机上的 App 点「断开连接」后，CarLife/CarWith **仍在后台编码推流**。

核实了两种公开资料，结论一致：**CarLife 协议里没有 HU→MD 的退出命令**。

- **CarLifeWirelessBox**（MIT，实现了完整 HU 角色，`role/CarLifeMsg.kt` 列了 33 条
  HU→MD 消息 + 28 条 MD→HU 消息）：HU 侧清单里**没有 EXIT / DISCONNECT**，
  最接近的只有 `HU_ERROR_CODE 0x18055`、`HU_MODULE_CONTROL 0x18028`、
  `GO_TO_FOREGROUND 0x18025`。而 `MD_EXIT 0x10059` 只出现在 MD→HU 方向。
- 该实现的 `HuRole.disconnect()` 做的事就是**关掉所有通道 + 取消协程**，
  一个字节都不往手机发；收到 `MD_EXIT` 时反过来 `disconnect("Phone requested exit")`。
- 仓库里虽然带 `CarlifeDisconnectRequest/Response.proto`，但它们**不挂任何消息号**，
  属于 CarLife SDK（App ↔ 服务）层面的接口，不在 HU↔MD 线协议里。

因此 HU 侧想「让手机停止投屏」只有两条**间接**手段：

| 手段 | 效果 | 代价 |
|---|---|---|
| 关流（`SO_LINGER=0` 发 RST，让对端看到连接异常而不是优雅关闭） | 多数实现会判定会话结束 | 手机可能仍把编码器留着等重连 |
| 结束手机上的 CarLife/CarWith 进程（`am force-stop`，需 Shizuku 或同机） | 一定停 | 不能用于"短暂离开车"的场景 |

本项目的取舍：**不做任何额外处理，断开就只是关流**（与参考实现一致）。

曾试过两种"更狠"的手段，都已回滚，避免引入副作用：

| 试过的做法 | 为什么不保留 |
|---|---|
| 关流时 `SO_LINGER=0` 发 RST | 对端只是更快看到"连接断了"，并不改变它是否退出投屏；反而可能让重连路径表现异常 |
| 断开时 `am force-stop` 掉 CarLife/CarWith | 能停推流，但"短暂离开车再回来"就秒连不了——违背使用习惯 |

结论：手机端停止投屏应由**它自己**决定（车机断链/用户点 CarLife 的退出）。
我们从 HU 侧只做关流，不去猜它的状态。

> 之前曾按"HU 侧 = MD 侧 + 0x8000"的位 15 约定猜过 `HU_EXIT = 0x18059`，
> 实测手机无反应，且与上面两份资料都不符——已从代码中移除，避免继续发无意义的帧。

