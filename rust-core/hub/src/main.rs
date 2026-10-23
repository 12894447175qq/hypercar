//! PC 侧联调 HU（Host Under-test / 车机侧）。
//!
//! 拓扑（与 Android 版分工不同，只为联调）：
//!
//! ```text
//! 手机 CarWith（车辆热点模式）
//!    │  CarLife over Wi-Fi
//!    ▼
//! 本程序：CarLife HU（监听 0.0.0.0:7240/8240/…）  ← 复用 rust-core 的协议实现
//!    │  AirPlay（本机回环）
//!    ▼
//! WinPlay：AirPlay 接收端 127.0.0.1:17000
//! ```
//!
//! 为什么要它：手机上的 CarLife/CarWith 只会连「车机的 IP」，而 Android 版 HU 监听的是
//! 手机自己的 127.0.0.1——同一台设备上的 MD 永远连不过来（现场 2026-10-23 定位）。
//! 把 HU 搬到 PC（= WinPlay 那台，也就是热点提供者 192.168.137.1）后，手机就连得上，
//! 整条 CarLife→AirPlay 数据面可以独立验证，不需要 iAP2 / 蓝牙 / 回环。
//!
//! 用法：
//! ```text
//! cargo run --release --bin hypercar-hub -- \
//!     --airplay 127.0.0.1:17000 \
//!     --device-id 84:AE:DE:56:D3:76 \
//!     --width 1920 --height 720 --fps 60
//! ```
//! `--device-id` 要填 **WinPlay「目标蓝牙设备」里那台手机的蓝牙 MAC**——WinPlay 的
//! `deviceFilter` 会拿 AirPlay 会话 SETUP 里的 deviceID 逐字符比对，不一致直接 403。
//!
//! 协议源码用 `#[path]` 直接引入 `rust-core/src/*.rs`（这些模块不含 JNI/Android 依赖），
//! 因此 Android 侧的 libhypercar_core.so 完全不受影响。

// ---- 复用 rust-core 的协议实现（不含 JNI 层）----
#[path = "../../src/airplay.rs"]
mod airplay;
#[path = "../../src/audio_out.rs"]
mod audio_out;
#[path = "../../src/bplist.rs"]
mod bplist;
#[path = "../../src/carlife.rs"]
mod carlife;
#[path = "../../src/crypto.rs"]
mod crypto;
#[path = "../../src/engine.rs"]
mod engine;
#[path = "../../src/iap2_nav.rs"]
mod iap2_nav;
#[path = "../../src/pairing.rs"]
mod pairing;
#[path = "../../src/rtsp.rs"]
mod rtsp;
#[path = "../../src/screen.rs"]
mod screen;
#[path = "../../src/srp6a.rs"]
mod srp6a;
#[path = "../../src/timing.rs"]
mod timing;
#[path = "../../src/tlv8.rs"]
mod tlv8;

use std::net::ToSocketAddrs;
use std::sync::Arc;
use std::time::Duration;

use tokio::net::{TcpStream, UdpSocket};
use tokio::sync::{mpsc, Notify};

use airplay::{SocketProvider, TouchInput};

// ---- lib.rs 里的 emit_* 在这里换成 stdout（Android 版走 JNI 回调）----

pub(crate) fn emit_log(line: &str) {
    println!("[log]   {line}");
}

pub(crate) fn emit_state(state: &str, detail: &str) {
    println!("[state] {state}: {detail}");
}

pub(crate) fn emit_stats(fps: i32, frames: i64, kbps: i64, audio_playing: bool, ducking: bool) {
    println!("[stats] fps={fps} frames={frames} kbps={kbps} audio={audio_playing} duck={ducking}");
}

/// 直连 socket 供给器：PC 上不需要「绑定车机网络」，普通 connect 即可。
struct PlainSockets;

impl SocketProvider for PlainSockets {
    fn open_tcp(&self, host: &str, port: u16, timeout_ms: u32) -> Option<TcpStream> {
        let addr = (host, port).to_socket_addrs().ok()?.next()?;
        let stream =
            std::net::TcpStream::connect_timeout(&addr, Duration::from_millis(timeout_ms.max(1_000) as u64))
                .ok()?;
        stream.set_nonblocking(true).ok()?;
        stream.set_nodelay(true).ok()?;
        // 调用方都在 tokio 任务里，from_std 需要 runtime 上下文——满足。
        TcpStream::from_std(stream).ok()
    }

    fn open_udp(&self, host: &str, port: u16) -> Option<UdpSocket> {
        let socket = std::net::UdpSocket::bind("0.0.0.0:0").ok()?;
        socket.connect((host, port)).ok()?;
        socket.set_nonblocking(true).ok()?;
        UdpSocket::from_std(socket).ok()
    }
}

struct Args {
    airplay: String,
    device_id: String,
    width: u32,
    height: u32,
    fps: u32,
    bind: String,
    identity: String,
    audio: bool,
    /// 非空 = 主动连手机的 CarLife 端口（小米 CarLife 的「车辆热点」方向）
    md_host: String,
    /// 触摸显示空间 "WxH"（接收端 /info 报 0x0 时用，例 2732x794）
    touch_display: String,
    /// 特性配置里的 voice：0=车机不提供语音（用手机自己的麦克风），1=HU 推麦克风
    voice: u32,
}

impl Args {
    fn parse() -> Result<Self, String> {
        let mut args = Args {
            airplay: "127.0.0.1:17000".to_string(),
            device_id: String::new(),
            width: 2560,
            height: 744,
            fps: 60,
            bind: "0.0.0.0".to_string(),
            identity: "controller_identity.txt".to_string(),
            audio: true,
            md_host: String::new(),
            touch_display: String::new(),
            voice: 0,
        };
        let mut it = std::env::args().skip(1);
        while let Some(flag) = it.next() {
            let mut value = || it.next().ok_or_else(|| format!("{flag} 缺少参数"));
            match flag.as_str() {
                "--airplay" => args.airplay = value()?,
                "--device-id" => args.device_id = value()?,
                "--width" => args.width = value()?.parse().map_err(|_| "--width 不是数字")?,
                "--height" => args.height = value()?.parse().map_err(|_| "--height 不是数字")?,
                "--fps" => args.fps = value()?.parse().map_err(|_| "--fps 不是数字")?,
                "--bind" => args.bind = value()?,
                "--identity" => args.identity = value()?,
                "--md-host" => args.md_host = value()?,
                "--touch-display" => args.touch_display = value()?,
                "--voice" => args.voice = value()?.parse().unwrap_or(0),
                "--no-audio" => args.audio = false,
                "-h" | "--help" => return Err(usage()),
                other => return Err(format!("未知参数 {other}\n{}", usage())),
            }
        }
        if args.device_id.is_empty() {
            return Err(format!("必须给 --device-id（WinPlay 目标蓝牙设备的 MAC）\n{}", usage()));
        }
        Ok(args)
    }
}

fn usage() -> String {
    "用法: hypercar-hub --device-id <手机蓝牙MAC> [--airplay 127.0.0.1:17000] \
     [--width 1920] [--height 720] [--fps 60] [--bind 0.0.0.0] [--identity <path>] [--no-audio] \
     [--md-host <手机IP>] [--touch-display 2732x794]"
        .to_string()
}

fn main() {
    // 与 Android 版一致的取证习惯：panic 打到 stdout（PC 上没有 logcat）。
    std::panic::set_hook(Box::new(|info| {
        println!("[PANIC] {info}");
    }));

    // 库里的 log::error!/warn! 默认没人接——例如事件通道解密失败只走 log::error!，
    // 联调时这类信息最关键，必须落到 stdout，否则表现就是"对方明明发了，我们什么都没收到"。
    struct StdoutLogger;
    impl log::Log for StdoutLogger {
        fn enabled(&self, _: &log::Metadata) -> bool {
            true
        }
        fn log(&self, record: &log::Record) {
            println!("[{}] {}", record.level(), record.args());
        }
        fn flush(&self) {}
    }
    let _ = log::set_logger(&StdoutLogger);
    log::set_max_level(log::LevelFilter::Info);

    let args = match Args::parse() {
        Ok(a) => a,
        Err(e) => {
            eprintln!("{e}");
            std::process::exit(2);
        }
    };

    // CarLife HU 的绑定地址：Android 版固定 127.0.0.1（同机回环），PC 侧要 0.0.0.0
    // 才能在 WinPlay 热点（192.168.137.x）里被手机连到。
    std::env::set_var("CARLIFE_BIND", &args.bind);

    let runtime = match tokio::runtime::Builder::new_multi_thread()
        .worker_threads(4)
        .enable_all()
        .build()
    {
        Ok(rt) => rt,
        Err(e) => {
            eprintln!("tokio runtime 创建失败: {e}");
            std::process::exit(1);
        }
    };

    if let Err(e) = runtime.block_on(run(args)) {
        eprintln!("[fatal] {e}");
        std::process::exit(1);
    }
}

async fn run(args: Args) -> Result<(), String> {
    println!("== HyperCar PC 侧 HU ==");
    println!("  CarLife HU 监听 : {}:7240/8240/9240/9241/9242/9340", args.bind);
    println!("  AirPlay 接收端  : {}", args.airplay);
    println!("  deviceID        : {}", args.device_id);
    println!("  编码            : {}x{}@{}", args.width, args.height, args.fps);
    println!();

    // 1) 先连 AirPlay 控制端口（对端要已在「接收」状态，否则这里就失败）
    let addr = args
        .airplay
        .to_socket_addrs()
        .map_err(|e| format!("解析 {} 失败: {e}", args.airplay))?
        .next()
        .ok_or_else(|| format!("解析 {} 无结果", args.airplay))?;
    let std_stream = std::net::TcpStream::connect_timeout(&addr, Duration::from_secs(5))
        .map_err(|e| format!("连 AirPlay {} 失败: {e}（WinPlay 是否已点「开始接收」？）", args.airplay))?;
    std_stream.set_nonblocking(true).map_err(|e| e.to_string())?;
    std_stream.set_nodelay(true).map_err(|e| e.to_string())?;
    let control = TcpStream::from_std(std_stream).map_err(|e| format!("控制 socket 注册失败: {e}"))?;

    let host_is_ipv6 = addr.is_ipv6();
    let car_ip = addr.ip().to_string();
    let _ = host_is_ipv6;

    // 2) 通道：触摸/按键注入 + 停止信号
    let (touch_tx, touch_rx) = mpsc::unbounded_channel::<TouchInput>();
    let (key_tx, key_rx) = mpsc::unbounded_channel::<u32>();

    let stop = Arc::new(Notify::new());

    // Ctrl+C → 通知引擎收摊
    {
        let stop = stop.clone();
        tokio::spawn(async move {
            if tokio::signal::ctrl_c().await.is_ok() {
                println!("[main] 收到 Ctrl+C，正在停止…");
                stop.notify_waiters();
                stop.notify_one();
            }
        });
    }

    let (touch_display_w, touch_display_h) = match args.touch_display.split_once('x') {
        Some((w, h)) => (w.parse().unwrap_or(0), h.parse().unwrap_or(0)),
        None => (0u32, 0u32),
    };

    let cfg = engine::EngineConfig {
        device_id: args.device_id.clone(),
        car_ip,
        airplay_port: addr.port(),
        width: args.width,
        height: args.height,
        fps: args.fps,
        enable_audio: args.audio,
        keep_alive_stats: true,
        identity_path: args.identity.clone(),
        md_host: if args.md_host.is_empty() { None } else { Some(args.md_host.clone()) },
        touch_display_w,
        touch_display_h,
        voice: args.voice,
    };

    // 3) 交给引擎：它内部会起 CarLife HU，再做 AirPlay 协商与双泵
    let result = engine::run_engine(
        cfg,
        control,
        Arc::new(PlainSockets),
        touch_tx,
        touch_rx,
        key_tx,
        key_rx,
        // PC 侧不提供麦克风：语音助手用手机自己的麦克风（见 --voice）
        None,
        stop,
    )
    .await;

    match result {
        Ok(()) => {
            println!("[main] 引擎正常退出");
            Ok(())
        }
        Err(e) => Err(format!("引擎退出: {e}")),
    }
}







