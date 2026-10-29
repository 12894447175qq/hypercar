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
// GUI 模式下彻底不创建控制台窗口：把子系统设成 windows。
// 代价是从 cmd 直接跑 CLI 时看不到 stdout —— 但 CLI 主要作为 GUI 的子进程运行
// （stdout 走管道，不受影响）；真要命令行调试就重定向到文件即可。
#![cfg_attr(windows, windows_subsystem = "windows")]


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
#[path = "../../src/session.rs"]
mod session;
#[path = "../../src/media_start.rs"]
mod media_start;
#[path = "../../src/srp6a.rs"]
mod srp6a;
#[path = "../../src/timing.rs"]
mod timing;
#[path = "../../src/video_pipeline.rs"]
mod video_pipeline;
#[path = "../../src/tlv8.rs"]
mod tlv8;

use std::net::ToSocketAddrs;
use std::time::Duration;

use tokio::net::{TcpStream, UdpSocket};
use tokio::sync::mpsc;

use airplay::{SocketProvider, TouchInput};

// ---- lib.rs 里的 emit_* 在这里换成 stdout（Android 版走 JNI 回调）----

pub(crate) fn emit_log(line: &str) {
    if !session::callback_allowed() { return; }
    println!("[log]   {line}");
}

pub(crate) fn emit_iap(bytes: &[u8]) {
    emit_log(&format!("IAPLINK: PC harness received {} bytes; virtual control is implemented by the Android client", bytes.len()));
}

pub(crate) fn emit_state(state: &str, detail: &str) {
    if !session::callback_allowed() { return; }
    println!("[state] {state}: {detail}");
}

pub(crate) fn emit_stats(fps: i32, frames: i64, kbps: i64, audio_playing: bool, ducking: bool) {
    if !session::callback_allowed() { return; }
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
    /// 只做参数自动检测并打印，不启动（GUI 的「自动检测」用）
    detect_only: bool,
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
            // 空 = 稍后解析到 %LOCALAPPDATA%\hypercar-hub\controller_identity.txt（绝不写进仓库）
            identity: String::new(),
            audio: true,
            md_host: String::new(),
            touch_display: String::new(),
            voice: 0,
            detect_only: false,
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
                "--detect-only" => args.detect_only = true,
                // GUI 的「启动」用 `--no-gui` 显式走命令行；`--gui` 反之。两者都不算未知参数。
                "--no-gui" | "--gui" => {}
                "-h" | "--help" => return Err(usage()),
                other => return Err(format!("未知参数 {other}\n{}", usage())),
            }
        }
        // ---- 自动补全 device-id / md-host（显式参数 > 上次缓存 > adb 现场读）----
        let (cached_mac, cached_host) = load_cache();
        if args.identity.is_empty() {
            let path = cache_dir().join("controller_identity.txt");
            let _ = std::fs::create_dir_all(cache_dir());
            args.identity = path.to_string_lossy().to_string();
            println!("[auto] identity 用用户目录：{}", args.identity);
        }

        if args.device_id.is_empty() {
            match detect_device_id() {
                Some(mac) => {
                    println!("[auto] device-id 由 adb 读取：{mac}");
                    args.device_id = mac;
                }
                None => match cached_mac {
                    Some(mac) => {
                        println!("[auto] device-id 用上次缓存：{mac}（adb 未取到）");
                        args.device_id = mac;
                    }
                    None => {
                        return Err(format!(
                            "device-id 无法自动获取：adb 不可用且没有缓存。\n\
                             装好 platform-tools 并连上手机，或显式传 --device-id <MAC>\n{}",
                            usage()
                        ));
                    }
                },
            }
        }
        if args.md_host.is_empty() {
            match detect_md_host() {
                Some(host) => {
                    println!("[auto] md-host 由 adb 读取：{host}");
                    args.md_host = host;
                }
                None => {
                    if let Some(host) = cached_host {
                        println!("[auto] md-host 用上次缓存：{host}（adb 未取到）");
                        args.md_host = host;
                    }
                }
            }
        }
        if args.detect_only {
            println!("device-id={}", args.device_id);
            println!("md-host={}", args.md_host);
            std::process::exit(0);
        }
        // 本次解析结果写回缓存，下次 adb 不在也能跑
        save_cache(&args.device_id, &args.md_host);
        Ok(args)
    }
}


// ---------------------------------------------------------------- 自动获取身份/IP

/// 依次尝试把 `adb` 跑起来（PATH → 常见安装位置）。
///
/// 联调时每次手抄手机蓝牙 MAC / IP 很烦，而且换手机、重装 App、换热点都会变，
/// 抄错一次就是「WinPlay 403」或「连不上 MD」，排查成本极高。这里优先问 adb
/// （同机已经装了 platform-tools），拿不到再回落到缓存/手工参数。
fn adb_path() -> Option<String> {
    let mut candidates: Vec<String> = vec!["adb".to_string()];
    if let Ok(local) = std::env::var("LOCALAPPDATA") {
        candidates.push(format!(r"{local}\Android\Sdk\platform-tools\adb.exe"));
    }
    candidates.push(r"D:\devtools\android-sdk\platform-tools\adb.exe".to_string());
    candidates.push(r"C:\platform-tools\adb.exe".to_string());
    for candidate in candidates {
        let ok = std::process::Command::new(&candidate)
            .arg("version")
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .status()
            .map(|s| s.success())
            .unwrap_or(false);
        if ok {
            return Some(candidate);
        }
    }
    None
}

/// 跑一条 adb shell 命令并返回输出（失败返回 None）。
fn adb_shell(adb: &str, cmd: &str) -> Option<String> {
    let out = std::process::Command::new(adb).args(["shell", cmd]).output().ok()?;
    if !out.status.success() {
        return None;
    }
    let text = String::from_utf8_lossy(&out.stdout).trim().to_string();
    if text.is_empty() || text.contains("Exception") || text.contains("Error") {
        None
    } else {
        Some(text)
    }
}

/// 手机蓝牙 MAC（`settings get secure bluetooth_address`）。
fn detect_device_id() -> Option<String> {
    let adb = adb_path()?;
    let raw = adb_shell(&adb, "settings get secure bluetooth_address")?;
    let mac = raw.trim();
    // 只接受 AA:BB:CC:DD:EE:FF 形状（Android 13+ 有的机型会返回 null 或打码）
    let looks_mac = mac.len() == 17
        && mac.chars().filter(|c| *c == ':').count() == 5
        && mac.chars().all(|c| c.is_ascii_hexdigit() || c == ':');
    if looks_mac {
        Some(mac.to_ascii_uppercase())
    } else {
        None
    }
}

/// 手机当前 Wi-Fi/热点的 IPv4（`ip -4 -o addr show`）。
///
/// 小米 CarWith 的「车辆热点」模式下 MD 监听在手机侧，HU 必须知道手机 IP 才能拨过去；
/// 换网/换热点后这个值也会变，所以同样自动取。
fn detect_md_host() -> Option<String> {
    let adb = adb_path()?;
    let raw = adb_shell(&adb, "ip -4 -o addr show")?;
    // 优先 wlan / ap / softap 接口上的地址
    let preferred = ["wlan", "ap", "softap", "swlan"];
    let mut fallback: Option<String> = None;
    for line in raw.lines() {
        let Some(inet_pos) = line.find("inet ") else { continue };
        let rest = &line[inet_pos + 5..];
        let Some(ip) = rest.split('/').next() else { continue };
        let ip = ip.trim();
        if !ip.contains('.') || ip.starts_with("127.") {
            continue;
        }
        let iface = line.split(':').nth(1).unwrap_or("").trim();
        if preferred.iter().any(|p| iface.starts_with(p)) {
            return Some(ip.to_string());
        }
        fallback.get_or_insert_with(|| ip.to_string());
    }
    fallback
}

/// 记住上次成功用过的身份/IP：adb 不可用时仍能跑起来。
fn cache_path() -> std::path::PathBuf {
    let base = std::env::var("LOCALAPPDATA").unwrap_or_else(|_| ".".to_string());
    std::path::PathBuf::from(base).join("hypercar-hub").join("identity-cache.txt")
}

fn load_cache() -> (Option<String>, Option<String>) {
    let Ok(text) = std::fs::read_to_string(cache_path()) else {
        return (None, None);
    };
    let mut mac = None;
    let mut host = None;
    for line in text.lines() {
        if let Some(v) = line.strip_prefix("device-id=") {
            mac = Some(v.trim().to_string());
        } else if let Some(v) = line.strip_prefix("md-host=") {
            host = Some(v.trim().to_string());
        }
    }
    (mac, host)
}

fn save_cache(mac: &str, host: &str) {
    let path = cache_path();
    if let Some(dir) = path.parent() {
        let _ = std::fs::create_dir_all(dir);
    }
    let body = format!("device-id={mac}\nmd-host={host}\n");
    let _ = std::fs::write(path, body);
}
fn usage() -> String {

    "用法: hypercar-hub [--device-id <手机蓝牙MAC>] [--airplay 127.0.0.1:17000] \
     [--width 2560] [--height 744] [--fps 60] [--bind 0.0.0.0] [--identity <path>] [--no-audio] \
     [--md-host <手机IP>] [--touch-display 2732x794] [--voice 0|1]\n\
     device-id / md-host 可省略：会用上次缓存，或经 adb 现场读取（手机需连着 adb）"
        .to_string()
}




// ---------------------------------------------------------------- 原生 Win32 参数面板
//
// 双击 hypercar-hub.exe（无参数）打开这个窗口；带参数启动时仍走命令行，
// 窗口里的「启动」按钮就是再拉一个自己（`--no-gui` + 参数）当子进程，
// 把它的 stdout 实时泵进下面的日志框。

use native_windows_gui as nwg;
use std::sync::Arc;
use std::cell::RefCell;
use std::io::{BufRead, BufReader};
use std::rc::Rc;


/// Win32：GUI 模式下把附带出现的控制台窗口藏掉（不改 subsystem，CLI 仍能正常输出）。
fn hide_console() {
    #[cfg(windows)]
    {
        extern "system" {
            fn GetConsoleWindow() -> *mut core::ffi::c_void;
            fn ShowWindow(hwnd: *mut core::ffi::c_void, cmd: i32) -> i32;
        }
        unsafe {
            let hwnd = GetConsoleWindow();
            if !hwnd.is_null() {
                ShowWindow(hwnd, 0); // SW_HIDE
            }
        }
    }
}

/// 面板配置目录（与 headless 的身份缓存同目录）。
fn cache_dir() -> std::path::PathBuf {
    let base = std::env::var("LOCALAPPDATA").unwrap_or_else(|_| ".".to_string());
    std::path::PathBuf::from(base).join("hypercar-hub")
}

fn gui_cfg_path() -> std::path::PathBuf {
    cache_dir().join("gui.ini")
}

/// 面板里的一份配置（读/写 `gui.ini`，键名与旧的 PowerShell 面板一致，可直接沿用）。
#[derive(Default)]
struct GuiCfg {
    device_id: String,
    md_host: String,
    airplay: String,
    width: String,
    height: String,
    touch: String,
    voice: String,
    audio: bool,
}

impl GuiCfg {
    fn load() -> Self {
        let mut cfg = Self {
            airplay: "127.0.0.1:17000".to_string(),
            width: "2560".to_string(),
            height: "744".to_string(),
            voice: "0".to_string(),
            audio: true,
            ..Default::default()
        };
        let Ok(text) = std::fs::read_to_string(gui_cfg_path()) else {
            return cfg;
        };
        for line in text.lines() {
            let Some((key, value)) = line.split_once('=') else { continue };
            let value = value.trim().to_string();
            match key.trim() {
                "device-id" => cfg.device_id = value,
                "md-host" => cfg.md_host = value,
                "airplay" if !value.is_empty() => cfg.airplay = value,
                "size" => {
                    let mut it = value.split(['x', 'X', '*', '×']).map(|s| s.trim().to_string());
                    cfg.width = it.next().unwrap_or_else(|| "2560".to_string());
                    cfg.height = it.next().unwrap_or_else(|| "744".to_string());
                }
                "touch-display" => cfg.touch = value,
                "voice" if !value.is_empty() => cfg.voice = value,
                "audio" => cfg.audio = value != "0",
                _ => {}
            }
        }
        cfg
    }

    fn save(&self) {
        let _ = std::fs::create_dir_all(cache_dir());
        let body = format!(
            "device-id={}\nmd-host={}\nairplay={}\nsize={}x{}\ntouch-display={}\nvoice={}\naudio={}\n",
            self.device_id,
            self.md_host,
            self.airplay,
            self.width,
            self.height,
            self.touch,
            self.voice,
            if self.audio { "1" } else { "0" },
        );
        let _ = std::fs::write(gui_cfg_path(), body);
    }
}

/// 窗口与控件。放在 `Rc<RefCell<_>>` 里供事件闭包使用（nwg 控件不可 Clone）。
struct Gui {
    device: nwg::TextInput,
    md_host: nwg::TextInput,
    airplay: nwg::TextInput,
    width: nwg::TextInput,
    height: nwg::TextInput,
    touch: nwg::TextInput,
    voice: nwg::TextInput,
    audio: nwg::CheckBox,
    log: nwg::TextBox,
    btn_detect: nwg::Button,
    btn_save: nwg::Button,
    btn_start: nwg::Button,
    btn_stop: nwg::Button,
    notice: nwg::Notice,
    /// 后台线程攒的日志行；UI 线程在收到 notice 时搬走
    queue: Arc<parking_lot::Mutex<Vec<String>>>,
    child: Option<std::process::Child>,
}

impl Gui {
    fn cfg(&self) -> GuiCfg {
        GuiCfg {
            device_id: self.device.text(),
            md_host: self.md_host.text(),
            airplay: self.airplay.text(),
            width: self.width.text(),
            height: self.height.text(),
            touch: self.touch.text(),
            voice: self.voice.text(),
            audio: self.audio.check_state() == nwg::CheckBoxState::Checked,
        }
    }

    fn append(&self, line: &str) {
        let current = self.log.text();
        let joined = if current.is_empty() {
            line.to_string()
        } else {
            format!("{current}\r\n{line}")
        };
        // 只保留最后 400 行，避免文本框无限增长
        let lines: Vec<&str> = joined.lines().collect();
        let start = lines.len().saturating_sub(400);
        let text = lines[start..].join("\r\n");
        self.log.set_text(&text);
        // set_text 不会自己滚到底：把插入点放末尾再 EM_SCROLLCARET，
        // 不然日志多了以后永远停在第一行（现场反馈）。
        scroll_textbox_to_end(&self.log);
    }

    /// 组装子进程参数（`--no-gui` + 非空项）。
    fn child_args(&self) -> Vec<String> {
        let mut out: Vec<String> = vec!["--no-gui".to_string()];
        let cfg = self.cfg();
        let mut push = |flag: &str, value: &str| {
            if !value.trim().is_empty() {
                out.push(flag.to_string());
                out.push(value.trim().to_string());
            }
        };
        push("--device-id", &cfg.device_id);
        push("--md-host", &cfg.md_host);
        push("--airplay", &cfg.airplay);
        push("--width", &cfg.width);
        push("--height", &cfg.height);
        push("--touch-display", &cfg.touch);
        push("--voice", &cfg.voice);
        if !cfg.audio {
            out.push("--no-audio".to_string());
        }
        out
    }

    fn start(&mut self) {
        if self.child.as_mut().map(|c| c.try_wait().ok().flatten().is_none()).unwrap_or(false) {
            self.append("[gui] 已经在运行，先点「停止」");
            return;
        }
        let args = self.child_args();
        self.cfg().save();
        let exe = std::env::current_exe().unwrap_or_else(|_| std::path::PathBuf::from("hypercar-hub"));
        self.append(&format!("[gui] 启动：{} {}", exe.display(), args.join(" ")));
        let mut command = std::process::Command::new(&exe);
        command
            .args(&args)
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::piped());
        // 不加 CREATE_NO_WINDOW 的话，console 子系统的 exe 会**弹出新的 cmd 窗口**，
        // 用户看到的现象就是"点一次启动多一个黑窗"。
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            const CREATE_NO_WINDOW: u32 = 0x0800_0000;
            command.creation_flags(CREATE_NO_WINDOW);
        }
        let spawned = command.spawn();
        match spawned {
            Ok(mut child) => {
                // stdout / stderr 各起一个读线程，统一塞进队列 + 通知 UI
                if let Some(out) = child.stdout.take() {
                    pump(out, self.queue.clone(), self.notice.sender());
                }
                if let Some(err) = child.stderr.take() {
                    pump(err, self.queue.clone(), self.notice.sender());
                }
                self.child = Some(child);
                self.btn_start.set_enabled(false);
                self.btn_stop.set_enabled(true);
            }
            Err(e) => self.append(&format!("[gui] 启动失败：{e}")),
        }
    }

    fn stop(&mut self) {
        if let Some(mut child) = self.child.take() {
            let _ = child.kill();
            let _ = child.wait();
            self.append("[gui] 已停止");
        }
        self.btn_start.set_enabled(true);
        self.btn_stop.set_enabled(false);
    }
}

/// 把只读文本框滚到最后一行（`EM_SETSEL` + `EM_SCROLLCARET`）。
fn scroll_textbox_to_end(textbox: &nwg::TextBox) {
    #[cfg(windows)]
    {
        extern "system" {
            fn SendMessageW(hwnd: isize, msg: u32, wparam: usize, lparam: isize) -> isize;
        }
        const EM_SETSEL: u32 = 0x00B1;
        const EM_SCROLLCARET: u32 = 0x00B7;
        let Some(hwnd) = textbox.handle.hwnd() else { return };
        let len = textbox.text().len();
        unsafe {
            // 选中点 = 插入点 = 末尾，再让控件滚到插入点
            SendMessageW(hwnd as isize, EM_SETSEL, len, len as isize);
            SendMessageW(hwnd as isize, EM_SCROLLCARET, 0, 0);
        }
    }
}
/// 读一个管道，把每行推进队列并触发 notice。
fn pump<R: std::io::Read + Send + 'static>(
    reader: R,
    queue: Arc<parking_lot::Mutex<Vec<String>>>,
    sender: nwg::NoticeSender,
) {
    std::thread::spawn(move || {
        for line in BufReader::new(reader).lines().map_while(Result::ok) {
            queue.lock().push(line);
            sender.notice();
        }
    });
}

/// 建窗 + 事件循环（阻塞到关闭窗口）。
fn run_gui() -> Result<(), String> {
    nwg::init().map_err(|e| format!("初始化 Win32 失败: {e}"))?;

    let cfg = GuiCfg::load();
    let mut window = nwg::Window::default();
    nwg::Window::builder()
        .size((640, 600))
        .position((260, 120))
        .title("HyperCar Hub · 参数面板")
        .build(&mut window)
        .map_err(|e| format!("建窗口失败: {e}"))?;

    // ---- 输入区：每行「标签 + 输入框」----
    // 标签必须留在作用域里：nwg 控件 Drop 时会销毁 HWND，建完就丢会让标签"消失"
    let mut labels: Vec<nwg::Label> = Vec::new();
    let mut label = |text: &str, x: i32, y: i32, w: i32| {
        let mut lbl = nwg::Label::default();
        nwg::Label::builder()
            .text(text)
            .position((x, y + 4))
            .size((w, 22))
            .parent(&window)
            .build(&mut lbl)
            .ok();
        labels.push(lbl);
    };
    let mut input = |text: &str, x: i32, y: i32, w: i32| {
        let mut t = nwg::TextInput::default();
        nwg::TextInput::builder()
            .text(text)
            .position((x, y))
            .size((w, 26))
            .parent(&window)
            .build(&mut t)
            .ok();
        t
    };

    label("device-id (MAC)", 16, 16, 120);
    let device = input(&cfg.device_id, 150, 14, 300);
    label("md-host (手机IP)", 16, 50, 120);
    let md_host = input(&cfg.md_host, 150, 48, 300);
    label("AirPlay 接收端", 16, 84, 120);
    let airplay = input(&cfg.airplay, 150, 82, 300);
    label("编码 宽", 16, 118, 60);
    let width = input(&cfg.width, 90, 116, 90);
    label("高", 200, 118, 30);
    let height = input(&cfg.height, 235, 116, 90);
    label("触摸空间", 350, 118, 70);
    let touch = input(&cfg.touch, 425, 116, 120);
    label("voice", 16, 152, 60);
    let voice = input(&cfg.voice, 90, 150, 60);

    let mut audio = nwg::CheckBox::default();
    nwg::CheckBox::builder()
        .text("音频转发")
        .position((180, 152))
        .size((120, 24))
        .parent(&window)
        .check_state(if cfg.audio {
            nwg::CheckBoxState::Checked
        } else {
            nwg::CheckBoxState::Unchecked
        })
        .build(&mut audio)
        .ok();

    // ---- 按钮 ----
    let mut button = |text: &str, x: i32, enabled: bool| {
        let mut b = nwg::Button::default();
        nwg::Button::builder()
            .text(text)
            .position((x, 190))
            .size((100, 32))
            .parent(&window)
            .enabled(enabled)
            .build(&mut b)
            .ok();
        b
    };
    let btn_detect = button("自动检测", 16, true);
    let btn_save = button("保存", 126, true);
    let btn_start = button("启动", 236, true);
    let btn_stop = button("停止", 346, false);

    // ---- 日志 ----
    let mut log = nwg::TextBox::default();
    nwg::TextBox::builder()
        .text("")
        .position((16, 236))
        .size((596, 310))
        .parent(&window)
        .readonly(true)
        .build(&mut log)
        .ok();

    let mut notice = nwg::Notice::default();
    nwg::Notice::builder()
        .parent(&window)
        .build(&mut notice)
        .map_err(|e| format!("建 notice 失败: {e}"))?;

    let state = Rc::new(RefCell::new(Gui {
        device,
        md_host,
        airplay,
        width,
        height,
        touch,
        voice,
        audio,
        log,
        btn_detect,
        btn_save,
        btn_start,
        btn_stop,
        notice,
        queue: Arc::new(parking_lot::Mutex::new(Vec::new())),
        child: None,
    }));

    // ---- 事件：按钮点击 / 关窗 / notice（日志到齐）----
    {
        let state = state.clone();
        nwg::bind_event_handler(&window.handle, &window.handle, move |event, _, handle| {
            // set_text / set_enabled 会同步再进本回调，普通 borrow_mut 会二次借用 panic；
            // 重入时直接跳过即可（外层那次已经把该做的做完了）。
            let Ok(mut gui) = state.try_borrow_mut() else { return };
            match event {
                nwg::Event::OnButtonClick => {
                    if handle == gui.btn_detect.handle {
                        match detect_device_id() {
                            Some(mac) => {
                                gui.device.set_text(&mac);
                                gui.append(&format!("[auto] device-id: {mac}"));
                            }
                            None => gui.append("[auto] adb 未取到 device-id（检查数据线/授权）"),
                        }
                        match detect_md_host() {
                            Some(host) => {
                                gui.md_host.set_text(&host);
                                gui.append(&format!("[auto] md-host: {host}"));
                            }
                            None => gui.append("[auto] adb 未取到 md-host"),
                        }
                    } else if handle == gui.btn_save.handle {
                        let cfg = gui.cfg();
                        cfg.save();
                        gui.append(&format!("[gui] 已保存到 {}", gui_cfg_path().display()));
                    } else if handle == gui.btn_start.handle {
                        gui.start();
                    } else if handle == gui.btn_stop.handle {
                        gui.stop();
                    }
                }
                nwg::Event::OnNotice => {
                    let lines: Vec<String> = std::mem::take(&mut *gui.queue.lock());
                    for line in lines {
                        gui.append(&line);
                    }
                }
                nwg::Event::OnWindowClose => {
                    gui.stop();
                    nwg::stop_thread_dispatch();
                }
                _ => {}
            }
        });
    }

    // 打开时先试一次自动检测（失败不报错，用户可手动填）
    {
        let Ok(mut gui) = state.try_borrow_mut() else { return Ok(()) };
        gui.append("提示：device-id / md-host 留空时，启动会自动经 adb 读取。");
        if gui.device.text().is_empty() {
            if let Some(mac) = detect_device_id() {
                gui.device.set_text(&mac);
                gui.append(&format!("[auto] 已填 device-id: {mac}"));
            }
        }
        if gui.md_host.text().is_empty() {
            if let Some(host) = detect_md_host() {
                gui.md_host.set_text(&host);
                gui.append(&format!("[auto] 已填 md-host: {host}"));
            }
        }
    }

    nwg::dispatch_thread_events();
    Ok(())
}
fn main() {

    // 无参数（双击）→ 原生窗口；带参数或 --no-gui → 命令行（子进程/GUI「启动」用这条）
    let args: Vec<String> = std::env::args().skip(1).collect();
    let want_gui = args.is_empty() || args.iter().any(|a| a == "--gui");
    let force_cli = args.iter().any(|a| a == "--no-gui");
    if want_gui && !force_cli {
        hide_console();
        if let Err(e) = run_gui() {
            eprintln!("[gui] 启动失败：{e}");
        }
        return;
    }
    
    
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

    let stop = session::SessionStop::new(1);

    // Ctrl+C → 通知引擎收摊
    {
        let stop = stop.clone();
        tokio::spawn(async move {
            if tokio::signal::ctrl_c().await.is_ok() {
                println!("[main] 收到 Ctrl+C，正在停止…");
                stop.cancel();
            }
        });
    }

    let (touch_display_w, touch_display_h) = match args.touch_display.split_once('x') {
        Some((w, h)) => (w.parse().unwrap_or(0), h.parse().unwrap_or(0)),
        None => (0u32, 0u32),
    };

    let cfg = engine::EngineConfig {
        carlife_enabled: true,
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
    let (_iap_tx, iap_rx) = mpsc::channel(1);
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
        iap_rx,
        stop.clone(),
    )
    .await;
    stop.cancel();
    stop.wait_idle().await;

    match result {
        Ok(()) => {
            println!("[main] 引擎正常退出");
            Ok(())
        }
        Err(e) => Err(format!("引擎退出: {e}")),
    }
}





















