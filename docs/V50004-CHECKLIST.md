# HyperCar 50004 连接顺序修复

版本为 `0.1.1.4 / 50004`。依据同车 CarDeck 成功日志和 DiPlay 接收端源码交叉核验，
修复媒体启动顺序，保留 50003 的 vivo/Jovi 来源与六端口归属检查。

## 改变的行为

1. 配对后先建立会话 `SETUP`，刷新 `/info`，连接事件通道并准备加密收发，
   再发送 `modesChanged` 资源声明；之后才建立主屏流和视频数据连接。
2. 首次 `RECORD` 成功后立即启动独立心跳并交付视频发送器。
   引擎先把视频交给视频泵，再启动后台音频协商；音频的 8 秒超时不阻塞视频或心跳。
3. 音频 `SETUP` 成功后补发一次 `RECORD`；收到成功应答后才交付音频发送器。
   音频拒绝、超时或激活失败仅关闭此次音频发送，视频保持。
4. 音频任务归属当前会话。取消会中止等待和后续激活，已取消的任务不能再次启动。
   事件通道继续存活到本次会话结束，取消后释放。

第 1、3 项是参照同车 CarDeck 的兼容性调整，不能泛化为所有 AirPlay 接收端的硬性要求。
DiPlay 对资源命令比较宽松，音频也不依赖补发 `RECORD`；在 DiPlay 或模拟对端通过不能代表 MIB3 实车通过。

## 保留的行为与影响范围

| 行为 | 实现边界 | 验证方法 |
| --- | --- | --- |
| vivo/Jovi 取源、显式包名选择 | `CarLifeSource.kt`、`VivoCarLifeBinding.kt`、`HyperCarRepo.kt` | 源码保持不变；运行已有来源选择回归 |
| 7240 等六端口 UID 归属与冲突处置 | Kotlin 端口决策及 Rust CarLife 原子监听 | 运行既有端口归属、全组回滚和重启回归 |
| 初始会话先于资源命令 | Rust 媒体协商 | 状态化 TCP 接收端检查真实报文顺序 |
| 视频先启动、音频可失败 | Rust 媒体协商与引擎交接 | 持有音频应答直到真实 8 秒超时，检查视频写入、反馈应答和 UDP 心跳 |
| 新音频激活、取消与失败 | 会话所属后台音频任务 | 检查第二次 RECORD 成功前不交付、失败和取消后不交付音频 |
| 事件加密和生命周期 | RTSP 事件通道 | 加密请求/应答及视频交接后存活测试 |

不禁用、卸载或修改原厂组件；不改变 Jovi 原厂协议授权、音频格式、视频封装、触摸坐标或配对身份。

## 诊断日志补全

增加控制/事件通道编号、请求 CSeq/状态/耗时、视频与音频两次 RECORD 标记、
视频泵接收发送器、来源首帧、首个 RTP 与非静音样本、心跳计数和停止摘要。
补全音频应答解析、端口、UDP 打开与发送失败；错误详情节流，正常心跳仅首次与周期汇总。
把引擎启动、RECORD 完成、来源握手与来源帧分开记录，不推断实际车机显示。
端点携带地址出处元信息；其合并只增加诊断来源，不改变原有地址、端口、凭据的合并规则。
转义文本中的 NUL 与换行，网络发现报文保持原样。全部沿用已开启的文件记录与导出通道。
详见[日志标记及取证边界](DIAGNOSTIC-LOGGING.md)。

## 回归与构建

新增回归先复现旧顺序的两项失败：事件通道前的资源声明被模拟对端拒绝为 454；
音频应答等待阻塞视频交付。修复后通过。其余测试覆盖音频成功、拒绝、超时、
第二次 RECORD 拒绝、首次 RECORD 拒绝、音频两个等待阶段的取消和取消后的迟到启动。

本地 Android 64 项、Rust 31 项回归通过；Lint 为 0 错误、30 条警告、2 条提示。
Rust 检查与 API 31 arm64 交叉编译通过。
正式构建结果、签名、哈希和代码提交以 `docs/artifacts/v50004.json` 及
本地 `release/v50004/build-info.json` 为准。构建命令：

```bash
cargo test --locked --manifest-path rust-core/Cargo.toml
cargo check --locked --manifest-path rust-core/Cargo.toml
cargo ndk -t arm64-v8a -P 31 -o ../app/src/main/jniLibs build --release --locked
bash ./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

`cargo ndk` 在 `rust-core` 目录运行；Gradle 在仓库根目录运行。

## 安装与实车验收

本机测试包沿用 50003 的 Mac 测试签名。它可覆盖同签名的旧包，
不能直接覆盖另一签名的 50002。保留数据覆盖需要原 50002 密钥重签同目录未签名包；
详细步骤见 `release/v50004/INSTALL.md`。版本码升高不会改变签名限制。

手机无需重新连接电脑。安装后确认版本头为 50004，启动并授权 Shizuku，
来源选 `com.baidu.carlife.vivo` 并启用 CarLife 联动，实验性“CarLife 优先启动”保持关闭。
上车蓝牙连好后打开 HyperCar 发起连接；已连车机热点也可以直接发起。
在 MMI 智能手机接口选择 Apple CarPlay，测试后立即导出新日志。

必须继续确认真实 MIB3 的会话建立、Jovi 桌面与首帧显示、扬声器声音、触控、断开重连和功耗。
本次没有连接手机、操作原厂组件或执行实车验证，也不自动创建 Release。
