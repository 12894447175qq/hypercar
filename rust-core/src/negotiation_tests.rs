//! Stateful MIB3 receiver: commands require an initial SETUP, not just pairing.
use super::{receiver_display, setup_media_session, AirplayParams};
use crate::{bplist::{self, Value}, rtsp::{parse_rtsp_messages, RtspMessage}, session::SessionStop};
use std::{sync::Arc, time::Duration};
use parking_lot::Mutex;
use tokio::{io::{AsyncReadExt, AsyncWriteExt}, net::{TcpListener, TcpStream}};

async fn request(stream: &mut TcpStream, buffered: &mut Vec<u8>) -> RtspMessage {
    tokio::time::timeout(Duration::from_secs(2), async {
        loop {
            if let Some(message) = parse_rtsp_messages(buffered).pop() { return message; }
            let mut bytes = [0; 8192];
            let read = stream.read(&mut bytes).await.unwrap();
            assert!(read > 0, "client closed before expected request");
            buffered.extend_from_slice(&bytes[..read]);
        }
    }).await.expect("negotiation request timed out")
}

async fn reply(stream: &mut TcpStream, message: &RtspMessage, status: &str, body: Vec<u8>) {
    let header = format!("RTSP/1.0 {status}\r\nCSeq: {}\r\nContent-Length: {}\r\n\r\n", message.cseq().unwrap(), body.len());
    stream.write_all(header.as_bytes()).await.unwrap();
    stream.write_all(&body).await.unwrap();
}

#[tokio::test]
async fn mib3_requires_session_setup_before_resource_command() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let client = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
    let (mut receiver, _) = listener.accept().await.unwrap();
    let stop = SessionStop::new(50003);
    let (read, write) = client.into_split();
    let (channel, _) = crate::rtsp::RtspChannel::spawn(read, write, None, stop.clone());
    let server = tokio::spawn(async move {
        let mut buffered = Vec::new();
        let first = request(&mut receiver, &mut buffered).await;
        if first.first_line != "SETUP /setup RTSP/1.0" {
            reply(&mut receiver, &first, "454 Session Not Found", vec![]).await;
            return false;
        }
        reply(&mut receiver, &first, "200 OK", bplist::encode(&Value::dict(vec![
            ("eventPort", Value::int(5001)), ("keepAlivePort", Value::int(5020)),
        ]))).await;
        let info = request(&mut receiver, &mut buffered).await;
        assert!(info.first_line.contains(" /info "));
        reply(&mut receiver, &info, "200 OK", bplist::encode(&Value::dict(vec![
            ("displays", Value::array(vec![Value::dict(vec![
                ("uuid", Value::str("mib3-screen")), ("widthPixels", Value::int(1412)),
                ("heightPixels", Value::int(654)),
            ])])),
        ]))).await;
        let command = request(&mut receiver, &mut buffered).await;
        assert_eq!(command.first_line, "POST /command RTSP/1.0");
        assert_eq!(bplist::parse(&command.body).unwrap().get("type").and_then(Value::as_str), Some("modesChanged"));
        reply(&mut receiver, &command, "200 OK", vec![]).await;
        true
    });
    let params = AirplayParams {
        car_ip: "127.0.0.1".into(), airplay_port: 7000,
        device_id: "00:11:22:33:44:55".into(), identity_path: String::new(),
        enable_audio: false, keep_alive_stats: false, width: 2560, height: 744,
        touch_display: None, encoder_size: Arc::new(Mutex::new((2560, 744))),
    };
    let outcome = setup_media_session(&channel, &params, Value::dict(vec![]), true, stop.clone()).await;
    let correct_order = server.await.unwrap();
    stop.cancel();
    tokio::time::timeout(Duration::from_secs(2), stop.wait_idle()).await.unwrap();
    assert!(outcome.is_ok(), "MIB3 rejected bootstrap: {:?}", outcome.as_ref().err());
    let media = outcome.unwrap();
    assert_eq!(receiver_display(&media.info), (Some("mib3-screen".into()), 1412, 654));
    assert!(correct_order);
}

#[test]
fn receiver_pixel_fields_and_legacy_aliases_are_both_supported() {
    for (width_key, height_key, uuid_key) in [
        ("widthPixels", "heightPixels", "uuid"),
        ("width", "height", "UUID"),
        ("widthPixels", "heightPixels", "displayUUID"),
    ] {
        let info = Value::dict(vec![("displays", Value::array(vec![Value::dict(vec![
            (width_key, Value::int(1412)), (height_key, Value::int(654)), (uuid_key, Value::str("screen")),
        ])]))]);
        assert_eq!(receiver_display(&info), (Some("screen".into()), 1412, 654));
    }
    let malformed = Value::dict(vec![("displays", Value::array(vec![Value::dict(vec![
        ("widthPixels", Value::int(-1)), ("heightPixels", Value::int(i64::MAX)),
    ])]))]);
    assert_eq!(receiver_display(&malformed), (None, 0, 0));
}

#[tokio::test]
async fn unavailable_info_does_not_probe_authentication_or_erase_known_geometry() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let client = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
    let (mut receiver, _) = listener.accept().await.unwrap();
    let stop = SessionStop::new(50004);
    let (r, w) = client.into_split();
    let (channel, _) = crate::rtsp::RtspChannel::spawn(r, w, None, stop.clone());
    let server = tokio::spawn(async move {
        let mut buffered = Vec::new();
        let setup = request(&mut receiver, &mut buffered).await;
        assert_eq!(setup.first_line, "SETUP /setup RTSP/1.0");
        reply(&mut receiver, &setup, "200 OK", bplist::encode(&Value::dict(vec![("eventPort", Value::int(5001))]))).await;
        for _ in 0..3 {
            let info = request(&mut receiver, &mut buffered).await;
            assert!(info.first_line.contains(" /info "), "capability lookup mutated authentication");
            reply(&mut receiver, &info, "455 Method Not Valid In This State", vec![]).await;
        }
        let modes = request(&mut receiver, &mut buffered).await;
        assert_eq!(modes.first_line, "POST /command RTSP/1.0");
        reply(&mut receiver, &modes, "200 OK", vec![]).await;
    });
    let params = AirplayParams {
        car_ip: "127.0.0.1".into(), airplay_port: 7000, device_id: "00:11:22:33:44:55".into(),
        identity_path: String::new(), enable_audio: false, keep_alive_stats: false, width: 640, height: 480,
        touch_display: None, encoder_size: Arc::new(Mutex::new((640, 480))),
    };
    let known = Value::dict(vec![("displays", Value::array(vec![Value::dict(vec![
        ("uuid", Value::str("known")), ("width", Value::int(640)), ("height", Value::int(480)),
    ])]))]);
    let outcome = setup_media_session(&channel, &params, known, false, stop.clone()).await;
    server.await.unwrap();
    stop.cancel();
    tokio::time::timeout(Duration::from_secs(2), stop.wait_idle()).await.unwrap();
    assert_eq!(receiver_display(&outcome.unwrap().info), (Some("known".into()), 640, 480));
}
