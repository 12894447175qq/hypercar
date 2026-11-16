//! Real production pump, TCP framing and authenticated encryption; no Android/vehicle required.
use super::*;
use crate::crypto::{chacha_open, nonce64};
use crate::screen::{annexb_nals, nal_type, ScreenCipher};
use tokio::io::AsyncReadExt;
use tokio::net::{TcpListener, TcpStream};

const SOURCE: &[u8] = include_bytes!("../testdata/source-1280x720.h264");

fn access_units() -> Vec<Vec<u8>> {
    let mut frames = Vec::new();
    let mut current = Vec::new();
    for nal in annexb_nals(SOURCE) {
        if nal_type(nal) == 9 && !current.is_empty() {
            frames.push(std::mem::take(&mut current));
        }
        current.extend_from_slice(&[0, 0, 0, 1]);
        current.extend_from_slice(nal);
    }
    if !current.is_empty() { frames.push(current); }
    frames
}

struct Capture {
    all: Vec<u8>,
    source: Vec<u8>,
    source_frames: usize,
    source_configs: usize,
    config_sizes: Vec<(u32, u32)>,
}

async fn receive(mut socket: TcpStream, key: [u8; 32]) -> Capture {
    let expected_sps = annexb_nals(SOURCE).into_iter().find(|n| nal_type(n) == 7).unwrap();
    let mut output = Capture { all: vec![], source: vec![], source_frames: 0, source_configs: 0, config_sizes: vec![] };
    let mut counter = 0;
    let mut source = false;
    let mut last_timestamp = None;
    loop {
        let mut header = [0u8; 128];
        if socket.read_exact(&mut header).await.is_err() { break; }
        let size = u32::from_le_bytes(header[0..4].try_into().unwrap()) as usize;
        assert!(size < 2_000_000);
        let mut body = vec![0; size];
        socket.read_exact(&mut body).await.unwrap();
        match header[4] {
            1 => {
                assert_eq!(header[6], 4);
                output.config_sizes.push((
                    f32::from_le_bytes(header[16..20].try_into().unwrap()) as u32,
                    f32::from_le_bytes(header[20..24].try_into().unwrap()) as u32,
                ));
                let sps_size = u16::from_be_bytes([body[6], body[7]]) as usize;
                let sps = &body[8..8 + sps_size];
                let p = 8 + sps_size;
                assert_eq!(body[p], 1);
                let pps_size = u16::from_be_bytes([body[p + 1], body[p + 2]]) as usize;
                let pps = &body[p + 3..p + 3 + pps_size];
                source = sps == expected_sps;
                if source {
                    output.source_configs += 1;
                    assert_eq!(f32::from_le_bytes(header[16..20].try_into().unwrap()), 1280.0);
                    assert_eq!(f32::from_le_bytes(header[20..24].try_into().unwrap()), 720.0);
                }
                for nal in [sps, pps] {
                    output.all.extend_from_slice(&[0, 0, 0, 1]); output.all.extend_from_slice(nal);
                    if source { output.source.extend_from_slice(&[0, 0, 0, 1]); output.source.extend_from_slice(nal); }
                }
            }
            0 => {
                let stamp = u64::from_le_bytes(header[8..16].try_into().unwrap());
                if let Some(previous) = last_timestamp { assert!(stamp > previous); }
                last_timestamp = Some(stamp);
                // Counter never resets when codec config, source or resolution changes.
                let clear = chacha_open(&key, &nonce64(counter), &body, &header).unwrap();
                counter += 1;
                let mut p = 0;
                while p < clear.len() {
                    let n = u32::from_be_bytes(clear[p..p + 4].try_into().unwrap()) as usize;
                    p += 4;
                    let nal = &clear[p..p + n]; p += n;
                    output.all.extend_from_slice(&[0, 0, 0, 1]); output.all.extend_from_slice(nal);
                    if source { output.source.extend_from_slice(&[0, 0, 0, 1]); output.source.extend_from_slice(nal); }
                }
                if source { output.source_frames += 1; }
            }
            2 | 5 => {}, // Screen keepalive packets do not consume encryption counters.
            op => panic!("unexpected screen opcode {op}"),
        }
    }
    output
}

async fn wait_frames(stats: &Mutex<PumpStat>, target: u64) {
    tokio::time::timeout(Duration::from_secs(3), async {
        while stats.lock().frames < target { tokio::time::sleep(Duration::from_millis(5)).await; }
    }).await.unwrap();
}

async fn switch_and_resume(initial: (u32, u32), label: &str) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let client = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
    let (server, _) = listener.accept().await.unwrap();
    let (_, writer) = client.into_split();
    let key = [13; 32];
    let capture = tokio::spawn(receive(server, key));
    let (ready, ready_rx) = tokio::sync::oneshot::channel();
    let (events, event_rx) = mpsc::unbounded_channel();
    let stats = Arc::new(Mutex::new(PumpStat::default()));
    let stop = SessionStop::new(8006);
    let readiness = MediaReadiness::new();
    let sample_dir=std::env::temp_dir().join(format!("hc-source-epoch-{}",uuid::Uuid::new_v4()));
    let options=VideoPumpOptions{capture_dir:Some(sample_dir.to_string_lossy().to_string()),capture_source:true,..Default::default()};
    let pump = tokio::spawn(run_video_pump(initial, ready_rx, event_rx, stats.clone(), stop.clone(), readiness.clone(), options));
    ready.send(ScreenSender::new(writer, ScreenCipher::ChaCha(key), initial.0, initial.1, false)).ok().unwrap();
    tokio::time::sleep(Duration::from_millis(80)).await;
    assert_eq!(stats.lock().frames, 0, "standby is not source video");
    assert!(!readiness.snapshot().source, "standby must not release audio startup");
    let frames = access_units();
    assert_eq!(frames.len(), 9);
    events.send(CarLifeEvent::EncoderResolution { width: 1280, height: 720 }).unwrap();
    for frame in frames.iter().take(8) {
        events.send(CarLifeEvent::VideoFrame { source_timestamp: 1_793_192_685, data: frame.clone() }).unwrap();
        tokio::time::sleep(Duration::from_millis(15)).await;
    }
    wait_frames(&stats, 8).await;
    assert!(readiness.snapshot().source);
    // A static screen is allowed to send no changed frames. Do not replace it after 900ms.
    tokio::time::sleep(Duration::from_millis(1_100)).await;
    // A reference frame after that pause must remain decodable, rather than wait for another IDR.
    events.send(CarLifeEvent::VideoFrame { source_timestamp: 1_793_192_686, data: frames[8].clone() }).unwrap();
    let static_frame = tokio::time::timeout(Duration::from_millis(400), async {
        while stats.lock().frames < 9 { tokio::time::sleep(Duration::from_millis(5)).await; }
    }).await.is_ok();
    // Explicit disconnection does require a new source keyframe.
    events.send(CarLifeEvent::ClientLeft { video: true }).unwrap();
    tokio::time::sleep(Duration::from_millis(60)).await;
    let before = stats.lock().frames;
    events.send(CarLifeEvent::VideoFrame { source_timestamp: 0, data: frames[1].clone() }).unwrap();
    tokio::time::sleep(Duration::from_millis(40)).await;
    assert_eq!(stats.lock().frames, before);
    for frame in &frames {
        events.send(CarLifeEvent::VideoFrame { source_timestamp: 0, data: frame.clone() }).unwrap();
        tokio::time::sleep(Duration::from_millis(15)).await;
    }
    wait_frames(&stats, before + 9).await;
    stop.cancel();
    pump.await.unwrap();
    let captured = capture.await.unwrap();
    assert!(static_frame, "a healthy static source was incorrectly replaced with standby");
    assert_eq!(captured.source_configs, 2, "one config per actual source epoch");
    assert_eq!(captured.source_frames, 18);
    // Evidence is a complete first source epoch, never mixed with the waiting page or a later epoch.
    for name in ["source","outgoing"] {
        assert_eq!(crate::video_capture::read_packets(&sample_dir.join(format!("{name}.packets"))).unwrap().len(),9);
    }
    std::fs::remove_dir_all(sample_dir).unwrap();
    if let Ok(directory) = std::env::var("HYPERCAR_VIDEO_EVIDENCE") {
        let directory = std::path::Path::new(&directory);
        std::fs::write(directory.join(format!("{label}-mixed.h264")), captured.all).unwrap();
        std::fs::write(directory.join(format!("{label}-source.h264")), captured.source).unwrap();
    }
}

#[tokio::test]
async fn same_size_source_switch_and_static_frame_recovery() { switch_and_resume((1412, 654), "same-size").await; }

#[tokio::test]
async fn changed_size_source_switch_and_static_frame_recovery() { switch_and_resume((2560, 744), "changed-size").await; }

#[tokio::test]
async fn negotiated_standby_is_not_replaced_by_stale_settings_or_encoder_requests() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let client = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
    let (server, _) = listener.accept().await.unwrap();
    let (_, writer) = client.into_split();
    let key = [13; 32];
    let capture = tokio::spawn(receive(server, key));
    let mut screen = ScreenSender::new(writer, ScreenCipher::ChaCha(key), 1280, 720, false);
    let standby = crate::airplay::standby_for_aspect(1412, 654).unwrap();
    screen.send_video_annexb(standby.frames[0]).await.unwrap();
    let (ready, ready_rx) = tokio::sync::oneshot::channel();
    let (events, event_rx) = mpsc::unbounded_channel();
    let stats = Arc::new(Mutex::new(PumpStat::default()));
    let stop = SessionStop::new(8007);
    let pump = tokio::spawn(run_video_pump((2560, 744), ready_rx, event_rx, stats, stop.clone(), MediaReadiness::new(), VideoPumpOptions::default()));
    ready.send(screen).ok().unwrap();
    tokio::time::sleep(Duration::from_millis(80)).await;
    events.send(CarLifeEvent::EncoderResolution { width: 1408, height: 656 }).unwrap();
    tokio::time::sleep(Duration::from_millis(80)).await;
    stop.cancel();
    pump.await.unwrap();
    let output = capture.await.unwrap();
    assert_eq!(output.config_sizes, vec![(1280, 720)], "do not reconfigure the waiting decoder before a real source IDR");
}

#[tokio::test]
async fn source_sps_controls_handoff_despite_early_and_late_resolution_callbacks() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let client = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
    let (server, _) = listener.accept().await.unwrap();
    let (_, writer) = client.into_split();
    let key = [13; 32];
    let capture = tokio::spawn(receive(server, key));
    let (ready, ready_rx) = tokio::sync::oneshot::channel();
    let (events, event_rx) = mpsc::unbounded_channel();
    let stats = Arc::new(Mutex::new(PumpStat::default()));
    let stop = SessionStop::new(8014);
    let pump = tokio::spawn(run_video_pump((1412, 654), ready_rx, event_rx, stats.clone(), stop.clone(), MediaReadiness::new(), VideoPumpOptions::default()));
    ready.send(ScreenSender::new(writer, ScreenCipher::ChaCha(key), 1412, 654, false)).ok().unwrap();
    tokio::time::sleep(Duration::from_millis(80)).await;
    let frames = access_units();
    // 50009 requests 1408x656, but the video SPS says 1280x720. CMD and VIDEO
    // are separate sockets: neither a missing nor a late CMD reply may win over SPS.
    events.send(CarLifeEvent::EncoderResolution { width: 1408, height: 656 }).unwrap();
    events.send(CarLifeEvent::VideoFrame { source_timestamp: 0, data: frames[0].clone() }).unwrap();
    wait_frames(&stats, 1).await;
    events.send(CarLifeEvent::EncoderResolution { width: 1920, height: 1080 }).unwrap();
    for frame in frames.iter().skip(1) {
        events.send(CarLifeEvent::VideoFrame { source_timestamp: 0, data: frame.clone() }).unwrap();
    }
    let delivered = tokio::time::timeout(Duration::from_millis(500), async {
        while stats.lock().frames < 9 { tokio::time::sleep(Duration::from_millis(5)).await; }
    }).await.is_ok();
    stop.cancel();
    pump.await.unwrap();
    let output = capture.await.unwrap();
    assert!(delivered, "a late CMD size callback discarded valid source reference frames");
    assert_eq!(output.source_configs, 1, "one source configuration, derived from this GOP's SPS");
    assert_eq!(output.source_frames, 9);
    if let Ok(directory) = std::env::var("HYPERCAR_VIDEO_EVIDENCE") {
        let directory = std::path::Path::new(&directory);
        std::fs::write(directory.join("async-size-mixed.h264"), output.all).unwrap();
        std::fs::write(directory.join("async-size-source.h264"), output.source).unwrap();
    }
}

#[tokio::test]
async fn disconnected_source_cannot_borrow_previous_epochs_decoder_parameters() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let client = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
    let (server, _) = listener.accept().await.unwrap();
    let (_, writer) = client.into_split();
    let key = [13; 32];
    let capture = tokio::spawn(receive(server, key));
    let (ready, ready_rx) = tokio::sync::oneshot::channel();
    let (events, event_rx) = mpsc::unbounded_channel();
    let stats = Arc::new(Mutex::new(PumpStat::default()));
    let stop = SessionStop::new(8015);
    let pump = tokio::spawn(run_video_pump((1280, 720), ready_rx, event_rx, stats.clone(), stop.clone(), MediaReadiness::new(), VideoPumpOptions::default()));
    ready.send(ScreenSender::new(writer, ScreenCipher::ChaCha(key), 1280, 720, false)).ok().unwrap();
    let frames = access_units();
    events.send(CarLifeEvent::VideoFrame { source_timestamp: 0, data: frames[0].clone() }).unwrap();
    wait_frames(&stats, 1).await;
    events.send(CarLifeEvent::ClientLeft { video: true }).unwrap();
    // A fresh encoder can reuse parameter IDs with different contents. No parameters
    // from the previous TCP source or the waiting card may bootstrap its naked IDR.
    let nals = annexb_nals(&frames[0]);
    let packet = |kinds: &[u8]| {
        let mut out = Vec::new();
        for nal in &nals {
            if kinds.contains(&nal_type(nal)) {
                out.extend_from_slice(&[0, 0, 0, 1]); out.extend_from_slice(nal);
            }
        }
        out
    };
    for data in [packet(&[5]), packet(&[7]), packet(&[5])] {
        events.send(CarLifeEvent::VideoFrame { source_timestamp: 0, data }).unwrap();
    }
    tokio::time::sleep(Duration::from_millis(100)).await;
    let before_fresh_parameters = stats.lock().frames;
    events.send(CarLifeEvent::VideoFrame { source_timestamp: 0, data: packet(&[8]) }).unwrap();
    events.send(CarLifeEvent::VideoFrame { source_timestamp: 0, data: packet(&[5]) }).unwrap();
    wait_frames(&stats, before_fresh_parameters + 1).await;
    stop.cancel();
    pump.await.unwrap();
    let output = capture.await.unwrap();
    assert_eq!(before_fresh_parameters, 1, "old source SPS/PPS leaked across the disconnect");
    assert_eq!(output.source_frames, 2);
    assert_eq!(output.source_configs, 2);
}
