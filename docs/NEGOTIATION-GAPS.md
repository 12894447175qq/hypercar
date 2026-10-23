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
| CarLife 端口 | 监听 7240~7244 连续端口 | 标准车机端口是 7240/8240/9240/9241/9242/9340/9440（发送端按此连接） | 监听标准端口 + 7241~7244 回退双布局（`carlife.rs`） |

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

## 5. CarLife 消息层：参考文档的缺失与不符

| 项 | 参考文档 | hypercar（实测口径） |
|---|---|---|
| 握手 | 无（直接发 encoder init） | 版本协商 0x18001→0x10002、鉴权 0x18004→0x1000B（放行）、心跳 0x20002；握手完成后才下发 0x10007/0x10008（否则老 app 可能不接受编码参数） |
| 触摸消息 ID | 0x00040001 | **0x68001**（425985 实测）；硬按键 **0x68008**（425992） |
| 音频初始化 | 解析 0x00020001 的 protobuf rate/ch | 一致；补充 TTS 域 0x00030001 与停止消息 0x00020002/0x00020004 的缓冲清理 |
| 通道健壮性 | 单次 accept、单客户端 | 每通道单客户端但新连接顶替旧连接（CarLife app 重连场景）；缓冲上限防 OOM |
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

## 7. 参考文档中直接可用的部分

- FD 移交模型（`ParcelFileDescriptor.detachFd` → `from_raw_fd` → tokio）——保留，
  范围扩大到「协商用的控制 FD + 按需新开的所有端口」。
- iAP2 0x5200 HUD 导航报文构造器（TLV 布局）——保留（`iap2_nav.rs`）。
- 引擎生命周期（全局 tokio runtime、Notify 停止、JNI 常量表）——保留。
- Kotlin 薄壳的「蓝牙触发唤醒 → 绑网 → 移交 FD → 唤醒本机 CarLife」时序——保留并
  补全（Shizuku 静默入网、ACL 冷启动 FGS、目标设备过滤、冗余端点去重）。

## 8. 仍未定谳 / 需实车校准

1. **BMW iD8.5 的 HID 触摸报告格式**：cardeck 只实测了奥迪（5B `[tip][X u16LE]
   [Y u16LE]`）；BMW 描述符未抓过。hypercar 默认按奥迪格式解析并做显示空间缩放，
   首次实车若触摸漂移，在 `airplay::handle_hid_report` 按 /info hidDevices 描述符调整。
2. **BMW 的 CarLife 唤醒广播**：`com.baidu.carlife.Action.StartWireless` 对 CarLife
   官方 app 有效；CarWith/荣耀车联的 CarLife 兼容组件是否监听同一 action 需实测
   （设置里可切换目标包名）。
3. **CarLife 握手的 protobuf 细节**（版本字段布局）：按 apollo-DuerOS 最小集实现
   并全程容忍应答缺失；若实车 CarLife app 不推流，用 `carlife_pc_tool` 抓一次
   真机对照补字段。
4. **0x5702 是否必须带参数**（BMW 空体接受性）——cardeck 同样未定谳，需要一次
   真 iPhone 同车抓包。
