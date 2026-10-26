# 协商层对比分析：参考文档 vs cardeck 实测协议

> 对比对象：
>  - **参考文档**：`E:\核心层rust实现.txt`（Kotlin 交 FD + 密钥、Rust 只做数据面转发的方案）
>  - **原项目**：fw867/cardeck（真车逐字节实测修正后的协议实现，含 Audi MIB3 /
>    BMW iD8.5 MGU-86 / WinPlay 的实测记录）
>
> 结论先行：参考文档的架构方向（FD 移交 + Rust 零拷贝数据面）是对的，但它把
> AirPlay 协商整体留在了 Kotlin，且数据面的**线格式多处与真车实测不符**。本项目
> （hypercar）在采纳其骨架的同时做了两件事：① 协商整体下沉 Rust；② 按 cardeck
> 实测协议逐项修正线格式与消息 ID。

## 1. 架构级差异：协商放哪边

| | 参考文档 | cardeck 实测事实 | hypercar 决策 |
|---|---|---|---|
| AirPlay 协商 | Kotlin 完成（pair-setup/verify、RTSP SETUP），Rust 只接「已连通 FD + 已派生密钥」 | 协商状态机极复杂：/info、pair-verify、modesChanged、两级 SETUP、RECORD、事件通道、三条心跳，跨语言传密钥要序列化 80B+ 且方向易错 | **协商下沉 Rust**（`airplay.rs`）；Kotlin 只提供「绑定车机网络的 socket 供给器」（`SocketProvider` 回调 openTcpFd/openUdpFd）。协商状态机只有一份实现，秒连路径少一次跨语言往返 |
| 密钥派生 | Kotlin 派生好交给 Rust（video_key/video_iv/audio_key） | 流密钥 = HKDF(shared, "DataStream-Salt<connId>")，connId 每次 SETUP 随机；「先派生后移交」意味着 Kotlin 必须完整跑完 SETUP | Rust 内自派生（`airplay::stream_key`），Kotlin 完全不接触密钥 |
| CarLife 端口 | 监听 7240~7244 连续端口 | 标准车机端口是 7240/8240/9240/9241/9242/9340，**没有 9440，也没有 7241~7244**；且方向取决于入口：Android 同机由 HU 监听，盒子角色（CarWith 车辆热点）由 MD 监听、HU 拨号 | 只实现 6 个标准端口；`CarLifeHub::start`（监听）/ `connect`（主动拨 MD）两种方向，见 `docs/CARLIFE-PROTOCOL.md` |

## 2. 视频线格式：参考文档按此实现会「真车黑屏」

参考文档的 `video_pipe.rs`：128B 头写 `payload_len + payload_type(0/1) + pts + w/h`，
SPS/PPS 当「明文 type=1」直发，帧用**固定 IV 的 ChaCha20 流密码**。

cardeck 实测（`VideoSender.kt` + docs §9e/§9g）的真车要求：

| 项 | 参考文档 | 真车协议（已实测） |
|---|---|---|
| 帧类型字段 | payload_type 0/1 | **opcode**：0=VideoFrame、1=VideoConfig、2=KeepAlive、5=KeepAliveWithBody |
| VideoConfig | 无（SPS/PPS 当明文帧发） | **必须**单独发 avcC body 的 VideoConfig 帧，且头里 `small_param[1] \|= 0x04`（Encrypted 声明）+ `params[1]=(width,height)` 两个 f32 LE——**缺这两项 Audi 4xxx 整屏黑（会话/触摸/心跳全部正常，日志无任何错误）** |
| 视频帧加密 | ChaCha20 流密码、固定 IV、无 tag | **ChaCha20-Poly1305**：AAD=128B 头本身、nonce=帧计数、body 长度含 16B tag；未配对会话是 AES-128-CTR（密钥流跨帧连续），不是裸流密码 |
| 存活信号 | 无 | **屏幕流内 keepAlive 帧每秒一发**（明文）；缺它 Audi MMI 3315 在 RECORD 后 10.2s 整轮被 reset（与 UDP 心跳、/feedback 相互独立，缺一不可） |
| SPS/PPS 处理 | 粘连包切分后明文发 | 粘连包按 NAL 类型切分，SPS/PPS 变化时转 avcC 发 VideoConfig，VCL 打成「4B BE 长度前缀序列」密封发 |

hypercar 实现：`screen.rs` 完整实现上表右列（128B 头布局、VideoConfig 双字段、
Poly1305 AAD=头、帧计数 nonce、1s keepAlive 节流、avcC 转换）。

## 3. 音频线格式：参考文档按此实现会「真车无声/沙沙声」

| 项 | 参考文档 | 真车协议（已实测） |
|---|---|---|
| RTP PT | 写死 96 | **PT=流类型**（100=PCM 通路；catplay `payload_type = stream_type`） |
| SSRC | 0x424D5738（"BMW8"） | **必须 0**——部分车机按 SSRC 关联 SETUP，随机/自定义值实测无声（2026-10-17） |
| AAD | 整个 12B RTP 头 | **头字节 4..12**（timestamp+SSRC 的 BE 表示，catplay "modern AAD"；DiPlay 解密取同一范围） |
| nonce | 用 RTP 头里的 timestamp+SSRC 拼 | **独立递增计数**（4B 零 + 8B LE 计数，与 seq/ts 无关）；尾部再附同样的 8B |
| PCM 字节序 | 小端 | **大端 s16**（接收端 byteSwap 回小端喂 AudioTrack；漏换序实测「沙沙声」2026-10-10；AAC 通路不换序） |
| 每包样本 | 512 帧 | **352 帧**（AirPlay 标准 SPF；512 在奥迪实测无声） |
| RTP↔NTP 锚点 | 无 | 每秒向流的 controlPort 发**同步包**（PT=0xD4，首包置 X 位），否则媒体时钟漂移 |
| 双路混音 | media/TTS 两个固定 512 帧队列直接相加 | CarLife 的媒体/TTS 格式先归一（采样率/通道数在 0x00020001/0x00030001 里声明），重采样到 48k 立体声再按 352 帧出包；TTS 触发 ducking 需带 ~400ms 尾音保持 |

hypercar 实现：`audio_out.rs`（PT=100、SSRC=0、AAD=4..12、独立 nonce、352 SPF、
大端样本、同步包）+ `engine.rs` 的重采样与闪避混音。

## 4. 控制/事件通道：参考文档完全缺失的部分

1. **控制通道密文帧 MTU**：每帧 `[2B LE len][密文][16B tag]`，单帧明文上限 **1422**
   （1440-2-16）。车机解码侧遇 len>1422 直接判协议违规。
2. **事件通道是双向 RTSP**：车机每个来向请求必须**立即**回 200 OK（echo CSeq），
   不应答 → 车机塞 3 条就断通道（表象「能滑一下」）。命令（hidSendReport、
   iAPSendMessage、modesChanged…）都走事件通道的 `POST /command`，RTSP 控制连接上的
   200 是假确认。
3. **三条相互独立的心跳**：UDP keepAlive（5s，4B，间隔必须**小于**车机 10s 看门狗，
   等长必输——sleep 抖动让心跳永远晚到 deadline 1ms）+ RTSP /feedback（1s）+
   屏幕流 keepAlive 帧（1s）。只做前两条在 MMI 3315 上活不过 10 秒。
4. **modesChanged**：SETUP 前必须在控制通道声明持有主屏(1)/主音频(2)资源
   （entity=permanentEntity=Controller），否则 Audi 对屏幕流 SETUP 直接 400
   （资源仲裁未通过）。
5. **/info 必须带空 bplist 体 + plist Content-Type**；缺体的 GET /info 被部分固件
   回 400/455，此后 SETUP 全灭。
6. **会话级 SETUP 的 deviceID** 必须是本机**真实蓝牙 MAC**（WinPlay 等接收端按它
   过滤会话，对不上直接 403；占位 02:00:00:00:00:00 必失败）。
7. **pair-verify 的 Apple 标识头**：`Content-Type: application/pairing+tlv8` +
   `X-Apple-HKP: 2`（pair-verify 再加 `X-Apple-PD: 1`），缺了 403。
8. **流 SETUP 路径**：初始 SETUP 用 `/setup`（相对路径，老固件对绝对 URI 敏感），
   流 SETUP/RECORD 用 `/`；features 必须发完整 14 项清单（接收端算交集，漏项整个
   能力被关——只提 videoPlayback 时 enabledFeatures=[]、屏幕流 400）。

hypercar 实现：`rtsp.rs`（1422 MTU、CSeq 异步派发、入向请求自动 200）+
`airplay.rs`（上列 1-8 全部内化；三条心跳分别在 `spawn_keepalive_loops` 与
视频泵的 keepAlive 节流里）。

## 5. CarLife 消息层：参考文档与实测协议的差异

> 详见 **[docs/CARLIFE-PROTOCOL.md](CARLIFE-PROTOCOL.md)**（14 步握手 / 两种帧头 / 载荷格式）。
> 本节只列与参考文档冲突、且已按 CarLifeWirelessBox 实测实现纠正的项。

| 项 | 参考文档 | hypercar（按 CarLifeWirelessBox/apollo-DuerOS 纠正） |
|---|---|---|
| 帧头 | 全通道 12 字节 `[len][ts][type]` | **CMD(7240)/CTRL(9340) 是 8 字节** `[u16 len][u16 rsv][u32 type]`；仅 VIDEO/MEDIA/TTS/VR 是 12 字节。写成 12 字节 → MD 侧把握手消息当垃圾 |
| 端口 | 7240~7244 + 9440 | 标准 6 口：7240/8240/9240/9241/9242/9340。**无 9440，无 7241~7244** |
| 握手 | 无（直接发 encoder init） | **14 步事件驱动**：版本→统计→设备信息→特性配置→鉴权(4 段)→编码器 init/done/start |
| 鉴权 ID | 0x00018004 / 0x0001000B | **0x00018048 / 0x00010049 / 0x0001804A / 0x0001004B**（请求/响应/结果/结果） |
| 编码器 ID | 0x00010007 + 0x00010008 | init **0x00018007**(HU→MD)、init_done **0x00010008**(MD→HU)、start **0x00018009**(HU→MD)；**必须等 done 再发 start** |
| 触摸消息 ID | 0x00040001 | **0x68001**；硬按键 **0x68008** |
| 触摸载荷 | protobuf varint | **裸 BE 结构** `[u32 action][u32 x][u32 y]`，action 0=DOWN/1=UP/2=MOVE |
| 音频/TTS 域 | 0x00020001 / 0x00030001 | MEDIA 域 **0x0003xxxx**、TTS 域 **0x0004xxxx**（0x0002xxxx 是 VIDEO） |
| 通道健壮性 | 单次 accept、单客户端 | 每通道单客户端但新连接顶替旧连接；缓冲上限防 OOM |
| 触摸坐标 | 直接透传车机坐标 | 车机显示空间 → CarLife 编码空间缩放（HID 报告按 /info displays 宽高归一） |

## 6. iAP2（蓝牙协商）侧：cardeck 实测教训全部继承

hypercar 的 Kotlin iAP2 层（`iap2/`）按 cardeck docs §3/§4/§5/§9h/§9j 移植，重点：

- **BMW iD8.5（MGU-86）§9j 三修复**：0xAA05 之后**立即**突发设备状态问候
  （0x4E0C/0x4E0A/0x4E0B/0x4E09——车机在等问候、我们若等 0x5703 会 5.4s 被拆链）；
  0xEA00 的 param1 必须是 **EA 协议名字符串**（发整数车机不回 0xEA03）；
  0x5703 分段等待（1.5s 强制重发问候 → 1.3s 重发 0x5702）。
- **§9h SYN-ACK 序号修复**：每条纯 SYN 都回 SYN|ACK 且消耗新序号；同一 SYN 的
  字节级重发幂等；主机路径只回 SYN|ACK（裸 ACK 会与 SYN|ACK 撞号）。
- **MFi 挑战长度阶梯** 32B(3s)→20B(6s)→16B(3s) + 按接收端身份记忆（不同年款偏好相反）。
- **0x5703/0x4301 的地址字段是定长 NUL 填充**：一律 rstrip 全部尾部 0x00（只去一个
  会得到 `10.174.189.1\0\0\0`，能过所有「是否 IPv4」判断但 getaddrinfo 解不了）。
- **0x4301 是生命周期事件**：重复到达都回调端点，推流中的冗余端点由上层忽略
  （防拆健康会话循环）。
- **0x5703 与 0x4301 是两条互补消息，不能取「第一个端点回调」当终点**：0x5703 只有
  热点 ssid/psk（没有 IP/端口），0x4301 才带 AirPlay 端口与车机地址，先后顺序由车机
  决定。WinPlay 实测 0x5703 先到、0x4301 晚 143ms（真车同理）；上层必须**合并两次
  回调并等到有 IP** 再继续，否则会立刻误判「端点无可用 IP」（2026-10-23 19:46:57 现场）。

## 7. 参考文档中直接可用的部分

- FD 移交模型（`ParcelFileDescriptor.detachFd` → `from_raw_fd` → tokio）——保留，
  范围扩大到「协商用的控制 FD + 按需新开的所有端口」。
- iAP2 0x5200 HUD 导航报文构造器（TLV 布局）——保留（`iap2_nav.rs`）。
- 引擎生命周期（全局 tokio runtime、Notify 停止、JNI 常量表）——保留。
- Kotlin 薄壳的「蓝牙触发唤醒 → 绑网 → 移交 FD → 唤醒本机 CarLife」时序——保留并
  补全（Shizuku 静默入网、ACL 冷启动 FGS、目标设备过滤、冗余端点去重）。

## 8. 仍未定谳 / 需实车校准

1. ~~**BMW iD8.5 的 HID 触摸报告格式**~~ → **已定谳（2026-10-24）**：不是奥迪的 5B
   `[tip][X u16LE][Y u16LE]`，而是**每指 6 字节** `[index][touch][X u16LE][Y u16LE]`，
   最多 2 指；载荷在事件报文的**顶层 `hidReport`** 字段（不是 `params.report`）。
   CarLife 侧也不是 12B 裸结构，而是 `CarlifeTouchAction` **protobuf**。
   实现见 `airplay::handle_hid_report` / `carlife::touch_proto`。
2. ~~**CarLife 唤醒广播**~~ → **已实测（HyperOS + CarWith）**：
   `com.baidu.carlife.Action.StartWireless` 对 `com.baidu.carlife` 有效，但小米上
   **没有这个包**（只有 `com.baidu.carlife.xiaomi` 与 `com.miui.carlink`），不带包名
   的广播被静默丢弃 → 表现为「一直 Waiting」。现行做法：包名自动探测 + 三级唤醒
   （应用内广播 → Shizuku shell 广播 → monkey），设置里也可手选。
3. **CarLife 握手已按 CarLifeWirelessBox 重构**（14 步 + 8 字节 CMD 头 + 正确 ID）。
   剩余不确定点收敛为：MEDIA/TTS 初始化载荷的 protobuf 字段号、MD 是否跳过
   MD_AUTHEN_RESPONSE（两者都已做兼容），详见 docs/CARLIFE-PROTOCOL.md §6。
4. **0x5702 是否必须带参数**（BMW 空体接受性）——cardeck 同样未定谳，需要一次
   真 iPhone 同车抓包。

5. **BMW MGU-55（iD8.5 实车，2026-10-24）不下发无线 CarPlay 凭据**：这台车机的
   0x1D01 清单里**没有** 0x5702/0x5703（WiFi 配置）、也没有 0x4300/0x4301（CarPlay
   会话启动），只声明了 iPod/BMW Apps 那一套（0x4C00/02/03/05/07 发送、
   0x4C01/04 接收、0x5000/01、0x6800/01、0xAE00/01、0xEA00/01/03，加 7 条
   `de.bmw.a4a.*` EA 协议），识别里也没有 WirelessCarPlayTransportComponent。

   **与 cardeck（fw867/cardeck）逐项对照后定谳**：cardeck PROTOCOL-NOTES §9j 记录
   BMW iD8.5（MGU-86）有 **A/B 两套能力档案**，本车当前这两轮全是 **档案 A**：

   | 档案 | 发送清单 | EA 协议 | 0x5702/0x5703 |
   |---|---|---|---|
   | **A**（本车现状，14 项） | 无 0x5703 | `de.bmw.a4a.bt` protocol=7 | 收=否 / 发=否 |
   | **B**（真车正常无线 CarPlay，16 项） | 含 0x5703、0xaa06 | `com.bmw.carplay` protocol=8 | **收=是 / 发=是** |

   档案 A = 车机**没开无线 CarPlay 窗口**（真 iPhone 此时也不会被当成 CarPlay 设备）；
   档案 B 才是能跑通的那一档。对照 cardeck 后本仓补齐了四处缺失（旧实现正是因为这四处，
   即使车机进了档案 B 也走不通）：

   1. **0x4E0D WirelessCarPlayUpdate（u8=1）**：真 iPhone 在 0xAA05 之后会发这条
      「本机支持无线 CarPlay」的可用性通知；旧实现只发 0x4E0C/0x4E0A/0x4E0B/0x4E09。
      本车可收清单里**恰有 0x4E0D**——车机很可能就是靠它判断对方是不是 CarPlay 设备。
   2. **0x4E0E DeviceTransportIdentifier（蓝牙 MAC）**：真机顺序是 0x4301 之后回它，
      车机按它把 iAP2 设备与 RFCOMM 对端关联（发后不管，Audi 实测从不 ACK）。
   3. **`_carplay-ctrl._tcp` mDNS 广播 + `GET /ctrl-int/1/connect` 应答**
      （`net/CarPlayCtrlAdvertiser.kt`）：真车是**邀请驱动**——车机在 WiFi 上发现该服务后
      反连手机发邀请，**邀请成功才下发 0x4301**。旧实现完全没有 mDNS，而且「等 0x4301 才
      入网」= 双方互等死锁（cardeck 在奥迪上实测过同一死锁）。现在 0x5703 一到就
      入网 + 广播（TXT `id`=真实蓝牙 MAC、`srcvers`=950.7.1，两者改动需先真车复测）。
   4. **0x6800 StartHID → 0x6801 应答**（`[u16 0][u8 0]`）与**EA 协议逐条开**（修掉
      同 id 重复参数被 `associateBy` 压成一条的 bug，BMW 的 7 条 EA 协议里
      `de.bmw.a4a.pairing` 从未被打开过）。

   现场处置（已实现）：
   - **档案 B**：拿不到凭据时不再拆链（旧行为 throw → 关 RFCOMM → 车机断 ACL → 上层拆会话 →
     3s 后重连，形成连-断死循环）；链路保持 + 5s/30s 节奏重探 0x5702/0x4300、补开未确认的
     0xEA00，等车机下发 0x5703。
   - **档案 A**：快速退场（见下），不握着会话挡车机下一轮连入。
   - 未知消息体（尤其 0x4Cxx）与非控制会话数据帧的原始体各 dump 一次；档案 A/B 直接判读。

   **cardeck Issues 里两份 iD8.5 日志的对照（2026-10-24）**：

   | 日志 | 车机 | 轮次与档案 | 0x5703 | 车机拆链时刻 |
   |---|---|---|---|---|
   | `CarDeck-log-20261021-202434`（0.1.12） | BMW 31726 / MGU-86-BMW | 20:22:39 **A**(14 项) → 20:23:07 **B**(16 项) → 20:23:20 **A** → 20:23:43 **B** | **一轮都没到** | 每轮在 0x5702 之后 **5.4~6.5s** |
   | `CarDeck-log-20261022-135133`（0.1.17-debug） | BMW 17713 / MGU-86 | 5 轮**全是 B**(16 项) | **一轮都没到** | 每轮在 0x5702 之后 **5.37s** |
   | 本仓 2026-10-24（BMW ***** / MGU-55-BMW） | 11:01:21、11:01:31 两轮**全是 A**(14 项) | — | 无 | 我们自己在 1.3s 内关的链 |

   三条结论：
   ① **A/B 由车机单方面决定，且会来回切**（同一台车 27 秒内 A→B→A→B，与手机发什么无关）；
      判据（四条，任一命中即 B）：发送清单含 `0x5703`、可收清单含 `0x5702`、
      多一条 EA `com.bmw.carplay`(protocol=8)、多一个 **WirelessCarPlayTransportComponent**
      （参数 id=0x18=24，component 名形如 `BMW 31726 Wifi`）。识别体大小约
      565B(A) vs 646~650B(B)；本仓 `Iap2Messages.Identification.hasWirelessCarPlayComponent`
      已解析该组件。
   ② **这两份 cardeck 日志其实也是失败的**（都没拿到 0x5703）：0.1.12/0.1.17 把设备状态
      问候排在 0x5702 超时之后才发，车机在 0x5702 后 5.4~6.5s 判「accessory 未就绪」拆链——
      正是 §9j 修复前的签名。本仓现在的顺序（0xAA05 之后立刻发 0x4E0D/0x4E0C/0x4E0A/
      0x4E0B/0x4E09，然后才 EA → 0x5702）已经是修好的那一版。
   ③ 所以对本车要做的不是「握着一轮等」，而是**在档案 A 时快速退场、让车机开下一轮**——
      而且 `Iap2Bluetooth` 的 `sessionActive` 门闸会让「握着 A 会话」直接挡掉车机下一次
      连入（它会回 `已有 iAP2 会话在跑，忽略新的连入`），B 轮永远轮不到。
      现行实现：能确认是 BMW 形态且档案 A → 抛 `NoCarPlayWindowException`，只关这条
      RFCOMM、不拆上层会话，车机下一次连入即接住；档案 B 才进入「保活 + 等 0x5703」。`

   仍需现场确认：a) 多轮重连里是否出现**档案 B**（现在档案 A 会快速退场换下一轮，
   日志里看 `无线 CarPlay 档案=B`）；b) 档案 B 下 0x5703 是否下发、ctrl 邀请是否到达
   （日志 `CTRL: ✓ 车机 ctrl 邀请`）；c) 若多轮始终只有档案 A，说明车机认的是「iPhone
   蓝牙 EIR 里的 CarPlay UUID」（`2D8D2466-E14D-451C-88BC-7301ABEA291A`，Android 侧
   做不出来）——那就只能走手动入网 + ctrl 广播 + 网关 7000 兜底这条非 iAP2 路径。

6. **从 cardeck 0.1.14 / PR #19 借鉴过来的三条（2026-10-24，已实现）**：

   - **`wifiGen` 分派取代「清单硬门禁」**（`iap2/ReceiverProfile.kt` + `Iap2SenderSession.run`）：
     旧实现 `legacyWifiExchange = declaresReceive != false && declaresSend != false`，
     车机清单没写 0x5702 就跳过旧协议。cardeck 在奥迪 MMI 3315 上定谳这是错的：
     **清单只能证明「有」，不能证明「没有」**——奥迪稀疏清单（发 2 / 收 5 项）不含
     0x5702，但它完整支持 0x5702→0x5703，且**要靠收到 0x5702 才把 AirPlay 服务拉起来**
     （0 次 0x5702 的那版：端口 7000 全程 ECONNREFUSED、mDNS 不广播、4 轮全灭）。
     现行：只有「两清单合计 ≥10 项仍无 0x57xx」（BMW iD9 形态）才判 NEW 跳过 0x5702；
     稀疏/未声明一律发（`0x5703` 有超时兜底）。同理 **0x4E0E 也不再按清单拦**
     （同日日志里 0x4E0D/0x4E0B/0x4E0E 不在清单里，车机逐条 ACK 到位）。
   - **新协议车机「声明会发 0x4301 但窗口内未到」不再拆链**（PR #19，凯迪拉克 CT5）：
     0x4301 是活事件，GM/Cinemo 与 BMW iD9 同族车机会晚发或等前置条件（奥迪 MMI 9310
     晚 2 分 35 秒）。超时无端点时**保持链路**、等晚到 0x4301 重新回调；端点等待上限
     180s → **300s**；同时确认「空端点不得缓存空 SSID」（否则保活期 `wifi ?: fromSession`
     会用空凭据盖掉晚到 0x4301 自带的 AP 信息——本仓本来就没缓存空值，保持不动）。
   - **ctrl 广播端口固定**：重注册只换 mDNS 注册、复用 socket 与 accept 线程
     （cardeck 实测旧实现每次换端口 43857→33383→…，中间最长 1.2s 服务空窗，依赖邀请的
     车机会连到已关闭的 socket；`acceptLoop` 的循环条件也不能绑 `registered`，
     否则重注册瞬间线程退出、之后再没人接邀请）。
   - 顺带移植 **MFi 记忆「硬拒绝」**（cardeck 0.1.25 / commit 1cd903c）：挑战期间被对端
     断链是强证据，即使该长度等于已记「成功」也要推翻（迟到应答会把上一档的应答记到
     当前档头上，记忆一旦锁死就再也试不到正确长度）。
   - **静默网关试探（用户 2026-10-24 要求）**：等端点期间每 5s 看一次手机当前 WiFi；
     **等待期间出现新 SSID 时**（或该 SSID 正是 iAP2 给的车机热点）就静默把该 WiFi 的
     默认网关当车机热点试一次 `网关:7000`：
     成功 → 直接合成端点继续（`ssid` 用当前 SSID、端口 7000、进程网络已绑定，不再等 0x4301）；
     失败 → 记一行日志继续等（保活链路不受影响）。每个 SSID 只试一次；手机上原本就有的
     WiFi 不会被误试（家里可能有 Apple TV 占着 7000）。对应 cardeck PR #19 的
     `watchForPendingStartSession`，只是把「弹窗确认」换成了静默试一次。



## 9. 真车实测：BMW MGU-55（2026-10-24）

一次完整的「连不上」日志（`hypercar-log-20261024-181315.txt`）逐段判读：

| 阶段 | 现象 | 结论 |
|---|---|---|
| iAP2 建链 | `0x1d00/0x1d01` → `0x1d02`，`0xaa00/0xaa01` MFi → `0xaa02/0xaa03` → `0xaa05` | 正常，车机 `BMW ***** / MGU-55-BMW` |
| EA 会话 | 连续 `0xea00`（8 个 a4a 协议），**`0xea03` 始终未到** | 车机不确认也不影响后续，先忽略 |
| 热点凭据 | `0x5702` → `0x5703`：`ssid=DIRECT-BMW ***** channel=157 security=2` | 正常 |
| 入网 | 静默加入 + 进程级绑定成功 | 正常 |
| ctrl 广播 | `_carplay-ctrl._tcp` 注册成功，车机 `172.16.*.*` 主动 `GET /ctrl-int/1/connect` | **车机已开 AirPlay 窗口** |
| 端点地址 | `0x4301` **永远不来**；网关兜底也失败（DIRECT- 链路无默认网关，`0.0.0.0:7000`） | **卡点** |
| 结果 | 15s 超时 → 主动拆会话 → 车机 3s 后重连 → 循环 | 表现为「连不上」 |

**根因**：这台车在 `0x1d01` 的报文清单里明确声明 `CarPlayStartSession(0x4301)=不发`。
我们的端点来源只有「0x4301 里的地址」和「热点网关」，两条都不成立 → 死等。

**解法（已实现）**：车机来邀请时的 **TCP 对端地址就是它在热点网段的地址**，
收到 `GET /ctrl-int/1/connect` 即视为端点可用，取 `对端IP:7000` 继续：

```
CTRL: 172.16.*.* -> [GET /ctrl-int/1/connect ...]   ← 邀请
SESSION: ✓ 车机已邀请（172.16.*.*）——用它当 AirPlay 端点 :7000
```

> 端口 7000 是 AirPlay 惯例值；若某车型不是 7000，下一步改成浏览 `_airplay._tcp`
> 取准确端口（车机一般会广播该服务）。

### 9.1 连接阶段日志格式（便于对照排查）

自 10012 起，连接链路的每行都带「阶段 + 相对本轮起点的毫秒」：

```
CONNECT +0ms     端点确定：<ip>:<port> ssid=… 有密码=… ips=[…] gateway=… devId=… srcvers=…
WIFI +820ms      ✓ 已绑定网络（进程级）：if=wlan0 ipv4=[172.16.*.x/24] ipv6=1 gw=- dns=[…] validated=false
NET  +1100ms     ✓ <ip>:7000 已连通（Wi-Fi 网络 #N，耗时 63ms，fd=57）
CORE +1150ms     引擎配置：{"width":2560,…}
```

排查时直接看**最后一条 +Nms** 就知道停在哪一步：停在 `WIFI` = 入网/绑网问题，
停在 `NET` = 端口/路由问题（每行都带失败原因与耗时），进到 `CORE` 之后才轮到
Rust 侧的 `AIRPLAY:` 日志。

## 10. 真 iPhone HCI 对照（2026-10-24，同一台 BMW MGU-55）

### 10.1 数据来源与解析方式

iPhone 上抓 `sysdiagnose`，取出 `logs/Bluetooth/bluetoothd-hci-latest.pklg`（Apple PacketLogger 格式）。
**抓取步骤（描述文件怎么装、按键怎么按、文件在哪）见
[docs/IPHONE-HCI-CAPTURE.md](IPHONE-HCI-CAPTURE.md)。**
报文是**明文**，可用如下方式定界：

- 帧同步 `0x40 0x40` 之后是 iAP2 负载；本项目的记录布局是
  `40 40 | len(u16 BE) | msgID(u16 BE) | params…`（`len` 只覆盖 msgID+params 之后的部分）。
- 记录容器是 `[len u32][ts_sec u32][ts_usec u32][type u8][data…]`；其中
  `ts` 存的是**本地时间当 UTC**，换算回本地要减时区。
- 参数按 `[len u16][id u16][value]` 或 TLV8（`<type><len><value>`）解析。

> 隐私：本文只保留协议结构。设备名、热点 SSID、PSK、蓝牙 MAC、传输 UUID、
> 车机标识串、IP 末段一律以 `*` 掩码；抓包文件本身不入库。

### 10.2 同一台车机对真 iPhone 的报文清单

`0x1D01` 的发送/接收清单与对我们的**逐字节一致**，关键声明也一致：

```
CarPlayStartSession(0x4301) = 不发        ← 这台车永远不主动给 AirPlay 端点地址
StartHID(0x6800)            = 会发
可收：0x4C01 0x4C04 0x4E09 0x4E0A 0x4E0B 0x4E0C 0x4E0D 0x5001 0x5702 0x6801 0xAE01 0xEA00 0xEA01
       （注意：**没有** 0x4300，也没有 0x4E0E）
```

### 10.3 真机的 iAP2 时间轴（相对首条报文）

| 时刻 | 方向 | 报文 | 说明 |
|---|---|---|---|
| +0.00s | 手机→车机 | `0x1D00` StartIDPS | |
| +0.16s | 车机→手机 | `0x1D01` IDPS | 清单见上 |
| +0.30s | 手机→车机 | `0x1D02` IDPSAccepted | |
| +0.30s | 手机→车机 | `0xAA00` DevAuthInfo | |
| +0.51s | 车机→手机 | `0xAA06` DevAuthFailed | 第一次签名被拒（随后重试成功） |
| +0.51s | 手机→车机 | `0xAA02` DevAuthChallenge | |
| +1.07s | 车机→手机 | `0xAA03` DevAuthSignature | |
| +1.14s | 手机→车机 | `0xAA05` DevAuthSucceeded | |
| +1.14s | 手机→车机 | `0x4E0C` TransportComponent | 载荷含一个 UUID（本文掩码） |
| +1.15s | 手机→车机 | `0x4E0A` | |
| **+1.32s** | 车机→手机 | **`0xAE00` StartPowerUpdates** | 车机要"电源状态上报" |
| +1.51s | 手机→车机 | `0x4E0B`、`0x4E0D` | |
| **+1.51s** | 手机→车机 | **`0xAE01` PowerUpdate ×3** | **真机会回，我们此前从不回** |
| +1.57s | 车机→手机 | `0x5703` WiFiConfigResp | ssid/psk/channel/security |
| +2.45s | 手机→车机 | `0x5702` 再请求一次 | |
| +2.47s | 车机→手机 | `0x5703` 第二次 | |
| — | — | （此后无 iAP2 报文） | AirPlay 全部走 Wi-Fi |

真机**从不发送**：`0x4300`、`0xEA00`、`0x4E0E`；
真机**从不接收**：`0x4301`、`0xEA03`。

### 10.4 链路存活：抓包里能证明什么、不能证明什么

时间对齐后（pklg 时钟 = 本地时间当 UTC）：

- iAP2 交换发生在抓取结束前约 34 秒；
- **这 34 秒内没有任何 `DisconnectionComplete`**，且最后一条记录正好是
  sysdiagnose 抓取结束的时刻 → **日志结束是抓取结束，不是链路断开**；
- 这 34 秒里真机**一条 iAP2 报文都没发**（连电源更新/keep-alive 都没有）。

由此可得的下限结论：**拿完 0x5703 之后至少 34 秒，真机不会主动拆 iAP2**，
且保活**不需要周期性发消息**。至于更长时间是否拆，这份抓包无法回答（需要一次完整会话的 sysdiagnose）。

> 反例检查：若"车机在 34 秒后自动关机"，ACL 必然先断，日志里应有
> `DisconnectionComplete`——实际一条都没有，故该解释不成立。

### 10.5 由对照得出的实现决策

| 行为 | 旧实现 | 决策 | 依据 |
|---|---|---|---|
| `0x4300` CarPlayAvailability | 无条件发送 | **仅当车机声明可收才发**（MGU-55 声明里没有 → 不发） | 真机从不发；硬发可能把车机推进"会话已请求"态 |
| `0xEA00` EA 会话 | 逐条开 8 个 | **默认关闭**（`ENABLE_EA_SESSIONS=false`） | 真机一条不发，车机也从不回 `0xEA03` |
| `0x4E0E` 传输标识 | 发送 | **默认关闭**（`SEND_TRANSPORT_IDENTIFIER=false`） | 真机不发 |
| `0xAE01` PowerUpdate | **从不回** | **收到 `0xAE00` 立即回 3 条**（原样重放真机字节） | 真机必回；参数语义未定谳，故只重放不解释 |
| AirPlay 失败后的 iAP2 | 立即拆除（~5s） | **原地重试 6×2.5s，不拆链路** | 真机 ≥34s 不拆；拆除会让车机收起 CarPlay 图标 |
| AirPlay 端点端口 | 写死 7000 | **mDNS `_airplay._tcp` 优先，失败退 7000/7001/7100** | 车机确实广播该服务（unified log 可见）；写死 7000 收到 `455` |



## 11. 2026-10-25 现场：CarPlay 图标消失（"车机不把手机当 CarPlay 设备"）

日志 `hypercar-log-20261025-114131.txt`（约 10 分钟）：ACL 反复连上/断开，但
**一次 iAP2 建链都没有**——没有 `BT-S: 车机已连入`、没有 `LINK tx/rx`、没有
`0x1d01`；出站 `BT-C: 连接 BMW 53636 失败` 每 3s 一次。用户现场描述更关键：
**车机的 CarPlay 图标消失**，手机在 iDrive 里只剩"电话/语音"，被当成普通蓝牙设备；
而 **0.27 版本图标是出现的**。图标不出现，车机就不会主动来连 iAP2，形成死锁。

### 11.1 版本 A/B：0.27 与之后的差别

| 版本（提交） | 会话里发什么 | 链路行为 | 现场结果 |
|---|---|---|---|
| 0.27（`d69ab5a`） | `0x4300` + `0xEA00`（7 条 EA）+ `0x4E0E` + 问候五连（含 `0x4E0D`） | **保活不拆链** | **CarPlay 图标出现** |
| 0.28（`d44c81e`） | 同上 | **档案 A 2.5s 主动结束会话** | 图标消失风险（§10.5：拆链会让车机收起图标） |
| 0.29（`a194910`） | **停发 `0x4300`/`0xEA00`/`0x4E0E`**（按真 iPhone HCI 对照） | 同上 | 车机把手机降级成"只有电话/语音的普通蓝牙设备" |
| 0.31+（`c00e6df`/`0d2aa4d`） | 同上 + 档案化 | 端点 300s 超时后 `fail()` 拆链 | 10 分钟 0 次 iAP2 发起（本日志） |

结论：**图标是被我们自己弄丢的**，两条独立原因叠加：

1. **停发 `0x4300`（与 `0x4E0E`）**。0x4300 的载荷就是"无线组件 + 本机蓝牙传输标识"，
   车机靠它把"CarPlay 设备"与已配对的那台手机对上。真 iPhone 的 HCI 里看不到它，
   并不等于车机不需要它（抓包那次车机已经认识这台 iPhone）——现场 A/B 比抓包缺失更强。
2. **主动拆 iAP2**（档案 A 快退 + 端点超时 `fail()`）。§10.5 已经写明"拆除会让车机
   收起 CarPlay 图标"，这两处正好在拆。

### 11.2 处理（已实现）

- `HeadUnitProfile` 新增两个字段，宝马档案取 0.27 值：
  - `sendCarPlayAvailability = true`（并**不再看可收清单**——清单只能证明"有"）、
  - `sendTransportIdentifier = true`、`openEaSessions = true`；
  - `abortWhenCarPlayWindowClosed = false`：档案 A 只记日志、**不结束会话**；
  - `dropProfilesBeforeIap2 = false`：不主动断车机 HFP/A2DP。
- 等端点超时不再 `fail()` 拆 iAP2，改为提示 + 继续下一轮等（直到用户/ACL 断开）。
- 「空转轮次」允许被车机的新连入顶替（`sessionProductive`）：既不拆链，也不把车机
  下一轮（可能是档案 B）挡在 `sessionActive` 门外。
- iAP2 身份一律用真实 MAC（`force=true`）：0x4300/0x4E0E 里的传输标识必须与已配对
  手机一致，伪 MAC 会让车机对不上任何设备；伪 MAC 时打显式警报。
- iAP2 目标锁定为本轮 ACL 连上的车机，不再去拨 HI-BOX 旧 dongle；`sessionStartCount`
  为 0 时日志直接指出"车机没发起 iAP2（车机侧状态）"。
- **与车机档案配置的冲突（同步修掉）**：`profiles/headunits.json` 里宝马条目原本写着
  `sendCarPlayAvailability/openEaSessions/sendTransportIdentifier = false`，而
  `RemoteProfiles.merge` 是"远程同 id 覆盖内置"——**设备上缓存的旧 JSON 会把上面的修复
  原样顶回去**，图标继续不出现。另外新加的两个档案字段没进 `readProfile/encode`，
  远程条目会拿到数据类默认值（`dropProfilesBeforeIap2=true`，正好是宝马的反例）。
  三处一起收口：① JSON 升 `_schema: 2` 并改回正确值；② 解析默认值改为「同 id 内置档案」；
  ③ `_schema < RemoteProfiles.SCHEMA(2)` 的远程条目**只能新增车型、不能覆盖内置**
  （要修内置必须显式升 `_schema`）。运行期还会打一行 `PROFILE: <id>（<label>）…` 自述
  当前档案与关键开关，避免再靠日志反推。

### 11.3 恢复步骤（必须做，代码只能保证不再丢）

图标消失后车机不会主动连 iAP2，我们也发不出 0x4300，所以**需要一次重新配对**让车机
重新评估：

1. 装新包，保持 App 运行（监听器在，SDP 里才有 iAP2 服务）；
2. iDrive →「移动设备」→ 删除本机；手机蓝牙设置 → 忽略 BMW；
3. 重新配对，**车机弹提示时选 Apple CarPlay**；
4. 看日志应出现：`AUTO: 本轮 iAP2 目标锁定…` → `BT-S: 车机已连入` → `iap2 rx=0x1d01`
   （`档案=A/B`）→ `iap2 tx=0x4300 CarPlayAvailability（…mac=真实MAC）`；
5. 图标应恢复；之后会话即使停在档案 A 也不会再拆链，图标不会再被我们弄丢。

若重新配对后仍然 0 次 iAP2：先用真 iPhone 在该车上确认车机 CarPlay 本身正常（排除
车机侧被其它设置关掉），再导出日志看第 4 步停在哪里。

## 12. 2026-10-25 车友日志：卡在 AirPlay `/info` 455（BMW 17713 / MGU-86）

日志 `hypercar-log-20261025-105328.txt`（Xiaomi 23127PN0CC / Android 16，0.32 版）。
**这次不是"图标不出现"**——整条链路都通了，只差 AirPlay 第一步：

| 阶段 | 实测 | 结论 |
|---|---|---|
| iAP2 识别 | 16 项清单、含 `0x5703`/`0xaa06`、EA 有 `com.bmw.carplay`、有 WirelessCarPlay 组件 | **档案 B**，车机很配合 |
| 0x5702 → 0x5703 | 600ms 内回 `DIRECT-BMW 17713` ch157 | 正常 |
| 入网 | 342ms 静默加入（172.16.222.105/20，**gw=0.0.0.0** 的 Direct AP） | 正常（网关兜底对 Direct AP 无效，靠邀请 IP） |
| ctrl 邀请 | 车机 172.16.222.97 `GET /ctrl-int/1/connect`（2s 后） | 正常 |
| 端口 | 7000 开（12ms），7001/7100 关；`_airplay._tcp` PTR/SRV/A 全 0 | 车机不广播 mDNS |
| **AirPlay `/info`** | `GET`/`POST`/`GET` 三次全 **`455 Method Not Valid In This State`**（`server=AirTunes/450.14`） | **卡点**，我们把它当致命错误退出引擎 |
| 6 次重试 | 每轮都是同样三条 455 | 全部无效 |
| 车机行为 | 邀请后 **21s**（10:52:55 → 10:53:16）拆 RFCOMM；重连后给**档案 A** | 窗口很短 |

时间账：`0x5703`(10:52:52.8) → 拆链(10:53:16) 共 **23.3s**；我们从 0x5703 到连上 7000
花了 **8.4s**（其中 **3.1s** 白等 5s tick 才把邀请变成端点、**3s** 白查 mDNS），
只剩 ~15s 给 AirPlay 协商——而 `/info` 一次就 3 连败。

### 12.1 修了什么

1. **Rust `/info` 改 best-effort**（`airplay.rs`）：455/403 只记日志，**继续
   pair-setup → pair-verify**，配对成功后再补查一次 `/info`；仍拿不到就用设置里的
   分辨率与默认能力跑 SETUP。依据：cardeck 真车记录「车机对**未配对连接**上的 /info
   应答 455」——正确顺序是先配对再查，/info 只是"增强"（拿显示尺寸/features）。
2. **邀请不再等 5s tick**：`ENDPOINT_TICK_MILLIS` 5s → 1s（邀请→端点最坏 1s）。
3. **邀请来的端点跳过 mDNS**：`CarPlayEndpoint.fromInvite`，地址/端口是车机亲口给的
   （这台车不广播 `_airplay._tcp`，browse 只会白花 3s）。
4. 档案 A 不再主动退场（本日志那一版还是旧行为：重连后 2.5s 就把会话结束）。

改完「凭据 → 连上 7000」的理论耗时从 8.4s 降到 **~2s**，把 23s 窗口基本都留给 AirPlay。

### 12.2 仍未验证 / 下一步

- **pair-verify 能否通过**：`/info` 455 之后我们没走到过配对，下一轮日志看
  `PAIR: pair-setup …` / `pair-verify …` 与 `SETUP` 的状态码即可定谳。
- 若 pair-verify 也回 455/403：真机成功路径是 **IPv6 link-local:7000**（cardeck 两车
  实录），而我们目前只连邀请里的 IPv4。下一轮可把「邀请 IPv4」与「该接口的 IPv6
  link-local」作为双候选逐个试（`::1` 之类不能算，必须从 `boundNetwork` 的 LinkProperties 取）。
- 这台车的 Direct AP 没有默认网关（gw=0.0.0.0），所以「网关兜底」对它天然无效——
  端点必须来自 ctrl 邀请（或 0x4301，但该车声明不发）。

## 13. 2026-10-25 现场：宝马 DIRECT 热点**没有网关**，端点发现三条路全断

日志（app 0.1.0.x，本地构建）：两次连接都是同一死法，iAP2 侧一切正常。

```
0x5703 ssid=DIRECT-BMW 53636 psk=***        ← 只有凭据，没有 IP
0x4301 未到（车机声明「不发」）
WIFI +9124ms ✓ 已绑定：ipv4=172.16.222.105/20 gw=0.0.0.0 dns=[] validated=false
SESSION: 端点缺 IP —— 继续等 0x4301
仍在等车机地址（port=0 ips=[]，已等 30s）
LINK closed: bt socket closed, read return: -1   ← 车机 30s 后自己拆 iAP2
```

### 13.1 为什么三条兜底同时失效

| 兜底路径 | 失效原因 |
|---|---|
| 车机 ctrl 邀请（`lastInvitePeerIp`） | 车机从未连我们的 `_carplay-ctrl` 端口（日志只有「已监听/注册成功」） |
| 网关当车机（`probeGatewayAsCarHotspot`） | **宝马 DIRECT AP 不回默认路由的网关**：`gw=0.0.0.0` → 取不到 `defaultRoute.gateway` 就 `continue`，连探测都没发生 |
| mDNS `_airplay._tcp` | 只在**端点确定之后**才调用（`openControlFd` 之前那一段）；等端点时正好没查，形成死锁 |

车机侧的行为是明确的：给完凭据后等 30s，手机不来 AirPlay 就拆 RFCOMM。

### 13.2 修法：入网后立刻"主动找"

在 `awaitUsableEndpoint` 的等待循环里，凡是「当前 SSID == 0x5703 给的热点」且还没试过，就按顺序做：

1. **mDNS**：在该 `Network` 上 browse `_airplay._tcp`（车机若广播，这一步就定端点）；
2. **本网段扫描**（网关为空时的唯一出路）：
   - `/proc/net/arp` 里已出现的邻居（车机回过包就一定在）；
   - 自己 /24 的 `.1`/`.2`/`.254`；
   - 掩码 < /24 时（宝马 /20）每个 /24 的 `.1`/`.254`，上限 48 个；
   - 并行 TCP 探测 `7000/7001/7100`，单个 250ms，命中即合成端点交给后续 pair-verify。

日志会明确打出命中或落空：

```
WIFI: 网关为空——在 172.16.222/20 扫 AirPlay（候选 48 个地址 × 端口 [7000, 7001, 7100]）
WIFI: ✓ 网段扫描命中车机 AirPlay 端点 172.16.x.x:7000（420ms）
```

> 教训：**不要假设热点一定有网关**。iPhone 之所以能连上，是因为它用 Bonjour 找到 AP 里的
> `_airplay._tcp`（或直接用保存的地址），而不是"网关 + 7000"。我们之前把"网关"当成等价物，
> 在这台车上等于零候选。

## 14. 2026-10-25 对照：宝马专用 CarPlay 盒子连 WinPlay 的真实交互

手上有一台 RK3528A 的「宝马专用 CarPlay 盒子」（伪装成 iPhone：插电脑枚举为
`VID_05AC&PID_12A8`，MTP 设备，**无 ADB**）。它除了连车机，也能直接推给 WinPlay，
所以我们在 PC 侧用系统自带 `pktmon` 抓了一次（`tools/capture-airplay.ps1`）。
**这是"能用的发送端"长什么样的第一份实证。**

### 14.1 抓到的关键事实

| 观察 | 数据 |
|---|---|
| 盒子 IP / 主机名 | `192.168.137.188`（PC 的移动热点网段），mDNS 自报 `rk3528-box.local` |
| 接收端 | `192.168.137.1:17000`（WinPlay，热点网关） |
| 控制面 | **`POST /pair-verify RTSP/1.0`**，头 `X-Apple-HKP: 2`、`X-Apple-PD: 1`、`User-Agent: AirPlay…`；WinPlay 回 `RTSP/1.0 200 OK` + `Content-Type: application/pairing+tlv8` |
| 是否 pair-setup | **完全没有**（只做 verify —— 说明它把配对密钥持久化了，或者接收端不校验） |
| 重试 | pair-verify 连发 **4 次**（前几次被拒也会继续，不放弃） |
| 数据面 | 盒子**主动连**接收端另外两个端口 `50768`/`50769`（RTSP 里协商出来的），视频走 TCP `50769`，120s 内 1.28 MB |
| 保活 | 与 `137.1:60165` 之间 32 字节 UDP 心跳（双向，~1s 一次） |
| 发现方式 | 本次窗口内**没有** `_airplay._tcp` 查询（盒子只查了自己的 `rk3528-box.local` 和 `_zj_mirror._tcp`）——抓到的是**已经在连**的会话，端点来自上一次配对/配置，不是本次发现 |

### 14.2 对我们的意义

1. **它也走 pair-verify**（和我们一样），并且**没有 pair-setup 也能成功**——说明
   "每次重建密钥"这条路可行，不必依赖持久身份。
2. **它重试 4 次**才成功；我们目前 pair-verify 失败就 fail。BMW 上可以照抄这个"重试"策略。
3. 数据面端口是**接收端在 RTSP 里给的**（`50768/50769` 这种临时端口），不是固定的
   7000/7100——这正是我们 `fallbackAirplayPorts` 思路的补充：**端口要听对方的**。
4. 关键结论：**本次抓包没能回答"盒子怎么找到车机"**，因为抓的是已连接会话。
   要回答那个问题，必须：抓包开始 → 再让盒子连接；或者抓盒子 ↔ 车机那一段（需 monitor 模式）。

### 14.3 第二次抓包（先抓包再让盒子连）：它**不做发现**

`-BoxIp 192.168.137.188` 重抓一次「抓包开始 → 盒子重新连」，结果非常干净：

```
+10.63s  盒子 192.168.137.188:50800 -> 192.168.137.1:17000  SYN        ← 直接连记住的地址
+10.75s  盒子 192.168.137.188:55772 -> 192.168.137.1:49860  SYN        ← 数据通道（接收端在 RTSP 里给的）
+67.72s  盒子 -> 224.0.0.251:5353  QR=1 通告 rk3528-box.local          ← 连上之后才发 mDNS 通告
```

**整个连接过程没有任何 mDNS 查询**（0 条 `_airplay._tcp` / `_ravi` / `_raop` 询问），
也没有广播发现；它**直接拨记忆里的 `IP:17000`**，连上之后才把自己以 `rk3528-box.local` 通告出去。

> 这条比 §14.1 更重要：它说明「稳定连上」的关键不是更强的发现算法，而是**记住端点**。

### 14.4 由此落地的实现（0.1.0.56）

`SettingsStore.lastAirplayEndpoint`（格式 `host:port|ssid`）：

- **成功即记**：控制端口连通那一刻就写盘（`$apHost:$port|当前SSID`）；
- **下次优先**：`awaitUsableEndpoint` 开头先读它，**SSID 一致**且 600ms 内 TCP 可连就直接用，
  完全跳过 `0x5703/0x4301/mDNS/扫网段`；
- **失败回退**：连不上（换网段/车机换地址）就打一行日志，回到原来的发现流程，不影响首次配对。

日志：

```
SESSION: ✓ 上次成功的端点 172.16.x.x:7000 仍然可连——直接用（跳过发现）
SESSION: 上次的端点 172.16.x.x:7000 连不上（换了网段/车机换了地址）——回退到发现流程
```

## 15. CarLife（com.baidu.carlife.xiaomi）实测拓扑与卡点（2026-10-26）

### 15.1 谁监听、谁连接——**CarLife 是服务端，车机/盒子是客户端**

盒子正常出画面时的实测（`ss -lntu` / `ss -ntu`，手机 10.242.24.159，盒子 10.242.24.214）：

```
LISTEN  *:7240  *:8240  *:9240  *:9241  *:9340          ← CarLife 在监听
ESTAB   10.242.24.159:7240 ← 10.242.24.214:56734        ← 盒子连进来
ESTAB   10.242.24.159:8240 ← 10.242.24.214:38280
ESTAB   10.242.24.159:9240 ← 10.242.24.214:36434
ESTAB   10.242.24.159:9340 ← 10.242.24.214:36386
```

所以 `CarLifeHub::connect(md_host)`（我们当 HU 去连 CarLife）方向是对的，
`CARLIFE_BIND` 的 HU 监听模式只适用于"CarLife 反过来连我们"的实现，不是小米这套的形态。

### 15.2 CarWith 在**副屏**上跑

```
input_focus: [displayId :10, Focus receive :… com.miui.carlink/com.carwith.launcher.home.HomeActivity]
```

车机画面来自一块**虚拟显示器**（display 10），不是手机主屏。这解释了"为什么必须让
CarLife 处于它自己的工作状态"：只有 CarWith 的会话真正建立后，那块虚拟显示器才存在、
才有内容可编码。

### 15.3 两种拉起方式的行为差异

| 拉起方式 | 7240 | Phase 1~7 | 存活 | 8240 视频帧 |
|---|---|---|---|---|
| `am start -n com.baidu.carlife.xiaomi/com.baidu.carlife.CarlifeActivity` | ✅ | ✅ 全过 | ❌ 第 34~35 秒 `ProcessUtil.killAllProcess` 自杀 | ❌ 一帧都没有 |
| `am start-foreground-service -n …/com.baidu.carlife.service.CarlifeConnectService` | ✅ | ❌ 卡在 Phase 1（不回版本匹配） | ✅ 45 秒+ | ❌ 一帧都没有 |

自杀现场（两次间隔都是 34~35 秒，非常稳定）：

```
Process(12204): at com.baidu.carlife.core.utils.ProcessUtil.killAllProcess(SearchBox:105)
ActivityManager: Process com.baidu.carlife.xiaomi (pid 12204) has died: fg
ActivityManager: Force stopping com.baidu.carlife.xiaomi … from process:com.miui.carlink
```

`CARLIFE: VIDEO 通道断开，1s 后重连` 说明**我们连上 8240 后立刻被对端关闭**，
这是"收不到视频"的直接原因；而 CarLife 的编码器（`MediaCodec [video-debug-enc] … STARTED`）
在同一时期确实在跑，说明编码在发生、但帧没发给我们这条连接。

### 15.4 待验证的两个假设

1. **会话状态假设**：CarLife 只有在自己判定"已连接车机"（CarWith 状态机）后才把虚拟显示器
   的画面编码并发给 HU。我们只做了 TCP 层的 Phase 1~7，没触发它的会话状态。
2. **握手内容假设**：盒子的 `HU_FEATURE_CONFIG_RESPONSE` 里带了视频相关字段
   （码率/GOP/分辨率/编码类型），我们发的不全 → CarLife 认为 HU 不需要视频。

判定方法：抓一次盒子与手机之间的 7240/8240 报文（盒子为客户端，手机为服务端）。
非 root 可在手机上用 VpnService 抓本地回环不成立（流量走 WiFi），
可行路径是让盒子的流量经过一台可控的中间设备，或拿盒子的调试口。

## 16. 与官方 CarLife 车机端源码逐项对照（2026-10-26，镜像 fgrcwp/apollo-DuerOS）

`ApolloAuto/apollo-DuerOS` 在 GitHub 上已下架，社区镜像 **`fgrcwp/apollo-DuerOS`** 仍在，
里面有完整的车机端 C++ 库（`CarLife-Vehicle-Lib/LibSource`）+ protobuf 定义。逐项对照结果：

| 项 | 官方实现 | 我们的实现 | 结论 |
|---|---|---|---|
| CMD/CTRL 帧头 | 8B：`[u16 BE len][2B 保留][u32 BE type]`（`packageHeadAnalysis`） | `read_cmd_frame` 同布局 | 一致 |
| VIDEO/MEDIA/TTS 帧头 | **12B**：`[u32 BE len][u32 BE ts][u32 BE type]` | `read_media_frame` 同布局 | 一致 |
| `VIDEO_ENCODER_INIT` | protobuf `CarlifeVideoEncoderInfo{1:width,2:height,3:frameRate}` | 同字段号 | 一致 |
| 握手顺序 | Phase1 版本 → 1.5 统计 → 2 设备信息 → 3 特性配置 → 4 鉴权 → 6 编码器 INIT → **等 INIT_DONE** → 7 START | 完全一致 | 一致 |
| FeatureConfig | `CarlifeFeatureConfigList{cnt, repeated{key,value}}`，共 9 个键：语音唤醒/麦克风/焦点 UI/媒体采样率/蓝牙自动配对/蓝牙内部 UI/音频传输模式/内容加密/引擎类型 | 未发/不全 | **与视频无关**，不影响 |
| 连接顺序 | `connectionSetup()`：CMD → VIDEO → MEDIA → TTS → VR → CTRL，**握手之前全部建好** | 0.1.0.107 起一致 | 一致 |
| 视频通道方向 | HU 只收（`readVideoData`），不往 VIDEO 通道发任何东西 | 一致 | 一致 |

### 16.1 仍未解决的点：CarLife 编码器启动了，但 8240 上一个字节都没有

0.1.0.107 实测（`hypercar-log-20261026-013703.txt` + logcat）：

```
01:36:43.212  ✓ VIDEO 已连上 MD 127.0.0.1:8240        ← 握手前建好，且不再被关
01:36:43.364  [Phase 7] → VIDEO_ENCODER_START
01:36:43.760  MediaCodec(6566): [video-debug-enc] setState: STARTING   ← CarLife 编码器真的启动了
01:36:43.773  [avcE_599] KPI_L1: start: ms=12
01:37:08.208  MediaCodec(6566): [video-debug-enc] stop                ← 约 4.4s 后停
（期间我们侧没有任何 `VIDEO 读到`）
```

也就是说：**握手对、连接稳、编码器确实在编码，但帧没有写到我们这条 socket 上。**

### 16.2 剩余假设

1. **写目标不是我们的连接**：CarLife 可能把帧写到它另外持有的一条连接（例如它自己 accept 的第一个），
   而我们的连接只是"挂着"。可试：在 START 之后**再开一条** 8240 连接，两条同时读，看哪条有数据。
2. **需要 CarWith 的会话状态**：CarLife 用虚拟显示器（logcat 里
   `isVirtualDisplayActive caller=com.android.shell target=com.baidu.carlife.xiaomi`）出画面，
   可能只有 CarWith 认为"已连接车机"时才把虚拟显示器的内容送去 HU。
3. **CTRL 通道遗漏**：`CCtrlChannelModule` 里 HU 只发触摸/硬按键，没有"开始视频"的命令；
   但 MD→HU 方向可能有 `video focus` 之类的通知需要我们回应。

### 16.3 下一步优先级

1. 双连接实验（成本最低，改 10 行）：START 后再连一条 8240，两条都读并各自打日志
2. 抓 CarLife 的 xlog（`/sdcard/Android/data/com.baidu.carlife.xiaomi/files/xLog/carlife_YYYYMMDD.xlog`）
   —— 目前是加密的，若能在 CarLife 设置里打开明文日志，就能看到它把视频写到哪
3. 手机 root 后 `tcpdump -i any port 8240`，直接看有没有字节从 CarLife 发出

### 16.4 双连接实验结果（0.1.0.108，2026-10-26 01:41）

```
01:41:05.252  ✓ VIDEO#0 已连上 MD 127.0.0.1:8240
01:41:05.252  ✓ VIDEO#1 已连上 MD 127.0.0.1:8240     ← 同时开两条都被接受
01:41:05.423  [Phase 7] → VIDEO_ENCODER_START
01:41:07.906  VIDEO#0 通道断开，1500ms 后重连          ← CarLife 约 2.6s 后关掉多余的一条
（VIDEO#1 保持连接，但同样一个字节都没有）
```

结论：

1. CarLife **允许多条 8240 连接**，但只保留一条（单消费者语义），说明"写目标"就是它保留的那条；
2. 无论保留哪条，**都没有数据**——所以不是"连错连接"的问题；
3. 帧在 CarLife 内部就没有被投递到 HU 侧的视频 socket。

下一处可疑点：手机上有 CarWith ↔ CarLife 的本地 IPC（实测 `127.0.0.1:57999 ↔ 34562` 等多条 ESTAB）。
CarLife 可能把画面送进"CarWith 会话"的那条通路，而不是直连 HU 的 8240。
`/sdcard/Android/data/com.baidu.carlife.xiaomi/files/xLog/*.xlog` 是加密的，
要看清只能 root 后 `tcpdump -i any port 8240 or port 57999`。

## 17. 拉起 CarLife 但不想占主屏（2026-10-26）

### 17.1 「无界面服务」启动不可用（已实测）

`am start-foreground-service -n com.baidu.carlife.xiaomi/com.baidu.carlife.service.CarlifeConnectService`
能让 7240/8240 起来、编码器也 `STARTED`，但 CarLife **不回 Phase 1**（版本匹配），握手永远
停在第一步，8240 一帧没有（见 §15.3 的表与 100101 现场）。根因是它的会话/编码状态绑定在
`CarlifeActivity` 上，Activity 不在"可见前台"时：

- 它自带的空闲看门狗 34~35s 必杀进程（`ProcessUtil.killAllProcess`）；
- 视频帧不会投递给 HU 的 8240 连接。

所以"完全不显示窗口"在**非 root** 条件下没有干净方案。

### 17.2 已加：把 Activity 投到副屏（`am start --display N`）

设置页「CarLife 启动屏（-1 = 手机主屏）」：

- `-1`（默认）：维持现状，窗口显示在手机主屏；
- `>=0`：`am start -n <CarLifeActivity> --display N`，把 Activity 投到副屏/虚拟屏。
  只有设备上**真的存在**这块屏（如 CarWith 的 display 10）才有效；`am start` 失败或 3s 内
  7240 没起来时，会自动回退主屏重试，不会把 CarLife 弄没。
- 开启该开关时，唤醒日志会先打印 `WAKE: 显示设备 …`（`dumpsys display` 摘要），
  用来确认填哪个 id。
- **不用连电脑查**：诊断页新增「显示器」按钮 → `HyperCarRepo.dumpDisplays()` 把
  `dumpsys display` 的 display id / 名称 / 尺寸打进日志（`DISP: …`），照着填设置里的启动屏即可。

判定成功的标准：日志出现 `WAKE: 拉起 CarLife 引擎页（display=N）` 后 7240 在监听、
`CARLIFE: [Phase 1] ← 版本匹配` 正常推进，且**手机主屏不再被 CarLife 占据**。

### 17.3 为什么不建议的其他做法

- **分屏 / 画中画**：窗口还在，只是变小；CarLife 不支持 PiP 时直接无效。
- **亮度 0 / 息屏保活**：Activity 仍算前台，但手机等于不可用（驾驶时取导航还得恢复亮度）。
- **禁用看门狗**：需要改 CarLife APK（LSPatch/Xposed）或 root，超出"装个 App 就能用"的范围。
