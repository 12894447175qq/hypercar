//! CarLife HU（车机）侧网关——严格按 CarLifeWirelessBox / apollo-DuerOS 的 HU 实现重构。
//!
//! 我们扮演「车机」，本机 CarLife / CarWith(CarLife 兼容) / 荣耀车联 作为「手机(MD)」连回
//! 127.0.0.1 的一组标准端口。
//!
//! ## 通道与端口（与 CarLifeWirelessBox `Constants.kt` 一致）
//! 7240 CMD、8240 VIDEO、9240 MEDIA、9241 TTS、9242 VR、9340 CTRL。
//! 注：**没有 9440**；cardeck 文档里的「9440 CTRL / 7241~7244 回退」是早期猜测，实际不存在。
//!
//! ## 两种帧头（关键！此前实现全部用 12 字节头，手机侧会当垃圾丢弃）
//! - **CMD(7240) / CTRL(9340)：8 字节**
//!   `[u16 BE payload_len][u16 reserved=0][u32 BE service_type][protobuf/raw payload]`
//! - **VIDEO/MEDIA/TTS/VR：12 字节**
//!   `[u32 BE payload_len][u32 BE timestamp_ms][u32 BE service_type][raw payload]`
//!
//! ## 消息 ID 域（0x18xxx=HU→MD，0x10xxx=MD→HU，0x2/3/4/6xxxx 分属各媒体通道）
//! 版本 0x18001/0x10002、统计 0x18027、设备信息 0x10004/0x18003、特性配置 0x10051/0x18052、
//! 鉴权 0x18048/0x10049/0x1804A/0x1004B、编码器 0x18007/0x10008/0x18009。
//!
//! ## 握手 14 步（事件驱动；HU 先发版本，其余按 MD 的应答推进）
//! 1 HU→MD 版本；2 MD→HU 版本匹配；3 HU→MD 统计；4 MD→HU MD_INFO；5 HU→MD HU_INFO；
//! 6 MD→HU 特性配置请求；7 HU→MD 特性配置响应；8 HU→MD 鉴权请求；
//! 9 MD→HU 鉴权响应；10 HU→MD 鉴权结果；11 MD→HU 鉴权结果；
//! 12 HU→MD 编码器初始化；13 MD→HU 初始化完成；14 HU→MD 开始推流。
//!
//! ## 触摸载荷（CTRL 通道，裸 BE 结构，非 protobuf）
//! TOUCH_ACTION 0x68001：`[u32 BE action][u32 BE x][u32 BE y]`，action 0=DOWN/1=UP/2=MOVE；
//! HARD_KEY 0x68008：`[u32 BE keycode]`。

use std::sync::Arc;
use std::time::Duration;

use parking_lot::Mutex;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{mpsc, Notify};

use crate::engine::CarLifeEvent;
use crate::{emit_log, emit_state};

// ---------------------------------------------------------------- 端口

pub const PORT_CMD: u16 = 7240;
pub const PORT_VIDEO: u16 = 8240;
pub const PORT_MEDIA: u16 = 9240;
pub const PORT_TTS: u16 = 9241;
pub const PORT_VR: u16 = 9242;
pub const PORT_CTRL: u16 = 9340;

// ---------------------------------------------------------------- CMD 消息 ID

const CMD_HU_PROTOCOL_VERSION: u32 = 0x0001_8001;
const CMD_VERSION_MATCH_STATUS: u32 = 0x0001_0002;
const CMD_HU_INFO: u32 = 0x0001_8003;
const CMD_MD_INFO: u32 = 0x0001_0004;
const CMD_VIDEO_ENCODER_INIT: u32 = 0x0001_8007;
const CMD_VIDEO_ENCODER_INIT_DONE: u32 = 0x0001_0008;
const CMD_VIDEO_ENCODER_START: u32 = 0x0001_8009;
const CMD_SCREEN_ON: u32 = 0x0001_0018;
const CMD_FOREGROUND: u32 = 0x0001_001B;
const CMD_MD_MODULE_STATUS: u32 = 0x0001_0026;
const CMD_HU_STATISTIC_INFO: u32 = 0x0001_8027;
const CMD_MD_CAR_DATA_SUBSCRIBE: u32 = 0x0001_0031;
const CMD_HU_CAR_DATA_SUBSCRIBE_DONE: u32 = 0x0001_8032;
const CMD_HU_AUTHEN_REQUEST: u32 = 0x0001_8048;
const CMD_MD_AUTHEN_RESPONSE: u32 = 0x0001_0049;
const CMD_HU_AUTHEN_RESULT: u32 = 0x0001_804A;
const CMD_MD_AUTHEN_RESULT: u32 = 0x0001_004B;
const CMD_MD_FEATURE_CONFIG_REQUEST: u32 = 0x0001_0051;
const CMD_HU_FEATURE_CONFIG_RESPONSE: u32 = 0x0001_8052;
const CMD_MD_EXIT: u32 = 0x0001_0059;

// 媒体/视频/TTS 通道消息（12 字节头内的 service_type）
const VIDEO_DATA: u32 = 0x0002_0001;
const VIDEO_HEARTBEAT: u32 = 0x0002_0002;
const MEDIA_INIT: u32 = 0x0003_0001;
const MEDIA_STOP: u32 = 0x0003_0002;
const MEDIA_DATA: u32 = 0x0003_0006;
const TTS_INIT: u32 = 0x0004_0001;
const TTS_STOP: u32 = 0x0004_0002;
const TTS_DATA: u32 = 0x0004_0003;

// CTRL 通道（8 字节头）
const CTRL_TOUCH_ACTION: u32 = 0x0006_8001;
const CTRL_HARD_KEY: u32 = 0x0006_8008;

/// 触摸动作（CarLife 语义：0=DOWN / 1=UP / 2=MOVE，**不是** Android 的 1/2/3）。
pub const TOUCH_DOWN: u32 = 0;
pub const TOUCH_UP: u32 = 1;
pub const TOUCH_MOVE: u32 = 2;

/// 协议版本（CarLifeWirelessBox 默认 v3.2，实测 CarWith 接受）。
const PROTOCOL_MAJOR: u32 = 3;
const PROTOCOL_MINOR: u32 = 2;

pub struct CarLifeConfig {
    pub width: u32,
    pub height: u32,
    pub fps: u32,
}

/// CarLife 网关：监听 6 条标准通道，产出结构化事件；接受外部触摸/按键注入。
pub struct CarLifeHub {
    events: mpsc::UnboundedSender<CarLifeEvent>,
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

        // ---- CMD 7240：14 步握手 ----
        let cmd_listener =
            bind_tcp(PORT_CMD).map_err(|e| format!("CarLife CMD 7240 绑定失败: {e}"))?;
        spawn_single_client_server(cmd_listener, stop.clone(), "CMD", {
            let events = events.clone();
            let (w, h, f) = (cfg.width, cfg.height, cfg.fps);
            let stop_c = stop.clone();
            move |stream| handle_cmd_client(stream, events.clone(), w, h, f, stop_c.clone())
        });

        // ---- VIDEO 8240 ----
        spawn_media_channel(PORT_VIDEO, "VIDEO", events.clone(), stop.clone(), MediaKind::Video);
        // ---- MEDIA 9240 ----
        spawn_media_channel(PORT_MEDIA, "MEDIA", events.clone(), stop.clone(), MediaKind::Media);
        // ---- TTS 9241 ----
        spawn_media_channel(PORT_TTS, "TTS", events.clone(), stop.clone(), MediaKind::Tts);
        // ---- VR 9242：只读保活，不消费 ----
        spawn_drain(PORT_VR, "VR", stop.clone());

        // ---- CTRL 9340：触摸/硬按键出向（8 字节 CMD 头）----
        spawn_ctrl_channel(PORT_CTRL, touch_rx, stop.clone());

        emit_state("carlife_waiting", "CarLife 网关就绪，等待本机 app 接入");
        emit_log(&format!(
            "CARLIFE: 回环网关就绪 127.0.0.1:{} CMD / {} VIDEO / {} MEDIA / {} TTS / {} VR / {} CTRL",
            PORT_CMD, PORT_VIDEO, PORT_MEDIA, PORT_TTS, PORT_VR, PORT_CTRL
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

    /// 外部（AirPlay 事件通道）注入触摸 → CarLife CTRL 通道 TOUCH_ACTION。
    pub fn inject_touch(&self, action: u32, x: u32, y: u32) {
        let mut payload = Vec::with_capacity(12);
        payload.extend_from_slice(&action.to_be_bytes());
        payload.extend_from_slice(&x.to_be_bytes());
        payload.extend_from_slice(&y.to_be_bytes());
        let _ = self
            .touch_tx
            .send(build_cmd_frame(CTRL_TOUCH_ACTION, &payload));
    }

    /// iDrive 旋钮/媒体键 → CarLife CTRL 通道 HARD_KEY。
    pub fn inject_hard_key(&self, code: u32) {
        let _ = self
            .touch_tx
            .send(build_cmd_frame(CTRL_HARD_KEY, &code.to_be_bytes()));
    }

    pub fn resolution(&self) -> (u32, u32) {
        (self.cfg.width, self.cfg.height)
    }
}

// ---------------------------------------------------------------- 帧编解码

/// CMD/CTRL 帧（8 字节头）：`[u16 BE len][u16 reserved][u32 BE type][payload]`。
fn build_cmd_frame(service_type: u32, payload: &[u8]) -> Vec<u8> {
    let len = payload.len().min(u16::MAX as usize);
    let mut out = Vec::with_capacity(8 + len);
    out.extend_from_slice(&(len as u16).to_be_bytes());
    out.extend_from_slice(&[0u8, 0u8]);
    out.extend_from_slice(&service_type.to_be_bytes());
    out.extend_from_slice(&payload[..len]);
    out
}

/// 从缓冲切一条 CMD/CTRL 帧：返回 (service_type, payload, 消费字节数)。
fn read_cmd_frame(buf: &[u8]) -> Option<(u32, Vec<u8>, usize)> {
    if buf.len() < 8 {
        return None;
    }
    let len = u16::from_be_bytes([buf[0], buf[1]]) as usize;
    let service_type = u32::from_be_bytes([buf[4], buf[5], buf[6], buf[7]]);
    let total = 8 + len;
    if buf.len() < total {
        return None;
    }
    Some((service_type, buf[8..total].to_vec(), total))
}

/// 媒体帧（12 字节头）：`[u32 BE len][u32 BE ts_ms][u32 BE type][payload]`。
fn read_media_frame(buf: &[u8]) -> Option<(u32, u32, Vec<u8>, usize)> {
    if buf.len() < 12 {
        return None;
    }
    let len = u32::from_be_bytes([buf[0], buf[1], buf[2], buf[3]]) as usize;
    if len > 32 * 1024 * 1024 {
        // 非法长度：无法安全重同步，整块丢弃由调用方处理
        return Some((0, 0, Vec::new(), buf.len()));
    }
    let total = 12 + len;
    if buf.len() < total {
        return None;
    }
    let ts = u32::from_be_bytes([buf[4], buf[5], buf[6], buf[7]]);
    let service_type = u32::from_be_bytes([buf[8], buf[9], buf[10], buf[11]]);
    Some((service_type, ts, buf[12..total].to_vec(), total))
}

// ---------------------------------------------------------------- protobuf 最小写入器

mod proto {
    fn put_varint(out: &mut Vec<u8>, mut value: u64) {
        loop {
            let mut b = (value & 0x7F) as u8;
            value >>= 7;
            if value != 0 {
                b |= 0x80;
            }
            out.push(b);
            if value == 0 {
                break;
            }
        }
    }

    pub fn varint(out: &mut Vec<u8>, field: u32, value: u64) {
        put_varint(out, ((field as u64) << 3) | 0);
        put_varint(out, value);
    }

    pub fn string(out: &mut Vec<u8>, field: u32, s: &str) {
        put_varint(out, ((field as u64) << 3) | 2);
        put_varint(out, s.len() as u64);
        out.extend_from_slice(s.as_bytes());
    }

    pub fn bytes(out: &mut Vec<u8>, field: u32, b: &[u8]) {
        put_varint(out, ((field as u64) << 3) | 2);
        put_varint(out, b.len() as u64);
        out.extend_from_slice(b);
    }

    pub fn boolean(out: &mut Vec<u8>, field: u32, v: bool) {
        varint(out, field, if v { 1 } else { 0 });
    }
}

// ---------------------------------------------------------------- CMD 握手状态机

/// 握手进度（每个 HU→MD 消息只发一次，避免重复消息扰动车机）。
#[derive(Default)]
struct Handshake {
    statistic_sent: bool,
    hu_info_sent: bool,
    feature_sent: bool,
    authen_req_sent: bool,
    authen_result_sent: bool,
    encoder_init_sent: bool,
    encoder_start_sent: bool,
}

impl Handshake {
    fn phase_name(&self) -> &'static str {
        if self.encoder_start_sent {
            "Phase 7: 已开始推流"
        } else if self.encoder_init_sent {
            "Phase 6: 等编码器初始化完成"
        } else if self.authen_result_sent {
            "Phase 5-6: 等鉴权结果"
        } else if self.authen_req_sent {
            "Phase 4: 等鉴权响应"
        } else if self.feature_sent {
            "Phase 3.5: 等特性配置后的鉴权"
        } else if self.hu_info_sent {
            "Phase 3: 等特性配置请求"
        } else if self.statistic_sent {
            "Phase 2: 等 MD_INFO"
        } else {
            "Phase 1: 等版本匹配"
        }
    }
}

async fn send_cmd(wr: &mut tokio::net::tcp::OwnedWriteHalf, ty: u32, payload: &[u8]) -> bool {
    let frame = build_cmd_frame(ty, payload);
    match wr.write_all(&frame).await {
        Ok(()) => {
            let _ = wr.flush().await;
            true
        }
        Err(e) => {
            emit_log(&format!("CARLIFE: CMD 写失败（0x{ty:08x}）: {e}"));
            false
        }
    }
}

/// CMD 通道：14 步握手状态机（严格按 HU 侧事件驱动）。
async fn handle_cmd_client(
    stream: TcpStream,
    events: mpsc::UnboundedSender<CarLifeEvent>,
    width: u32,
    height: u32,
    fps: u32,
    stop: Arc<Notify>,
) {
    let (mut rd, mut wr) = stream.into_split();
    let mut hs = Handshake::default();

    // Phase 1：HU 主动发协议版本
    if !send_cmd(&mut wr, CMD_HU_PROTOCOL_VERSION, &proto_version_payload()).await {
        return;
    }
    emit_log(&format!(
        "CARLIFE: [Phase 1] → HU_PROTOCOL_VERSION ({PROTOCOL_MAJOR}.{PROTOCOL_MINOR})"
    ));

    let mut buf: Vec<u8> = Vec::with_capacity(4096);
    let mut chunk = [0u8; 2048];
    let mut tick = tokio::time::interval(Duration::from_secs(5));
    tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);

    loop {
        let n = tokio::select! {
            _ = stop.notified() => return,
            _ = tick.tick() => {
                if !hs.encoder_start_sent {
                    emit_log(&format!("CARLIFE: 握手进行中（{}）", hs.phase_name()));
                }
                continue;
            }
            r = rd.read(&mut chunk) => match r {
                Ok(0) => {
                    emit_log(&format!("CARLIFE: CMD 通道关闭（停在 {}）", hs.phase_name()));
                    return;
                }
                Ok(n) => n,
                Err(_) => return,
            }
        };
        buf.extend_from_slice(&chunk[..n]);

        while let Some((service_type, payload, consumed)) = read_cmd_frame(&buf) {
            buf.drain(..consumed);
            match service_type {
                CMD_VERSION_MATCH_STATUS => {
                    emit_log("CARLIFE: [Phase 1] ← 版本匹配");
                    if !hs.statistic_sent {
                        // Phase 1.5：统计信息
                        let ok = send_cmd(
                            &mut wr,
                            CMD_HU_STATISTIC_INFO,
                            &proto_statistic_payload(),
                        )
                        .await;
                        if !ok {
                            return;
                        }
                        hs.statistic_sent = true;
                        emit_log("CARLIFE: [Phase 1.5] → HU_STATISTIC_INFO");
                    }
                }
                CMD_MD_INFO => {
                    emit_log("CARLIFE: [Phase 2] ← MD_INFO");
                    if !hs.hu_info_sent {
                        let ok = send_cmd(&mut wr, CMD_HU_INFO, &proto_hu_info_payload()).await;
                        if !ok {
                            return;
                        }
                        hs.hu_info_sent = true;
                        emit_log("CARLIFE: [Phase 2] → HU_INFO");
                    }
                }
                CMD_MD_FEATURE_CONFIG_REQUEST => {
                    emit_log("CARLIFE: [Phase 3] ← MD_FEATURE_CONFIG_REQUEST");
                    if !hs.feature_sent {
                        let ok = send_cmd(
                            &mut wr,
                            CMD_HU_FEATURE_CONFIG_RESPONSE,
                            &proto_feature_config_payload(),
                        )
                        .await;
                        if !ok {
                            return;
                        }
                        hs.feature_sent = true;
                        emit_log("CARLIFE: [Phase 3.5] → HU_FEATURE_CONFIG_RESPONSE");
                    }
                    if !hs.authen_req_sent {
                        let ok = send_cmd(
                            &mut wr,
                            CMD_HU_AUTHEN_REQUEST,
                            &proto_authen_request_payload(),
                        )
                        .await;
                        if !ok {
                            return;
                        }
                        hs.authen_req_sent = true;
                        emit_log("CARLIFE: [Phase 4] → HU_AUTHEN_REQUEST");
                    }
                }
                CMD_MD_AUTHEN_RESPONSE => {
                    emit_log(&format!("CARLIFE: [Phase 4] ← MD_AUTHEN_RESPONSE {}B", payload.len()));
                    if !hs.authen_result_sent {
                        let ok = send_cmd(
                            &mut wr,
                            CMD_HU_AUTHEN_RESULT,
                            &proto_authen_result_payload(true),
                        )
                        .await;
                        if !ok {
                            return;
                        }
                        hs.authen_result_sent = true;
                        emit_log("CARLIFE: [Phase 4.5] → HU_AUTHEN_RESULT(true)");
                    }
                }
                CMD_MD_AUTHEN_RESULT => {
                    emit_log("CARLIFE: [Phase 5] ← MD_AUTHEN_RESULT —— 连接建立");
                    if !hs.encoder_init_sent {
                        let ok = send_cmd(
                            &mut wr,
                            CMD_VIDEO_ENCODER_INIT,
                            &proto_encoder_info_payload(width, height, fps),
                        )
                        .await;
                        if !ok {
                            return;
                        }
                        hs.encoder_init_sent = true;
                        emit_log(&format!(
                            "CARLIFE: [Phase 6] → VIDEO_ENCODER_INIT {width}x{height}@{fps}"
                        ));
                    }
                }
                CMD_VIDEO_ENCODER_INIT_DONE => {
                    emit_log(&format!(
                        "CARLIFE: [Phase 6] ← VIDEO_ENCODER_INIT_DONE {}B",
                        payload.len()
                    ));
                    if !hs.encoder_start_sent {
                        let ok = send_cmd(&mut wr, CMD_VIDEO_ENCODER_START, &[]).await;
                        if !ok {
                            return;
                        }
                        hs.encoder_start_sent = true;
                        emit_log("CARLIFE: [Phase 7] → VIDEO_ENCODER_START —— 握手完成，开始推流");
                        emit_state("carlife_active", "CarLife 已开始推流");
                        let _ = events.send(CarLifeEvent::Handshake);
                    }
                }
                CMD_MD_CAR_DATA_SUBSCRIBE => {
                    // 手机订阅车辆数据：回一个空车辆信息列表
                    let _ = send_cmd(
                        &mut wr,
                        CMD_HU_CAR_DATA_SUBSCRIBE_DONE,
                        &proto_car_data_subscribe_done_payload(),
                    )
                    .await;
                    emit_log("CARLIFE: → HU_CAR_DATA_SUBSCRIBE_DONE");
                }
                CMD_SCREEN_ON | CMD_FOREGROUND => {
                    emit_log(&format!(
                        "CARLIFE: ← {}（握手期通知）",
                        if service_type == CMD_SCREEN_ON {
                            "SCREEN_ON"
                        } else {
                            "FOREGROUND"
                        }
                    ));
                }
                CMD_MD_MODULE_STATUS => {
                    emit_log("CARLIFE: ← MD_MODULE_STATUS");
                }
                CMD_MD_EXIT => {
                    emit_log("CARLIFE: ← MD_EXIT（手机会话主动退出）");
                    return;
                }
                other => {
                    emit_log(&format!(
                        "CARLIFE: CMD 未处理消息 0x{other:08x}（{}B）",
                        payload.len()
                    ));
                }
            }
        }
        // 异常长度保护：读循环卡住时避免缓冲无限增长
        if buf.len() > 4 * 1024 * 1024 {
            buf.clear();
        }
    }
}

// ---------------------------------------------------------------- 握手载荷

fn proto_version_payload() -> Vec<u8> {
    let mut out = Vec::with_capacity(8);
    proto::varint(&mut out, 1, PROTOCOL_MAJOR as u64);
    proto::varint(&mut out, 2, PROTOCOL_MINOR as u64);
    out
}

fn proto_statistic_payload() -> Vec<u8> {
    // CarlifeStatisticsInfo: 1=cuid, 2=version_name, 3=version_code, 4=channel,
    // 5=connect_count, 6=connect_success_count, 7=connect_time
    let mut out = Vec::with_capacity(64);
    proto::string(&mut out, 1, "hypercar");
    proto::string(&mut out, 2, "4.0.0");
    proto::varint(&mut out, 3, 16);
    proto::string(&mut out, 4, "20352101");
    proto::varint(&mut out, 5, 0);
    proto::varint(&mut out, 6, 0);
    proto::varint(&mut out, 7, 0);
    out
}

fn proto_hu_info_payload() -> Vec<u8> {
    // CarlifeDeviceInfo: 1=device_type(HEAD_UNIT=2), 2=os_type(ANDROID=1),
    // 3=os_version, 4=manufacturer, 5=model, 6=device_id, 8=device_name,
    // 9=screen_width, 10=screen_height, 11=screen_dpi
    let mut out = Vec::with_capacity(96);
    proto::varint(&mut out, 1, 2);
    proto::varint(&mut out, 2, 1);
    proto::string(&mut out, 3, "12");
    proto::string(&mut out, 4, "HyperCar");
    proto::string(&mut out, 5, "HyperCar HU");
    proto::string(&mut out, 6, "hypercar-hu");
    proto::string(&mut out, 8, "HyperCar");
    out
}

fn proto_feature_config_payload() -> Vec<u8> {
    // CarlifeFeatureConfigList: 1=cnt, 2=repeated {1=key string, 2=value int32},
    // 3=hu_bt_audio_support, 4=hu_bt_name, 5=hu_bt_mac
    let features: [(&str, u32); 6] = [
        ("video", 1),
        ("audio", 1),
        ("touch", 1),
        ("music", 1),
        ("navigation", 1),
        ("voice", 1),
    ];
    let mut out = Vec::with_capacity(96);
    proto::varint(&mut out, 1, features.len() as u64);
    for (key, value) in features {
        let mut item = Vec::with_capacity(16);
        proto::string(&mut item, 1, key);
        proto::varint(&mut item, 2, value as u64);
        proto::bytes(&mut out, 2, &item);
    }
    proto::boolean(&mut out, 3, false);
    out
}

fn proto_authen_request_payload() -> Vec<u8> {
    // CarlifeAuthenRequest: 1=random_value(string)
    let mut out = Vec::with_capacity(48);
    proto::string(&mut out, 1, &uuid::Uuid::new_v4().to_string());
    out
}

fn proto_authen_result_payload(ok: bool) -> Vec<u8> {
    // CarlifeAuthenResult: 1=authen_result(bool)
    let mut out = Vec::with_capacity(4);
    proto::boolean(&mut out, 1, ok);
    out
}

fn proto_encoder_info_payload(width: u32, height: u32, fps: u32) -> Vec<u8> {
    // CarlifeVideoEncoderInfo: 1=width, 2=height, 3=frameRate（均 int32）
    let mut out = Vec::with_capacity(16);
    proto::varint(&mut out, 1, width as u64);
    proto::varint(&mut out, 2, height as u64);
    proto::varint(&mut out, 3, fps as u64);
    out
}

fn proto_car_data_subscribe_done_payload() -> Vec<u8> {
    // CarlifeVehicleInfoList: 1=cnt
    let mut out = Vec::with_capacity(4);
    proto::varint(&mut out, 1, 0);
    out
}

// ---------------------------------------------------------------- 媒体通道

#[derive(Clone, Copy)]
enum MediaKind {
    Video,
    Media,
    Tts,
}

fn spawn_media_channel(
    port: u16,
    name: &'static str,
    events: mpsc::UnboundedSender<CarLifeEvent>,
    stop: Arc<Notify>,
    kind: MediaKind,
) {
    let listener = match bind_tcp(port) {
        Ok(l) => l,
        Err(e) => {
            emit_log(&format!("CARLIFE: {name} {port} 绑定失败: {e}"));
            return;
        }
    };
    spawn_single_client_server(listener, stop, name, move |stream| {
        handle_media_client(stream, events.clone(), kind)
    });
}

/// VIDEO/MEDIA/TTS 通道：12 字节头 + 裸数据（视频 H.264 AnnexB / 音频 PCM）。
async fn handle_media_client(
    mut stream: TcpStream,
    events: mpsc::UnboundedSender<CarLifeEvent>,
    kind: MediaKind,
) {
    let mut buf: Vec<u8> = Vec::with_capacity(256 * 1024);
    let mut chunk = [0u8; 65536];
    let mut rate: u32 = 48_000;
    let mut channels: u8 = 2;

    loop {
        match stream.read(&mut chunk).await {
            Ok(0) => {
                let _ = events.send(CarLifeEvent::ClientLeft {
                    video: matches!(kind, MediaKind::Video),
                });
                return;
            }
            Ok(n) => buf.extend_from_slice(&chunk[..n]),
            Err(_) => return,
        }
        if buf.len() > 8 * 1024 * 1024 {
            buf.clear();
        }
        while let Some((service_type, ts_ms, payload, consumed)) = read_media_frame(&buf) {
            buf.drain(..consumed);
            match kind {
                MediaKind::Video => match service_type {
                    VIDEO_DATA => {
                        if !payload.is_empty() {
                            let _ = events.send(CarLifeEvent::VideoFrame {
                                pts_us: ts_ms as u64 * 1000,
                                data: payload,
                            });
                        }
                    }
                    VIDEO_HEARTBEAT => { /* 流级心跳，忽略 */ }
                    other => {
                        if other != 0 {
                            emit_log(&format!("CARLIFE: VIDEO 未处理 0x{other:08x}"));
                        }
                    }
                },
                MediaKind::Media | MediaKind::Tts => {
                    let tts = matches!(kind, MediaKind::Tts);
                    match service_type {
                        MEDIA_INIT | TTS_INIT => {
                            let (r, c) = parse_audio_init(&payload);
                            if (8_000..=96_000).contains(&r) {
                                rate = r;
                            }
                            if (1..=2).contains(&c) {
                                channels = c as u8;
                            }
                            let _ = events.send(CarLifeEvent::AudioInit {
                                tts,
                                rate,
                                channels,
                            });
                            emit_log(&format!(
                                "CARLIFE: {} 音频初始化 {rate}Hz {channels}ch",
                                if tts { "TTS" } else { "MEDIA" }
                            ));
                        }
                        MEDIA_STOP | TTS_STOP => {
                            let _ = events.send(CarLifeEvent::AudioStop { tts });
                        }
                        MEDIA_DATA | TTS_DATA => {
                            if !payload.is_empty() {
                                let _ = events.send(CarLifeEvent::AudioData {
                                    tts,
                                    rate,
                                    channels,
                                    data: payload,
                                });
                            }
                        }
                        other => {
                            if other != 0 {
                                emit_log(&format!(
                                    "CARLIFE: {} 未处理 0x{other:08x}",
                                    if tts { "TTS" } else { "MEDIA" }
                                ));
                            }
                        }
                    }
                }
            }
        }
    }
}

/// 解析音频初始化 protobuf：优先按 CarlifeAudioStart（3=sample_rate, 4=channels），
/// 兜底按旧版 1/2 字段布局。返回 (rate, channels)；未给则 (0,0)。
fn parse_audio_init(bytes: &[u8]) -> (u32, u32) {
    let mut fields: Vec<(u32, u64)> = Vec::new();
    let mut i = 0usize;
    while i < bytes.len() {
        let (tag, used) = read_varint(&bytes[i..]);
        let Some(tag) = tag else { break };
        i += used;
        let field = (tag >> 3) as u32;
        let wire = (tag & 0x07) as u8;
        match wire {
            0 => {
                let (v, used) = read_varint(&bytes[i..]);
                let Some(v) = v else { break };
                fields.push((field, v));
                i += used;
            }
            2 => {
                let (len, used) = read_varint(&bytes[i..]);
                let Some(len) = len else { break };
                i += used;
                i += len as usize;
            }
            5 => i += 4,
            1 => i += 8,
            _ => break,
        }
        if i > bytes.len() {
            break;
        }
    }
    let get = |f: u32| fields.iter().find(|(k, _)| *k == f).map(|(_, v)| *v as u32);
    let rate = get(3).or_else(|| get(1)).unwrap_or(0);
    let channels = get(4).or_else(|| get(2)).unwrap_or(0);
    (rate, channels)
}

fn read_varint(bytes: &[u8]) -> (Option<u64>, usize) {
    let mut value: u64 = 0;
    let mut shift = 0u32;
    for (index, &b) in bytes.iter().enumerate() {
        value |= ((b & 0x7F) as u64) << shift;
        if b & 0x80 == 0 {
            return (Some(value), index + 1);
        }
        shift += 7;
        if shift >= 64 {
            return (None, index + 1);
        }
    }
    (None, bytes.len())
}

// ---------------------------------------------------------------- CTRL 通道

fn spawn_ctrl_channel(
    port: u16,
    mut touch_rx: mpsc::UnboundedReceiver<Vec<u8>>,
    stop: Arc<Notify>,
) {
    // 注入帧先进队列，客户端在线时按 8ms 节拍刷出
    let queue: Arc<Mutex<Vec<Vec<u8>>>> = Arc::new(Mutex::new(Vec::new()));
    {
        let queue = queue.clone();
        let stop_fill = stop.clone();
        tokio::spawn(async move {
            loop {
                tokio::select! {
                    _ = stop_fill.notified() => return,
                    frame = touch_rx.recv() => match frame {
                        Some(f) => {
                            let mut q = queue.lock();
                            if q.len() > 256 {
                                q.remove(0);
                            }
                            q.push(f);
                        }
                        None => return,
                    },
                }
            }
        });
    }

    let listener = match bind_tcp(port) {
        Ok(l) => l,
        Err(e) => {
            emit_log(&format!("CARLIFE: CTRL {port} 绑定失败: {e}"));
            return;
        }
    };
    tokio::spawn(async move {
        loop {
            tokio::select! {
                _ = stop.notified() => return,
                accepted = listener.accept() => match accepted {
                    Ok((mut stream, _)) => {
                        let _ = stream.set_nodelay(true);
                        emit_log("CARLIFE: ✓ CTRL 通道已接入（触摸/硬按键就绪）");
                        loop {
                            let frame = {
                                let mut q = queue.lock();
                                if q.is_empty() { None } else { Some(q.remove(0)) }
                            };
                            match frame {
                                Some(f) => {
                                    if stream.write_all(&f).await.is_err() {
                                        break;
                                    }
                                }
                                None => {
                                    tokio::select! {
                                        _ = stop.notified() => return,
                                        _ = tokio::time::sleep(Duration::from_millis(8)) => {}
                                    }
                                }
                            }
                        }
                    }
                    Err(_) => return,
                },
            }
        }
    });
}

// ---------------------------------------------------------------- 通用 accept / 保活

fn bind_tcp(port: u16) -> Result<TcpListener, std::io::Error> {
    // 重绑竞争：上一会话刚停时端口可能还在旧任务手里，短重试几次
    let mut last_err = None;
    for _ in 0..15 {
        match std::net::TcpListener::bind(("127.0.0.1", port)) {
            Ok(listener) => {
                listener.set_nonblocking(true)?;
                return TcpListener::from_std(listener);
            }
            Err(e) => {
                last_err = Some(e);
                std::thread::sleep(Duration::from_millis(100));
            }
        }
    }
    Err(last_err.unwrap())
}

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
                accepted = listener.accept() => match accepted {
                    Ok((stream, _)) => {
                        let _ = stream.set_nodelay(true);
                        if let Some(h) = current.take() { h.abort(); }
                        emit_log(&format!("CARLIFE: {name} 客户端已接入"));
                        let handler = handler.clone();
                        current = Some(tokio::spawn(async move { handler(stream).await }));
                    }
                    Err(_) => return,
                },
            }
        }
    });
}

/// VR 通道：只接受并读空（语音识别暂不消费）。
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
