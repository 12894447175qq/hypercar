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

