# 50003：MIB3 会话顺序与 CarLife 端口修复

目标版本为 `0.1.1.3 / 50003`，基线为 `674a80d`。本轮依据 2026-10-28 早晨
`50002` 实车导出与同车 CarDeck 成功记录修复；保留 `com.baidu.carlife.vivo`
和 Jovi 绑定取源，没有连接手机、安装应用、禁用原厂组件或修改原厂协议授权。

## 修复方案与最终行为

1. 媒体协商采用“配对 → 会话级 `SETUP /setup` → 刷新 `/info` → `modesChanged`
   → 屏幕流 `SETUP` → `RECORD`”。配对前保留只读能力查询以识别接收端公钥，
   完整显示参数在会话建立后重新读取。不会把两个层次的 SETUP 混淆。
2. 删除 `/info` 失败后向 `/pair-setup`、`/pair-verify` 发送空字典的所谓侦察。
   信息查询失败不再注入认证请求；失败时保留已知能力。
3. 显示信息兼容 `widthPixels` / `heightPixels` 及原 `width` / `height`，
   显示器标识兼容 `uuid`、`UUID`、`displayUUID`，拒绝负数、越界尺寸。
4. vivo 按已验证的原厂监听端处理。一次读取内核表，分别判断六个端口的
   `OWNED`（属于目标）、`FOREIGN`（不属于可用的目标监听）、
   `NOT_LISTENING`（未监听）、`UNKNOWN`（无法核实）。六条拨号通道都须匹配目标 UID。
   未就绪有界等待；vivo 绝不退回为竞争监听方，也不盲连未知程序。
5. vivo 连接前检查 Shizuku 运行及授权状态。当前系统下需要它读取端口归属；
   缺失时提前说明条件，不再等 30 秒后报一个笼统“占用”错误。
   仅做绑定诊断与关闭联动的车机传输测试不代表 vivo 视频已经可用。
6. 来源就绪后再打开车机控制连接，避免一个已打开的车机连接空等原厂初始化。
   原地重试会重新确认来源端口，旧会话检查结果不能影响新会话。
7. 关闭 CarLife 联动时显式传 `carlife_enabled=false`，不监听、不拨号六个来源端口，
   只验证车机传输。旧配置省略该字段仍保持启用来源的行为；PC 联调入口显式保持启用。
8. 网关模式先一次性取得全部六个监听端口，再启动任何工作任务；某个端口失败时
   全部回滚，避免 CMD 已启动而 VIDEO/CTRL 等失败后留下半套服务。
   本机端口绑定错误停止本轮，不再作为 AirPlay 协商错误重复启动六次。

## 影响与回归证据

| 范围 | 验证方式与状态 |
|---|---|
| MIB3 会话顺序 | 真实本地 TCP 模拟对端在 SETUP 前对资源命令返回 454。原顺序复现失败，修复后通过；验证 SETUP、读取信息、资源声明先后关系 |
| 信息读取失败 | 模拟 GET/POST 均返回 455，仍能使用已知显示信息；检查没有任何无效认证探测 |
| 其他接收端字段 | 像素字段、旧尺寸键、不同 UUID 键及非法尺寸回归 |
| vivo/其他来源 | 显式选择不被覆盖、组件缺失、绑定生命周期、取消与迟到回调，沿用既有测试 |
| 端口安全 | 目标与其他 UID、未知读取、TIME_WAIT、仅 IPv6 回环不能授权 IPv4 拨号、缺少任一通道、部分就绪、vivo 禁止转竞争监听、旧客户端来源回归 |
| 网关原子启动 | 最后一个端口故意被占用时，验证前面端口全部释放、占用方仍保留；整组取得后可以关闭并重启 |
| 新旧配置 | 缺省来源开关仍启用，显式关闭才进入无 CarLife 端口的传输诊断 |
| 生命周期、视频与协议 | 保留任务停止、加密写入顺序、虚拟 iAP2、首帧缓存、时间戳、等待页切换、计时端口释放等已有回归 |

本轮 Android 59 项单元测试、Rust 18 项回归均通过；Lint 为 0 Error/Fatal、
30 Warning、2 Information。安装包摘要见 `docs/artifacts/v50003.json`。本轮自行复核实际改动，
没有独立代理审查，也没有把模拟对端当成实车验收。

## 本地验证命令

在仓库 `/Users/xiaolongyitiao/project/hypercar` 中执行：

```bash
/Users/xiaolongyitiao/.cargo/bin/cargo check --locked --manifest-path rust-core/Cargo.toml
/Users/xiaolongyitiao/.cargo/bin/cargo test --locked --manifest-path rust-core/Cargo.toml
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
ANDROID_HOME=/Users/xiaolongyitiao/Library/Android/sdk \
bash ./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process \
  :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

原生库在 `rust-core` 目录使用 `cargo ndk -t arm64-v8a --platform 31
-o ../app/src/main/jniLibs build --release --locked` 重建；本机使用 Rust 1.99.1、
NDK 27.2.12479018、JDK 21。APK 中库的哈希必须匹配此次交叉编译产物。
构建沿用项目工具链，SDK/AGP 兼容提示和既有 Lint 警告保留，不称零警告。
Windows hub 入口只同步默认字段；本 Mac 未编译其原生 Windows GUI，不把它算作已验证平台。

## 签名与安装边界

手机 `50002` 的清单记录签名证书为
`35b28aaa5780a1c0f41f7b0515a018a71cf883dc942f580a3a3f33964ba8f3ba`，
来自另一台机器。本机现有测试签名为
`01020c4ff672bdef9550f9c740285fdaf1f4ae0d70e9296050c0815d1570d5af`。
本轮没有取得 50002 私钥，版本码提高不能绕过 Android 的同签名覆盖要求。

本机签名测试 APK 不能直接覆盖该 50002。交付目录另保留未签名压缩 APK，
原打包机器可用 50002 的签名重签后再覆盖；匹配映射也保留。
没有自动卸载、清数据或把签名不匹配说成“可以覆盖”。私钥及密码不提交仓库。

## 上车验收

1. 使用与现装应用同签名的新版安装包，确认日志头为 `50003`。
2. 车型选 Audi MIB3，再指定目标奥迪设备；来源选 vivo，开启 CarLife 联动，
   实验性优先启动保持关闭。Shizuku 必须运行并授权 Hypercar，供 vivo 来源端口核验。
3. 单独运行 Hypercar 连接；车机选择 Apple CarPlay。观察会话 SETUP、资源声明、
   屏幕流 SETUP、RECORD，再观察实际桌面，而非只看累计帧数。
4. 测试后立即导出日志，注明时间、是否锁屏、车机实际画面及声音/触控表现。

原厂 Jovi 完整授权、设备会话及真正桌面仍可能存在独立阻塞。实车显示、音频、触控、
后台重连及功耗均未在本轮验证。没有自动发布 Release。
