//! Bounded, generation-owned hardware codec output bridge.
use crate::session::SessionStop;
use once_cell::sync::Lazy;
use parking_lot::Mutex;
use std::sync::Arc;
use tokio::sync::mpsc;
pub struct Frame {
    pub width: u32,
    pub height: u32,
    pub pts: u64,
    pub data: Vec<u8>,
}
struct Slot {
    stop: Arc<SessionStop>,
    tx: mpsc::Sender<Frame>,
}
static SLOT: Lazy<Mutex<Option<Slot>>> = Lazy::new(|| Mutex::new(None));
pub struct Lease {
    id: i64,
}
impl Drop for Lease {
    fn drop(&mut self) {
        let mut slot = SLOT.lock();
        if slot.as_ref().is_some_and(|s| s.stop.id == self.id) {
            *slot = None;
        }
    }
}
pub fn register(stop: Arc<SessionStop>) -> (Lease, mpsc::Receiver<Frame>) {
    let (tx, rx) = mpsc::channel(4);
    let id = stop.id;
    *SLOT.lock() = Some(Slot { stop, tx });
    (Lease { id }, rx)
}
pub fn submit(id: i64, frame: Frame) -> bool {
    let slot = SLOT.lock();
    let Some(s) = slot.as_ref() else { return false };
    s.stop.id == id
        && !s.stop.is_cancelled()
        && frame.width >= 2
        && frame.height >= 2
        && frame.data.len() <= crate::video_capture::MAX_PACKET
        && s.tx.try_send(frame).is_ok()
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn stale_full_and_cancelled_output_cannot_enter_a_session() {
        let stop = SessionStop::new(135);
        let (_lease, mut rx) = register(stop.clone());
        let frame = || Frame {
            width: 1280,
            height: 720,
            pts: 0,
            data: vec![1],
        };
        assert!(!submit(134, frame()));
        for _ in 0..4 {
            assert!(submit(135, frame()));
        }
        assert!(!submit(135, frame()));
        rx.try_recv().unwrap();
        stop.cancel();
        assert!(!submit(135, frame()));
    }
}
