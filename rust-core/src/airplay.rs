//! AirPlay 发送端会话（协商整体下沉 Rust —— 与参考文档的关键差异，见 docs/NEGOTIATION-GAPS.md）。
//!
//! 流程（cardeck 真车验证的顺序，Audi MIB3 / BMW iD8.5 通用骨架）：
//!  1. `GET /info`（带空 bplist 体 + plist Content-Type，老固件对缺体的 /info 回 400/455）
//!  2. `/pair-verify` M1..M4（本机 ed25519 长期身份）→ 控制通道密钥
//!  3. 会话级 `SETUP /setup`（先建立会话，MIB3 在此之前拒绝资源命令）
//!  4. SETUP 后刷新 /info，再 modesChanged（在屏幕流 SETUP 前声明资源）
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

use parking_lot::Mutex;

use tokio::net::tcp::{OwnedReadHalf, OwnedWriteHalf};
use tokio::net::{TcpStream, UdpSocket};
use tokio::sync::mpsc;
use crate::session::{spawn_scoped, SessionStop};

use crate::audio_out::AudioOut;
use crate::bplist::{self, Value};
use crate::pairing::{ControllerIdentity, PairSetupClient, PairVerifyClient, TlvTransport};
use crate::rtsp::{ControlCipher, RtspChannel};
use crate::screen::{ScreenCipher, ScreenSender};
use crate::timing::TimingServer;
use crate::{emit_iap, emit_log, emit_state};

#[cfg(test)]
#[path = "negotiation_tests.rs"]
mod negotiation_tests;

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
    /// 设置里的 CarLife 编码分辨率（仅作 `/info` 无显示器信息时的兜底）。
    pub width: u32,
    pub height: u32,
    /// **触摸的显示空间**（接收端屏幕像素）。`/info` 报 0x0 时用它；
    /// 真车会给真实尺寸，WinPlay 这类接收端固定报 0x0（它按自己的窗口 2732x794 算 HID 坐标）。
    /// 给错会让触摸整体缩放偏移——越靠右/下越明显（"按钮很难点上"）。
    pub touch_display: Option<(u32, u32)>,
    /// 当前 CarLife 编码分辨率（共享单元）：
    /// `/info` 报到车机真实显示尺寸后由协商写入，手机在 `VIDEO_ENCODER_INIT_DONE`
    /// 里回报实际选择后由 CarLife 层覆盖。触摸坐标换算与 VideoConfig 都读它。
    pub encoder_size: Arc<Mutex<(u32, u32)>>,
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
    pub timing: TimingServer,
}

/// 跑完整个 AirPlay 协商，返回屏幕/音频发送器（交给 pump 任务）。
pub async fn negotiate(
    read: OwnedReadHalf,
    write: OwnedWriteHalf,
    p: &AirplayParams,
    factory: Arc<dyn SocketProvider>,
    touch_tx: mpsc::UnboundedSender<TouchInput>,
    key_tx: mpsc::UnboundedSender<u32>,
    iap_rx: mpsc::Receiver<Vec<u8>>,
    stop: Arc<SessionStop>,
) -> Result<AirplayOutcome, String> {
    let (mut channel, _control_inbound) = RtspChannel::spawn(read, write, None, stop.clone());

    // ---- 1. /info（**best-effort**，不可用不致命）----
    //
    // Only read the receiver public key before pairing; it distinguishes receivers
    // sharing an IP. MIB3 may reject this until a media session exists, so failure
    // is nonfatal and full geometry is always refreshed after the initial SETUP.
    let info = match fetch_info(&channel).await {
        Ok(v) => v,
        Err(e) => {
            emit_log(&format!(
                "AIRPLAY: /info 不可用（{e}）——未配对连接上被 455 拒是已知行为，\
                 继续走 pair-verify（显示尺寸/能力先用设置值），会话 SETUP 后会再试一次"
            ));
            Value::dict(vec![])
        }
    };
    let receiver_pk = info
        .get("pk")
        .and_then(Value::as_bytes)
        .and_then(|b| <[u8; 32]>::try_from(b).ok());

    // ---- 2. 配对：**先试 pair-verify（快路径），不行再 pair-setup** ----
    //
    // 为什么顺序要反过来：pair-setup 会在车机上**注册一条新配对**。我们以前每次都先
    // 做 pair-setup，第二次上车车机那边已经有我们的记录、又不接受重复注册，结果
    // 车机报"连接失败"、手机报"没发现 CarPlay 车机"，只有两边都删除配对才能重来
    // （2026-10-25 现场）。真 iPhone 的第二次连接只走 pair-verify，不碰 pair-setup。
    //
    // 因此：有身份就先 verify；verify 失败（未配对/车机重置过）才 pair-setup 再 verify。
    let identity = ControllerIdentity::load_or_create(std::path::Path::new(&p.identity_path))
        .map_err(|e| format!("身份载入失败: {e}"))?;
    let pair_log = |s: &str| emit_log(&format!("PAIR: {s}"));

    let mut shared_secret: Option<[u8; 32]> = None;

    // 与**这个接收端**是否配对过：pair-verify 只能验证"已存在的配对"。
    // 对 WinPlay/DiPlay 这类全新接收端，我们此前直接跳过快路径 verify，对方根本没有我们的
    // 配对记录，于是 verify 表面走完、派生的密钥却对不上，之后每个加密请求都被静默丢弃
    // （2026-10-25 23:19 DiPlay 现场：POST /command 与 SETUP /setup 一律无响应）。
    //
    // 标记的键 = **接收端的公钥指纹**（/info.pk 前 4 字节），拿不到才退回 IP：
    //   同一台手机可以连多台接收端，IP 常常一样（192.168.1.35 / 172.16.222.97），
    //   用 IP 当键会把"另一台没配过的接收端"误判成已配对；公钥指纹则是每台接收端唯一且稳定。
    // 标记文件与身份文件同目录（每台手机各自一份），不会跨设备互相覆盖。
    let receiver_tag = match receiver_pk {
        Some(pk) => format!("{:02x}{:02x}{:02x}{:02x}", pk[0], pk[1], pk[2], pk[3]),
        None => p.car_ip.replace('.', "_"),
    };
    let paired_marker = format!("{}.paired-{}", p.identity_path, receiver_tag);
    let paired_before = std::path::Path::new(&paired_marker).exists();
    emit_log(&format!(
        "PAIR: 身份 {}… / 接收端 {}（{}）——{}",
        format!("{:02x}{:02x}", identity.public_key[0], identity.public_key[1]),
        receiver_tag,
        p.car_ip,
        if paired_before { "已配对，走 verify 快路径" } else { "未配对，先 pair-setup" }
    ));

    // 2a. 快路径：只在本机与这个接收端配对过时才直接 verify
    if paired_before {
        let mut transport = RtspTransport { channel: &channel };
        match PairVerifyClient::run(&identity, &mut transport, receiver_pk.as_ref(), &pair_log) {
            Ok(keys) => {
                channel.set_cipher(ControlCipher::new(keys.read_key, keys.write_key));
                emit_log("AIRPLAY: ✓ pair-verify 直接用上次配对完成（跳过 pair-setup，不再重复注册）");
                shared_secret = Some(keys.shared_secret);
            }
            Err(e) => {
                emit_log(&format!(
                    "PAIR: pair-verify 未通过（{e}）——按真 iPhone 逻辑先做 pair-setup 注册本机"
                ));
                // 失败会污染这条连接的状态机：换一条新的再做 pair-setup
                match factory.open_tcp(&p.car_ip, p.airplay_port, 5_000) {
                    Some(stream) => {
                        let (r, w) = stream.into_split();
                        let (fresh, _rx) = RtspChannel::spawn(r, w, None, stop.clone());
                        channel = fresh;
                        emit_log("PAIR: 已换新控制连接");
                    }
                    None => emit_log("PAIR: ⚠ 新控制连接打开失败，沿用旧连接"),
                }
            }
        }
    }

    // 2b. 慢路径：pair-setup → pair-verify
    if shared_secret.is_none() {
        let mut pairing_forbidden = false;
        let mut pairing_unsupported = false;
        {
            let mut transport = RtspTransport { channel: &channel };
            match PairSetupClient::run(&identity, &mut transport, &pair_log) {
                Ok(_) => {
                    emit_log("PAIR: ✓ pair-setup 完成（长期公钥已交换）");
                    let _ = std::fs::write(&paired_marker, b"1");
                }
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

        if pairing_forbidden {
            match factory.open_tcp(&p.car_ip, p.airplay_port, 5_000) {
                Some(stream) => {
                    let (r, w) = stream.into_split();
                    let (fresh, _rx) = RtspChannel::spawn(r, w, None, stop.clone());
                    channel = fresh;
                    emit_log("PAIR: 已换新控制连接");
                }
                None => emit_log("PAIR: ⚠ 新控制连接打开失败，沿用旧连接（可能仍被 403 污染）"),
            }
        }

        if !pairing_unsupported {
            let mut transport = RtspTransport { channel: &channel };
            match PairVerifyClient::run(&identity, &mut transport, receiver_pk.as_ref(), &pair_log) {
                Ok(keys) => {
                    channel.set_cipher(ControlCipher::new(keys.read_key, keys.write_key));
                    emit_log("AIRPLAY: ✓ pair-verify 完成，控制通道已加密");
                    let _ = std::fs::write(&paired_marker, b"1");
                    shared_secret = Some(keys.shared_secret);
                }
                Err(e) => {
                    emit_log(&format!(
                        "AIRPLAY: pair-verify 未通过（{e}）——按 et=NONE 明文会话继续"
                    ));
                }
            }
        }
    }

    let MediaSession { info, timing, event_port, keep_alive_port } =
        setup_media_session(&channel, p, info, shared_secret.is_some(), stop.clone()).await?;

    // ---- 2.6 用 /info 结果校准显示尺寸与能力（上面拿不到就全用兜底）----
    let keep_alive_stats = p.keep_alive_stats
        || info
            .get("keepAliveSendStatsAsBody")
            .and_then(Value::as_bool)
            .unwrap_or(false);
    let (display_uuid, disp_w, disp_h) = receiver_display(&info);
    // 车机能力位图：判断是否支持 HEVC 等能力，只能靠它（我们无法向 CarLife 指定 codec）
    let car_features = info.get("features").and_then(Value::as_int).unwrap_or(0);
    emit_log(&format!(
        "AIRPLAY: /info displays[0].uuid={:?} {}x{} features=0x{:x}",
        display_uuid, disp_w, disp_h, car_features
    ));

    // 车机真实显示分辨率 → CarLife 编码请求。
    // 设置里的分辨率只是"用户以为的"，实际以 `/info` 为准：两侧不一致时
    // 车机要把小画面拉伸整屏（发虚），所以这里直接改成车机原生尺寸。
    if disp_w >= 320 && disp_h >= 240 {
        let target = (even(disp_w.max(2)), even(disp_h.max(2)));
        *p.encoder_size.lock() = target;
        emit_log(&format!(
            "AIRPLAY: 车机实际 {}x{} → 请求 CarLife 按 {}x{} 编码（设置值 {}x{} 仅作兜底）",
            disp_w, disp_h, target.0, target.1, p.width, p.height
        ));
    } else {
        emit_log(&format!(
            "AIRPLAY: /info 未提供有效显示器尺寸（{disp_w}x{disp_h}），沿用设置 {}x{}",
            p.width, p.height
        ));
    }

    // ---- 5. 事件通道 ----
    if event_port == 0 {
        return Err("SETUP 未返回 eventPort".to_string());
    }
    let event_stream = factory
        .open_tcp(&p.car_ip, event_port, 5_000)
        .ok_or_else(|| format!("事件通道连接失败 {}:{event_port}", p.car_ip))?;
    let (ev_r, ev_w) = event_stream.into_split();
    let (event_channel, event_rx) = RtspChannel::spawn(ev_r, ev_w, None, stop.clone());
    if let Some(shared) = &shared_secret {
        let (read_key, write_key) = derive_event_keys(shared);
        event_channel.set_cipher(ControlCipher::new(read_key, write_key));
    }
    // 事件通道必须活到会话结束：`RtspChannel` 的 `Drop` 会 abort 读任务并关闭 socket。
    // 之前这里只是临时变量，函数返回时就被丢弃 → 事件通道刚连上即被我们自己关掉，
    // 接收端（WinPlay）的 close 回调把 eventSock 清回 null，触摸全进 pendingEvents
    // 队列，攒到 64 条报「事件通道发送队列超限」（现场 2026-10-23 23:33 定位）。
    {
        let stop_ev = stop.clone();
        spawn_scoped(stop.clone(), async move {
            let _keep_alive = event_channel;
            stop_ev.notified().await;
        });
    }
    spawn_event_dispatcher(
        event_rx,
        channel.clone(),
        touch_tx,
        key_tx,
        // 触摸的「显示空间」分母，优先级：
        //  ① /info 报的显示器尺寸（真车会给真实值）
        //  ② `--touch-display` 手动指定（WinPlay 固定报 0x0，但它按自己窗口
        //     2732x794 算 HID 坐标；给错就整体缩放偏移 →「按钮很难点上」）
        //  ③ 协商的屏幕尺寸
        // 绝不能用 max(1) 当分母：x_raw*编码宽/1 溢出后被 clamp 到边界，
        // 表现是「每一下都落在右下角」（2026-10-23 23:38 定位）。
        {
            let (dw, dh) = match (disp_w, disp_h) {
                (0, _) | (_, 0) => p.touch_display.unwrap_or((p.width, p.height)),
                v => v,
            };
            (dw.max(1), dh.max(1))
        },
        p.encoder_size.clone(),
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
    // 画布尺寸取当前编码分辨率（/info 已写入；手机回报实际值后 CarLife 层会再覆盖）
    let (enc_w, enc_h) = *p.encoder_size.lock();
    let screen = ScreenSender::new(screen_w, cipher, enc_w, enc_h, keep_alive_stats);
    emit_log(&format!(
        "AIRPLAY: ✓ 主屏流就绪 dataPort={data_port} connId={conn_id_screen} {enc_w}x{enc_h}"
    ));
    // 屏幕流一建好就发**待机卡片**（真有画面，车机不再黑屏），避免车机 1.5s 收不到数据就拆通道。
    // 素材是母版分辨率（1280x372 宽屏 / 1280x720 16:9），VideoConfig 按母版尺寸下发，车机放大到全屏。
    let mut screen = screen;
    match standby_for_aspect(enc_w, enc_h) {
        Some(set) => {
            screen.set_size(set.width, set.height);
            if let Some(first) = set.frames.first() {
                let nals = crate::screen::annexb_nals(first);
                let sps = nals.iter().find(|n| crate::screen::nal_type(n) == 7).copied();
                let pps = nals.iter().find(|n| crate::screen::nal_type(n) == 8).copied();
                if let (Some(sps), Some(pps)) = (sps, pps) {
                    let avcc = crate::screen::build_avcc(sps, pps);
                    screen.send_config(&avcc).await.map_err(|e| format!("等待页配置写失败 kind={:?} os={:?}: {e}", e.kind(), e.raw_os_error()))?;
                    let frame_ok = screen.send_video_annexb(first).await
                        .map_err(|e| format!("等待页首帧写失败 kind={:?} os={:?}: {e}", e.kind(), e.raw_os_error()))?;
                    let cfg_ok = true;
                    emit_log(&format!(
                        "AIRPLAY: 已写出等待页母版 {}x{}（config={cfg_ok} idr={frame_ok}，{} 帧素材）；接收端显示仍需实车核对",
                        set.width, set.height, set.frames.len()
                    ));
                } else {
                    emit_log("AIRPLAY: ⚠ 待机素材缺少 SPS/PPS——跳过卡片");
                }
            }
        }
        None => emit_log("AIRPLAY: 没有匹配宽高比的待机素材——跳过卡片"),
    }


    // MIB3 虚拟 iAP2：控制通道出站、事件通道入站；与当前引擎一起取消。
    spawn_iap_sender(channel.clone(), iap_rx, stop.clone());
    emit_state("iap_ready", "主屏 SETUP 已完成，可启动虚拟 iAP2 控制链路");

    // ---- 7. （音频挪到 RECORD 之后，见第 8 步）----
    // 宝马这台（AirTunes/450.14）在屏幕流 SETUP 后约 1.5s 就关控制通道；
    // 若这段时间还在做音频 SETUP，RECORD 永远发不出去（21:13 现场）。

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

    // ---- 9. 音频流 SETUP（会话已激活后再试；失败只记录）----
    let audio = if p.enable_audio {
        negotiate_audio(&channel, &factory, shared_secret.as_ref(), p).await
    } else {
        None
    };

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
        timing,
    })
}

// ---------------------------------------------------------------- /info

fn receiver_display(info: &Value) -> (Option<String>, u32, u32) {
    let display = info.get("displays").and_then(Value::as_array).and_then(|v| v.first());
    let Some(display) = display else { return (None, 0, 0); };
    let dimension = |pixel_key: &str, legacy_key: &str| {
        [pixel_key, legacy_key].iter().find_map(|key| {
            display.get(key).and_then(Value::as_int)
                .and_then(|value| u32::try_from(value).ok()).filter(|v| *v > 0)
        }).unwrap_or(0)
    };
    let uuid = ["uuid", "UUID", "displayUUID"].iter()
        .find_map(|key| uuid_text(display.get(key)));
    (uuid, dimension("widthPixels", "width"), dimension("heightPixels", "height"))
}

struct MediaSession {
    info: Value,
    timing: TimingServer,
    event_port: u16,
    keep_alive_port: u16,
}

/// Post-pairing media bootstrap, exercised against a stateful TCP receiver in tests.
async fn setup_media_session(
    channel: &Arc<RtspChannel>,
    p: &AirplayParams,
    mut info: Value,
    encrypted: bool,
    stop: Arc<SessionStop>,
) -> Result<MediaSession, String> {
    // ---- 3. 计时服务 + 会话级 SETUP ----
    let timing = TimingServer::start(stop.clone()).map_err(|e| format!("计时服务启动失败: {e}"))?;
    let body = build_session_setup(&p.device_id, timing.port, encrypted);
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

    // Refresh after the session exists: MIB3 can reject /info before SETUP.
    if event_port == 0 { return Err("SETUP 未返回 eventPort".to_string()); }
    match fetch_info(channel).await {
        Ok(v) if v.as_dict().is_some_and(|d| !d.is_empty()) => {
            info = v;
            emit_log("AIRPLAY: 会话 SETUP 后 /info 已刷新");
        }
        Ok(_) => emit_log("AIRPLAY: 会话 SETUP 后 /info 为空，保留已知能力"),
        Err(e) => emit_log(&format!("AIRPLAY: 会话 SETUP 后 /info 仍不可用，保留已知能力：{e}")),
    }
    // MIB3 needs a session before /command, but resources before the screen SETUP.
    assert_modes(channel).await?;
    Ok(MediaSession { info, timing, event_port, keep_alive_port })
}

async fn fetch_info(channel: &Arc<RtspChannel>) -> Result<Value, String> {
    let body = bplist::encode(&Value::dict(vec![]));
    // /info 的方法在各代固件上不一样：
    //  - AirPlay 1 / WinPlay：`GET /info`（空 bplist 体 + plist Content-Type）
    //  - AirPlay 2（真车 BMW MGU-55 实测）：只认 **POST /info**，对 GET 直接回
    //    `455 Method Not Valid In This State`（2026-10-24 真车日志）
    // 所以按 GET → POST → GET（带 AirPlay 2 头）依次试，并把每次的原始应答记下来。
    let attempts: [(&str, &[(&str, String)]); 3] = [
        ("GET", &[]),
        ("POST", &[]),
        (
            "GET",
            &[
                ("X-Apple-ProtocolVersion", "1".to_string()),
                ("X-Apple-Device-ID", "0x0".to_string()),
                ("X-Apple-Session-ID", "1".to_string()),
            ],
        ),
    ];
    let mut failures = Vec::new();
    for (method, extra) in attempts {
        let resp = channel
            .request(method, "/info", &body, "application/x-apple-binary-plist", extra, Duration::from_secs(10))
            .await?;
        let status = resp.status();
        if status != 200 {
            // 把车机原话完整记下来：状态行 + 头部（Server/X-Apple-* 头能直接看出它在跑什么）
            let mut head: Vec<String> = resp.headers.iter().map(|(k, v)| format!("{k}={v}")).collect();
            head.sort();
            let noted = format!(
                "{method} /info → [{}] 头部[{}]",
                resp.first_line,
                if head.is_empty() { "无".to_string() } else { head.join(", ") }
            );
            emit_log(&format!("AIRPLAY: ✗ {noted}"));
            failures.push(noted);
            continue;
        }
        if resp.body.is_empty() {
            return Ok(Value::dict(vec![]));
        }
        return bplist::parse(&resp.body).map_err(|e| format!("/info 解析失败: {e}"));
    }
    // Capability discovery must never send malformed authentication requests.

    Err(format!("/info 三次尝试都被拒：{}", failures.join("；")))
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

async fn assert_modes(channel: &RtspChannel) -> Result<(), String> {
    let resp = channel.request(
        "POST", "/command", &build_modes_changed(), "application/x-apple-binary-plist",
        &[], Duration::from_secs(5),
    ).await?;
    if resp.status() != 200 { return Err(format!("modesChanged 被拒绝 [{}]", resp.first_line)); }
    emit_log(&format!("AIRPLAY: modesChanged [{}]", resp.first_line));
    Ok(())
}

#[cfg(test)]
mod event_tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;

    #[tokio::test]
    async fn ui_and_mode_requests_send_resource_ownership_reply_on_control_channel() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let client = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut server, _) = listener.accept().await.unwrap();
        let (r, w) = client.into_split();
        let stop = SessionStop::new(81);
        let (control, _) = RtspChannel::spawn(r, w, None, stop.clone());
        let (inbound, rx) = mpsc::unbounded_channel();
        let (touch, _) = mpsc::unbounded_channel();
        let (keys, _) = mpsc::unbounded_channel();
        spawn_event_dispatcher(rx, control, touch, keys, (1920, 720), Arc::new(Mutex::new((1920, 720))), stop.clone());
        for kind in ["changeModes", "requestUI"] {
        inbound.send(crate::rtsp::RtspMessage {
            first_line: "POST /command RTSP/1.0".into(),
            headers: Default::default(),
            body: bplist::encode(&Value::dict(vec![("type", Value::str(kind))])),
        }).unwrap();
        let mut buffer = Vec::new();
        let request = tokio::time::timeout(Duration::from_secs(1), async {
            loop {
                let mut chunk = [0u8; 4096];
                let n = server.read(&mut chunk).await.unwrap();
                assert!(n > 0);
                buffer.extend_from_slice(&chunk[..n]);
                if let Some(request) = crate::rtsp::parse_rtsp_messages(&mut buffer).pop() { break request; }
            }
        }).await.unwrap();
        let body = bplist::parse(&request.body).unwrap();
        assert_eq!(body.get("type").and_then(Value::as_str), Some("modesChanged"));
        let resources = body.get("params").unwrap().get("resources").unwrap().as_array().unwrap();
        assert_eq!(resources.len(), 2);
        assert!(resources.iter().all(|r| r.get("entity").and_then(Value::as_int) == Some(1)));
        server.write_all(format!("RTSP/1.0 200 OK\r\nCSeq: {}\r\nContent-Length: 0\r\n\r\n", request.cseq().unwrap()).as_bytes()).await.unwrap();
        }
        stop.cancel();
        tokio::time::timeout(Duration::from_secs(1), stop.wait_idle()).await.unwrap();
    }

    #[tokio::test]
    async fn virtual_iap_bytes_are_sent_as_control_commands_and_cancel_cleanly() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let client = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut server, _) = listener.accept().await.unwrap();
        let (r, w) = client.into_split();
        let stop = SessionStop::new(82);
        let (control, _) = RtspChannel::spawn(r, w, None, stop.clone());
        let (tx, rx) = mpsc::channel(2);
        spawn_iap_sender(control, rx, stop.clone());
        let packet = vec![0xff, 0x55, 2, 0, 0xee, 0x10];
        tx.send(packet.clone()).await.unwrap();
        let mut buffer = Vec::new();
        let request = tokio::time::timeout(Duration::from_secs(1), async {
            loop {
                let mut chunk = [0u8; 4096];
                let n = server.read(&mut chunk).await.unwrap();
                assert!(n > 0);
                buffer.extend_from_slice(&chunk[..n]);
                if let Some(request) = crate::rtsp::parse_rtsp_messages(&mut buffer).pop() { break request; }
            }
        }).await.unwrap();
        let body = bplist::parse(&request.body).unwrap();
        assert_eq!(request.first_line, "POST /command RTSP/1.0");
        assert_eq!(body.get("type").and_then(Value::as_str), Some("iAPSendMessage"));
        assert_eq!(body.get("params").unwrap().get("data").and_then(Value::as_bytes), Some(packet.as_slice()));
        stop.cancel();
        tokio::time::timeout(Duration::from_secs(1), stop.wait_idle()).await.unwrap();
        assert!(tx.send(vec![1]).await.is_err());
    }
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
    stop: Arc<SessionStop>,
) {
    if keep_alive_port != 0 {
        let stop_k = stop.clone();
        spawn_scoped(stop.clone(), async move {
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
                if let Err(e) = udp.send(&n.to_be_bytes()).await {
                    emit_log(&format!("AIRPLAY: UDP keepAlive 写失败 kind={:?} os={:?}: {e}", e.kind(), e.raw_os_error()));
                    return;
                }
            }
        });
    }

    spawn_scoped(stop.clone(), async move {
        loop {
            tokio::select! {
                _ = stop.notified() => return,
                _ = tokio::time::sleep(Duration::from_secs(1)) => {}
            }
            if let Err(error) = channel
                .request(
                    "POST",
                    "/feedback",
                    &[],
                    "application/octet-stream",
                    &[],
                    Duration::from_secs(3),
                )
                .await
            {
                let detail = format!("AirPlay feedback 失败：{error}");
                emit_log(&detail);
                emit_state("ended", &detail);
                stop.cancel();
                return;
            }
        }
    });
}

fn spawn_iap_sender(channel: Arc<RtspChannel>, mut packets: mpsc::Receiver<Vec<u8>>, stop: Arc<SessionStop>) {
    spawn_scoped(stop.clone(), async move {
        while let Some(packet) = packets.recv().await {
            let body = bplist::encode(&Value::dict(vec![
                ("type", Value::str("iAPSendMessage")),
                ("params", Value::dict(vec![("data", Value::Data(packet))])),
            ]));
            if let Err(error) = channel.post_fire_and_forget("/command", &body, "application/x-apple-binary-plist").await {
                let detail = format!("虚拟 iAP2 控制写失败: {error}");
                emit_state("ended", &detail);
                stop.cancel();
                return;
            }
        }
    });
}

// ---------------------------------------------------------------- 事件通道分发

fn spawn_event_dispatcher(
    mut inbound: tokio::sync::mpsc::UnboundedReceiver<crate::rtsp::RtspMessage>,
    control: Arc<RtspChannel>,
    touch_tx: mpsc::UnboundedSender<TouchInput>,
    key_tx: mpsc::UnboundedSender<u32>,
    display_size: (u32, u32),
    target_size: Arc<Mutex<(u32, u32)>>,
    stop: Arc<SessionStop>,
) {
    spawn_scoped(stop.clone(), async move {
        let mut bad_commands = 0usize;
        let mut tip_down = false; // 上一次报告的按下状态（down/move/up 边沿检测）
        let mut seen_commands: Vec<String> = Vec::new();
        let mut hid_reports: u64 = 0;
    let mut touch_logged: u32 = 0; // 触摸换算诊断计数（前几次 + 每 200 次记一行）
        let mut other_lines: u64 = 0;
        let mut unhandled: std::collections::HashMap<String, u64> = std::collections::HashMap::new();
        loop {
            let msg = tokio::select! {
                _ = stop.notified() => return,
                m = inbound.recv() => match m {
                    Some(m) => m,
                    None => {
                        emit_state("ended", "AirPlay 事件通道已关闭，停止本轮媒体推流");
                        stop.cancel();
                        return;
                    }
                },
            };
            if !msg.first_line.contains("/command") {
                // 事件通道上除了 /command 还会有别的东西（RTSP 应答、心跳等）。
                // 以前这里静默 continue，导致「触控一点都没有」时完全无法判断
                // 是对方没发、还是报文格式和预期不同（现场 2026-10-23 23:20：
                // WinPlay 侧已确认发出触摸，我们这边却一条 EVENT 都没有）。
                other_lines += 1;
                if other_lines <= 5 {
                    emit_log(&format!(
                        "EVENT: 非 /command 报文（第 {other_lines} 条）first_line={:?} body={}B",
                        msg.first_line,
                        msg.body.len()
                    ));
                }
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
            // 事件通道是「车机 → 手机」的唯一通道（触摸/HID/命令）。它是否在工作，
            // 现场只能靠日志判断，所以每类命令第一次出现时记一行（之后不刷屏）。
            if !seen_commands.iter().any(|c| c == cmd_type) {
                if seen_commands.len() < 32 {
                    seen_commands.push(cmd_type.to_string());
                }
                emit_log(&format!("EVENT: 车机命令 type={cmd_type}（首次）"));
            }
            match cmd_type {
                "hidSendReport" => {
                    // 两种布局都要认：
                    //  - 苹果/真车常见：{ params: { report: <data> } }
                    //  - WinPlay：      { type, uuid, hidReport: <data> }（顶层）
                    // 只认前者会表现成「收到了 hidSendReport 但没有 HID 报告」，
                    // 触摸静默失效（现场 2026-10-23 23:33 定位）。
                    let report = command
                        .get("hidReport")
                        .and_then(Value::as_bytes)
                        .or_else(|| {
                            command
                                .get("params")
                                .and_then(|p| p.get("report"))
                                .and_then(Value::as_bytes)
                        });
                    if let Some(report) = report {
                        hid_reports += 1;
                        if hid_reports <= 3 || hid_reports % 20 == 0 {
                            emit_log(&format!(
                                "EVENT: HID 报告 #{hid_reports} {}B {}",
                                report.len(),
                                report.iter().take(6).map(|b| format!("{b:02X}")).collect::<Vec<_>>().join(" ")
                            ));
                        }
                        handle_hid_report(
                            report,
                            &touch_tx,
                            &key_tx,
                            display_size,
                            &target_size,
                            &mut tip_down,
                            &mut touch_logged,
                        );
                    }
                }
                "hidSetInputMode" | "ping" | "fps" => {}
                "changeModes" | "requestUI" => {
                    // RTSP 200 只确认收包；资源切换还要回 modesChanged。
                    if let Err(error) = assert_modes(&control).await {
                        emit_log(&format!("EVENT: {cmd_type} 资源应答失败: {error}"));
                    }
                }
                "iAPSendMessage" => {
                    if let Some(data) = command.get("params").and_then(|p| p.get("data")).and_then(Value::as_bytes) {
                        emit_iap(data);
                    } else {
                        emit_log("EVENT: iAPSendMessage 缺少 params.data，无法交给虚拟链路");
                    }
                }
                other => {
                    let count = unhandled.entry(other.to_string()).or_default();
                    *count += 1;
                    if *count <= 3 || *count % 100 == 0 {
                        let params = command.get("params");
                        let keys: Vec<_> = params.and_then(Value::as_dict)
                            .map(|pairs| pairs.iter().filter_map(|(key, _)| key.as_str()).collect()).unwrap_or_default();
                        let bytes = params.and_then(|p| p.get("data")).and_then(Value::as_bytes).map(|data| data.len());
                        emit_log(&format!("EVENT: 未实现命令语义 type={other} count={count} paramsKeys={keys:?} dataBytes={bytes:?}；RTSP 层已应答 200"));
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
    target_size: &Arc<Mutex<(u32, u32)>>,
    tip_down: &mut bool,
    logged: &mut u32,
) {
    if report.len() < 5 {
        // 短报告按 Consumer 媒体键候选处理（奥迪 uuid=0 是媒体键设备）
        if let Some(code) = report.last().and_then(|_| consumer_key_of(report)) {
            let _ = key_tx.send(code);
        }
        return;
    }
    // 每次触摸都读当前编码分辨率：手机可能把请求的分辨率压缩后回报，
    // 用旧快照会让触摸整体偏移（越靠边越明显）。
    let target = *target_size.lock();
    let target = (target.0.max(2), target.1.max(2));

    // 两种报告布局：
    //  ① **每指 6 字节** `[index][touch][X u16LE][Y u16LE]`，最多 2 指（12 字节）——
    //     苹果触摸描述符 / WinPlay 都是这个（WinPlay: TOUCH_CONTACTS=2、BYTES_PER_FINGER=6）。
    //  ② 旧的 5 字节 `[tip][X u16LE][Y u16LE]`（cardeck 奥迪实测）。
    // 之前只按 ② 解析，于是把 6 字节布局里的 `report[0]`（触点 index=0）当成按下位 →
    // 永远判成"未按下" → 触摸被整段丢弃（现场 2026-10-23 23:35 定位）。
    let (pressed, x_raw, y_raw) = if report.len() >= 6 && report.len() % 6 == 0 {
        // 取第一个真正的指（跳过 index 非 0 或未按下的）
        let mut picked = None;
        for finger in report.chunks_exact(6) {
            if finger[1] & 0x01 != 0 {
                picked = Some(finger);
                break;
            }
        }
        match picked {
            Some(f) => (
                true,
                u16::from_le_bytes([f[2], f[3]]) as u32,
                u16::from_le_bytes([f[4], f[5]]) as u32,
            ),
            // 全部手指都抬起：沿用上次坐标发 UP（坐标本身对抬起不重要）
            None => {
                let f = &report[..6];
                (
                    false,
                    u16::from_le_bytes([f[2], f[3]]) as u32,
                    u16::from_le_bytes([f[4], f[5]]) as u32,
                )
            }
        }
    } else {
        (
            report[0] & 0x01 != 0,
            u16::from_le_bytes([report[1], report[2]]) as u32,
            u16::from_le_bytes([report[3], report[4]]) as u32,
        )
    };
    // 防下溢：`target.0 - 1` 在 target 为 0 时 release 会回绕成巨大值、
    // debug 直接 panic（编码器还没回报尺寸时就是 0）。
    let (tw, th) = (target.0.max(1), target.1.max(1));
    let (dw, dh) = (display.0.max(1), display.1.max(1));
    let x = (x_raw.saturating_mul(tw) / dw).min(tw.saturating_sub(1));
    let y = (y_raw.saturating_mul(th) / dh).min(th.saturating_sub(1));
    *logged = logged.wrapping_add(1);
    if *logged <= 5 || *logged % 200 == 0 {
        crate::emit_log(&format!(
            "TOUCH: 车机({x_raw},{y_raw}) 空间 {dw}x{dh} → 画布 {tw}x{th} = ({x},{y})（第 {n} 次）",
            n = *logged
        ));
        if *logged == 1 {
            let rd = dw as f32 / dh as f32;
            let rt = tw as f32 / th as f32;
            if (rd - rt).abs() / rt.max(0.1) > 0.03 {
                crate::emit_log(&format!(
                    "TOUCH: ⚠ 触摸空间 {dw}x{dh}（{rd:.2}:1）与画布 {tw}x{th}（{rt:.2}:1）宽高比不一致——\
                     接收端拉伸显示时线性映射正确；若它加了黑边，边缘会偏",
                ));
            }
        }
    }
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

/// 偶数对齐：H.264 4:2:0 要求宽高为偶数（奇数会让编解码器首帧进错误态）。
fn even(v: u32) -> u32 {
    (v / 2) * 2
}

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

// ---------------------------------------------------------------- 待机素材
//
// 只保留**两套母版**（宽屏 3.44:1 与 16:9），按屏幕宽高比自动选，运行时不再按分辨率编码：
//   宝马带鱼屏 2560x744 → 3.44:1 → 用 1280x372 母版（车机放大到全屏）
//   1920x720 / 1280x720 → 16:9 → 用 1280x720 母版
// 每套都是 1 秒 @60fps（1 个 IDR + 59 个极小 P 帧）：既占住屏幕流，又不是黑屏。
const STANDBY_WIDE: &[u8] = include_bytes!("../assets/standby-wide.h264");
const STANDBY_WIDE_16X9: &[u8] = include_bytes!("../assets/standby-wide16x9.h264");
// 2560x744 宽屏母版（由 1280x372 用 lanczos 放大生成，ffmpeg 一次成型）：
// 宝马 iD8.5 这类 2560x744 的带鱼屏直接用原生尺寸，车机不用再放大 → 待机字更锐。
const STANDBY_WIDE_2X: &[u8] = include_bytes!("../assets/standby-wide-2x.h264");

/// 一套待机素材：帧序列 + 母版原始尺寸（VideoConfig 必须按母版尺寸下发）。
pub(crate) struct StandbySet {
    pub frames: &'static [&'static [u8]],
    pub width: u32,
    pub height: u32,
}

/// 按屏幕宽高比挑母版：> 2.6 用宽屏（带鱼屏），否则用 16:9。
pub(crate) fn standby_for_aspect(width: u32, height: u32) -> Option<StandbySet> {
    if width == 0 || height == 0 {
        return None;
    }
    // 按**最接近的宽高比**挑母版（原来用固定 2.6 阈值：2560x744=3.44、2732x794=3.44
    // 走宽屏都对，但 1920x900=2.13 这类中间比例会被强行拉伸变形）。
    let ratio = width as f32 / height as f32;
    let wide_ratio = 1280.0f32 / 372.0f32; // 3.441
    let wide16_ratio = 1280.0f32 / 720.0f32; // 1.778
    let wide = (ratio - wide_ratio).abs() <= (ratio - wide16_ratio).abs();
    // 宽屏且车机宽度 >= 2000 时直接用 2560x744 原生母版（否则用 1280x372，车机放大）
    let (bytes, w, h) = if wide && width >= 2000 {
        (STANDBY_WIDE_2X, 2560u32, 744u32)
    } else if wide {
        (STANDBY_WIDE, 1280u32, 372u32)
    } else {
        (STANDBY_WIDE_16X9, 1280u32, 720u32)
    };
    let frames = frames_of(w, h, bytes)?;
    Some(StandbySet { frames, width: w, height: h })
}

fn frames_of(w: u32, h: u32, data: &'static [u8]) -> Option<&'static [&'static [u8]]> {
    use std::sync::OnceLock;
    static WIDE: OnceLock<Vec<&'static [u8]>> = OnceLock::new();
    static WIDE16: OnceLock<Vec<&'static [u8]>> = OnceLock::new();
    static WIDE2X: OnceLock<Vec<&'static [u8]>> = OnceLock::new();
    if w == 1280 && h == 372 {
        Some(WIDE.get_or_init(|| split_annexb_frames(STANDBY_WIDE)))
    } else if w == 1280 && h == 720 {
        Some(WIDE16.get_or_init(|| split_annexb_frames(STANDBY_WIDE_16X9)))
    } else if w == 2560 && h == 744 {
        Some(WIDE2X.get_or_init(|| split_annexb_frames(STANDBY_WIDE_2X)))
    } else {
        let _ = data;
        None
    }
}

/// 把 Annex B 流切成「一帧一片」：帧边界 = 每个 VCL NAL（type 1/5）的起点；
/// **首帧从文件头开始**，这样 SPS/PPS/SEI 与第一个 IDR 落在同一帧里。
fn split_annexb_frames(data: &'static [u8]) -> Vec<&'static [u8]> {
    let mut starts: Vec<(usize, u8)> = Vec::new();
    let mut i = 0usize;
    while i + 4 < data.len() {
        let sc3 = data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1;
        let sc4 = data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 0 && data[i + 3] == 1;
        if sc3 || sc4 {
            let hdr = if sc4 { i + 4 } else { i + 3 };
            if hdr < data.len() {
                starts.push((i, data[hdr] & 0x1f));
            }
            i = hdr + 1;
        } else {
            i += 1;
        }
    }
    let vcl: Vec<usize> = starts
        .iter()
        .enumerate()
        .filter(|(_, (_, t))| matches!(t, 1 | 5))
        .map(|(i, _)| i)
        .collect();
    let mut frames = Vec::with_capacity(vcl.len());
    for (k, &vi) in vcl.iter().enumerate() {
        let start = if k == 0 { 0 } else { starts[vi].0 };
        let end = vcl.get(k + 1).map(|&n| starts[n].0).unwrap_or(data.len());
        if end > start {
            frames.push(&data[start..end]);
        }
    }
    frames
}
