//! AirPlay RTSP 控制通道（加密帧 + CSeq 异步派发）。
//!
//! 协议依据（docs/PROTOCOL-NOTES.md §9c/§9f 与 cardeck 实测）：
//!  - 控制通道密文帧 = [2B LE 长度][密文][16B Poly1305 tag]，AAD = 前 2 字节，
//!    nonce = 4 字节零 + 8 字节小端计数；收发方向各自的密钥与计数独立递增。
//!  - 单帧明文上限 **1422**（1440 MTU - 2B AAD - 16B tag；catplay 解码侧对
//!    len > 1422 直接判协议违规）；读方向保持宽松（车机的分片长度由它决定）。
//!  - 应答必须按 CSeq 异步派发：车机的音频 SETUP 实测可耗时 ~2.7s，
//!    同步读会把 /feedback 心跳饿死，3s 看门狗即拆会话（cardeck 2026-10-17 实测）。
//!  - 事件通道是双向 RTSP：车机的每个来向请求必须立即回 200 OK（echo CSeq），
//!    不应答 → 车机塞 3 条就断通道（§9c「触摸无报文（三）」）。

use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;

use parking_lot::Mutex;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::tcp::{OwnedReadHalf, OwnedWriteHalf};
use tokio::sync::{mpsc, oneshot};
use crate::session::{spawn_scoped, SessionStop};

use crate::crypto::{chacha_open, chacha_seal, nonce64};

/// 1440 MTU - 2B AAD - 16B tag（catplay AIRPLAY_CIPHER_MTU_MAX 同口径）。
pub const CIPHER_MAX_PAYLOAD: usize = 1422;

const HEADER_SIZE: usize = 2;
const TAG_SIZE: usize = 16;

/// 控制通道密文帧编解码（每方向独立密钥与计数器）。
pub struct ControlCipher {
    read_key: [u8; 32],
    write_key: [u8; 32],
    read_counter: u64,
    write_counter: u64,
}

impl ControlCipher {
    pub fn new(read_key: [u8; 32], write_key: [u8; 32]) -> Self {
        Self {
            read_key,
            write_key,
            read_counter: 0,
            write_counter: 0,
        }
    }

    /// 明文 → 密文帧序列（按 1422B 分片，每片独立封帧）。
    pub fn encrypt(&mut self, plaintext: &[u8]) -> Vec<u8> {
        let mut out = Vec::with_capacity(plaintext.len() + 32);
        let mut offset = 0usize;
        loop {
            let end = (offset + CIPHER_MAX_PAYLOAD).min(plaintext.len());
            let chunk = &plaintext[offset..end];
            let header = [(chunk.len() & 0xFF) as u8, ((chunk.len() >> 8) & 0xFF) as u8];
            let sealed = chacha_seal(&self.write_key, &nonce64(self.write_counter), chunk, &header);
            out.extend_from_slice(&header);
            out.extend_from_slice(&sealed);
            self.write_counter = self.write_counter.wrapping_add(1);
            if end >= plaintext.len() {
                break;
            }
            offset = end;
        }
        out
    }

    /// 从 buffer 里解出所有完整密文帧的明文拼接；返回 (明文, 已消费字节数)。
    /// 单帧 tag 校验失败视为通道被污染，返回 Err。
    pub fn decrypt(&mut self, buffer: &[u8]) -> Result<(Vec<u8>, usize), String> {
        let mut out = Vec::with_capacity(buffer.len());
        let mut offset = 0usize;
        while buffer.len() - offset >= HEADER_SIZE {
            let len =
                (buffer[offset] as usize) | ((buffer[offset + 1] as usize) << 8);
            let frame_end = offset + HEADER_SIZE + len + TAG_SIZE;
            if buffer.len() < frame_end {
                break;
            }
            let aad = &buffer[offset..offset + HEADER_SIZE];
            let sealed = &buffer[offset + HEADER_SIZE..frame_end];
            let plain =
                chacha_open(&self.read_key, &nonce64(self.read_counter), sealed, aad)
                    .map_err(|e| format!("控制通道解密失败: {e}"))?;
            out.extend_from_slice(&plain);
            self.read_counter = self.read_counter.wrapping_add(1);
            offset = frame_end;
        }
        Ok((out, offset))
    }
}

/// 一条已解析的 RTSP 消息（请求或应答）。
#[derive(Debug, Clone)]
pub struct RtspMessage {
    /// 应答："RTSP/1.0 200 OK"；请求："POST /command RTSP/1.0"。
    pub first_line: String,
    pub headers: HashMap<String, String>,
    pub body: Vec<u8>,
}

impl RtspMessage {
    pub fn is_response(&self) -> bool {
        self.first_line.starts_with("RTSP/")
    }

    pub fn status(&self) -> u16 {
        self.first_line
            .split_whitespace()
            .nth(1)
            .and_then(|s| s.parse().ok())
            .unwrap_or(0)
    }

    pub fn cseq(&self) -> Option<i32> {
        self.headers.get("cseq").and_then(|s| s.parse().ok())
    }

    /// 请求的方法（POST/SETUP…）；应答返回 None。
    pub fn method(&self) -> Option<&str> {
        if self.is_response() {
            None
        } else {
            self.first_line.split_whitespace().next()
        }
    }
}

/// 增量重组：从裸字节流切出完整 RTSP 消息，残余留在 buf。
pub fn parse_rtsp_messages(buf: &mut Vec<u8>) -> Vec<RtspMessage> {
    let mut messages = Vec::new();
    loop {
        let head_end = match find_head_separator(buf) {
            Some(idx) => idx,
            None => break,
        };
        let head_text = String::from_utf8_lossy(&buf[..head_end]).to_string();
        let mut lines = head_text.split("\r\n");
        let first_line = lines.next().unwrap_or("").trim().to_string();
        let mut headers: HashMap<String, String> = HashMap::new();
        for line in lines {
            if let Some((k, v)) = line.split_once(':') {
                headers.insert(k.trim().to_ascii_lowercase(), v.trim().to_string());
            }
        }
        let content_length: usize = headers
            .get("content-length")
            .and_then(|s| s.parse().ok())
            .unwrap_or(0);
        let total = head_end + 4 + content_length;
        if buf.len() < total {
            break;
        }
        let body = buf[head_end + 4..total].to_vec();
        messages.push(RtspMessage {
            first_line,
            headers,
            body,
        });
        buf.drain(..total);
    }
    messages
}

fn find_head_separator(buf: &[u8]) -> Option<usize> {
    if buf.len() < 4 {
        return None;
    }
    buf.windows(4)
        .position(|w| w == b"\r\n\r\n")
}

/// 控制通道：一条 RTSP 连接 + 可选密文帧 + CSeq 派发 + 入向请求应答。
///
/// - `request()`：串行化写、按 CSeq 登记 oneshot、读线程派发（心跳不会被慢应答饿死）。
/// - 入向请求（车机发来的 POST /command 等）：一律立即回 200 OK，
///   `/command` 的 body 经 [RtspChannel::inbound] 交给上层分发。
pub struct RtspChannel {
    label: String,
    /// 写半部：必须用 tokio Mutex——写+flush 是 await 点，parking_lot 的 guard
    /// 跨 await 会让 future 非 Send，tokio::spawn 拒收（CI 首编实证）。
    write: Arc<tokio::sync::Mutex<OwnedWriteHalf>>,
    cipher: Arc<Mutex<Option<ControlCipher>>>,
    cseq: Arc<Mutex<i32>>,
    pending: Arc<Mutex<HashMap<i32, oneshot::Sender<RtspMessage>>>>,
    reader: tokio::task::JoinHandle<()>,
}

impl RtspChannel {
    /// 建立通道：返回共享句柄 + 入向请求接收器（车机 POST 来的 /command 等；
    /// reader 已对每个入向请求自动回 200 OK，上层只需消费消息体）。
    pub fn spawn(
        read: OwnedReadHalf,
        write: OwnedWriteHalf,
        cipher: Option<ControlCipher>,
        stop: Arc<SessionStop>,
    ) -> (Arc<Self>, mpsc::UnboundedReceiver<RtspMessage>) {
        Self::spawn_named(read, write, cipher, stop, "control")
    }

    pub fn spawn_named(
        read: OwnedReadHalf,
        write: OwnedWriteHalf,
        cipher: Option<ControlCipher>,
        stop: Arc<SessionStop>,
        role: &'static str,
    ) -> (Arc<Self>, mpsc::UnboundedReceiver<RtspMessage>) {
        static NEXT_CHANNEL: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(1);
        let label = format!("{role}#{}", NEXT_CHANNEL.fetch_add(1, std::sync::atomic::Ordering::Relaxed));
        let write = Arc::new(tokio::sync::Mutex::new(write));
        let cipher = Arc::new(Mutex::new(cipher));
        let cseq = Arc::new(Mutex::new(0));
        let pending: Arc<Mutex<HashMap<i32, oneshot::Sender<RtspMessage>>>> =
            Arc::new(Mutex::new(HashMap::new()));
        let (inbound_tx, inbound) = mpsc::unbounded_channel();

        let reader = spawn_scoped(stop, reader_loop(
            read,
            write.clone(),
            cipher.clone(),
            pending.clone(),
            inbound_tx,
            label.clone(),
        ));

        let this = Arc::new(Self {
            label,
            write,
            cipher,
            cseq,
            pending,
            reader,
        });
        (this, inbound)
    }

    /// pair-verify 完成后切换到密文帧（读写计数从 0 开始）。
    pub fn set_cipher(&self, cipher: ControlCipher) {
        *self.cipher.lock() = Some(cipher);
    }

    pub fn is_encrypted(&self) -> bool {
        self.cipher.lock().is_some()
    }

    pub fn next_cseq(&self) -> i32 {
        let mut c = self.cseq.lock();
        *c += 1;
        *c
    }

    /// 发起一次 RTSP 事务；[extra_headers] 为 (名, 值) 追加头。
    pub async fn request(
        &self,
        method: &str,
        path: &str,
        body: &[u8],
        content_type: &str,
        extra_headers: &[(&str, String)],
        timeout: Duration,
    ) -> Result<RtspMessage, String> {
        let started = std::time::Instant::now();
        let cseq = self.next_cseq();
        let traced = method == "SETUP" || method == "RECORD";
        if traced {
            crate::emit_log(&format!("RTSP[{}]: begin CSeq={cseq} {method} {path} bodyBytes={} encrypted={} timeoutMs={}", self.label, body.len(), self.is_encrypted(), timeout.as_millis()));
        }
        let (tx, rx) = oneshot::channel();
        self.pending.lock().insert(cseq, tx);
        let _pending = PendingRequest { pending: self.pending.clone(), cseq };

        let head = format!(
            "{method} {path} RTSP/1.0\r\n\
             CSeq: {cseq}\r\n\
             User-Agent: AirPlay/566.25.21\r\n\
             Content-Type: {content_type}\r\n\
             Content-Length: {}\r\n\
             {extra}\r\n",
            body.len(),
            extra = extra_headers
                .iter()
                .map(|(k, v)| format!("{k}: {v}\r\n"))
                .collect::<String>(),
        );
        let mut plaintext = Vec::with_capacity(head.len() + body.len());
        plaintext.extend_from_slice(head.as_bytes());
        plaintext.extend_from_slice(body);

        if let Err(error) = write_serialized(&self.write, &self.cipher, &plaintext).await {
            let detail = format!("RTSP[{}]: write_failed CSeq={cseq} {method} {path} elapsedMs={} kind={:?} os={:?}: {error}", self.label, started.elapsed().as_millis(), error.kind(), error.raw_os_error());
            crate::emit_log(&detail);
            return Err(detail);
        }

        let result = match tokio::time::timeout(timeout, rx).await {
            Ok(Ok(msg)) => Ok(msg),
            Ok(Err(_)) => {
                self.pending.lock().remove(&cseq);
                Err("控制通道已关闭".to_string())
            }
            Err(_) => {
                self.pending.lock().remove(&cseq);
                Err(format!("RTSP 超时（{timeout:?}，CSeq={cseq} {method} {path}）"))
            }
        };
        if traced || result.as_ref().map_or(true, |response| response.status() != 200) {
            let status = match &result {
                Ok(response) => format!("status={}", response.status()),
                Err(error) => format!("error={error}"),
            };
            crate::emit_log(&format!("RTSP[{}]: end CSeq={cseq} {method} {path} elapsedMs={} {status}", self.label, started.elapsed().as_millis()));
        }
        result
    }

    /// 事件通道 fire-and-forget：POST /command（不等应答，DiPlay 同款）。
    pub async fn post_fire_and_forget(
        &self,
        path: &str,
        body: &[u8],
        content_type: &str,
    ) -> Result<(), String> {
        let cseq = self.next_cseq();
        let head = format!(
            "POST {path} RTSP/1.0\r\n\
             Content-Type: {content_type}\r\n\
             Content-Length: {}\r\n\
             CSeq: {cseq}\r\n\r\n",
            body.len()
        );
        let mut plaintext = Vec::with_capacity(head.len() + body.len());
        plaintext.extend_from_slice(head.as_bytes());
        plaintext.extend_from_slice(body);
        write_serialized(&self.write, &self.cipher, &plaintext).await
            .map_err(|e| format!("事件通道写失败 kind={:?} os={:?}: {e}", e.kind(), e.raw_os_error()))?;
        Ok(())
    }

    pub fn close(&self) {
        self.reader.abort();
        self.pending.lock().clear();
    }
}

impl Drop for RtspChannel {
    fn drop(&mut self) {
        self.reader.abort();
    }
}

type SharedWrite = Arc<tokio::sync::Mutex<OwnedWriteHalf>>;
type SharedCipher = Arc<Mutex<Option<ControlCipher>>>;
type PendingMap = Arc<Mutex<HashMap<i32, oneshot::Sender<RtspMessage>>>>;

struct PendingRequest { pending: PendingMap, cseq: i32 }

impl Drop for PendingRequest {
    fn drop(&mut self) { self.pending.lock().remove(&self.cseq); }
}

/// nonce 分配与 TCP 写入必须在同一串行区间。先加密再等待写锁会让并发心跳/应答乱序。
async fn write_serialized(write: &SharedWrite, cipher: &SharedCipher, plaintext: &[u8]) -> std::io::Result<()> {
    tokio::time::timeout(Duration::from_secs(5), async {
        let mut w = write.lock().await;
        let wire = {
            let mut guard = cipher.lock();
            match guard.as_mut() {
                Some(c) => c.encrypt(plaintext),
                None => plaintext.to_vec(),
            }
        };
        w.write_all(&wire).await
    }).await.map_err(|_| std::io::Error::new(std::io::ErrorKind::TimedOut, "RTSP write exceeded 5s"))?
}

async fn reader_loop(
    mut read: OwnedReadHalf,
    write: SharedWrite,
    cipher: SharedCipher,
    pending: PendingMap,
    inbound_tx: mpsc::UnboundedSender<RtspMessage>,
    label: String,
) {
    let mut raw: Vec<u8> = Vec::with_capacity(8192);
    let mut plain: Vec<u8> = Vec::with_capacity(8192);
    let mut chunk = [0u8; 4096];
    loop {
        let n = match read.read(&mut chunk).await {
            Ok(0) => {
                crate::emit_log(&format!("RTSP[{label}]: TCP EOF，对端已关闭读取通道"));
                break;
            }
            Ok(n) => n,
            Err(e) => {
                crate::emit_log(&format!("RTSP[{label}]: 读取失败 kind={:?} os={:?}: {e}", e.kind(), e.raw_os_error()));
                break;
            }
        };
        raw.extend_from_slice(&chunk[..n]);

        // 1) 若通道已加密，把所有完整密文帧解到 plain
        let decrypted = {
            let mut guard = cipher.lock();
            match guard.as_mut() {
                Some(c) => match c.decrypt(&raw) {
                    Ok((p, consumed)) => {
                        raw.drain(..consumed);
                        Some(p)
                    }
                    Err(e) => {
                        crate::emit_log(&format!("RTSP[{label}]: {e}——关闭通道"));
                        break;
                    }
                },
                None => None,
            }
        };
        let stream: &mut Vec<u8> = if let Some(p) = decrypted {
            plain.extend_from_slice(&p);
            &mut plain
        } else {
            &mut raw
        };

        // 2) 切出完整 RTSP 消息并派发
        for msg in parse_rtsp_messages(stream) {
            if msg.is_response() {
                if let Some(c) = msg.cseq() {
                    if let Some(tx) = pending.lock().remove(&c) {
                        let _ = tx.send(msg);
                    }
                }
            } else {
                // 车机的来向请求：先应答（立即，防止车机塞满 3 条拆通道），再分发
                if let Err(e) = respond_ok(&write, &cipher, msg.cseq()).await {
                    crate::emit_log(&format!("RTSP[{label}]: 应答写失败 kind={:?} os={:?}: {e}", e.kind(), e.raw_os_error()));
                    pending.lock().clear();
                    return;
                }
                // 上层接收器被丢弃（会话收尾）时静默跳过，不结束读循环
                let _ = inbound_tx.send(msg);
            }
        }
    }
    // 读循环退出：唤醒所有等待者
    pending.lock().clear();
}

async fn respond_ok(write: &SharedWrite, cipher: &SharedCipher, cseq: Option<i32>) -> std::io::Result<()> {
    let head = match cseq {
        Some(c) => format!("RTSP/1.0 200 OK\r\nContent-Length: 0\r\nCSeq: {c}\r\n\r\n"),
        None => "RTSP/1.0 200 OK\r\nContent-Length: 0\r\n\r\n".to_string(),
    };
    write_serialized(write, cipher, head.as_bytes()).await
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::net::{TcpListener, TcpStream};

    #[tokio::test(flavor = "multi_thread", worker_threads = 4)]
    async fn encrypted_concurrent_writes_and_inbound_replies_keep_nonce_order() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let client = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut server, _) = listener.accept().await.unwrap();
        let (r, w) = client.into_split();
        let stop = SessionStop::new(90);
        let (channel, mut inbound) = RtspChannel::spawn(r, w, Some(ControlCipher::new([1; 32], [2; 32])), stop.clone());
        let hold_write = channel.write.lock().await;
        let mut tasks = Vec::new();
        for i in 0..128 {
            let channel = channel.clone();
            tasks.push(tokio::spawn(async move {
                channel.post_fire_and_forget("/command", format!("body-{i}").as_bytes(), "text/plain").await.unwrap();
            }));
        }
        // 正在等待写锁的消息不得提前消耗 nonce。
        tokio::time::sleep(Duration::from_millis(30)).await;
        assert_eq!(channel.cipher.lock().as_ref().unwrap().write_counter, 0);
        drop(hold_write);
        let mut remote_cipher = ControlCipher::new([2; 32], [1; 32]);
        let request = remote_cipher.encrypt(b"POST /command RTSP/1.0\r\nCSeq: 701\r\nContent-Length: 0\r\n\r\n");
        server.write_all(&request).await.unwrap();
        let mut raw = Vec::new();
        let mut plain = Vec::new();
        let mut received = Vec::new();
        tokio::time::timeout(Duration::from_secs(5), async {
            while received.len() < 129 {
                let mut chunk = [0u8; 16384];
                let n = server.read(&mut chunk).await.unwrap();
                assert!(n > 0);
                raw.extend_from_slice(&chunk[..n]);
                let (decoded, consumed) = remote_cipher.decrypt(&raw).unwrap();
                raw.drain(..consumed);
                plain.extend_from_slice(&decoded);
                received.extend(parse_rtsp_messages(&mut plain));
            }
        }).await.unwrap();
        for task in tasks { task.await.unwrap(); }
        assert_eq!(received.iter().filter(|m| !m.is_response()).count(), 128);
        assert!(received.iter().any(|m| m.status() == 200 && m.cseq() == Some(701)));
        assert_eq!(inbound.recv().await.unwrap().cseq(), Some(701));
        stop.cancel();
        tokio::time::timeout(Duration::from_secs(1), stop.wait_idle()).await.unwrap();
    }

    #[tokio::test]
    async fn cancelled_request_removes_pending_transaction() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let client = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (_server, _) = listener.accept().await.unwrap();
        let (r, w) = client.into_split();
        let stop = SessionStop::new(91);
        let (channel, _inbound) = RtspChannel::spawn(r, w, None, stop.clone());
        let request_channel = channel.clone();
        let task = tokio::spawn(async move {
            let _ = request_channel.request("GET", "/info", &[], "", &[], Duration::from_secs(20)).await;
        });
        tokio::task::yield_now().await;
        assert_eq!(channel.pending.lock().len(), 1);
        task.abort();
        let _ = task.await;
        assert!(channel.pending.lock().is_empty());
        stop.cancel();
        stop.wait_idle().await;
    }
}
