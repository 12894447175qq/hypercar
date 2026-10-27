//! 会话级停止状态、任务归属及退出屏障。停止是持续状态，迟到任务也必须退出。
use std::future::Future;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::Arc;

use tokio::sync::Notify;
use tokio::task::JoinHandle;

pub struct SessionStop {
    pub id: i64,
    cancelled: AtomicBool,
    changed: Notify,
    active: AtomicUsize,
    drained: Notify,
}

impl SessionStop {
    pub fn new(id: i64) -> Arc<Self> {
        Arc::new(Self {
            id,
            cancelled: AtomicBool::new(false),
            changed: Notify::new(),
            active: AtomicUsize::new(0),
            drained: Notify::new(),
        })
    }

    pub fn cancel(&self) {
        self.cancelled.store(true, Ordering::Release);
        self.changed.notify_waiters();
    }

    pub fn is_cancelled(&self) -> bool {
        self.cancelled.load(Ordering::Acquire)
    }

    pub fn is_idle(&self) -> bool {
        self.active.load(Ordering::Acquire) == 0
    }

    pub async fn notified(&self) {
        // 先创建通知 future，再读状态，覆盖取消发生在注册前/后的两种竞态。
        loop {
            let changed = self.changed.notified();
            if self.is_cancelled() {
                return;
            }
            changed.await;
        }
    }

    pub async fn wait_idle(&self) {
        loop {
            let drained = self.drained.notified();
            if self.is_idle() {
                return;
            }
            drained.await;
        }
    }
}

tokio::task_local! {
    static TASK_SESSION: Arc<SessionStop>;
}

pub fn current_id() -> i64 {
    TASK_SESSION.try_with(|session| session.id).unwrap_or(0)
}

pub fn callback_allowed() -> bool {
    TASK_SESSION.try_with(|session| !session.is_cancelled()).unwrap_or(true)
}

struct TaskRegistration(Arc<SessionStop>);

impl Drop for TaskRegistration {
    fn drop(&mut self) {
        if self.0.active.fetch_sub(1, Ordering::AcqRel) == 1 {
            self.0.drained.notify_waiters();
        }
    }
}

/// 外层取消分支覆盖内部正在等待的 read、重试 delay 和子协议处理器。
/// guard 在 spawn 前登记，任务尚未首次运行就被取消也不会漏计。
pub fn spawn_scoped<F>(session: Arc<SessionStop>, future: F) -> JoinHandle<()>
where
    F: Future<Output = ()> + Send + 'static,
{
    session.active.fetch_add(1, Ordering::AcqRel);
    let registration = TaskRegistration(session.clone());
    tokio::spawn(TASK_SESSION.scope(session.clone(), async move {
        let _registration = registration;
        tokio::select! {
            biased;
            _ = session.notified() => {}
            _ = future => {}
        }
    }))
}

pub struct CancelOnDrop(pub Arc<SessionStop>);

impl Drop for CancelOnDrop {
    fn drop(&mut self) {
        self.0.cancel();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;
    use tokio::io::AsyncReadExt;
    use tokio::net::{TcpListener, TcpStream};

    #[tokio::test]
    async fn cancellation_reaches_late_waiters_and_closes_blocked_socket() {
        let session = SessionStop::new(7);
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let client = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut server, _) = listener.accept().await.unwrap();
        let (started_tx, started_rx) = tokio::sync::oneshot::channel();
        spawn_scoped(session.clone(), async move {
            let mut client = client;
            started_tx.send(()).unwrap();
            let mut byte = [0];
            let _ = client.read(&mut byte).await;
        });
        started_rx.await.unwrap();
        session.cancel();
        tokio::time::timeout(Duration::from_secs(1), session.notified()).await.unwrap();
        tokio::time::timeout(Duration::from_secs(1), session.wait_idle()).await.unwrap();
        let mut byte = [0];
        assert_eq!(server.read(&mut byte).await.unwrap(), 0);
        let calls = Arc::new(AtomicUsize::new(0));
        for _ in 0..6 {
            let calls = calls.clone();
            spawn_scoped(session.clone(), async move { calls.fetch_add(1, Ordering::Relaxed); });
        }
        tokio::time::timeout(Duration::from_secs(1), session.wait_idle()).await.unwrap();
        assert_eq!(calls.load(Ordering::Relaxed), 0);
    }

    #[tokio::test]
    async fn task_context_is_retained_for_nested_workers() {
        let session = SessionStop::new(42);
        let (tx, rx) = tokio::sync::oneshot::channel();
        let child_session = session.clone();
        spawn_scoped(session.clone(), async move {
            spawn_scoped(child_session, async move { tx.send(current_id()).unwrap(); });
        });
        assert_eq!(rx.await.unwrap(), 42);
        session.wait_idle().await;
    }
}
