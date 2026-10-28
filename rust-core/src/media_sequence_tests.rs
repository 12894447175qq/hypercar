//! Real loopback sockets at the post-pairing boundary; no phone/MIB3 acceptance claim.
use super::*;
use crate::rtsp::{parse_rtsp_messages, RtspMessage};
use std::collections::VecDeque;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

pub(super) struct LoopbackSockets;

impl SocketProvider for LoopbackSockets {
    fn open_tcp(&self, host: &str, port: u16, _: u32) -> Option<TcpStream> {
        assert_eq!(host, "127.0.0.1");
        let socket = std::net::TcpStream::connect((host, port)).ok()?;
        socket.set_nonblocking(true).ok()?;
        TcpStream::from_std(socket).ok()
    }

    fn open_udp(&self, host: &str, port: u16) -> Option<UdpSocket> {
        assert_eq!(host, "127.0.0.1");
        let socket = std::net::UdpSocket::bind((host, 0)).ok()?;
        socket.connect((host, port)).ok()?;
        socket.set_nonblocking(true).ok()?;
        UdpSocket::from_std(socket).ok()
    }
}

struct Wire {
    socket: TcpStream,
    buffered: Vec<u8>,
    messages: VecDeque<RtspMessage>,
}

impl Wire {
    async fn next(&mut self) -> RtspMessage {
        tokio::time::timeout(Duration::from_secs(3), async {
            loop {
                if let Some(message) = self.messages.pop_front() {
                    return message;
                }
                let mut bytes = [0; 8192];
                let count = self.socket.read(&mut bytes).await.unwrap();
                assert!(count > 0, "control closed before expected message");
                self.buffered.extend_from_slice(&bytes[..count]);
                self.messages
                    .extend(parse_rtsp_messages(&mut self.buffered));
            }
        })
        .await
        .expect("receiver timed out")
    }

    async fn reply(&mut self, request: &RtspMessage, status: &str, body: Value) {
        let body = bplist::encode(&body);
        let head = format!(
            "RTSP/1.0 {status}\r\nCSeq: {}\r\nContent-Length: {}\r\n\r\n",
            request.cseq().unwrap(),
            body.len()
        );
        self.socket.write_all(head.as_bytes()).await.unwrap();
        self.socket.write_all(&body).await.unwrap();
    }
}

struct Receiver {
    control: Wire,
    event: TcpListener,
    video: TcpListener,
    keepalive: UdpSocket,
    audio: UdpSocket,
}

struct ActiveReceiver {
    control: Wire,
    _event: TcpStream,
    video: TcpStream,
    keepalive: UdpSocket,
    audio: UdpSocket,
}

impl Receiver {
    async fn bootstrap(self, require_event_first: bool) -> Result<ActiveReceiver, &'static str> {
        self.bootstrap_record(require_event_first, "200 OK").await
    }

    async fn bootstrap_record(
        mut self,
        require_event_first: bool,
        record_status: &str,
    ) -> Result<ActiveReceiver, &'static str> {
        let setup = self.control.next().await;
        assert_eq!(setup.first_line, "SETUP /setup RTSP/1.0");
        self.control
            .reply(
                &setup,
                "200 OK",
                Value::dict(vec![
                    (
                        "eventPort",
                        Value::int(self.event.local_addr().unwrap().port() as i64),
                    ),
                    (
                        "keepAlivePort",
                        Value::int(self.keepalive.local_addr().unwrap().port() as i64),
                    ),
                ]),
            )
            .await;
        let info = self.control.next().await;
        assert!(info.first_line.contains(" /info "));
        self.control
            .reply(
                &info,
                "200 OK",
                Value::dict(vec![(
                    "displays",
                    Value::array(vec![Value::dict(vec![
                        ("uuid", Value::str("test-main-screen")),
                        ("widthPixels", Value::int(800)),
                        ("heightPixels", Value::int(480)),
                    ])]),
                )]),
            )
            .await;

        let event = if require_event_first {
            tokio::select! {
                biased;
                event = self.event.accept() => event.unwrap().0,
                command = self.control.next() => {
                    self.control.reply(&command, "454 Session Not Found", Value::dict(vec![])).await;
                    return Err("modesChanged arrived before event connection");
                }
            }
        } else {
            let modes = self.control.next().await;
            assert_eq!(modes.first_line, "POST /command RTSP/1.0");
            self.control
                .reply(&modes, "200 OK", Value::dict(vec![]))
                .await;
            self.event.accept().await.unwrap().0
        };
        if require_event_first {
            let modes = self.control.next().await;
            assert_eq!(
                bplist::parse(&modes.body)
                    .unwrap()
                    .get("type")
                    .and_then(Value::as_str),
                Some("modesChanged")
            );
            self.control
                .reply(&modes, "200 OK", Value::dict(vec![]))
                .await;
        }
        let screen = self.control.next().await;
        assert_eq!(screen.first_line, "SETUP / RTSP/1.0");
        let streams = bplist::parse(&screen.body).unwrap();
        assert_eq!(
            streams.get("streams").and_then(Value::as_array).unwrap()[0]
                .get("type")
                .and_then(Value::as_int),
            Some(110)
        );
        self.control
            .reply(
                &screen,
                "200 OK",
                Value::dict(vec![(
                    "streams",
                    Value::array(vec![Value::dict(vec![
                        ("type", Value::int(110)),
                        (
                            "dataPort",
                            Value::int(self.video.local_addr().unwrap().port() as i64),
                        ),
                    ])]),
                )]),
            )
            .await;
        let video = self.video.accept().await.unwrap().0;
        let record = self.control.next().await;
        assert_eq!(record.first_line, "RECORD / RTSP/1.0");
        self.control
            .reply(&record, record_status, Value::dict(vec![]))
            .await;
        Ok(ActiveReceiver {
            control: self.control,
            _event: event,
            video,
            keepalive: self.keepalive,
            audio: self.audio,
        })
    }
}

impl ActiveReceiver {
    async fn next_media_request(&mut self) -> RtspMessage {
        loop {
            let request = self.control.next().await;
            if request.first_line == "POST /feedback RTSP/1.0" {
                self.control
                    .reply(&request, "200 OK", Value::dict(vec![]))
                    .await;
            } else {
                return request;
            }
        }
    }

    async fn audio_setup(&mut self, status: &str) {
        let request = self.next_media_request().await;
        assert!(request.first_line.starts_with("SETUP rtsp://127.0.0.1:"));
        let body = bplist::parse(&request.body).unwrap();
        let stream = &body.get("streams").and_then(Value::as_array).unwrap()[0];
        assert_eq!(stream.get("type").and_then(Value::as_int), Some(100));
        self.control
            .reply(
                &request,
                status,
                Value::dict(vec![(
                    "streams",
                    Value::array(vec![Value::dict(vec![
                        ("type", Value::int(100)),
                        (
                            "dataPort",
                            Value::int(self.audio.local_addr().unwrap().port() as i64),
                        ),
                    ])]),
                )]),
            )
            .await;
    }
}

async fn video_message(video: &mut TcpStream) -> (u8, Vec<u8>) {
    let mut header = [0; 128];
    video.read_exact(&mut header).await.unwrap();
    let mut body = vec![0; u32::from_le_bytes(header[..4].try_into().unwrap()) as usize];
    video.read_exact(&mut body).await.unwrap();
    (header[4], body)
}

// Distinct from the initial standby config. This checks wire delivery, not decoder output.
const PROBE_CONFIG: &[u8] = &[1, 100, 0, 31, 255, 225, 0, 1, 103, 1, 0, 1, 104];

async fn fixture(
    enable_audio: bool,
    shared: Option<[u8; 32]>,
) -> (
    tokio::task::JoinHandle<Result<AirplayOutcome, String>>,
    Receiver,
    Arc<SessionStop>,
) {
    let control = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let socket = TcpStream::connect(control.local_addr().unwrap())
        .await
        .unwrap();
    let receiver = control.accept().await.unwrap().0;
    let stop = SessionStop::new(50004);
    let (read, write) = socket.into_split();
    let (channel, _) = RtspChannel::spawn(read, write, None, stop.clone());
    let params = AirplayParams {
        car_ip: "127.0.0.1".into(),
        airplay_port: control.local_addr().unwrap().port(),
        device_id: "00:11:22:33:44:55".into(),
        identity_path: String::new(),
        enable_audio,
        keep_alive_stats: false,
        width: 800,
        height: 480,
        touch_display: None,
        encoder_size: Arc::new(Mutex::new((800, 480))),
    };
    let receiver = Receiver {
        control: Wire {
            socket: receiver,
            buffered: vec![],
            messages: VecDeque::new(),
        },
        event: TcpListener::bind("127.0.0.1:0").await.unwrap(),
        video: TcpListener::bind("127.0.0.1:0").await.unwrap(),
        keepalive: UdpSocket::bind("127.0.0.1:0").await.unwrap(),
        audio: UdpSocket::bind("127.0.0.1:0").await.unwrap(),
    };
    let child_stop = stop.clone();
    let task = tokio::spawn(async move {
        let (touch_tx, _touch_rx) = mpsc::unbounded_channel();
        let (key_tx, _key_rx) = mpsc::unbounded_channel();
        let (_iap_tx, iap_rx) = mpsc::channel(8);
        negotiate_media(
            channel,
            &params,
            Value::dict(vec![]),
            shared,
            Arc::new(LoopbackSockets),
            touch_tx,
            key_tx,
            iap_rx,
            child_stop,
        )
        .await
    });
    (task, receiver, stop)
}

async fn drain(stop: &SessionStop) {
    stop.cancel();
    tokio::time::timeout(Duration::from_secs(2), stop.wait_idle())
        .await
        .unwrap();
}

#[tokio::test]
async fn event_connection_precedes_modes_and_screen_setup() {
    let (task, receiver, stop) = fixture(false, None).await;
    let server_stop = stop.clone();
    let server = tokio::spawn(async move {
        let active = receiver.bootstrap(true).await?;
        server_stop.notified().await;
        drop(active);
        Ok::<_, &'static str>(())
    });
    let result = tokio::time::timeout(Duration::from_secs(3), task)
        .await
        .unwrap()
        .unwrap();
    drain(&stop).await;
    let order = server.await.unwrap();
    assert!(result.is_ok(), "media bootstrap failed: {:?}", result.err());
    assert!(order.is_ok(), "{order:?}");
}

#[tokio::test]
async fn video_handoff_does_not_wait_for_audio_setup_response() {
    let (mut task, receiver, stop) = fixture(true, Some([9; 32])).await;
    let server_stop = stop.clone();
    let server = tokio::spawn(async move {
        let _active = receiver.bootstrap(false).await.unwrap();
        // Keep all sockets alive while withholding the optional audio response.
        server_stop.notified().await;
    });
    let result = tokio::time::timeout(Duration::from_millis(600), &mut task).await;
    drain(&stop).await;
    server.await.unwrap();
    if result.is_err() {
        task.abort();
    }
    assert!(
        result.is_ok(),
        "optional audio blocked video handoff after RECORD"
    );
    assert!(result.unwrap().unwrap().is_ok());
}

async fn audio_timeout(record_timeout: bool) {
    let (task, receiver, stop) = fixture(true, Some([9; 32])).await;
    let (finish_tx, mut finish_rx) = tokio::sync::oneshot::channel();
    let server = tokio::spawn(async move {
        let mut active = receiver.bootstrap(true).await.unwrap();
        if record_timeout {
            active.audio_setup("200 OK").await;
            let record = active.next_media_request().await;
            assert_eq!(record.first_line, "RECORD / RTSP/1.0");
        } else {
            let audio = active.next_media_request().await;
            assert!(audio.first_line.starts_with("SETUP rtsp://"));
        }
        let mut video = active.video;
        let video_probe = tokio::spawn(async move {
            let mut probe_seen = false;
            let mut frame_seen = false;
            loop {
                let (opcode, body) = video_message(&mut video).await;
                if opcode == 1 && body == PROBE_CONFIG {
                    probe_seen = true;
                }
                if probe_seen && opcode == 0 && !body.is_empty() {
                    frame_seen = true;
                }
                if frame_seen && matches!(opcode, 2 | 5) {
                    return video;
                }
            }
        });
        // No response for the real 8s audio timeout. Other CSeq transactions must work.
        let mut feedback = 0;
        let mut udp_packets = 0;
        let mut udp = [0; 16];
        loop {
            tokio::select! {
                _ = &mut finish_rx => break,
                message = active.control.next() => {
                    assert_eq!(message.first_line, "POST /feedback RTSP/1.0", "unexpected retry or audio RECORD after failed SETUP");
                    feedback += 1;
                    active.control.reply(&message, "200 OK", Value::dict(vec![])).await;
                }
                count = active.keepalive.recv(&mut udp) => {
                    assert_eq!(count.unwrap(), 4);
                    udp_packets += 1;
                }
            }
        }
        assert!(
            feedback >= if record_timeout { 3 } else { 5 },
            "feedback was delayed by optional audio"
        );
        if !record_timeout {
            assert!(
                udp_packets >= 1,
                "5s UDP keepalive was delayed by optional audio"
            );
        }
        active.video = tokio::time::timeout(Duration::from_secs(1), video_probe)
            .await
            .unwrap()
            .unwrap();
        active
    });
    let mut outcome = tokio::time::timeout(Duration::from_secs(2), task)
        .await
        .unwrap()
        .unwrap()
        .unwrap();
    let (ready_tx, ready_rx) = tokio::sync::oneshot::channel();
    outcome.audio.take().unwrap().start(ready_tx);
    spawn_scoped(stop.clone(), async move {
        let mut screen = outcome.screen;
        screen.send_config(PROBE_CONFIG).await.unwrap();
        let standby = standby_for_aspect(800, 480).unwrap();
        assert!(screen.send_video_annexb(&standby.frames[0]).await.unwrap());
        loop {
            tokio::time::sleep(Duration::from_secs(1)).await;
            screen.maybe_send_keep_alive().await.unwrap();
        }
    });
    assert!(tokio::time::timeout(Duration::from_secs(10), ready_rx)
        .await
        .unwrap()
        .is_err());
    assert!(
        !stop.is_cancelled(),
        "optional audio failure cancelled video"
    );
    finish_tx.send(()).unwrap();
    let active = server.await.unwrap();
    drain(&stop).await;
    drop(active);
}

#[tokio::test]
async fn audio_timeout_keeps_video_feedback_and_udp_keepalive_running() {
    audio_timeout(false).await;
}

#[tokio::test]
async fn audio_record_timeout_keeps_video_and_feedback_running() {
    audio_timeout(true).await;
}

async fn audio_activation(setup_status: &'static str, record_status: &'static str) {
    let (task, receiver, stop) = fixture(true, Some([9; 32])).await;
    let (record_seen_tx, record_seen_rx) = tokio::sync::oneshot::channel();
    let (release_tx, release_rx) = tokio::sync::oneshot::channel();
    let (consumed_tx, consumed_rx) = tokio::sync::oneshot::channel();
    let server_stop = stop.clone();
    let server = tokio::spawn(async move {
        let mut active = receiver.bootstrap(true).await.unwrap();
        active.audio_setup(setup_status).await;
        if setup_status == "200 OK" {
            let record = active.next_media_request().await;
            assert_eq!(record.first_line, "RECORD / RTSP/1.0");
            record_seen_tx.send(()).unwrap();
            release_rx.await.unwrap();
            active
                .control
                .reply(&record, record_status, Value::dict(vec![]))
                .await;
        }
        // Even after audio rejection the video socket must remain usable.
        loop {
            let (opcode, body) = video_message(&mut active.video).await;
            if opcode == 1 && body == PROBE_CONFIG {
                break;
            }
        }
        if setup_status == "200 OK" && record_status == "200 OK" {
            let mut packet = [0; 2048];
            let count =
                tokio::time::timeout(Duration::from_secs(1), active.audio.recv(&mut packet))
                    .await
                    .unwrap()
                    .unwrap();
            assert_eq!(packet[1] & 0x7f, 100);
            assert_eq!(count, 12 + 352 * 2 * 2 + 16 + 8);
        }
        consumed_tx.send(()).unwrap();
        server_stop.notified().await;
        active
    });
    let mut outcome = task.await.unwrap().unwrap();
    let (ready_tx, mut ready_rx) = tokio::sync::oneshot::channel();
    outcome.audio.take().unwrap().start(ready_tx);
    if setup_status == "200 OK" {
        record_seen_rx.await.unwrap();
        assert!(
            matches!(
                ready_rx.try_recv(),
                Err(tokio::sync::oneshot::error::TryRecvError::Empty)
            ),
            "audio published before second RECORD acknowledgement"
        );
        release_tx.send(()).unwrap();
    }
    let ready = tokio::time::timeout(Duration::from_secs(2), ready_rx)
        .await
        .unwrap();
    if setup_status == "200 OK" && record_status == "200 OK" {
        let mut audio = ready.unwrap();
        audio.send_pcm(&vec![1234; 352 * 2]).await.unwrap();
    } else {
        assert!(ready.is_err());
    }
    assert!(!stop.is_cancelled());
    outcome.screen.send_config(PROBE_CONFIG).await.unwrap();
    // Wait for the receiver to consume the video/audio before closing the session.
    tokio::time::timeout(Duration::from_secs(2), consumed_rx)
        .await
        .unwrap()
        .unwrap();
    drain(&stop).await;
    server.await.unwrap();
}

#[tokio::test]
async fn audio_is_published_only_after_second_record_acknowledgement() {
    audio_activation("200 OK", "200 OK").await;
}

#[tokio::test]
async fn rejected_audio_setup_does_not_send_record_or_close_video() {
    audio_activation("400 Bad Request", "200 OK").await;
}

#[tokio::test]
async fn rejected_audio_record_does_not_publish_audio_or_close_video() {
    audio_activation("200 OK", "500 Internal Server Error").await;
}

async fn cancel_audio(during_record: bool, before_start: bool) {
    let (task, receiver, stop) = fixture(true, Some([9; 32])).await;
    let (stage_tx, stage_rx) = tokio::sync::oneshot::channel();
    let server = tokio::spawn(async move {
        let mut active = receiver.bootstrap(true).await.unwrap();
        if !before_start {
            if during_record {
                active.audio_setup("200 OK").await;
                let record = active.next_media_request().await;
                assert_eq!(record.first_line, "RECORD / RTSP/1.0");
            } else {
                let setup = active.next_media_request().await;
                assert!(setup.first_line.starts_with("SETUP rtsp://"));
            }
        }
        stage_tx.send(()).unwrap();
        let mut bytes = [0; 2048];
        let read = tokio::time::timeout(
            Duration::from_secs(2),
            active.control.socket.read(&mut bytes),
        )
        .await
        .unwrap()
        .unwrap();
        assert_eq!(read, 0, "cancelled session wrote another request");
        let mut packet = [0; 2048];
        assert!(
            tokio::time::timeout(Duration::from_millis(80), active.audio.recv(&mut packet))
                .await
                .is_err()
        );
    });
    let mut outcome = task.await.unwrap().unwrap();
    let (ready_tx, ready_rx) = tokio::sync::oneshot::channel();
    if before_start {
        stage_rx.await.unwrap();
        stop.cancel();
        outcome.audio.take().unwrap().start(ready_tx);
    } else {
        outcome.audio.take().unwrap().start(ready_tx);
        stage_rx.await.unwrap();
        stop.cancel();
    }
    assert!(tokio::time::timeout(Duration::from_secs(1), ready_rx)
        .await
        .unwrap()
        .is_err());
    drain(&stop).await;
    server.await.unwrap();
}

#[tokio::test]
async fn cancellation_during_audio_setup_does_not_send_late_record() {
    cancel_audio(false, false).await;
}

#[tokio::test]
async fn cancellation_during_audio_record_does_not_publish_audio() {
    cancel_audio(true, false).await;
}

#[tokio::test]
async fn cancelled_pending_audio_cannot_start_again() {
    cancel_audio(false, true).await;
}

#[tokio::test]
async fn rejected_first_record_does_not_handoff_media_or_start_audio() {
    let (task, receiver, stop) = fixture(true, Some([9; 32])).await;
    let server_stop = stop.clone();
    let server = tokio::spawn(async move {
        let mut active = receiver
            .bootstrap_record(true, "500 Internal Server Error")
            .await
            .unwrap();
        server_stop.notified().await;
        let mut bytes = [0; 2048];
        let read = tokio::time::timeout(
            Duration::from_secs(1),
            active.control.socket.read(&mut bytes),
        )
        .await
        .unwrap()
        .unwrap();
        assert_eq!(read, 0, "audio SETUP followed a failed video RECORD");
    });
    let outcome = task.await.unwrap();
    assert!(matches!(outcome, Err(ref error) if error.contains("RECORD 被拒")));
    drain(&stop).await;
    server.await.unwrap();
}

#[tokio::test]
async fn event_channel_is_encrypted_and_retained_after_video_handoff() {
    let shared = [9; 32];
    let (task, receiver, stop) = fixture(false, Some(shared)).await;
    let (tested_tx, tested_rx) = tokio::sync::oneshot::channel();
    let server_stop = stop.clone();
    let server = tokio::spawn(async move {
        let mut active = receiver.bootstrap(true).await.unwrap();
        let (read_key, write_key) = derive_event_keys(&shared);
        let mut cipher = ControlCipher::new(write_key, read_key);
        let body = bplist::encode(&Value::dict(vec![("type", Value::str("ping"))]));
        let header = format!(
            "POST /command RTSP/1.0\r\nCSeq: 77\r\nContent-Length: {}\r\n\r\n",
            body.len()
        );
        let mut request = header.into_bytes();
        request.extend_from_slice(&body);
        active
            ._event
            .write_all(&cipher.encrypt(&request))
            .await
            .unwrap();
        let mut encrypted = vec![];
        let mut plain = vec![];
        loop {
            let mut bytes = [0; 2048];
            let count = active._event.read(&mut bytes).await.unwrap();
            assert!(count > 0);
            encrypted.extend_from_slice(&bytes[..count]);
            let (decoded, consumed) = cipher.decrypt(&encrypted).unwrap();
            encrypted.drain(..consumed);
            plain.extend_from_slice(&decoded);
            if let Some(response) = parse_rtsp_messages(&mut plain).pop() {
                assert_eq!(response.status(), 200);
                assert_eq!(response.cseq(), Some(77));
                break;
            }
        }
        tested_tx.send(()).unwrap();
        server_stop.notified().await;
    });
    let outcome = task.await.unwrap().unwrap();
    assert!(
        outcome.audio.is_none(),
        "disabled audio should not schedule SETUP/RECORD"
    );
    tokio::time::timeout(Duration::from_secs(2), tested_rx)
        .await
        .unwrap()
        .unwrap();
    drain(&stop).await;
    server.await.unwrap();
}
