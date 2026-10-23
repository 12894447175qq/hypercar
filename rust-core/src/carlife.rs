//! CarLife 本机回环接收端（我们扮演「车机」角色，百度 CarLife / CarWith·CarLife 兼容 /
//! 荣耀车联 作为「手机发送端」连回 127.0.0.1）。
//!
//! 协议依据（docs/CARLIFE-GATEWAY-FEASIBILITY.md + apollo-DuerOS / CarLifeWirelessBox）：
//!  - WiFi/回环模式：发送端 app 主动连「车机 IP:端口」纯 TCP 直连。我们监听标准
//!    车机端口：7240 CMD、8240 VIDEO、9240 MEDIA、9241 TTS、9242 VR、9340 TOUCH、
//!    9440 CTRL；为兼容旧版监听习惯同时提供 7241/7242/7243/7244 回退。
//!  - 消息帧：[4B BE 长度][4B BE 时间戳][4B BE 消息 ID][protobuf 载荷]。
//!  - 已实测消息 ID：版本协商 0x18001→0x10002、鉴权 0x18004→0x1000B（result=0 放行）、
//!    心跳 0x20002、视频编码器初始化 0x10007/启动 0x10008、触摸 0x68001、硬按键 0x68008。
//!    （参考文档用的 0x00040001 触摸 ID 与实测不符，见 docs/NEGOTIATION-GAPS.md。）
//!  - 视频通道载荷 = H.264 Annex B 裸流（零转码的根源：直接拆 NAL 推 AirPlay）。
//!  - 媒体/导航音频：PCM LE，0x00020001/0x00030001 携带采样率/通道数（protobuf）。
//!
//! 设计：每个通道单客户端（CarLife app 只连一次）；新连接顶替旧连接。

use std::sync::Arc;

use parking_lot::Mutex;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{mpsc, Notify};

use crate::engine::CarLifeEvent;
use crate::{emit_log, emit_state};

pub const PORT_CMD: u16 = 7240;
pub const PORT_VIDEO: u16 = 8240;
pub const PORT_MEDIA: u16 = 9240;
pub const PORT_TTS: u16 = 9241;
pub const PORT_VR: u16 = 9242;
pub const PORT_TOUCH: u16 = 9340;
pub const PORT_CTRL: u16 = 9440;

/// 旧版监听端口（参考文档布局 7241~7244，作为回退）。
pub const PORT_VIDEO_LEGACY: u16 = 7241;
pub const PORT_MEDIA_LEGACY: u16 = 7242;
pub const PORT_TTS_LEGACY: u16 = 7243;
pub const PORT_TOUCH_LEGACY: u16 = 7244;

// 消息 ID（BE 头第 4 字段）
const MSG_VERSION_REQ: u32 = 0x0001_8001; // 98305  HU→MD 版本协商请求
const MSG_VERSION_RESP: u32 = 0x0001_0002; // 65538  MD→HU 版本应答
const MSG_AUTH_REQ: u32 = 0x0001_8004; // 98380  HU→MD 鉴权请求
const MSG_AUTH_RESP: u32 = 0x0001_000B; // 65611  MD→HU 鉴权应答
const MSG_HEARTBEAT: u32 = 0x0002_0002; // 131074 心跳
const MSG_VIDEO_ENCODER_INIT: u32 = 0x0001_0007; // 视频编码器初始化（下发宽高帧率）
const MSG_VIDEO_ENCODER_START: u32 = 0x0001_0008; // 视频编码器启动
const MSG_MEDIA_INIT: u32 = 0x0002_0001;
const MSG_MEDIA_STOP: u32 = 0x0002_0004; // 媒体停止（0x00020002 是心跳，勿混）
const MSG_MEDIA_DATA: u32 = 0x0002_0003;
const MSG_TTS_INIT: u32 = 0x0003_0001;
const MSG_TTS_DATA: u32 = 0x0003_0003;
const MSG_TOUCH: u32 = 0x0006_8001; // 425985 实测触摸
const MSG_HARD_KEY: u32 = 0x0006_8008; // 425992 实测硬按键

/// 触摸动作（与 CarLife 字段 1 对齐，同 RustCoreBridge 常量）。
pub const TOUCH_DOWN: u32 = 1;
pub const TOUCH_UP: u32 = 2;
pub const TOUCH_MOVE: u32 = 3;

pub struct CarLifeConfig {
    pub width: u32,
    pub height: u32,
    pub fps: u32,
}

/// CarLife 网关：监听全部通道，产出结构化事件；接受外部触摸/按键注入。
pub struct CarLifeHub {
    /// 事件出口：视频帧 / 音频块 / 客户端接入状态。
    events: mpsc::UnboundedSender<CarLifeEvent>,
    /// 触摸与硬按键出口（写触摸通道的任务消费）。
    touch_tx: mpsc::UnboundedSender<Vec<u8>>,
    cfg: CarLifeConfig,
}

impl CarLifeHub {
    pub fn start(
        cfg: CarLifeConfig,
        stop: Arc<Notify>,
    ) -> Result<(Self, mpsc::UnboundedReceiver<CarLifeEvent>), String> {
        let (events, event_rx) = mpsc::unbounded_channel();
        let (touch_tx, touch_rx) = mpsc::unbounded_channel();

        // ---- CMD 7240：握手（版本/鉴权/心跳）+ 编码器指令 ----
        let cmd_listener = bind_tcp(PORT_CMD).map_err(|e| format!("CarLife CMD 7240 绑定失败: {e}"))?;
        spawn_single_client_server(
            cmd_listener,
            stop.clone(),
            "CMD",
            {
                let events = events.clone();
                let cfg_w = cfg.width;
                let cfg_h = cfg.height;
                let cfg_fps = cfg.fps;
                let stop_c = stop.clone();
                move |stream| handle_cmd_client(stream, events.clone(), cfg_w, cfg_h, cfg_fps, stop_c.clone())
            },
        );

        // ---- VIDEO 8240（回退 7241）：H.264 Annex B 帧 ----
        spawn_port_or_legacy(
            PORT_VIDEO,
            PORT_VIDEO_LEGACY,
            "VIDEO",
            stop.clone(),
            {
                let events = events.clone();
                move |stream| handle_media_client(stream, events.clone(), MediaKind::Video)
            },
        );

        // ---- MEDIA 9240（回退 7242）：媒体 PCM ----
        spawn_port_or_legacy(
            PORT_MEDIA,
            PORT_MEDIA_LEGACY,
            "MEDIA",
            stop.clone(),
            {
                let events = events.clone();
                move |stream| handle_media_client(stream, events.clone(), MediaKind::Media)
            },
        );

        // ---- TTS 9241（回退 7243）：导航语音 PCM ----
        spawn_port_or_legacy(
            PORT_TTS,
            PORT_TTS_LEGACY,
            "TTS",
            stop.clone(),
            {
                let events = events.clone();
                move |stream| handle_media_client(stream, events.clone(), MediaKind::Tts)
            },
        );

        // ---- TOUCH 9340（回退 7244）：触摸/硬按键出向（接收端交给通道任务消费）----
        spawn_touch_channel(PORT_TOUCH, PORT_TOUCH_LEGACY, touch_rx, stop.clone());

        // ---- VR 9242 / CTRL 9440：接受并保活（语音识别/媒体信息通道，暂不消费）----
        spawn_drain(PORT_VR, "VR", stop.clone());
        spawn_drain(PORT_CTRL, "CTRL", stop.clone());

        emit_state("carlife_waiting", "CarLife 网关就绪，等待本机 app 接入");
        emit_log(&format!(
            "CARLIFE: 回环网关就绪 127.0.0.1:{}(CMD) {}(VIDEO) {}(MEDIA) {}(TTS) {}(TOUCH)",
            PORT_CMD, PORT_VIDEO, PORT_MEDIA, PORT_TTS, PORT_TOUCH
        ));

        Ok((
            Self {
                events,
                touch_tx,
                cfg,
            },
            event_rx,
        ))
    }

    /// 外部（AirPlay 事件通道）注入触摸，转发给 CarLife 触摸通道。
    pub fn inject_touch(&self, action: u32, x: u32, y: u32) {
        let _ = self.touch_tx.send(build_ctrl_frame(MSG_TOUCH, |out| {
            encode_varint_field(out, 1, action);
            encode_varint_field(out, 2, x);
            encode_varint_field(out, 3, y);
        }));
    }

    pub fn inject_hard_key(&self, code: u32) {
        let _ = self.touch_tx.send(build_ctrl_frame(MSG_HARD_KEY, |out| {
            encode_varint_field(out, 1, code);
        }));
    }

    pub fn resolution(&self) -> (u32, u32) {
        (self.cfg.width, self.cfg.height)
    }
}

// ---------------------------------------------------------------- 通道实现

fn bind_tcp(port: u16) -> Result<TcpListener, std::io::Error> {
    // 重绑竞争：上一会话刚停时端口可能还在 TIME_WAIT/旧任务手里（重连场景），
    // 短重试几次避免被迫落到 legacy 端口（CarLife app 只认标准端口）。
    let mut last_err = None;
    for attempt in 0..15 {
        match std::net::TcpListener::bind(("127.0.0.1", port)) {
            Ok(listener) => {
                listener.set_nonblocking(true)?;
                return TcpListener::from_std(listener);
            }
            Err(e) => {
                last_err = Some(e);
                std::thread::sleep(std::time::Duration::from_millis(100));
                let _ = attempt;
            }
        }
    }
    Err(last_err.unwrap())
}

/// 主端口优先、被占则回退 legacy（旧版 CarLife app 连 7241~7244 布局）。
fn spawn_port_or_legacy<F, Fut>(
    port: u16,
    legacy: u16,
    name: &'static str,
    stop: Arc<Notify>,
    handler: F,
) where
    F: Fn(TcpStream) -> Fut + Send + Sync + 'static,
    Fut: std::future::Future<Output = ()> + Send + 'static,
{
    match bind_tcp(port) {
        Ok(listener) => spawn_single_client_server(listener, stop, name, handler),
        Err(e) => {
            emit_log(&format!("CARLIFE: {name} {port} 绑定失败（{e}），尝试回退 {legacy}"));
            if let Ok(listener) = bind_tcp(legacy) {
                spawn_single_client_server(listener, stop, name, handler);
            } else {
                emit_log(&format!("CARLIFE: {name} 回退端口也失败——该通道不可用"));
            }
        }
    }
}

/// 单客户端 accept 循环：新连接顶替旧连接（CarLife app 重连场景）。
fn spawn_single_client_server<F, Fut>(
    listener: TcpListener,
    stop: Arc<Notify>,
    name: &'static str,
    handler: F,
) where
    F: Fn(TcpStream) -> Fut + Send + Sync + 'static,
    Fut: std::future::Future<Output = ()> + Send + 'static,
{
    tokio::spawn(async move {
        let handler = Arc::new(handler);
        let mut current: Option<tokio::task::JoinHandle<()>> = None;
        loop {
            tokio::select! {
                _ = stop.notified() => {
                    if let Some(h) = current { h.abort(); }
                    return;
                }
                accepted = listener.accept() => {
                    match accepted {
                        Ok((stream, _)) => {
                            let _ = stream.set_nodelay(true);
                            if let Some(h) = current.take() { h.abort(); }
                            emit_log(&format!("CARLIFE: {name} 客户端已接入"));
                            let handler = handler.clone();
                            current = Some(tokio::spawn(async move { handler(stream).await }));
                        }
                        Err(_) => return,
                    }
                }
            }
        }
    });
}

/// CMD 通道：版本/鉴权/心跳 + 连接后下发视频编码器初始化（宽/高/帧率）。
async fn handle_cmd_client(
    stream: TcpStream,
    events: mpsc::UnboundedSender<CarLifeEvent>,
    width: u32,
    height: u32,
    fps: u32,
    stop: Arc<Notify>,
) {
    // 入向消息泵（版本应答/鉴权应答/心跳）放读循环；出向握手由本函数先手。
    let (mut rd, mut wr) = stream.into_split();

    // 1) 主动发起版本协商（车机角色先手；对端若先发我们也会应答）
    let version = build_ctrl_frame(MSG_VERSION_REQ, |out| {
        encode_varint_field(out, 1, 1); // protocol major
        encode_varint_field(out, 2, 0); // protocol minor
    });
    if wr.write_all(&version).await.is_err() {
        return;
    }

    // 2) 下发视频编码器初始化 + 启动（零转码的关键：直接告诉 CarLife 我们的画布）
    let init = build_ctrl_frame(MSG_VIDEO_ENCODER_INIT, |out| {
        encode_varint_field(out, 1, width);
        encode_varint_field(out, 2, height);
        encode_varint_field(out, 3, fps);
    });
    let _ = wr.write_all(&init).await;
    let start = build_ctrl_frame(MSG_VIDEO_ENCODER_START, |_| {});
    let _ = wr.write_all(&start).await;
    emit_log(&format!(
        "CARLIFE: 已下发编码器指令 {width}x{height}@{fps}fps（消息 0x10007/0x10008）"
    ));

    // 3) 读循环：应答一切握手消息 + 回声心跳
    let mut buf: Vec<u8> = Vec::with_capacity(4096);
    let mut chunk = [0u8; 2048];
    loop {
        let n = tokio::select! {
            _ = stop.notified() => return,
            r = rd.read(&mut chunk) => match r {
                Ok(0) => return,
                Ok(n) => n,
                Err(_) => return,
            }
        };
        buf.extend_from_slice(&chunk[..n]);
        while let Some((msg_id, payload, consumed)) = read_frame(&mut buf) {
            buf.drain(..consumed);
            match msg_id {
                MSG_VERSION_REQ => {
                    // 对端也在发起版本协商：回版本应答
                    let resp = build_ctrl_frame(MSG_VERSION_RESP, |out| {
                        encode_varint_field(out, 1, 1);
                        encode_varint_field(out, 2, 0);
                    });
                    if wr.write_all(&resp).await.is_err() {
                        return;
                    }
                }
                MSG_VERSION_RESP => {
                    emit_log("CARLIFE: ✓ 版本协商完成");
                    let _ = events.send(CarLifeEvent::Handshake);
                }
                MSG_AUTH_REQ => {
                    // 鉴权：发送端 app 询问是否允许连接——一律放行（本机回环无信任边界）
                    let resp = build_ctrl_frame(MSG_AUTH_RESP, |out| {
                        encode_varint_field(out, 1, 0); // result = 0（成功）
                    });
                    if wr.write_all(&resp).await.is_err() {
                        return;
                    }
                    emit_log("CARLIFE: ✓ 鉴权放行（本机回环）");
                }
                MSG_AUTH_RESP => {
                    emit_log(&format!(
                        "CARLIFE: 鉴权应答 result={}",
                        decode_first_varint(&payload).unwrap_or(0)
                    ));
                }
                MSG_HEARTBEAT => { /* 对端心跳，链路存活即可 */ }
                _ => {
                    // 其他控制消息（媒体信息/导航状态等）暂不消费，记长度防呆
                    if payload.len() > 0 {
                        emit_log(&format!("CARLIFE: CMD 未消费消息 0x{msg_id:08x} {}B", payload.len()));
                    }
                }
            }
        }
    }
}

#[derive(Clone, Copy)]
enum MediaKind {
    Video,
    Media,
    Tts,
}

/// 视频/媒体/TTS 通道：帧头 + 载荷 → CarLifeEvent。
async fn handle_media_client(mut stream: TcpStream, events: mpsc::UnboundedSender<CarLifeEvent>, kind: MediaKind) {
    let mut buf: Vec<u8> = Vec::with_capacity(256 * 1024);
    let mut chunk = [0u8; 65536];
    let mut sample_rate: u32 = if matches!(kind, MediaKind::Tts) { 16000 } else { 44100 };
    let mut channels: u8 = if matches!(kind, MediaKind::Tts) { 1 } else { 2 };

    loop {
        match stream.read(&mut chunk).await {
            Ok(0) => {
                let _ = events.send(CarLifeEvent::ClientLeft { video: matches!(kind, MediaKind::Video) });
                return;
            }
            Ok(n) => buf.extend_from_slice(&chunk[..n]),
            Err(_) => return,
        }
        // 上限保护：异常客户端灌数据时丢弃而不是 OOM
        if buf.len() > 8 * 1024 * 1024 {
            buf.clear();
        }
        while let Some((msg_id, ts_ms, payload, consumed)) = read_frame_ts(&mut buf) {
            buf.drain(..consumed);
            match kind {
                MediaKind::Video => {
                    if !payload.is_empty() {
                        let _ = events.send(CarLifeEvent::VideoFrame {
                            pts_us: ts_ms as u64 * 1000,
                            data: payload,
                        });
                    }
                }
                MediaKind::Media | MediaKind::Tts => {
                    let tts = matches!(kind, MediaKind::Tts);
                    match msg_id {
                        MSG_MEDIA_INIT | MSG_TTS_INIT => {
                            let (r, c) = parse_audio_init(&payload);
                            if r > 0 {
                                sample_rate = r;
                            }
                            if (1..=2).contains(&c) {
                                channels = c as u8;
                            }
                            emit_log(&format!(
                                "CARLIFE: {} 音频初始化 {sample_rate}Hz {channels}ch",
                                if tts { "TTS" } else { "MEDIA" }
                            ));
                            let _ = events.send(CarLifeEvent::AudioInit {
                                tts,
                                rate: sample_rate,
                                channels,
                            });
                        }
                        MSG_MEDIA_STOP => {
                            let _ = events.send(CarLifeEvent::AudioStop { tts });
                        }
                        MSG_MEDIA_DATA | MSG_TTS_DATA => {
                            if !payload.is_empty() {
                                let _ = events.send(CarLifeEvent::AudioData {
                                    tts,
                                    rate: sample_rate,
                                    channels,
                                    data: payload,
                                });
                            }
                        }
                        _ => {}
                    }
                }
            }
        }
    }
}

/// 触摸通道：消费注入的触摸/硬按键帧并写出（持有 Receiver，与 hub 的 Sender 配对）。
fn spawn_touch_channel(
    port: u16,
    legacy: u16,
    mut touch_rx: mpsc::UnboundedReceiver<Vec<u8>>,
    stop: Arc<Notify>,
) {
    // 触摸通道的接收端是「写者」：CarLife app 连上来后我们才有写目标。
    // 这里用一个共享队列承接注入帧，客户端在线时实时刷出。
    let queue: Arc<Mutex<Vec<Vec<u8>>>> = Arc::new(Mutex::new(Vec::new()));
    // 桥接：hub.touch_tx 发来的帧先进 queue（接收端在此消费）。
    {
        let queue = queue.clone();
        tokio::spawn(async move {
            loop {
                tokio::select! {
                    _ = stop.notified() => return,
                    frame = touch_rx.recv() => {
                        match frame {
                            Some(f) => {
                                let mut q = queue.lock();
                                if q.len() > 256 { q.remove(0); } // 防积压
                                q.push(f);
                            }
                            None => return,
                        }
                    }
                }
            }
        });
    }

    let listener = match bind_tcp(port).or_else(|_| bind_tcp(legacy)) {
        Ok(l) => l,
        Err(e) => {
            emit_log(&format!("CARLIFE: TOUCH 端口绑定失败: {e}"));
            return;
        }
    };
    tokio::spawn(async move {
        loop {
            tokio::select! {
                _ = stop.notified() => return,
                accepted = listener.accept() => {
                    match accepted {
                        Ok((mut stream, _)) => {
                            let _ = stream.set_nodelay(true);
                            emit_log("CARLIFE: ✓ 触摸通道已接入（车机 HID 回传就绪）");
                            let queue = queue.clone();
                            loop {
                                let frame = {
                                    let mut q = queue.lock();
                                    if q.is_empty() { None } else { Some(q.remove(0)) }
                                };
                                match frame {
                                    Some(f) => {
                                        if stream.write_all(&f).await.is_err() { break; }
                                    }
                                    None => {
                                        // 无注入帧时短睡，避免忙轮
                                        tokio::select! {
                                            _ = stop.notified() => return,
                                            _ = tokio::time::sleep(std::time::Duration::from_millis(8)) => {}
                                        }
                                    }
                                }
                            }
                        }
                        Err(_) => return,
                    }
                }
            }
        }
    });
}

/// VR/CTRL：接受连接并持续读空（保活），不处理内容。
fn spawn_drain(port: u16, name: &'static str, stop: Arc<Notify>) {
    let listener = match bind_tcp(port) {
        Ok(l) => l,
        Err(_) => return,
    };
    tokio::spawn(async move {
        loop {
            tokio::select! {
                _ = stop.notified() => return,
                accepted = listener.accept() => {
                    if let Ok((mut stream, _)) = accepted {
                        emit_log(&format!("CARLIFE: {name} 通道接入"));
                        let mut buf = [0u8; 2048];
                        loop {
                            tokio::select! {
                                _ = stop.notified() => return,
                                r = stream.read(&mut buf) => {
                                    if matches!(r, Ok(0) | Err(_)) { break; }
                                }
                            }
                        }
                    }
                }
            }
        }
    });
}

// ---------------------------------------------------------------- 帧编解码

/// 读一帧（无时间戳版）：返回 (msg_id, payload, 消费字节数)。
fn read_frame(buf: &[u8]) -> Option<(u32, Vec<u8>, usize)> {
    let (id, _ts, payload, consumed) = read_frame_ts(buf)?;
    Some((id, payload, consumed))
}

/// 读一帧：[4B BE len][4B BE ts][4B BE msgId][payload]；len = ts+msgId+payload 的总长。
fn read_frame_ts(buf: &[u8]) -> Option<(u32, u32, Vec<u8>, usize)> {
    if buf.len() < 12 {
        return None;
    }
    let len = u32::from_be_bytes([buf[0], buf[1], buf[2], buf[3]]) as usize;
    if len < 8 || len > 4 * 1024 * 1024 {
        // 非法长度：无法安全重同步，丢弃整个缓冲
        return Some((0, 0, Vec::new(), buf.len()));
    }
    let total = 4 + len;
    if buf.len() < total {
        return None;
    }
    let ts = u32::from_be_bytes([buf[4], buf[5], buf[6], buf[7]]);
    let msg_id = u32::from_be_bytes([buf[8], buf[9], buf[10], buf[11]]);
    let payload = buf[12..total].to_vec();
    Some((msg_id, ts, payload, total))
}

/// 组帧：[4B BE 总长(ts+id+payload)][4B BE ts=0][4B BE msgId][payload]。
pub fn build_ctrl_frame(msg_id: u32, fill: impl FnOnce(&mut Vec<u8>)) -> Vec<u8> {
    let mut payload = Vec::with_capacity(16);
    fill(&mut payload);
    let len = (8 + payload.len()) as u32;
    let mut pkt = Vec::with_capacity(4 + len as usize);
    pkt.extend_from_slice(&len.to_be_bytes());
    pkt.extend_from_slice(&0u32.to_be_bytes()); // ts
    pkt.extend_from_slice(&msg_id.to_be_bytes());
    pkt.extend_from_slice(&payload);
    pkt
}

/// protobuf varint 字段写入（field<<3 | wiretype 0）。
pub fn encode_varint_field(out: &mut Vec<u8>, field: u32, mut val: u32) {
    out.push((((field << 3) | 0) & 0xFF) as u8);
    loop {
        let mut b = (val & 0x7F) as u8;
        val >>= 7;
        if val != 0 {
            b |= 0x80;
        }
        out.push(b);
        if val == 0 {
            break;
        }
    }
}

fn decode_first_varint(bytes: &[u8]) -> Option<u32> {
    let mut val: u32 = 0;
    let mut shift = 0;
    for &b in bytes {
        val |= ((b & 0x7F) as u32) << shift;
        if b & 0x80 == 0 {
            return Some(val);
        }
        shift += 7;
        if shift > 28 {
            return None;
        }
    }
    None
}

/// AudioInit protobuf：field1=采样率、field2=通道数（与 apollo-DuerOS 一致）。
fn parse_audio_init(bytes: &[u8]) -> (u32, u32) {
    let (mut rate, mut ch) = (0u32, 0u32);
    let mut i = 0;
    while i < bytes.len() {
        let tag = bytes[i];
        i += 1;
        if (tag & 0x07) != 0 {
            break;
        }
        let field = tag >> 3;
        let mut val: u32 = 0;
        let mut shift = 0;
        while i < bytes.len() {
            let b = bytes[i];
            i += 1;
            val |= ((b & 0x7F) as u32) << shift;
            if b & 0x80 == 0 {
                break;
            }
            shift += 7;
        }
        match field {
            1 => rate = val,
            2 => ch = val,
            _ => {}
        }
    }
    (rate, ch)
}
