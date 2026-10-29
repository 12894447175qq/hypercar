//! Per-session media milestones. Optional MIB3 audio must not race control setup or video.
use std::sync::Arc;
use tokio::sync::watch;

pub enum IapCommand {
    Packet(Vec<u8>),
    ControlReady,
}

#[derive(Clone, Copy, Debug, Default)]
pub struct Readiness {
    pub control: bool,
    pub source: bool,
}

#[derive(Debug)]
pub struct MediaReadiness(watch::Sender<Readiness>);

impl MediaReadiness {
    pub fn new() -> Arc<Self> { Arc::new(Self(watch::channel(Readiness::default()).0)) }
    pub fn control_ready(&self) { self.0.send_modify(|s| s.control = true); }
    pub fn source_written(&self) { self.0.send_modify(|s| s.source = true); }
    pub fn snapshot(&self) -> Readiness { *self.0.borrow() }
    pub async fn ready(&self) {
        let mut state = self.0.subscribe();
        loop {
            let current = *state.borrow_and_update();
            if current.control && current.source { return; }
            if state.changed().await.is_err() { return; }
        }
    }
}
