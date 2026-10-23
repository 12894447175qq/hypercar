//! AirPlay 发送端会话（协商整体下沉 Rust —— 与参考文档的关键差异，见 docs/NEGOTIATION-GAPS.md）。
//!
//! 流程（cardeck 真车验证的顺序，Audi MIB3 / BMW iD8.5 通用骨架）：
//!  1. `GET /info`（带空 bplist 体 + plist Content-Type，老固件对缺体的 /info 回 400/455）
//!  2. `/pair-verify` M1..M4（本机 ed25519 长期身份）→ 控制通道密钥
//!  3. modesChanged（声明持有主屏/主音频资源；跳过它 Audi 对屏幕流 SETUP 直接 400）
//!  4. 会话级 `SETUP /setup`（deviceID 必须是本机真实蓝牙 MAC；et 仅未配对会话带）
//!  5. 连接事件通道（SETUP 应答 eventPort；车机在此通道 POST /command）
//!  6. 屏幕流 `SETUP /`（type 110，uuid 必须取 /info displays[].uuid）→ dataPort
//!  7. 音频流 `SETUP /`（type 100 PCM 48k，spf=352）→ dataPort/controlPort
//!  8. `RECORD` 激活 → 三条相互独立的心跳：
//!     UDP keepAlive 5s（4B；间隔必须小于车机 10s 看门狗——等长必输）
//!     RTSP /feedback 1s（车机 ~3s 看门狗）
//!     屏幕流 keepAlive 帧 1s（在视频 pump 内节流；缺它 Audi MMI 3315 10.2s 拆轮）
//!  9. 事件通道 hidSendReport → 触摸坐标换算 → CarLife 触摸通道

use std::sync::Arc;
use std::time::Duration;

use tokio::net::tcp::{OwnedReadHalf, OwnedWriteHalf};
use tokio::net::{TcpStream, UdpSocket};
use tokio::sync::{mpsc, Notify};

use crate::audio_out::AudioOut;
use crate::bplist::{self, Value};
use crate::pairing::{ControllerIdentity, PairSetupClient, PairVerifyClient, TlvTransport};
use crate::rtsp::{ControlCipher, RtspChannel};
use crate::screen::{ScreenCipher, ScreenSender};
use crate::timing::TimingServer;
use crate::{emit_log, emit_state};

/// Rust 需要新 socket 时回调 Kotlin（必须绑定车机网络，否则流量走手机）。
pub trait SocketProvider: Send + Sync {
    fn open_tcp(&self, host: &str, port: u16, timeout_ms: u32) -> Option<TcpStream>;
    fn open_udp(&self, host: &str, port: u16) -> Option<UdpSocket>;
}

#[derive(Clone, Debug)]
pub struct AirplayParams {
    pub car_ip: String,
    pub airplay_port: u16,
    /// 本机真实蓝牙 MAC（WinPlay 等接收端按 SETUP deviceID 过滤，占位值必 403）。
    pub device_id: String,
    pub identity_path: String,
    pub enable_audio: bool,
    pub keep_alive_stats: bool,
    /// CarLife 编码分辨率（触摸坐标换算目标；也是 VideoConfig 画布）。
    pub width: u32,
    pub height: u32,
}

/// 车机触摸（已是 CarLife 编码空间坐标）。
#[derive(Debug, Clone, Copy)]
pub struct TouchInput {
    pub action: u32,
    pub x: u32,
    pub y: u32,
}

pub struct AirplayOutcome {
    pub screen: ScreenSender,
    pub audio: Option<AudioOut>,
    pub keep_alive_stats: bool,
}

/// 跑完整个 AirPlay 协商，返回屏幕/音频发送器（交给 pump 任务）。
pub async fn negotiate(
    read: OwnedReadHalf,
    write: OwnedWriteHalf,
    p: &AirplayParams,
    factory: Arc<dyn SocketProvider>,
    touch_tx: mpsc::UnboundedSender<TouchInput>,
    key_tx: mpsc::UnboundedSender<u32>,
    stop: Arc<Notify>,
) -> Result<AirplayOutcome, String> {
    let (mut channel, _control_inbound) = RtspChannel::spawn(read, write, None);

    // ---- 1. /info ----
    let info = fetch_info(&channel).await?;
    let keep_alive_stats = p.keep_alive_stats
        || info
            .get("keepAliveSendStatsAsBody")
            .and_then(Value::as_bool)
            .unwrap_or(false);
    let display = info
        .get("displays")
        .and_then(Value::as_array)
        .and_then(|a| a.first());
    let display_uuid = display.and_then(|d| uuid_text(d.get("uuid")));
    let (disp_w, disp_h) = display
        .map(|d| {
            (
                d.get("width").and_then(Value::as_int).unwrap_or(0) as u32,
                d.get("height").and_then(Value::as_int).unwrap_or(0) as u32,
            )
        })
        .unwrap_or((0, 0));
    // 车机能力位图：判断是否支持 HEVC 等能力，只能靠它（我们无法向 CarLife 指定 codec）
    let car_features = info.get("features").and_then(Value::as_int).unwrap_or(0);
    emit_log(&format!(
        "AIRPLAY: /info displays[0].uuid={:?} {}x{} features=0x{:x}",
        display_uuid, disp_w, disp_h, car_features
    ));

    // ---- 2. 配对：pair-setup → pair-verify（与 cardeck `runPairingLoop` 同策略）----
    //
    // 正确顺序是「先 pair-setup 交换长期公钥，再 pair-verify 派生会话密钥」。
    // 但**已通过 iAP2/MFi 建立信任的接收端**（奥迪 MIB3 实测）会对匿名 pair-setup 回 403；
    // 此时旧连接的状态机已被污染，必须**换一条新连接**走 pair-verify 快路径。
    // 老车机（pre-HomeKit）对 /pair-setup 回 404/405/455/501 → 明文 et=NONE 会话。
    // 此前实现跳过 pair-setup 直接 verify，对「需要真正 pair-setup 的接收端」会失败。
    let identity = ControllerIdentity::load_or_create(std::path::Path::new(&p.identity_path))
        .map_err(|e| format!("身份载入失败: {e}"))?;
    let receiver_pk = info
        .get("pk")
        .and_then(Value::as_bytes)
        .and_then(|b| <[u8; 32]>::try_from(b).ok());
    let pair_log = |s: &str| emit_log(&format!("PAIR: {s}"));

    let mut pairing_forbidden = false;
    let mut pairing_unsupported = false;
    {
        let mut transport = RtspTransport { channel: &channel };
        match PairSetupClient::run(&identity, &mut transport, &pair_log) {
            Ok(_) => emit_log("PAIR: ✓ pair-setup 完成（长期公钥已交换）"),
            Err(e) => {
                let lower = e.to_ascii_lowercase();
                if lower.contains("403") {
                    pairing_forbidden = true;
                    emit_log(
                        "PAIR: pair-setup 403（接收端已通过 MFi 信任本机）→ 换新连接走 pair-verify 快路径",
                    );
                } else if ["404", "405", "455", "501"].iter().any(|s| lower.contains(s)) {
                    pairing_unsupported = true;
                    emit_log(&format!(
                        "PAIR: pair-setup 不被支持（{e}）→ pre-HomeKit 老车机，走明文 et=NONE"
                    ));
                } else {
                    emit_log(&format!("PAIR: pair-setup 失败（{e}）→ 仍尝试 pair-verify"));
                }
            }
        }
    }

    // 403 后必须换新连接：旧连接的状态机不可再用
    if pairing_forbidden {
        match factory.open_tcp(&p.car_ip, p.airplay_port, 5_000) {
            Some(stream) => {
                let (r, w) = stream.into_split();
                let (fresh, _rx) = RtspChannel::spawn(r, w, None);
                channel = fresh;
                emit_log("PAIR: 已换新控制连接");
            }
            None => emit_log("PAIR: ⚠ 新控制连接打开失败，沿用旧连接（可能仍被 403 污染）"),
        }
    }

    let shared_secret: Option<[u8; 32]> = if pairing_unsupported {
        None
    } else {
        let mut transport = RtspTransport { channel: &channel };
        match PairVerifyClient::run(&identity, &mut transport, receiver_pk.as_ref(), &pair_log) {
            Ok(keys) => {
                channel.set_cipher(ControlCipher::new(keys.read_key, keys.write_key));
                emit_log("AIRPLAY: ✓ pair-verify 完成，控制通道已加密");
                Some(keys.shared_secret)
            }
            Err(e) => {
                // 真车 403 的恢复路径：保持连接、退避重试由上层重连处理。
                emit_log(&format!("AIRPLAY: pair-verify 未通过（{e}）——按 et=NONE 明文会话继续"));
                None
            }
        }
    };

    // ---- 3. modesChanged ----
    let modes = build_modes_changed();
    match channel
        .request(
            "POST",
            "/command",
            &modes,
            "application/x-apple-binary-plist",
            &[],
            Duration::from_secs(5),
        )
        .await
    {
        Ok(resp) => emit_log(&format!("AIRPLAY: modesChanged [{}]", resp.first_line)),
        Err(e) => emit_log(&format!("AIRPLAY: modesChanged 失败 - {e}")),
    }

    // ---- 4. 计时服务 + 会话级 SETUP ----
    let timing = TimingServer::start().map_err(|e| format!("计时服务启动失败: {e}"))?;
    let body = build_session_setup(&p.device_id, timing.port, shared_secret.is_some());
    let resp = channel
        .request(
            "SETUP",
            "/setup",
            &body,
            "application/x-apple-binary-plist",
            &[],
            Duration::from_secs(10),
        )
        .await?;
    if resp.status() != 200 {
        return Err(format!(
            "会话 SETUP 被拒 [{}]（deviceID={}；接收端若启用设备过滤请选择该地址）",
            resp.first_line,
            p.device_id
        ));
    }
    let root = bplist::parse(&resp.body).map_err(|e| format!("SETUP 应答解析失败: {e}"))?;
    let event_port = port_of(root.get("eventPort"));
    let keep_alive_port = port_of(root.get("keepAlivePort"));
    emit_log(&format!(
        "AIRPLAY: ✓ 会话 SETUP eventPort={event_port} keepAlive={keep_alive_port:?}"
    ));

    // ---- 5. 事件通道 ----
    if event_port == 0 {
        return Err("SETUP 未返回 eventPort".to_string());
    }
    let event_stream = factory
        .open_tcp(&p.car_ip, event_port, 5_000)
        .ok_or_else(|| format!("事件通道连接失败 {}:{event_port}", p.car_ip))?;
    let (ev_r, ev_w) = event_stream.into_split();
    let (event_channel, event_rx) = RtspChannel::spawn(ev_r, ev_w, None);
    if let Some(shared) = &shared_secret {
        let (read_key, write_key) = derive_event_keys(shared);
        event_channel.set_cipher(ControlCipher::new(read_key, write_key));
    }
    spawn_event_dispatcher(
        event_rx,
        touch_tx,
        key_tx,
        (disp_w.max(1), disp_h.max(1)),
        (p.width.max(1), p.height.max(1)),
        stop.clone(),
    );

    // ---- 6. 屏幕流 SETUP ----
    let conn_id_screen = random_positive_i64();
    let stream_uuid = display_uuid
        .clone()
        .unwrap_or_else(|| "6E4E3B31-0E5B-4A8F-9F1B-2C5D7E9A0C11".to_string());
    let screen_body = build_screen_setup(conn_id_screen, &stream_uuid);
    let resp = channel
        .request(
            "SETUP",
            "/",
            &screen_body,
            "application/x-apple-binary-plist",
            &[],
            Duration::from_secs(8),
        )
        .await?;
    if resp.status() != 200 {
        return Err(format!(
            "屏幕流 SETUP 被拒 [{}]（uuid={}）",
            resp.first_line, stream_uuid
        ));
    }
    let root = bplist::parse(&resp.body).map_err(|e| format!("屏幕流应答解析失败: {e}"))?;
    let data_port = root
        .get("streams")
        .and_then(Value::as_array)
        .and_then(|a| a.first())
        .map(|s| port_of(s.get("dataPort")))
        .unwrap_or(0);
    if data_port == 0 {
        return Err("屏幕流未返回 dataPort".to_string());
    }
    let cipher = match &shared_secret {
        Some(shared) => ScreenCipher::ChaCha(stream_key(shared, conn_id_screen as u64)),
        None => {
            let (k, iv) = aes_stream_keys_zero(conn_id_screen as u64);
            ScreenCipher::AesCtr(crate::crypto::Aes128CtrStream::new(&k, &iv))
        }
    };
    let screen_stream = factory
        .open_tcp(&p.car_ip, data_port, 5_000)
        .ok_or_else(|| format!("屏幕流连接失败 {}:{data_port}", p.car_ip))?;
    // into_split 返回 (读半部, 写半部)；屏幕流只推不收，读半部直接丢弃
    let (_screen_r, screen_w) = screen_stream.into_split();
    let screen = ScreenSender::new(screen_w, cipher, p.width, p.height, keep_alive_stats);
    emit_log(&format!(
        "AIRPLAY: ✓ 主屏流就绪 dataPort={data_port} connId={conn_id_screen} {}x{}",
        p.width, p.height
    ));

    // ---- 7. 音频流 SETUP（可选）----
    let audio = if p.enable_audio {
        negotiate_audio(&channel, &factory, shared_secret.as_ref(), p).await
    } else {
        None
    };

    // ---- 8. RECORD + 心跳 ----
    let rec = channel
        .request(
            "RECORD",
            "/",
            &bplist::encode(&Value::dict(vec![])),
            "application/x-apple-binary-plist",
            &[],
            Duration::from_secs(5),
        )
        .await?;
    if rec.status() != 200 {
        return Err(format!("RECORD 被拒 [{}]", rec.first_line));
    }
    emit_log("AIRPLAY: ✓ RECORD 完成——会话已激活");
    emit_state("connected", "AirPlay 会话已激活");

    spawn_keepalive_loops(
        channel.clone(),
        factory.clone(),
        keep_alive_port,
        p.car_ip.clone(),
        stop.clone(),
    );

    Ok(AirplayOutcome {
        screen,
        audio,
        keep_alive_stats,
    })
}

// ---------------------------------------------------------------- /info

async fn fetch_info(channel: &Arc<RtspChannel>) -> Result<Value, String> {
    let body = bplist::encode(&Value::dict(vec![]));
    let resp = channel
        .request(
            "GET",
            "/info",
            &body,
            "application/x-apple-binary-plist",
            &[],
            Duration::from_secs(10),
        )
        .await?;
    if resp.status() != 200 {
        return Err(format!("/info 应答 [{}]", resp.first_line));
    }
    if resp.body.is_empty() {
        return Ok(Value::dict(vec![]));
    }
    bplist::parse(&resp.body).map_err(|e| format!("/info 解析失败: {e}"))
}

// ---------------------------------------------------------------- SETUP 报文体

/// SETUP 能力清单（接收端按「车机支持 ∩ 提议」计算 enabledFeatures；
/// 只提议 videoPlayback 会让视频管线整个不启用——奥迪 400 教训）。
const CONTROLLER_FEATURES: &[&str] = &[
    "uiContext",
    "viewAreas",
    "cornerMasks",
    "focusTransfer",
    "h.264Level5.1",
    "mainBuffered",
    "altScreen",
    "enhancedSiri",
    "hevc",
    "sessionManagement",
    "logTransfer",
    "iAPChannel",
    "vehicleStateProtocol",
    "videoPlayback",
];

fn build_session_setup(device_id: &str, timing_port: u16, encrypted: bool) -> Vec<u8> {
    let session_uuid = random_uuid_upper();
    let mut fields: Vec<(&str, Value)> = vec![
        ("deviceID", Value::str(device_id)),
        ("macAddress", Value::str("aa:bb:cc:dd:ee:ff")),
        ("name", Value::str("iPhone")),
        ("model", Value::str("iPhone14,2")),
        ("osName", Value::str("iPhone OS")),
        ("osBuildVersion", Value::str("19A404")),
        ("osVersion", Value::str("15.0.2")),
        ("sourceVersion", Value::str("566.25.21")),
        ("timingPort", Value::int(timing_port as i64)),
        ("sessionUUID", Value::str(&session_uuid)),
        ("sessionCorrelationUUID", Value::str(&random_uuid_upper())),
        ("statsCollectionEnabled", Value::bool(false)),
        ("updateSessionRequest", Value::bool(false)),
        ("keepAliveLowPower", Value::bool(true)),
        (
            "features",
            Value::array(CONTROLLER_FEATURES.iter().map(|f| Value::str(f)).collect()),
        ),
    ];
    if !encrypted {
        // AirPlayEncryptionType::NONE = 1<<0（未配对会话必须显式声明，否则被当
        // 「声明加密但无密钥」拒收）。
        fields.push(("et", Value::int(1)));
    }
    bplist::encode(&Value::dict(fields))
}

fn build_modes_changed() -> Vec<u8> {
    let app_states = Value::array(vec![
        Value::dict(vec![("appStateID", Value::int(2)), ("entity", Value::int(0))]),
        Value::dict(vec![("appStateID", Value::int(3)), ("entity", Value::int(0))]),
        Value::dict(vec![
            ("appStateID", Value::int(1)),
            ("entity", Value::int(0)),
            ("speechMode", Value::int(-1)),
        ]),
    ]);
    let resources = Value::array(vec![
        Value::dict(vec![
            ("resourceID", Value::int(1)),
            ("entity", Value::int(1)),
            ("permanentEntity", Value::int(1)),
        ]),
        Value::dict(vec![
            ("resourceID", Value::int(2)),
            ("entity", Value::int(1)),
            ("permanentEntity", Value::int(1)),
        ]),
    ]);
    bplist::encode(&Value::dict(vec![
        ("type", Value::str("modesChanged")),
        (
            "params",
            Value::dict(vec![("appStates", app_states), ("resources", resources)]),
        ),
    ]))
}

fn build_screen_setup(conn_id: i64, uuid: &str) -> Vec<u8> {
    bplist::encode(&Value::dict(vec![(
        "streams",
        Value::array(vec![Value::dict(vec![
            ("type", Value::int(110)), // 110=主屏（100 是音频，会开 UDP 端口）
            ("streamConnectionID", Value::int(conn_id)),
            ("uuid", Value::str(uuid)),
            ("latencyMs", Value::int(25)),
        ])]),
    )]))
}

async fn negotiate_audio(
    channel: &Arc<RtspChannel>,
    factory: &Arc<dyn SocketProvider>,
    shared: Option<&[u8; 32]>,
    p: &AirplayParams,
) -> Option<AudioOut> {
    let conn_id = random_positive_i64();
    let body = bplist::encode(&Value::dict(vec![(
        "streams",
        Value::array(vec![Value::dict(vec![
            ("type", Value::int(100)),          // 主音频
            ("streamConnectionID", Value::int(conn_id)),
            ("audioFormat", Value::int(1 << 15)), // PCM 48k 立体声位图
            ("audioType", Value::str("media")),   // 必须 media（compatibility 档无声）
            ("audioLatencyMs", Value::int(0)),
            ("input", Value::bool(false)),
            ("supportsRTPPacketRedundancy", Value::bool(false)),
            ("spf", Value::int(352)), // AirPlay 标准 SPF（512 在真车无声）
        ])]),
    )]));
    // 音频 SETUP 必须用**绝对 URI**（cardeck runAudioStream 同款：`rtsp://host:port/`）；
    // 屏幕流与初始 SETUP 才用相对路径。URI 用反会 400/404。
    let audio_uri = format!("rtsp://{}:{}/", p.car_ip, p.airplay_port);
    let resp = match channel
        .request(
            "SETUP",
            &audio_uri,
            &body,
            "application/x-apple-binary-plist",
            &[],
            Duration::from_secs(8),
        )
        .await
    {
        Ok(r) => r,
        Err(e) => {
            emit_log(&format!("AIRPLAY: 音频 SETUP 失败 - {e}"));
            return None;
        }
    };
    if resp.status() != 200 {
        emit_log(&format!("AIRPLAY: 音频流被拒 [{}]", resp.first_line));
        return None;
    }
    let root = bplist::parse(&resp.body).ok()?;
    let stream = root.get("streams").and_then(Value::as_array)?.first()?;
    let data_port = port_of(stream.get("dataPort"));
    let control_port = port_of(stream.get("controlPort"));
    if data_port == 0 {
        emit_log("AIRPLAY: 音频流未返回 dataPort");
        return None;
    }
    let key = match shared {
        Some(s) => stream_key(s, conn_id as u64),
        None => {
            emit_log("AIRPLAY: 未配对会话无法派生音频密钥，跳过音频");
            return None;
        }
    };
    let udp = factory.open_udp(&p.car_ip, data_port)?;
    let sync = if control_port != 0 {
        factory.open_udp(&p.car_ip, control_port)
    } else {
        None
    };
    emit_log(&format!(
        "AIRPLAY: ✓ 音频流就绪 data={data_port} control={control_port}（PCM48k/SPF352）"
    ));
    Some(AudioOut::new(udp, sync, key, 100))
}

// ---------------------------------------------------------------- 心跳

/// 三条心跳里的前两条（屏幕 keepAlive 帧在视频 pump 里按 1s 节流）：
///  - UDP keepAlive：4B 随机、5s（10s 与车机看门狗等长必输——奥迪 3315 实测 10.001s 被拆）
///  - RTSP /feedback：1s 空体 POST（车机 ~3s 看门狗）
fn spawn_keepalive_loops(
    channel: Arc<RtspChannel>,
    factory: Arc<dyn SocketProvider>,
    keep_alive_port: u16,
    car_ip: String,
    stop: Arc<Notify>,
) {
    if keep_alive_port != 0 {
        let stop_k = stop.clone();
        tokio::spawn(async move {
            let Some(udp) = factory.open_udp(&car_ip, keep_alive_port) else {
                emit_log("AIRPLAY: keepAlive UDP 端口不可用");
                return;
            };
            let mut n: u32 = 0;
            loop {
                tokio::select! {
                    _ = stop_k.notified() => return,
                    _ = tokio::time::sleep(Duration::from_secs(5)) => {}
                }
                n = n.wrapping_add(1);
                if udp.send(&n.to_be_bytes()).await.is_err() {
                    return;
                }
            }
        });
    }

    tokio::spawn(async move {
        loop {
            tokio::select! {
                _ = stop.notified() => return,
                _ = tokio::time::sleep(Duration::from_secs(1)) => {}
            }
            if channel
                .request(
                    "POST",
                    "/feedback",
                    &[],
                    "application/octet-stream",
                    &[],
                    Duration::from_secs(3),
                )
                .await
                .is_err()
            {
                return; // 控制通道已断
            }
        }
    });
}

// ---------------------------------------------------------------- 事件通道分发

fn spawn_event_dispatcher(
    mut inbound: tokio::sync::mpsc::UnboundedReceiver<crate::rtsp::RtspMessage>,
    touch_tx: mpsc::UnboundedSender<TouchInput>,
    key_tx: mpsc::UnboundedSender<u32>,
    display_size: (u32, u32),
    target_size: (u32, u32),
    stop: Arc<Notify>,
) {
    tokio::spawn(async move {
        let mut bad_commands = 0usize;
        let mut tip_down = false; // 上一次报告的按下状态（down/move/up 边沿检测）
        loop {
            let msg = tokio::select! {
                _ = stop.notified() => return,
                m = inbound.recv() => match m {
                    Some(m) => m,
                    None => return,
                },
            };
            if !msg.first_line.contains("/command") {
                continue;
            }
            let command = match bplist::parse(&msg.body) {
                Ok(v) => v,
                Err(_) => {
                    bad_commands += 1;
                    if bad_commands <= 3 {
                        emit_log(&format!(
                            "EVENT: /command plist 解析失败 {}B（累计 {bad_commands}）",
                            msg.body.len()
                        ));
                    }
                    continue;
                }
            };
            let cmd_type = command.get("type").and_then(Value::as_str).unwrap_or("");
            match cmd_type {
                "hidSendReport" => {
                    let report = command
                        .get("params")
                        .and_then(|p| p.get("report"))
                        .and_then(Value::as_bytes);
                    if let Some(report) = report {
                        handle_hid_report(
                            report,
                            &touch_tx,
                            &key_tx,
                            display_size,
                            target_size,
                            &mut tip_down,
                        );
                    }
                }
                "hidSetInputMode" | "ping" | "fps" => {}
                other => {
                    if bad_commands <= 8 {
                        emit_log(&format!("EVENT: 未处理的车机命令 type={other}"));
                    }
                }
            }
        }
    });
}

/// HID 触摸报告 → CarLife 触摸。
///
/// 坐标格式（cardeck 奥迪实测；BMW 按 /info hidDevices 描述符校准，见 docs）：
/// 5 字节 `[tip 1bit][X u16LE][Y u16LE]`，X/Y 为车机显示空间绝对坐标；
/// 这里缩放到 CarLife 编码空间（VideoConfig 画布）。
///
/// 动作边沿：按下沿 → DOWN(1)，保持 → MOVE(3)，抬起沿 → UP(2)——
/// CarLife 触摸通道需要完整的 down/move/up 序列才认点按。
fn handle_hid_report(
    report: &[u8],
    touch_tx: &mpsc::UnboundedSender<TouchInput>,
    key_tx: &mpsc::UnboundedSender<u32>,
    display: (u32, u32),
    target: (u32, u32),
    tip_down: &mut bool,
) {
    if report.len() < 5 {
        // 短报告按 Consumer 媒体键候选处理（奥迪 uuid=0 是媒体键设备）
        if let Some(code) = report.last().and_then(|_| consumer_key_of(report)) {
            let _ = key_tx.send(code);
        }
        return;
    }
    let pressed = report[0] & 0x01 != 0;
    let x_raw = u16::from_le_bytes([report[1], report[2]]) as u32;
    let y_raw = u16::from_le_bytes([report[3], report[4]]) as u32;
    let x = (x_raw * target.0 / display.0).min(target.0 - 1);
    let y = (y_raw * target.1 / display.1).min(target.1 - 1);
    // 动作边沿：按下沿 → DOWN，保持 → MOVE，抬起沿 → UP。
    // 用 **CarLife 语义**（0=DOWN/1=UP/2=MOVE），不是 Android MotionEvent 的 1/2/3——
    // 下游 carlife::inject_touch 直接按 CarLife 裸结构封包。
    let action = match (*tip_down, pressed) {
        (false, true) => crate::carlife::TOUCH_DOWN,
        (true, true) => crate::carlife::TOUCH_MOVE,
        (true, false) => crate::carlife::TOUCH_UP,
        (false, false) => return, // 无状态变化
    };
    *tip_down = pressed;
    let _ = touch_tx.send(TouchInput { action, x, y });
}

/// Consumer 页媒体键的极简映射。
fn consumer_key_of(report: &[u8]) -> Option<u32> {
    let usage = *report.last()?;
    let code = match usage {
        0xCD => 0x10, // play/pause
        0xB5 => 0x0E, // next
        0xB6 => 0x0F, // prev
        _ => return None,
    };
    Some(code)
}

// ---------------------------------------------------------------- 工具

/// RtspTransport 适配：pairing 层是同步 trait，跑在多线程 runtime 的 worker 上，
/// 用 block_in_place + block_on 桥接（配对只有 M1..M4 四次往返，代价可忽略）。
struct RtspTransport<'a> {
    channel: &'a Arc<RtspChannel>,
}

impl TlvTransport for RtspTransport<'_> {
    fn exchange(&mut self, path: &str, body: &[u8]) -> Result<Vec<u8>, String> {
        let channel = self.channel.clone();
        let result = tokio::task::block_in_place(|| {
            tokio::runtime::Handle::current().block_on(async move {
                channel
                    .request(
                        "POST",
                        path,
                        body,
                        "application/pairing+tlv8",
                        &[("X-Apple-HKP", "2".to_string()), ("X-Apple-PD", "1".to_string())],
                        Duration::from_secs(10),
                    )
                    .await
            })
        });
        match result {
            Ok(resp) => {
                if resp.status() != 200 {
                    Err(format!("{path} 应答 [{}]", resp.first_line))
                } else {
                    Ok(resp.body)
                }
            }
            Err(e) => Err(e),
        }
    }
}

/// 事件通道控制密钥。
///
/// **注意：事件通道有独立的 HKDF 域**，不能复用控制通道的 `Control-Salt`——
/// 对照 cardeck `connectEventChannel`：
///   phoneWriteKey = HKDF(secret, "Events-Salt", "Events-Read-Encryption-Key")
///   phoneReadKey  = HKDF(secret, "Events-Salt", "Events-Write-Encryption-Key")
///   ControlCipher(phoneReadKey, phoneWriteKey)
/// 即标签按「配件视角」命名，与我们 read/write 是交叉的。
/// 曾错用 Control-Salt 派生 → 事件通道解密必失败 → 触摸/命令全丢。
fn derive_event_keys(shared: &[u8; 32]) -> ([u8; 32], [u8; 32]) {
    let write: [u8; 32] = crate::crypto::hkdf_sha512(
        shared,
        b"Events-Salt",
        b"Events-Read-Encryption-Key",
        32,
    )
    .try_into()
    .unwrap();
    let read: [u8; 32] = crate::crypto::hkdf_sha512(
        shared,
        b"Events-Salt",
        b"Events-Write-Encryption-Key",
        32,
    )
    .try_into()
    .unwrap();
    (read, write)
}

/// 屏幕/音频流密钥：HKDF-SHA512(shared, "DataStream-Salt<connId>", "DataStream-Output-Encryption-Key")。
pub fn stream_key(shared: &[u8; 32], conn_id: u64) -> [u8; 32] {
    let salt = format!("DataStream-Salt{conn_id}");
    crate::crypto::hkdf_sha512(
        shared,
        salt.as_bytes(),
        b"DataStream-Output-Encryption-Key",
        32,
    )
    .try_into()
    .unwrap()
}

/// et=NONE 会话的 AES 流密钥（会话密钥全零；catplay ScreenFrameCodec 同款）。
fn aes_stream_keys_zero(conn_id: u64) -> ([u8; 16], [u8; 16]) {
    fn digest(prefix: &str, conn_id: u64) -> [u8; 16] {
        let conn = conn_id.to_string();
        let mut out = [0u8; 16];
        out.copy_from_slice(
            &crate::crypto::sha512(&[prefix.as_bytes(), conn.as_bytes(), &[0u8; 16]])[..16],
        );
        out
    }
    (
        digest("AirPlayStreamKey", conn_id),
        digest("AirPlayStreamIV", conn_id),
    )
}

/// /info displays[].uuid 三种编码统一字符串化（字符串 / 16B UUID / UID 整数）。
fn uuid_text(v: Option<&Value>) -> Option<String> {
    match v? {
        Value::String(s) if !s.is_empty() => Some(s.clone()),
        Value::Data(d) if d.len() == 16 => {
            let hex: String = d.iter().map(|b| format!("{b:02x}")).collect();
            Some(format!(
                "{}-{}-{}-{}-{}",
                &hex[0..8],
                &hex[8..12],
                &hex[12..16],
                &hex[16..20],
                &hex[20..]
            ))
        }
        Value::Int(i) => Some(i.to_string()),
        _ => None,
    }
}

fn port_of(v: Option<&Value>) -> u16 {
    match v {
        Some(Value::Int(i)) => (*i).clamp(0, u16::MAX as i64) as u16,
        _ => 0,
    }
}

fn random_positive_i64() -> i64 {
    let mut b = [0u8; 8];
    getrandom::getrandom(&mut b).unwrap_or_default();
    (i64::from_le_bytes(b) & i64::MAX).max(1)
}

fn random_uuid_upper() -> String {
    uuid::Uuid::new_v4().simple().to_string().to_uppercase()
}
