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

**CTRL 触摸是裸结构，不是 protobuf**：

```
TOUCH_ACTION 0x68001: [u32 BE action][u32 BE x][u32 BE y]
    action: 0=DOWN, 1=UP, 2=MOVE    ← 不是 Android MotionEvent 的 1/2/3
HARD_KEY 0x68008:     [u32 BE keycode]
```

## 6. 仍待真机确认的点

1. **MEDIA/TTS 初始化载荷**：HU 侧不解析（转发给桥接层），故 `sample_rate/channels` 的
   protobuf 字段号未实测。当前实现按 `CarlifeAudioStart{3:sample_rate,4:channels}` 解析，
   失败回落 48kHz/立体声——若真车语音音调异常，从这里查。
2. **MD 是否跳过 MD_AUTHEN_RESPONSE 直发 MD_AUTHEN_RESULT**：资料称可能发生；
   实现里两者都会推进到「发编码器初始化」，不会卡死。
3. **版本号 3.2** 是 CarLifeWirelessBox 的实测默认值；若某版 CarLife 拒绝，可在
   `PROTOCOL_MAJOR/MINOR` 调整。
