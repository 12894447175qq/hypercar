use super::*;

async fn command(socket: &mut TcpStream) -> (u32, Vec<u8>) {
    let mut header = [0; 8];
    socket.read_exact(&mut header).await.unwrap();
    let mut body = vec![0; u16::from_be_bytes([header[0], header[1]]) as usize];
    socket.read_exact(&mut body).await.unwrap();
    (u32::from_be_bytes(header[4..8].try_into().unwrap()), body)
}

#[tokio::test]
async fn encoder_init_waits_for_airplay_geometry_and_cancellation_closes_waiters() {
    for cancelled in [false, true] {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let mut md = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (hu, _) = listener.accept().await.unwrap();
        let (events, _) = mpsc::unbounded_channel();
        let size = Arc::new(Mutex::new((2560, 744)));
        let stop = SessionStop::new(8007);
        let (video_ready, ready_rx) = tokio::sync::watch::channel(false);
        let handler = tokio::spawn(handle_cmd_client(hu, events, size.clone(), 30, 0,
            "00:11:22:33:44:55".into(), "test".into(), stop.clone(),
            Arc::new(std::sync::atomic::AtomicBool::new(false)), ready_rx));
        assert_eq!(command(&mut md).await.0, CMD_HU_PROTOCOL_VERSION);
        // Replay the phase-5 boundary observed arriving before AirPlay /info.
        md.write_all(&[0, 0, 0, 0, 0, 1, 0, 0x4b]).await.unwrap();
        let mut byte = [0];
        assert!(tokio::time::timeout(Duration::from_millis(40), md.read_exact(&mut byte)).await.is_err());
        if cancelled {
            stop.cancel();
            handler.await.unwrap();
            assert_eq!(md.read(&mut byte).await.unwrap(), 0);
        } else {
            *size.lock() = (1412, 654);
            video_ready.send_replace(true);
            let (id, body) = command(&mut md).await;
            assert_eq!(id, CMD_VIDEO_ENCODER_INIT);
            // Independent protobuf fixture: width=1408, height=656, fps=30.
            assert_eq!(body, [0x08, 0x80, 0x0b, 0x10, 0x90, 0x05, 0x18, 0x1e]);
            stop.cancel();
            handler.await.unwrap();
        }
    }
}

#[tokio::test]
async fn fragmented_carlife_video_preserves_the_actual_h264_payload() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let mut md = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
    let (hu, _) = listener.accept().await.unwrap();
    let (events, mut receiver) = mpsc::unbounded_channel();
    let source = include_bytes!("../testdata/source-1280x720.h264");
    let handler = tokio::spawn(handle_media_client(hu, events, MediaKind::Video, "VIDEO",
        Arc::new(std::sync::atomic::AtomicBool::new(false))));
    let mut wire = Vec::new();
    wire.extend_from_slice(&(source.len() as u32).to_be_bytes());
    wire.extend_from_slice(&1_793_192_685u32.to_be_bytes());
    wire.extend_from_slice(&0x0002_0001u32.to_be_bytes());
    wire.extend_from_slice(source);
    // Fragment the header and body independently of TCP read boundaries.
    for part in wire.chunks(733) { md.write_all(part).await.unwrap(); }
    let frame = tokio::time::timeout(Duration::from_secs(2), receiver.recv()).await.unwrap().unwrap();
    match frame {
        CarLifeEvent::VideoFrame { source_timestamp, data } => {
            assert_eq!(source_timestamp, 1_793_192_685);
            assert_eq!(data, source);
        }
        _ => panic!("expected intact source video"),
    }
    drop(md);
    handler.await.unwrap();
}

#[tokio::test]
async fn late_port_conflict_rolls_back_every_earlier_listener() {
    let mut reservations: Vec<_> = (0..6).map(|_| std::net::TcpListener::bind("0.0.0.0:0").unwrap()).collect();
    let ports: [u16; 6] = reservations.iter().map(|l| l.local_addr().unwrap().port()).collect::<Vec<_>>().try_into().unwrap();
    let occupied = reservations.pop().unwrap();
    drop(reservations);
    let error = reserve_listener_set(ports).err().expect("foreign listener must not be adopted");
    assert!(error.contains(&ports[5].to_string()));
    for port in &ports[..5] {
        let rebound = std::net::TcpListener::bind(("0.0.0.0", *port));
        assert!(rebound.is_ok(), "partial gateway leaked port {port}");
    }
    assert_eq!(occupied.local_addr().unwrap().port(), ports[5]);
}

#[tokio::test]
async fn complete_reservation_closes_cleanly_and_can_restart() {
    let listeners = reserve_listener_set([0; 6]).unwrap();
    let ports: [u16; 6] = listeners.iter().map(|l| l.local_addr().unwrap().port()).collect::<Vec<_>>().try_into().unwrap();
    drop(listeners);
    let restarted = reserve_listener_set(ports).unwrap();
    drop(restarted);
}

#[test]
fn older_configs_keep_source_enabled_and_transport_only_is_explicit() {
    let json = r#"{"device_id":"test","car_ip":"127.0.0.1","airplay_port":7000,"width":640,"height":480,"fps":30,"identity_path":"test"}"#;
    let previous: crate::engine::EngineConfig = serde_json::from_str(json).unwrap();
    assert!(previous.carlife_enabled);
    assert!(!previous.wait_for_control, "older/non-MIB3 configurations must retain audio startup");
    let explicit = json.replace("\"fps\":30", "\"fps\":30,\"carlife_enabled\":false");
    let only_transport: crate::engine::EngineConfig = serde_json::from_str(&explicit).unwrap();
    assert!(!only_transport.carlife_enabled);
    let mib3: crate::engine::EngineConfig = serde_json::from_str(&json.replace("\"fps\":30", "\"fps\":30,\"wait_for_control\":true")).unwrap();
    assert!(mib3.wait_for_control && mib3.carlife_enabled);
}
