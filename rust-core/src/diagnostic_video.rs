//! Diagnostic source/replay uses the production video pump and a real, independently parsed TCP peer.
use crate::{
    carlife::{CarLifeConfig, CarLifeHub},
    crypto::{chacha_open, nonce64},
    engine::{run_video_pump, CarLifeEvent, EngineConfig, PumpStat, VideoPumpOptions},
    media_start::MediaReadiness,
    screen::{annexb_nals, nal_type, ScreenCipher, ScreenSender},
    session::{spawn_scoped, CancelOnDrop, SessionStop},
    video_capture::{read_packets, CaptureWriter, MAX_PACKET},
};
use parking_lot::Mutex;
use std::{path::Path, sync::Arc, time::Duration};
use tokio::{
    io::AsyncReadExt,
    net::{TcpListener, TcpStream},
    sync::{mpsc, oneshot},
};

fn err(e: impl std::fmt::Display) -> String {
    e.to_string()
}
fn idr(data: &[u8]) -> Option<Vec<u8>> {
    annexb_nals(data)
        .into_iter()
        .find(|n| nal_type(n) == 5)
        .map(Vec::from)
}

/// This parser deliberately does not call ScreenSender's packet builders.
async fn receive(
    mut socket: TcpStream,
    key: [u8; 32],
    directory: String,
    expected: Arc<Mutex<Option<Vec<u8>>>>,
) -> Result<usize, String> {
    let mut capture = CaptureWriter::create(Path::new(&directory), "transport")?;
    let mut config = Vec::new();
    let mut counter = 0;
    let mut previous = None;
    let mut source = false;
    let mut inline_idrs = 0;
    let mut source_frames = 0;
    loop {
        let mut head = [0; 128];
        if socket.read(&mut head[..1]).await.map_err(err)? == 0 {
            break;
        }
        socket.read_exact(&mut head[1..]).await.map_err(err)?;
        let size = u32::from_le_bytes(head[..4].try_into().unwrap()) as usize;
        if size > MAX_PACKET + 16 {
            return Err("屏幕协议包超过4MiB".into());
        }
        let mut body = vec![0; size];
        socket.read_exact(&mut body).await.map_err(err)?;
        match head[4] {
            1 => {
                if head[6] != 4
                    || body.len() < 8
                    || body[0] != 1
                    || body[4] & 3 != 3
                    || body[5] & 31 != 1
                {
                    return Err("avcC/加密标志非法".into());
                }
                let s = u16::from_be_bytes([body[6], body[7]]) as usize;
                let p = 8 + s;
                if p + 3 > body.len() || body[p] != 1 {
                    return Err("avcC SPS/PPS 被截断".into());
                }
                let n = u16::from_be_bytes([body[p + 1], body[p + 2]]) as usize;
                if p + 3 + n != body.len() || s == 0 || n == 0 {
                    return Err("avcC 长度非法".into());
                }
                let codec=crate::video_compat::SpsInfo::parse(&body[8..p])?;
                let width=f32::from_le_bytes(head[16..20].try_into().unwrap());
                let height=f32::from_le_bytes(head[20..24].try_into().unwrap());
                if width!=codec.width as f32 || height!=codec.height as f32 {
                    return Err(format!("屏幕配置尺寸 {width}x{height} 与真实SPS {}x{} 不一致",codec.width,codec.height));
                }
                config = [
                    &[0, 0, 0, 1][..],
                    &body[8..p],
                    &[0, 0, 0, 1],
                    &body[p + 3..],
                ]
                .concat();
                source = false;
            }
            0 => {
                let stamp = u64::from_le_bytes(head[8..16].try_into().unwrap());
                if previous.is_some_and(|p| stamp <= p) {
                    return Err("屏幕帧时间戳未递增".into());
                }
                previous = Some(stamp);
                let clear = chacha_open(&key, &nonce64(counter), &body, &head).map_err(err)?;
                counter += 1;
                let mut frame = Vec::new();
                let mut p = 0;
                while p < clear.len() {
                    if p + 4 > clear.len() {
                        return Err("NAL 长度头被截断".into());
                    }
                    let n = u32::from_be_bytes(clear[p..p + 4].try_into().unwrap()) as usize;
                    p += 4;
                    if n == 0 || n > clear.len() - p {
                        return Err("NAL 长度非法".into());
                    }
                    frame.extend_from_slice(&[0, 0, 0, 1]);
                    frame.extend_from_slice(&clear[p..p + n]);
                    p += n;
                }
                if !source {
                    source = idr(&frame).is_some_and(|n| expected.lock().as_ref() == Some(&n));
                }
                if source {
                    source_frames += 1;
                    let ns = annexb_nals(&frame);
                    if ns.iter().any(|n| nal_type(n) == 5)
                        && ns.iter().any(|n| nal_type(n) == 7)
                        && ns.iter().any(|n| nal_type(n) == 8)
                    {
                        inline_idrs += 1;
                    }
                    if idr(&frame).is_some() {
                        frame = [config.clone(), frame].concat();
                    }
                    capture.record(&frame, ((stamp as u128 * 1_000_000) >> 32) as u64);
                }
            }
            2 | 5 => {}
            _ => return Err("未知屏幕协议类型".into()),
        }
    }
    capture.finish();
    std::fs::write(Path::new(&directory).join("transport-wire.json"),serde_json::to_vec_pretty(&serde_json::json!({"encryptedFrames":counter,"sourceFrames":source_frames,"inlineHeaderIdrs":inline_idrs,"timestampStrictlyIncreasing":true,"authenticationVerified":true})).map_err(err)?).map_err(err)?;
    if capture.frames() == 0 {
        return Err("TCP 接收端没有真实来源关键帧".into());
    }
    Ok(capture.frames())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::video_compat::VideoPolicy;
    #[tokio::test]
    async fn receiver_rejects_header_dimensions_that_do_not_describe_the_sps() {
        let dir=std::env::temp_dir().join(format!("hc-wrong-size-{}",uuid::Uuid::new_v4()));
        let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();
        let client=TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (peer,_)=listener.accept().await.unwrap();let(_,writer)=client.into_split();
        let sample=include_bytes!("../testdata/source-1280x720.h264");
        let expected=Arc::new(Mutex::new(idr(sample)));let path=dir.to_string_lossy().to_string();
        let receiver=tokio::spawn(receive(peer,[17;32],path,expected));
        let mut sender=ScreenSender::new(writer,ScreenCipher::ChaCha([17;32]),1282,720,false);
        let _=sender.send_video_annexb(sample).await;drop(sender);
        assert!(receiver.await.unwrap().unwrap_err().contains("不一致"));
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[tokio::test]
    async fn four_strategies_cross_production_pump_tcp_and_authenticated_receiver() {
        let root = std::env::var("HYPERCAR_VIDEO_EVIDENCE")
            .map(std::path::PathBuf::from)
            .unwrap_or_else(|_| {
                std::env::temp_dir().join(format!("hc-loopback-{}", uuid::Uuid::new_v4()))
            });
        let input = root.join("input");
        let mut capture = CaptureWriter::create(&input, "source").unwrap();
        let mut au = Vec::new();
        let mut pts = 0;
        for nal in annexb_nals(include_bytes!("../testdata/source-1280x720.h264")) {
            if nal_type(nal) == 9 && !au.is_empty() {
                capture.record(&au, pts);
                pts += 33_333;
                au.clear();
            }
            au.extend_from_slice(&[0, 0, 0, 1]);
            au.extend_from_slice(nal);
        }
        capture.record(&au, pts);
        capture.finish();
        drop(capture);
        assert_eq!(
            read_packets(&input.join("source.packets")).unwrap().len(),
            9
        );
        for policy in [
            VideoPolicy::Original,
            VideoPolicy::Level40,
            VideoPolicy::InlineHeaders,
            VideoPolicy::Level40Inline,
        ] {
            let name = serde_json::to_value(policy)
                .unwrap()
                .as_str()
                .unwrap()
                .to_owned();
            let dir = root.join(&name);
            let cfg:EngineConfig=serde_json::from_value(serde_json::json!({"device_id":"diagnostic","car_ip":"127.0.0.1","airplay_port":1,"width":1280,"height":720,"fps":30,"identity_path":"","touch_display_w":0,"touch_display_h":0,"video_policy":name,"diagnostic_dir":dir,"diagnostic_replay":input.join("source.packets")})).unwrap();
            let stop = SessionStop::new(7008);
            run(cfg, stop.clone()).await.unwrap();
            stop.cancel();
            stop.wait_idle().await;
            let packets = read_packets(&dir.join("transport.packets")).unwrap();
            assert_eq!(packets.len(), 9);
            let level = annexb_nals(&packets[0].1)
                .into_iter()
                .find(|n| nal_type(n) == 7)
                .unwrap()[3];
            assert_eq!(
                level,
                if matches!(policy, VideoPolicy::Level40 | VideoPolicy::Level40Inline) {
                    40
                } else {
                    31
                }
            );
            let wire: serde_json::Value =
                serde_json::from_slice(&std::fs::read(dir.join("transport-wire.json")).unwrap())
                    .unwrap();
            assert_eq!(
                wire["inlineHeaderIdrs"].as_u64().unwrap() > 0,
                policy.inline_headers()
            );
            if std::env::var("HYPERCAR_VIDEO_EVIDENCE").is_ok() {
                std::fs::write(
                    dir.join("decoded-input.h264"),
                    packets
                        .iter()
                        .flat_map(|(_, d)| d.clone())
                        .collect::<Vec<_>>(),
                )
                .unwrap();
            }
        }
        if std::env::var("HYPERCAR_VIDEO_EVIDENCE").is_err() {
            std::fs::remove_dir_all(root).unwrap();
        }
    }
}

pub async fn run(cfg: EngineConfig, stop: Arc<SessionStop>) -> Result<(), String> {
    let directory = cfg.diagnostic_dir.clone().ok_or("缺少诊断目录")?;
    let replay = cfg
        .diagnostic_replay
        .as_ref()
        .map(|p| read_packets(Path::new(p)))
        .transpose()?;
    let listener = TcpListener::bind("127.0.0.1:0").await.map_err(err)?;
    let client = TcpStream::connect(listener.local_addr().map_err(err)?)
        .await
        .map_err(err)?;
    let (server, _) = listener.accept().await.map_err(err)?;
    let (_, writer) = client.into_split();
    let mut key = [0u8; 32];
    getrandom::getrandom(&mut key).map_err(err)?;
    let expected = Arc::new(Mutex::new(None));
    let expected_rx = expected.clone();
    let (done_tx, done_rx) = oneshot::channel();
    let rx_dir = directory.clone();
    spawn_scoped(stop.clone(), async move {
        let _ = done_tx.send(receive(server, key, rx_dir, expected_rx).await);
    });
    let pump_stop = SessionStop::new(stop.id);
    let _pump_cleanup = CancelOnDrop(pump_stop.clone());
    let (ready, ready_rx) = oneshot::channel();
    let (events, events_rx) = mpsc::unbounded_channel();
    let stats = Arc::new(Mutex::new(PumpStat::default()));
    let opts = VideoPumpOptions {
        policy: cfg.video_policy,
        fps: cfg.fps,
        capture_dir: Some(directory.clone()),
        capture_source: replay.is_none(),
    };
    let pstop = pump_stop.clone();
    let pstats = stats.clone();
    let pump = spawn_scoped(stop.clone(), async move {
        run_video_pump(
            (cfg.width, cfg.height),
            ready_rx,
            events_rx,
            pstats,
            pstop,
            MediaReadiness::new(),
            opts,
        )
        .await;
    });
    ready
        .send(ScreenSender::new(
            writer,
            ScreenCipher::ChaCha(key),
            cfg.width,
            cfg.height,
            false,
        ))
        .map_err(|_| "视频泵未启动")?;
    if let Some(packets) = replay {
        for (pts, data) in packets {
            if expected.lock().is_none() {
                *expected.lock() = idr(&data);
            }
            if let Some(sps) = annexb_nals(&data).into_iter().find(|n| nal_type(n) == 7) {
                let codec = crate::video_compat::SpsInfo::parse(sps)?;
                events
                    .send(CarLifeEvent::EncoderResolution {
                        width: codec.width,
                        height: codec.height,
                    })
                    .map_err(err)?;
            }
            events
                .send(CarLifeEvent::VideoFrame {
                    source_timestamp: (pts / 1000) as u32,
                    data,
                })
                .map_err(err)?;
            tokio::time::sleep(Duration::from_millis(34)).await;
        }
        tokio::time::sleep(Duration::from_millis(500)).await;
    } else {
        if cfg.md_host.as_deref() != Some("127.0.0.1") {
            return Err("预检只接受已由 Android 核验的本机来源".into());
        }
        let life = CarLifeConfig {
            width: cfg.width,
            height: cfg.height,
            fps: cfg.fps,
            voice: 0,
            wait_for_airplay: false,
            bt_address: cfg.device_id,
            bt_name: "Hypercar diagnostic".into(),
        };
        let (_hub, mut life_events) = CarLifeHub::connect(life, "127.0.0.1".into(), stop.clone())?;
        let deadline = tokio::time::Instant::now() + Duration::from_secs(60);
        let mut first = None;
        loop {
            tokio::select! {
                _=tokio::time::sleep_until(deadline)=>break,
                _=tokio::time::sleep(Duration::from_millis(100))=>{
                    if stats.lock().frames>=90 || first.is_some_and(|t:tokio::time::Instant|t.elapsed()>Duration::from_secs(8)) {break;}
                },
                event=life_events.recv()=>{
                    let Some(event)=event else {break};
                    if matches!(event,CarLifeEvent::ClientLeft{video:true}) {return Err("来源采样期间视频连接断开；已有片段保留供分析".into());}
                    if let CarLifeEvent::VideoFrame{ref data,..}=event {
                        if expected.lock().is_none() {*expected.lock()=idr(data);}
                        if expected.lock().is_some() && first.is_none() {first=Some(tokio::time::Instant::now());}
                    }
                    events.send(event).map_err(err)?;
                }
            }
        }
    }
    pump_stop.cancel();
    let _ = pump.await;
    let received = tokio::time::timeout(Duration::from_secs(6), done_rx)
        .await
        .map_err(err)?
        .map_err(err)??;
    crate::emit_state(
        "diagnostic_complete",
        &format!("TCP 接收并解密 {received} 帧；等待手机解码与用户内容确认"),
    );
    Ok(())
}
