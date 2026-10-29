//! HyperCar 核心层 —— JNI 入口、Tokio 运行时、引擎生命周期。
//!
//! 与「参考文档」的架构差异（docs/NEGOTIATION-GAPS.md）：
//!  参考文档里 AirPlay 协商留在 Kotlin，Rust 只接「已连通 FD + 已派生密钥」；
//!  本项目把协商整体下沉到 Rust（/info → pair-verify → SETUP → RECORD → 心跳），
//!  Kotlin 只提供「绑定车机网络的 socket FD 供给器」——协商状态机只有一份实现。
//!
//! JNI 命名对应 io.github.hypercar.core.RustCoreBridge（下划线式导出名）。
//!
//! # dead-code lint 说明
//!
//! 本 crate 是 `crate-type = ["cdylib"]`，dead-code lint 只把「从 8 个 JNI 导出
//! 可达」的代码算作活跃。但协议实现刻意保留了完整覆盖面（如 pair-setup M1..M6
//! 完整链路，主路径只走 pair-verify）与一批诊断/统计访问器（`frames_sent`、
//! `packets_sent`、`resolution` 等，供后续 UI 或日志接线）。这些不是遗留垃圾，
//! 而是库的 API 面——故 crate 级 allow，避免用 `#[allow(dead_code)]` 逐个标注
//! 淹没真正的意图注释。新增代码若确属误删引用，仍应靠 code review 拦截。
#![allow(dead_code)]

mod airplay;
mod audio_out;
mod bplist;
mod carlife;
mod crypto;
mod engine;
mod iap2_nav;
mod pairing;
mod rtsp;
mod screen;
mod session;
mod media_start;
mod srp6a;
mod tlv8;
mod timing;
mod video_pipeline;
mod video_compat;
mod video_capture;
mod diagnostic_video;
mod video_transform;
mod video_recovery;

use std::os::unix::io::{FromRawFd, RawFd};
use std::sync::Arc;

use jni::objects::{GlobalRef, JByteArray, JClass, JObject, JShortArray, JString};
use jni::sys::{jboolean, jint, jlong, JNI_TRUE, JNI_FALSE};
use jni::JNIEnv;
use once_cell::sync::Lazy;
use parking_lot::RwLock;
use tokio::net::{TcpStream, UdpSocket};
use tokio::runtime::{Builder, Runtime};
use tokio::sync::mpsc;
use crate::session::{spawn_scoped, CancelOnDrop, SessionStop};

use crate::airplay::{SocketProvider, TouchInput};

static TOKIO_RT: Lazy<Runtime> = Lazy::new(|| {
    Builder::new_multi_thread()
        .worker_threads(4)
        .thread_name("hypercar-core")
        .enable_all()
        .build()
        .expect("tokio runtime 创建失败")
});

static JAVA_VM: RwLock<Option<jni::JavaVM>> = RwLock::new(None);
static LISTENER: RwLock<Option<GlobalRef>> = RwLock::new(None);

struct EngineHandle {
    stop: Arc<SessionStop>,
    touch_tx: mpsc::UnboundedSender<TouchInput>,
    key_tx: mpsc::UnboundedSender<u32>,
    /// 手机麦克风 PCM → CarLife VR 通道（语音助手用）
    mic_tx: mpsc::UnboundedSender<Vec<i16>>,
    iap_tx: mpsc::Sender<media_start::IapCommand>,
}

fn enqueue_iap(stop: &SessionStop, tx: &mpsc::Sender<media_start::IapCommand>, generation: i64, command: media_start::IapCommand) -> bool {
    stop.id == generation && !stop.is_cancelled() && tx.try_send(command).is_ok()
}

#[cfg(test)]
mod control_generation_tests {
    use super::*;
    #[test]
    fn old_or_cancelled_native_callbacks_cannot_release_new_session_audio() {
        let stop = SessionStop::new(7);
        let (tx, mut rx) = mpsc::channel(2);
        assert!(!enqueue_iap(&stop, &tx, 6, media_start::IapCommand::ControlReady));
        assert!(rx.try_recv().is_err());
        assert!(enqueue_iap(&stop, &tx, 7, media_start::IapCommand::ControlReady));
        assert!(matches!(rx.try_recv(), Ok(media_start::IapCommand::ControlReady)));
        stop.cancel();
        assert!(!enqueue_iap(&stop, &tx, 7, media_start::IapCommand::ControlReady));
        assert!(!enqueue_iap(&stop, &tx, 7, media_start::IapCommand::Packet(vec![1])));
        assert!(rx.try_recv().is_err());
    }
}

static ENGINE: Lazy<parking_lot::Mutex<Option<EngineHandle>>> =
    Lazy::new(|| parking_lot::Mutex::new(None));
static LIFECYCLE: parking_lot::Mutex<()> = parking_lot::Mutex::new(());
static RETIRING: Lazy<parking_lot::Mutex<Vec<Arc<SessionStop>>>> =
    Lazy::new(|| parking_lot::Mutex::new(Vec::new()));

// ---------------------------------------------------------------- 回调工具

/// Rust → Kotlin 日志（经 EventListener.onLog；同时走 logcat）。
pub(crate) fn emit_log(line: &str) {
    if !session::callback_allowed() { return; }
    emit_log_for(session::current_id(), line);
}

fn emit_log_for(id: i64, line: &str) {
    log::info!("{line}");
    call_listener("onLog", |env, obj| {
        let jline = env.new_string(line)?;
        env.call_method(obj, "onLog", "(JLjava/lang/String;)V", &[id.into(), (&jline).into()])
            .map(|_| ())
    });
}

pub(crate) fn emit_state(state: &str, detail: &str) {
    if !session::callback_allowed() { return; }
    emit_state_for(session::current_id(), state, detail);
}

fn emit_state_for(id: i64, state: &str, detail: &str) {
    log::info!("STATE {state}: {detail}");
    call_listener("onState", |env, obj| {
        let js = env.new_string(state)?;
        let jd = env.new_string(detail)?;
        env.call_method(
            obj,
            "onState",
            "(JLjava/lang/String;Ljava/lang/String;)V",
            &[id.into(), (&js).into(), (&jd).into()],
        )
        .map(|_| ())
    });
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn emit_stats(
    fps: i32,
    frames: i64,
    kbps: i64,
    audio_playing: bool,
    ducking: bool,
) {
    if !session::callback_allowed() { return; }
    let id = session::current_id();
    call_listener("onStats", |env, obj| {
        env.call_method(
            obj,
            "onStats",
            // Kotlin: onStats(fps:Int, frames:Long, kbps:Long, audioPlaying:Boolean, ducking:Boolean)
            // → I J J Z Z（5 个参数）。**必须严格 5 位**：旧值多写了一个 I（"(IJJIZZ)V"）
            // 导致 GetMethodID 直接失败、onStats 一次都没调到——首页帧率/码率/音频芯片全空
            // （2026-10-25 定谳）。
            "(JIJJZZ)V",
            &[
                id.into(),
                fps.into(),
                frames.into(),
                kbps.into(),
                jni::sys::jboolean::from(audio_playing).into(),
                jni::sys::jboolean::from(ducking).into(),
            ],
        )
        .map(|_| ())
    });
}

fn with_env<T>(
    f: impl FnOnce(&mut JNIEnv) -> jni::errors::Result<T>,
) -> Option<T> {
    let vm_guard = JAVA_VM.read();
    let vm = vm_guard.as_ref()?;
    // 已 attach 的线程（JNI 调用栈内 / 已挂载 worker）→ 直接用；
    // tokio worker 线程 → daemon 挂载（进程退出不阻塞）。
    // jni 0.21：attach_current_thread_as_daemon 返回 JNIEnv（非 AttachGuard），无需 unsafe。
    let (mut env, owned) = match vm.get_env() {
        Ok(env) => (env, false),
        Err(_) => (vm.attach_current_thread_as_daemon().ok()?, true),
    };
    let result = env.with_local_frame(32, |env| f(env));
    // **必须清理 Pending Java Exception**：JNI 调用失败（如方法签名不对 / 业务异常）后，
    // 异常会挂在这个线程上，下一次该线程的任何 JNI 调用（emit_log / openTcpFd …）都会
    // 立即失败甚至崩——必须 exception_clear 掉（2026-10-25 加固）。
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_describe();
        let _ = env.exception_clear();
    }
    // 本地引用只在本次 f 内使用；线程 attach 生命周期由 daemon 管理，无需 detach。
    let _ = owned;
    result.ok()
}

fn call_listener(method: &str, f: impl FnOnce(&mut JNIEnv, &JObject) -> jni::errors::Result<()>) {
    // Kotlin 回调可能重入 nativeStop；不能把原生锁一直持有到 Java 返回。
    let global = LISTENER.read().clone();
    let Some(global) = global else { return };
    // JObject 不实现 Clone；as_obj() 返回 &JObject，直接按引用传给 f。
    let obj = global.as_obj();
    let _ = with_env(|env| f(env, obj));
    let _ = method;
}

// ---------------------------------------------------------------- socket 供给器

pub(crate) fn emit_iap(bytes: &[u8]) {
    if !session::callback_allowed() || bytes.len() > 65_536 { return; }
    let id = session::current_id();
    call_listener("onIap", |env, obj| {
        let packet = env.byte_array_from_slice(bytes)?;
        env.call_method(obj, "onIap", "(J[B)V", &[id.into(), (&packet).into()]).map(|_| ())
    });
}

/// Kotlin SocketProvider（carNetwork 绑定的 socket 工厂）→ Rust trait 实现。
struct JniSocketProvider {
    vm: jni::JavaVM,
    obj: GlobalRef,
}

impl JniSocketProvider {
    fn call_fd(&self, method: &str, host: &str, port: u16, timeout_ms: u32) -> Option<RawFd> {
        if !session::callback_allowed() { return None; }
        let mut env = self.vm.attach_current_thread_as_daemon().ok()?;
        let jhost = env.new_string(host).ok()?;
        let fd: jint = env
            .call_method(
                self.obj.as_obj(),
                method,
                "(Ljava/lang/String;II)I",
                &[
                    (&jhost).into(),
                    (port as jint).into(),
                    (timeout_ms as jint).into(),
                ],
            )
            .ok()?
            .i()
            .ok()?;
        if fd < 0 {
            None
        } else {
            Some(fd as RawFd)
        }
    }
}

impl SocketProvider for JniSocketProvider {
    fn open_tcp(&self, host: &str, port: u16, timeout_ms: u32) -> Option<TcpStream> {
        let fd = self.call_fd("openTcpFd", host, port, timeout_ms)?;
        unsafe {
            let std_stream = std::net::TcpStream::from_raw_fd(fd);
            if !session::callback_allowed() { return None; }
            let _ = std_stream.set_nonblocking(true);
            let _ = std_stream.set_nodelay(true);
            TcpStream::from_std(std_stream).ok()
        }
    }

    fn open_udp(&self, host: &str, port: u16) -> Option<UdpSocket> {
        if !session::callback_allowed() { return None; }
        // 注意 Kotlin 侧 openUdpFd(host, port) 只有两个参数，签名为 (Ljava/lang/String;I)I
        let mut env = self.vm.attach_current_thread_as_daemon().ok()?;
        let jhost = env.new_string(host).ok()?;
        let fd: jint = env
            .call_method(
                self.obj.as_obj(),
                "openUdpFd",
                "(Ljava/lang/String;I)I",
                &[(&jhost).into(), (port as jint).into()],
            )
            .ok()?
            .i()
            .ok()?;
        if fd < 0 {
            return None;
        }
        unsafe {
            let std_udp = std::net::UdpSocket::from_raw_fd(fd as RawFd);
            if !session::callback_allowed() { return None; }
            let _ = std_udp.set_nonblocking(true);
            UdpSocket::from_std(std_udp).ok()
        }
    }
}

// ---------------------------------------------------------------- JNI 导出

#[no_mangle]
pub extern "system" fn Java_io_github_hypercar_core_RustCoreBridge_nativeInitLogger(
    env: JNIEnv,
    _class: JClass,
) {
    *JAVA_VM.write() = Some(env.get_java_vm().expect("JavaVM 获取失败"));
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag("HyperCar-Core"),
    );
    // 崩溃取证：默认 panic hook 写 stderr，Android 应用进程的 stderr 基本看不到。
    // 换成打到 logcat（tag HyperCar-Core）后，App 侧用 Shizuku 跑 `logcat -s HyperCar-Core`
    // 就能把「哪一行 panic 的」抓进导出日志（现场 2026-10-23 20:30 崩得无声无息）。
    // 这里只用 `log`（liblog），不碰 JNI——panic 钩子里调 JNI 有死锁风险。
    std::panic::set_hook(Box::new(|info| {
        log::error!("PANIC: {info}");
    }));
    log::info!("HyperCar Rust 核心层已初始化（协商下沉版）");
}

#[no_mangle]
pub extern "system" fn Java_io_github_hypercar_core_RustCoreBridge_nativeSetListener(
    env: JNIEnv,
    _class: JClass,
    listener: JObject,
) {
    let mut guard = LISTENER.write();
    *guard = if listener.is_null() {
        None
    } else {
        Some(env.new_global_ref(listener).expect("GlobalRef 创建失败"))
    };
}

/// 启动全链路网关：controlFd（已连车机 AirPlay 端口）+ socket 供给器 + 引擎配置 JSON。
#[no_mangle]
pub extern "system" fn Java_io_github_hypercar_core_RustCoreBridge_nativeStart(
    env: JNIEnv,
    _class: JClass,
    config_json: JString,
    control_fd: jint,
    socket_provider: JObject,
    generation: jlong,
) -> jboolean {
    let _lifecycle = LIFECYCLE.lock();
    // 任何 panic 都不得越过 JNI 边界：一旦越过（哪怕 panic=unwind）Rust 会 abort 整个进程，
    // 现场只剩「进程消失 + 内存日志全丢」。这里兜住，翻译成 JNI_FALSE + 一条日志。
    let outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        native_start_impl(env, config_json, control_fd, socket_provider, generation)
    }));
    match outcome {
        Ok(value) => value,
        Err(payload) => {
            let detail = payload
                .downcast_ref::<&str>()
                .map(|s| (*s).to_string())
                .or_else(|| payload.downcast_ref::<String>().cloned())
                .unwrap_or_else(|| "未知 panic".to_string());
            emit_log(&format!("nativeStart 内部 panic（已兜住，进程未退出）：{detail}"));
            emit_state("failed", "核心层启动时 panic（详见日志）");
            JNI_FALSE
        }
    }
}

fn native_start_impl(
    mut env: JNIEnv,
    config_json: JString,
    control_fd: jint,
    socket_provider: JObject,
    generation: jlong,
) -> jboolean {
    // JNI 调用一进入就接管 FD，配置失败或 panic 时也由 RAII 关闭，避免泄漏已连车机的连接。
    let owned_stream = if control_fd >= 0 {
        Some(unsafe { std::net::TcpStream::from_raw_fd(control_fd as RawFd) })
    } else { None };
    // 停旧引擎
    stop_engine();

    let config_str: String = match env.get_string(&config_json) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    let cfg: engine::EngineConfig = match serde_json::from_str(&config_str) {
        Ok(c) => c,
        Err(e) => {
            emit_log(&format!("引擎配置解析失败: {e}"));
            return JNI_FALSE;
        }
    };
    if (owned_stream.is_none() && !cfg.diagnostic_probe && cfg.diagnostic_replay.is_none()) || generation <= 0 {
        emit_log("controlFd 或 generation 非法");
        return JNI_FALSE;
    }
    emit_log("nativeStart: 配置已解析，开始接管控制 FD");

    // 控制 FD → 非阻塞 tokio TcpStream（所有权移交 Rust）
    //
    // **必须在 tokio runtime 上下文里做 `from_std`**：tokio 的文档明确写着
    // 「This function panics if thread-local runtime is not set」——而本函数是被 Kotlin
    // 的 IO 线程（Dispatchers.IO）直接调进来的，不是 tokio worker，没有线程局部 runtime。
    // 2026-10-23 20:44 的秒崩就死在这一行：日志停在「CORE: 调用 nativeStart」，之后
    // 一条 CORE 都没有（'there is no reactor running' panic → abort）。这里显式 enter()。
    let control_stream = if let Some(std_stream) = owned_stream {
        let std_stream = {
            let _ = std_stream.set_nonblocking(true);
            let _ = std_stream.set_nodelay(true);
            std_stream
        };
        let _enter = TOKIO_RT.enter();
        match TcpStream::from_std(std_stream) {
            Ok(s) => Some(s),
            Err(e) => {
                emit_log(&format!("控制 FD 转换失败: {e}"));
                return JNI_FALSE;
            }
        }
    } else { None };
    emit_log("nativeStart: socket 所有权检查完成");

    let vm = match env.get_java_vm() {
        Ok(vm) => vm,
        Err(_) => return JNI_FALSE,
    };
    let factory_obj = match env.new_global_ref(socket_provider) {
        Ok(g) => g,
        Err(_) => return JNI_FALSE,
    };
    let factory: Arc<dyn SocketProvider> = Arc::new(JniSocketProvider {
        vm,
        obj: factory_obj,
    });

    let (touch_tx, touch_rx) = mpsc::unbounded_channel::<TouchInput>();
    let (key_tx, key_rx) = mpsc::unbounded_channel::<u32>();
    // 手机麦克风 → CarLife VR（语音助手要靠车机喂麦克风）
    let (mic_tx, mic_rx) = mpsc::unbounded_channel::<Vec<i16>>();
    let (iap_tx, iap_rx) = mpsc::channel::<media_start::IapCommand>(64);
    let stop = SessionStop::new(generation);

    let stop_run = stop.clone();
    let touch_tx_engine = touch_tx.clone();
    let key_tx_engine = key_tx.clone();
    let retired = RETIRING.lock().clone();
    let engine_task = {
      let _runtime = TOKIO_RT.enter();
      spawn_scoped(stop.clone(), async move {
        let _cleanup = CancelOnDrop(stop_run.clone());
        emit_state(if cfg.diagnostic_probe || cfg.diagnostic_replay.is_some() {"diagnostic_starting"} else {"starting"}, "核心层引擎已启动");
        // 新会话接触 CarLife 之前，等待旧任务释放读写中的所有 socket。
        for previous in retired {
            if tokio::time::timeout(std::time::Duration::from_secs(15), previous.wait_idle()).await.is_err() {
                emit_state("failed", "上一次核心层任务尚未退出，本轮停止以避免重复连接来源");
                return;
            }
        }
        RETIRING.lock().retain(|previous| !previous.is_idle());
        if stop_run.is_cancelled() { return; }
        let result = if cfg.diagnostic_probe || cfg.diagnostic_replay.is_some() {
            diagnostic_video::run(cfg, stop_run).await
        } else { engine::run_engine(
            cfg,
            control_stream.expect("normal session control socket"),
            factory,
            touch_tx_engine,
            touch_rx,
            key_tx_engine,
            key_rx,
            // 手机麦克风（AudioRecord）经 nativeSendMic 注入
            Some(mic_rx),
            iap_rx,
            stop_run,
        )
        .await };
        if let Err(e) = &result {
            emit_log(&format!("引擎退出: {e}"));
            emit_state("failed", e);
        }
      })
    };
    // panic 隔离看门狗：Cargo 已去掉 panic = "abort"，任务内 panic 由 tokio 捕获在
    // JoinHandle 里。这里把它报成「会话失败」并留日志，而不是让进程无声消失。
    TOKIO_RT.spawn(async move {
        if let Err(join_error) = engine_task.await {
            if join_error.is_panic() {
                emit_log_for(generation, &format!("引擎任务异常退出: {join_error}"));
                emit_state_for(generation, "failed", "核心层内部异常（panic），请导出日志");
            }
        }
    });

    // 发送端留在句柄里供 nativeSendTouch/nativeSendHardKey 注入
    // （AirPlay 事件通道触摸与 JNI 触摸共用同一 mpsc，最终都进 CarLife 触摸通道）
    *ENGINE.lock() = Some(EngineHandle {
        stop,
        touch_tx,
        key_tx,
        mic_tx,
        iap_tx,
    });

    emit_log("nativeStart: 引擎任务已派生");
    JNI_TRUE
}

#[no_mangle]
pub extern "system" fn Java_io_github_hypercar_core_RustCoreBridge_nativeStop(
    _env: JNIEnv,
    _class: JClass,
) {
    let _lifecycle = LIFECYCLE.lock();
    stop_engine();
}

fn stop_engine() {
    if let Some(handle) = ENGINE.lock().take() {
        handle.stop.cancel();
        // 生命周期锁内只发非会话日志，避免与 Kotlin 回调的状态锁互相等待。
        emit_log_for(0, "核心层已收到停止请求；等待所有本轮任务退出");
        RETIRING.lock().push(handle.stop);
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_hypercar_core_RustCoreBridge_nativeSendIap(
    env: JNIEnv,
    _class: JClass,
    generation: jlong,
    packet: JByteArray,
) -> jboolean {
    let Ok(len) = env.get_array_length(&packet) else { return JNI_FALSE; };
    if len <= 0 || len > 65_536 { return JNI_FALSE; }
    let Ok(bytes) = env.convert_byte_array(packet) else { return JNI_FALSE; };
    let engine = ENGINE.lock();
    let Some(handle) = engine.as_ref() else { return JNI_FALSE; };
    if enqueue_iap(&handle.stop, &handle.iap_tx, generation, media_start::IapCommand::Packet(bytes)) { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_io_github_hypercar_core_RustCoreBridge_nativeControlReady(
    _env: JNIEnv, _class: JClass, generation: jlong,
) -> jboolean {
    let engine = ENGINE.lock();
    let Some(handle) = engine.as_ref() else { return JNI_FALSE; };
    if enqueue_iap(&handle.stop, &handle.iap_tx, generation, media_start::IapCommand::ControlReady) { JNI_TRUE } else { JNI_FALSE }
}

/// 手机麦克风 PCM（16kHz 单声道 16-bit）→ CarLife VR 通道。
///
/// CarLife 的语音助手要求「车机」提供麦克风数据（VR 通道 `VR_MIC_DATA 0x00058001`）；
/// 本机 HU 就用手机自己的 `AudioRecord` 采，经这里注入，再由 `carlife::inject_mic`
/// 封帧推给 MD。Android 侧需要在 manifest 申请 RECORD_AUDIO 并运行时授权。
#[no_mangle]
pub extern "system" fn Java_io_github_hypercar_core_RustCoreBridge_nativeSendMic(
    env: JNIEnv,
    _class: JClass,
    pcm: JShortArray,
) {
    let Ok(len) = env.get_array_length(&pcm) else {
        return;
    };
    if len <= 0 {
        return;
    }
    let mut buf = vec![0i16; len as usize];
    if env.get_short_array_region(&pcm, 0, &mut buf).is_err() {
        return;
    }
    if let Some(handle) = ENGINE.lock().as_ref() {
        let _ = handle.mic_tx.send(buf);
    }
}

/// AirPlay 事件通道触摸 → CarLife（坐标已换算到 CarLife 编码空间）。
#[no_mangle]
pub extern "system" fn Java_io_github_hypercar_core_RustCoreBridge_nativeSendTouch(
    _env: JNIEnv,
    _class: JClass,
    action: jint,
    x: jint,
    y: jint,
) {
    if let Some(handle) = ENGINE.lock().as_ref() {
        let _ = handle.touch_tx.send(TouchInput {
            action: action as u32,
            x: x.max(0) as u32,
            y: y.max(0) as u32,
        });
    }
}

/// iDrive 旋钮/方向盘媒体键 → CarLife 硬按键 0x68008。
#[no_mangle]
pub extern "system" fn Java_io_github_hypercar_core_RustCoreBridge_nativeSendHardKey(
    _env: JNIEnv,
    _class: JClass,
    carlife_key_code: jint,
) {
    if let Some(handle) = ENGINE.lock().as_ref() {
        let _ = handle.key_tx.send(carlife_key_code as u32);
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_hypercar_core_RustCoreBridge_nativeIsRunning(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    if ENGINE.lock().as_ref().is_some_and(|handle| !handle.stop.is_cancelled()) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// 构造宝马 HUD 导航报文（iAP2 0x5200）；返回 jbyteArray（Rust 分配、JNI 拷贝）。
#[no_mangle]
pub extern "system" fn Java_io_github_hypercar_core_RustCoreBridge_nativeBuildIap2NavPacket<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    maneuver_type: jint,
    current_street: JString<'local>,
    next_street: JString<'local>,
    dist_to_maneuver_m: jint,
    remain_time_s: jint,
    remain_dist_m: jint,
) -> jni::sys::jbyteArray {
    let cur: String = env
        .get_string(&current_street)
        .map(|s| s.into())
        .unwrap_or_default();
    let nxt: String = env
        .get_string(&next_street)
        .map(|s| s.into())
        .unwrap_or_default();
    let packet = iap2_nav::build_route_guidance_packet(
        maneuver_type as u8,
        &cur,
        &nxt,
        dist_to_maneuver_m.max(0) as u32,
        remain_time_s.max(0) as u32,
        remain_dist_m.max(0) as u32,
    );
    env.byte_array_from_slice(&packet)
        .expect("jbyteArray 创建失败")
        .into_raw()
}




#[no_mangle]
pub extern "system" fn Java_io_github_hypercar_core_RustCoreBridge_nativeIsIdle(_env: JNIEnv, _class: JClass) -> jboolean {
    let _lifecycle=LIFECYCLE.lock();
    let current=ENGINE.lock().as_ref().is_none_or(|h|h.stop.is_idle());
    if current && RETIRING.lock().iter().all(|s|s.is_idle()) {JNI_TRUE} else {JNI_FALSE}
}

pub(crate) fn emit_transcode(sw:u32,sh:u32,tw:u32,th:u32,pts:u64,data:&[u8])->bool {
    if !session::callback_allowed() || data.len()>video_capture::MAX_PACKET {return false;}
    let Some(global)=LISTENER.read().clone() else {return false};
    with_env(|env| {
        let bytes=env.byte_array_from_slice(data)?;
        env.call_method(global.as_obj(),"onTranscodeInput","(JIIIIJ[B)Z",&[
            session::current_id().into(),(sw as jint).into(),(sh as jint).into(),(tw as jint).into(),(th as jint).into(),(pts as jlong).into(),(&bytes).into()])?.z()
    }).unwrap_or(false)
}
#[no_mangle]
pub extern "system" fn Java_io_github_hypercar_core_RustCoreBridge_nativeSubmitTranscodedVideo(
    env:JNIEnv,_class:JClass,id:jlong,width:jint,height:jint,pts:jlong,data:JByteArray)->jboolean {
    if env.get_array_length(&data).unwrap_or(0)>video_capture::MAX_PACKET as i32 {return JNI_FALSE;}
    let Ok(data)=env.convert_byte_array(data) else {return JNI_FALSE};
    if video_transform::submit(id,video_transform::Frame{width:width as u32,height:height as u32,pts:pts as u64,data}) {JNI_TRUE} else {JNI_FALSE}
}
