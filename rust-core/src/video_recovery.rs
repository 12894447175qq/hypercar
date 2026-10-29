//! Receiver requests are bounded per session; requesting an IDR is not proof of receiving one.
use crate::session::SessionStop;
use once_cell::sync::Lazy;
use parking_lot::Mutex;
use std::{
    collections::HashMap,
    sync::Arc,
    time::{Duration, Instant},
};
struct Entry {
    stop: Arc<SessionStop>,
    hardware: bool,
    count: u8,
    last: Option<Instant>,
    pending: bool,
    waiting: Option<Instant>,
}
static ENTRIES: Lazy<Mutex<HashMap<i64, Entry>>> = Lazy::new(|| Mutex::new(HashMap::new()));
pub struct Lease(i64);
impl Drop for Lease {
    fn drop(&mut self) {
        ENTRIES.lock().remove(&self.0);
    }
}
pub fn register(stop: Arc<SessionStop>, hardware: bool) -> Lease {
    let id = stop.id;
    ENTRIES.lock().insert(
        id,
        Entry {
            stop,
            hardware,
            count: 0,
            last: None,
            pending: false,
            waiting: None,
        },
    );
    Lease(id)
}
pub fn request(id: i64) -> Option<bool> {
    let mut entries = ENTRIES.lock();
    let e = entries.get_mut(&id)?;
    if e.stop.is_cancelled()
        || e.count >= 3
        || e.last.is_some_and(|t| t.elapsed() < Duration::from_secs(5))
    {
        return None;
    }
    e.count += 1;
    e.last = Some(Instant::now());
    e.waiting = e.last;
    e.pending = !e.hardware;
    Some(e.hardware)
}
pub fn take(id: i64) -> bool {
    let (take, expired) = {
        let mut entries = ENTRIES.lock();
        let Some(e) = entries.get_mut(&id) else {
            return false;
        };
        if e.stop.is_cancelled() {
            return false;
        }
        let expired = e
            .waiting
            .is_some_and(|t| t.elapsed() > Duration::from_secs(8));
        if expired {
            e.waiting = None;
        }
        (std::mem::take(&mut e.pending), expired)
    };
    if expired {
        crate::emit_log("VIDEO: keyframe_recovery=unconfirmed timeoutMs=8000；未取得新来源IDR");
    }
    take
}
pub fn observe(id: i64, data: &[u8], hardware: bool) {
    if !ENTRIES.lock().get(&id).is_some_and(|e| e.hardware==hardware && e.waiting.is_some() && !e.stop.is_cancelled()) { return; }
    if !crate::screen::annexb_nals(data)
        .iter()
        .any(|n| crate::screen::nal_type(n) == 5)
    {
        return;
    }
    let observed = {
        let mut entries = ENTRIES.lock();
        entries.get_mut(&id).is_some_and(|e| {
            !e.stop.is_cancelled() && e.hardware == hardware && e.waiting.take().is_some()
        })
    };
    if observed {
        crate::emit_log(
            "VIDEO: keyframe_recovery=source_idr_observed vehicleDisplayVerified=false",
        );
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn request_is_coalesced_generation_owned_and_cancelled() {
        let stop = SessionStop::new(9008);
        let _lease = register(stop.clone(), false);
        assert_eq!(request(9007), None);
        assert_eq!(request(9008), Some(false));
        assert_eq!(request(9008), None);
        assert!(take(9008));
        assert!(!take(9008));
        stop.cancel();
        assert_eq!(request(9008), None);
        assert!(!take(9008));
    }
}
