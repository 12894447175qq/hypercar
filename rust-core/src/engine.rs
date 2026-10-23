//! 网关引擎编排：CarLife 回环 ⇄ AirPlay 推流 的粘合层。
//!
//! 数据面（全部零转码）：
//!  - 视频：CarLife 8240 的 H.264 Annex B 裸流 → ScreenSender（128B 头 + ChaCha20-Poly1305）
//!  - 音频：CarLife 9240/9241 的 PCM → 线性重采样 48kHz 立体声 → TTS 闪避混音 → RTP
//!  - 触摸：AirPlay 事件通道 HID → CarLife 9340；iDrive 旋钮 → 硬按键 0x68008
//!
//! 控制面：Kotlin 交来「已连上车机 AirPlay 端口的 control FD」+ socket 供给器，
//! 其余协商（/info、pair-verify、SETUP、RECORD、三条心跳）全部在本层闭环。
//!
//! 任务拓扑（单分发器 → 双泵，避免多消费者抢同一接收器）：
//!   CarLifeHub ─events─▶ demux ─video─▶ video pump ─▶ ScreenSender
//!                          └──audio─▶ audio pump ─▶ AudioOut(RTP)

use std::collections::VecDeque;
use std::sync::Arc;
use std::time::Duration;

use parking_lot::Mutex;
use tokio::sync::{mpsc, Notify};

use crate::airplay::{self, AirplayParams, SocketProvider, TouchInput};
use crate::audio_out::AudioOut;
use crate::carlife::{CarLifeConfig, CarLifeHub};
use crate::screen::ScreenSender;
use crate::{emit_log, emit_stats, emit_state};

/// CarLife 通道产出的事件。
pub enum CarLifeEvent {
    Handshake,
    VideoFrame { pts_us: u64, data: Vec<u8> },
    AudioInit { tts: bool, rate: u32, channels: u8 },
    AudioStop { tts: bool },
    AudioData { tts: bool, rate: u32, channels: u8, data: Vec<u8> },
    ClientLeft { video: bool },
}

/// 引擎配置（SettingsStore.engineConfigJson 的反序列化目标）。
#[derive(Debug, Clone, serde::Deserialize)]
pub struct EngineConfig {
    pub device_id: String,
    pub car_ip: String,
    pub airplay_port: u16,
    pub width: u32,
    pub height: u32,
    pub fps: u32,
    #[serde(default = "default_true")]
    pub enable_audio: bool,
    #[serde(default)]
    pub keep_alive_stats: bool,
    pub identity_path: String,
}

fn default_true() -> bool {
    true
}

const TARGET_RATE: u32 = 48_000;
const SPF: usize = 352; // AirPlay 每包样本帧（512 在真车无声）

/// 引擎主入口：control FD → AirPlay 协商 → 双向泵。
/// 触摸/按键注入通道由 JNI 层创建（nativeSendTouch/nativeSendHardKey 持发送端），
/// 发送端同时交给 AirPlay 事件分发器，接收端在此转发给 CarLife 触摸通道。
#[allow(clippy::too_many_arguments)]
pub async fn run_engine(
    cfg: EngineConfig,
    control_stream: tokio::net::TcpStream,
    factory: Arc<dyn SocketProvider>,
    touch_tx: mpsc::UnboundedSender<TouchInput>,
    mut touch_rx: mpsc::UnboundedReceiver<TouchInput>,
    key_tx: mpsc::UnboundedSender<u32>,
    mut key_rx: mpsc::UnboundedReceiver<u32>,
    stop: Arc<Notify>,
) -> Result<(), String> {
    emit_state("negotiating", "CarLife 网关启动");

    // ---- CarLife 网关先行：协商期间 CarLife app 即可接入回环 ----
    // （触摸接收端已内化给通道任务，hub 只保留注入 Sender）
    let (hub, mut life_events) = CarLifeHub::start(
        CarLifeConfig {
            width: cfg.width,
            height: cfg.height,
            fps: cfg.fps,
        },
        stop.clone(),
    )?;
    let hub = Arc::new(hub);

    // 触摸/按键注入桥：AirPlay 事件通道 → CarLife 触摸通道
    {
        let hub = hub.clone();
        let stop_t = stop.clone();
        tokio::spawn(async move {
            loop {
                tokio::select! {
                    _ = stop_t.notified() => return,
                    t = touch_rx.recv() => match t {
                        Some(t) => hub.inject_touch(t.action, t.x, t.y),
                        None => return,
                    },
                    k = key_rx.recv() => match k {
                        Some(k) => hub.inject_hard_key(k),
                        None => return,
                    },
                }
            }
        });
    }

    // ---- 分发器：CarLife 事件 → 视频泵 / 音频泵 ----
    let (video_tx, video_rx) = mpsc::unbounded_channel::<CarLifeEvent>();
    let (audio_tx, audio_rx) = mpsc::unbounded_channel::<CarLifeEvent>();
    {
        let stop_d = stop.clone();
        tokio::spawn(async move {
            loop {
                tokio::select! {
                    _ = stop_d.notified() => return,
                    ev = life_events.recv() => match ev {
                        Some(ev) => {
                            let to_audio = matches!(
                                ev,
                                CarLifeEvent::AudioInit { .. }
                                    | CarLifeEvent::AudioStop { .. }
                                    | CarLifeEvent::AudioData { .. }
                            );
                            let to_video = matches!(ev, CarLifeEvent::VideoFrame { .. });
                            if to_audio {
                                if audio_tx.send(ev).is_err() { return; }
                            } else if to_video {
                                if video_tx.send(ev).is_err() { return; }
                            } else {
                                match ev {
                                    CarLifeEvent::Handshake => emit_log("CARLIFE: 握手完成"),
                                    CarLifeEvent::ClientLeft { video: true } =>
                                        emit_log("CARLIFE: 视频客户端断开"),
                                    _ => {}
                                }
                            }
                        }
                        None => return,
                    },
                }
            }
        });
    }

    // ---- 视频泵 ----
    let (screen_ready_tx, screen_ready_rx) = tokio::sync::oneshot::channel::<ScreenSender>();
    let video_stats = Arc::new(Mutex::new(PumpStat::default()));
    {
        let stop_v = stop.clone();
        let stats = video_stats.clone();
        tokio::spawn(async move {
            let mut sender: Option<ScreenSender> = None;
            let mut ready = Some(screen_ready_rx);
            let mut video_rx = video_rx;
            let mut tick = tokio::time::interval(Duration::from_millis(400));
            tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
            loop {
                tokio::select! {
                    _ = stop_v.notified() => return,
                    r = async {
                        match ready.as_mut() {
                            Some(rx) => Some(rx.await.ok()),
                            None => std::future::pending().await,
                        }
                    } => {
                        if let Some(s) = r.flatten() { sender = Some(s); }
                        ready = None;
                    }
                    ev = video_rx.recv() => match ev {
                        Some(CarLifeEvent::VideoFrame { pts_us, data }) => {
                            if let Some(s) = sender.as_mut() {
                                let len = data.len() as u64;
                                if s.send_video_annexb(&data, pts_us).await.is_err() {
                                    emit_state("ended", "屏幕流写失败（车机侧断开）");
                                    return;
                                }
                                let mut st = stats.lock();
                                st.frames += 1;
                                st.bytes += len;
                            }
                            // 屏幕流未就绪：丢帧（CarLife 先于 AirPlay 接入属正常）
                        }
                        Some(_) => {}
                        None => return,
                    },
                    _ = tick.tick() => {
                        // 屏幕流 keepAlive 帧（明文、1s 节流；缺它 Audi MMI 3315 10.2s 拆轮）
                        if let Some(s) = sender.as_mut() {
                            let _ = s.maybe_send_keep_alive().await;
                        }
                    }
                }
            }
        });
    }

    // ---- 音频泵：重采样 + 闪避混音 + RTP ----
    let (audio_out_tx, audio_out_rx) = tokio::sync::oneshot::channel::<AudioOut>();
    let audio_stats = Arc::new(Mutex::new(AudioPumpStat::default()));
    {
        let stop_a = stop.clone();
        let stats = audio_stats.clone();
        tokio::spawn(async move {
            let mut out: Option<AudioOut> = None;
            let mut ready = Some(audio_out_rx);
            let mut audio_rx = audio_rx;
            // 源格式余数缓冲（重采样跨包连续）
            let mut media_remain: Vec<i16> = Vec::new();
            let mut tts_remain: Vec<i16> = Vec::new();
            let mut media_fmt: (u32, u8) = (44_100, 2);
            let mut tts_fmt: (u32, u8) = (16_000, 1);
            // 48k 立体声混音缓冲
            let mut media_mix: VecDeque<i16> = VecDeque::with_capacity(48_000 * 2);
            let mut tts_mix: VecDeque<i16> = VecDeque::with_capacity(48_000 * 2);
            let mut duck_hold: u32 = 0;
            let mut gain: f32 = 1.0;
            let mut tick =
                tokio::time::interval(Duration::from_nanos((SPF as u64) * 1_000_000_000 / TARGET_RATE as u64));
            tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
            let mut sync_tick = tokio::time::interval(Duration::from_secs(1));
            sync_tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
            let mut mixed = vec![0i16; SPF * 2];
            loop {
                tokio::select! {
                    _ = stop_a.notified() => return,
                    r = async {
                        match ready.as_mut() {
                            Some(rx) => Some(rx.await.ok()),
                            None => std::future::pending().await,
                        }
                    } => {
                        if let Some(a) = r.flatten() { out = Some(a); }
                        ready = None;
                    }
                    ev = audio_rx.recv() => match ev {
                        Some(CarLifeEvent::AudioInit { tts, rate, channels }) => {
                            if tts { tts_fmt = (rate.max(1), channels.max(1)); }
                            else { media_fmt = (rate.max(1), channels.max(1)); }
                            stats.lock().playing = true;
                        }
                        Some(CarLifeEvent::AudioStop { tts }) => {
                            if tts { tts_mix.clear(); } else { media_mix.clear(); }
                        }
                        Some(CarLifeEvent::AudioData { tts, rate, channels, data }) => {
                            let (fmt, remain, mix) = if tts {
                                (&mut tts_fmt, &mut tts_remain, &mut tts_mix)
                            } else {
                                (&mut media_fmt, &mut media_remain, &mut media_mix)
                            };
                            if rate > 0 && rate != fmt.0 { fmt.0 = rate; }
                            if channels >= 1 && channels <= 2 && channels != fmt.1 { fmt.1 = channels; }
                            push_resampled(&data, fmt.0, fmt.1, remain, mix);
                            // 缓冲上限 ~0.5s：过载丢旧（导航长语音不至于拖垮媒体）
                            let cap = (TARGET_RATE as usize) * 2 / 2;
                            while mix.len() > cap { mix.pop_front(); }
                        }
                        Some(_) => {}
                        None => return,
                    },
                    _ = sync_tick.tick() => {
                        // RTP↔NTP 同步包（每秒；缺它车机照收 UDP 但喇叭无声）。
                        // 时间基与 TimingServer 的 timesync 应答同源（进程级单调钟）。
                        if let Some(a) = out.as_mut() {
                            let _ = a.send_sync_now(crate::timing::monotonic_nanos()).await;
                        }
                    }
                    _ = tick.tick() => {
                        if duck_hold > 0 { duck_hold -= 1; }
                        let tts_active = !tts_mix.is_empty();
                        if tts_active { duck_hold = 40; } // ~410ms 尾音保持
                        let target = if duck_hold > 0 { 0.25f32 } else { 1.0f32 };
                        gain += (target - gain) * 0.15;

                        for i in 0..SPF {
                            let m = take_or_zero(&mut media_mix);
                            let t = take_or_zero(&mut tts_mix);
                            let mv = (m as f32 * gain) as i32;
                            let mixed_v = (mv + t as i32).clamp(i16::MIN as i32, i16::MAX as i32) as i16;
                            mixed[i * 2] = mixed_v;
                            mixed[i * 2 + 1] = mixed_v;
                        }
                        stats.lock().ducking = duck_hold > 0;
                        if let Some(a) = out.as_mut() {
                            // 静音时也发包：保持 RTP 时钟连续（车机按包排播）
                            let _ = a.send_pcm(&mixed).await;
                        }
                    }
                }
            }
        });
    }

    // ---- AirPlay 协商（控制 FD 已连接车机端口）----
    emit_state("negotiating", "AirPlay 协商开始");
    let (ctrl_r, ctrl_w) = control_stream.into_split();
    let params = AirplayParams {
        car_ip: cfg.car_ip.clone(),
        airplay_port: cfg.airplay_port,
        device_id: cfg.device_id.clone(),
        identity_path: cfg.identity_path.clone(),
        enable_audio: cfg.enable_audio,
        keep_alive_stats: cfg.keep_alive_stats,
        width: cfg.width,
        height: cfg.height,
    };
    let outcome = airplay::negotiate(
        ctrl_r,
        ctrl_w,
        &params,
        factory,
        touch_tx,
        key_tx,
        stop.clone(),
    )
    .await?;

    // 屏幕/音频发送器交给泵
    let _ = screen_ready_tx.send(outcome.screen);
    if let Some(audio) = outcome.audio {
        let _ = audio_out_tx.send(audio);
    }

    // ---- 统计心跳（1s）----
    {
        let stop_s = stop.clone();
        let vstats = video_stats.clone();
        let astats = audio_stats.clone();
        tokio::spawn(async move {
            let mut last_frames = 0u64;
            let mut last_bytes = 0u64;
            let mut last_tick = tokio::time::Instant::now();
            loop {
                tokio::select! {
                    _ = stop_s.notified() => return,
                    _ = tokio::time::sleep(Duration::from_secs(1)) => {}
                }
                let (frames, bytes) = { let s = vstats.lock(); (s.frames, s.bytes) };
                let dt = last_tick.elapsed().as_secs_f64().max(0.001);
                let fps = ((frames - last_frames) as f64 / dt).round() as i64;
                let kbps = (((bytes - last_bytes) * 8) as f64 / dt / 1000.0).round() as i64;
                last_frames = frames;
                last_bytes = bytes;
                last_tick = tokio::time::Instant::now();
                let a = astats.lock();
                emit_stats(fps.max(0) as i32, frames as i64, kbps.max(0) as i64, a.playing, a.ducking);
            }
        });
    }

    // 等待停止信号
    stop.notified().await;
    emit_state("stopping", "引擎停止");
    Ok(())
}

// ---------------------------------------------------------------- 音频重采样

/// 把一包 PCM（LE i16 交错）线性重采样为 48kHz 立体声，推入混音缓冲。
///
/// 注：参考文档用简单线性插值；44.1k→48k 的镜像抑制对车机喇叭听感足够
/// （CarLife 音源本身已是有损编码后的 PCM，抗混叠收益有限）。后续如需
/// 更好音质可换 windowed-sinc（此函数是唯一改动点）。
fn push_resampled(
    raw: &[u8],
    src_rate: u32,
    src_ch: u8,
    remain: &mut Vec<i16>,
    out: &mut VecDeque<i16>,
) {
    let shorts = raw.len() / 2;
    for i in 0..shorts {
        remain.push(i16::from_le_bytes([raw[i * 2], raw[i * 2 + 1]]));
    }
    let ch = src_ch.max(1) as usize;
    let src_frames = remain.len() / ch;
    if src_frames == 0 || src_rate == 0 {
        return;
    }
    if src_rate == TARGET_RATE && ch == 2 {
        let total = src_frames * 2;
        for i in 0..total {
            out.push_back(remain[i]);
        }
        remain.drain(..total);
        return;
    }
    let ratio = src_rate as f64 / TARGET_RATE as f64;
    let dst_frames = ((src_frames as f64) / ratio) as usize;
    let read = |idx: usize, lane: usize| -> i16 {
        if ch == 1 {
            remain[idx]
        } else {
            remain[idx * ch + lane]
        }
    };
    for i in 0..dst_frames {
        let pos = i as f64 * ratio;
        let i0 = (pos as usize).min(src_frames - 1);
        let i1 = (i0 + 1).min(src_frames - 1);
        let frac = (pos - i0 as f64) as f32;
        let l = if ch == 1 {
            let m0 = read(i0, 0) as f32;
            let m1 = read(i1, 0) as f32;
            m0 + (m1 - m0) * frac
        } else {
            let l0 = read(i0, 0) as f32;
            let l1 = read(i1, 0) as f32;
            l0 + (l1 - l0) * frac
        };
        let r = if ch == 1 {
            l
        } else {
            let r0 = read(i0, 1) as f32;
            let r1 = read(i1, 1) as f32;
            r0 + (r1 - r0) * frac
        };
        out.push_back(l.clamp(i16::MIN as f32, i16::MAX as f32) as i16);
        out.push_back(r.clamp(i16::MIN as f32, i16::MAX as f32) as i16);
    }
    // 已消费的源帧丢弃（浮点取整误差在余数里可忽略）
    let consumed = (dst_frames as f64 * ratio) as usize;
    let consumed = consumed.min(src_frames) * ch;
    remain.drain(..consumed);
}

fn take_or_zero(q: &mut VecDeque<i16>) -> i16 {
    q.pop_front().unwrap_or(0)
}

// ---------------------------------------------------------------- 统计

#[derive(Default)]
struct PumpStat {
    frames: u64,
    bytes: u64,
}

#[derive(Default)]
struct AudioPumpStat {
    playing: bool,
    ducking: bool,
}
