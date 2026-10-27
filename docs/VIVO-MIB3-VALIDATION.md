# vivo CarLife 与 Audi MIB3：实现及验收记录

日期：2026-10-27。版本基线 `0.1.1`，本地版本码 `20000`，高于手机原 `10050`。
代码起点：`4d9f6c0ea7c5c69c62ca2d50199100f2fdb7153e`。

## 交付范围

- vivo 唤醒预设、候选包可见性、Activity 和服务兜底使用 `com.baidu.carlife.vivo`。
  显式选择后，组件或 Jovi 渲染依赖缺失会提示，不能被 CarWith 或公版覆盖。仅空包名表示自动选择。
  vivo 还会启动同一引擎的连接服务，传入 `bindPackageName=com.vivo.car.networking`、
  `bindClassName=com.vivo.vivo_car_service.CarLifeMixingService`、`usb_type=13`、`mix_manual=true`。
  即使 7240 已监听也必须初始化 Jovi 渲染；复用已有监听时不重复启动 Activity。
- 一次连接捕获一个引擎；延迟唤醒、重试、诊断与退出清理均使用该引擎。
  取消关闭会话并取消唤醒任务，下轮等待旧清理结束。未使用引擎时不强停它。
  小米 AIDL/前台保持处理只在小米分支运行。
- 7240/8240 采用零连接探测，并核对 `/proc/net/tcp{,6}` 中 LISTEN 的 UID。
  不向 CarLife 发送额外探测握手，不把其他应用占用的端口当作所选引擎。
  监听端口无法核实时明确失败，不等于已完成 CarLife 协议握手或取到视频。
- 启动时已连接目标热点，也会执行绑定、控制服务公告、AirPlay 发现。
  热点必须匹配目标蓝牙名、iAP2 凭据，或该蓝牙地址已通过 RECORD 验证的热点。
  不再把蜂窝默认网络作为绑定成功；多 Wi-Fi 无法确认归属时不猜。
- API 33+ 的控制服务公告显式设置 `NsdServiceInfo.network`；控制连接和 mDNS 使用同一目标网络。
  mDNS 修复完整域名/压缩指针、SRV 的 target 偏移、附加记录解析及组播接收。
  有可用服务即可推进，不强等 `0x4301` 或 ctrl 邀请；保留 BMW 无网关热点的网段发现兜底。
- Audi MIB3 默认首试 20B MFi，其余默认 32B；按车机记住的成功长度仍优先。
  新偏好只对“全部长度静默、无成功”的旧历史清理一次静默标记，不删除成功或硬拒绝。
  凭据超时日志记录实际声明，不能误报为不支持无线 CarPlay。
- `origin`、更新、车型配置、反馈和项目链接迁至 `12894447175qq/hypercar`。
  原默认车型 URL 自动迁移，自定义镜像保留；原项目署名和许可证保留。
- fork 的 Issues 与 Actions 已开启。未配置正式签名的 CI 推送执行验证并上传明确标识的测试 APK；
  显式发布仍必须具备正式签名，普通提交不创建 Release。

网络公告 API 依据：[Android NsdServiceInfo.setNetwork](https://developer.android.com/reference/android/net/nsd/NsdServiceInfo#setNetwork(android.net.Network))。

## CarDeck 日志对照

来源为本机保存的脱敏导出，未把用户日志、配对密钥、热点密码或厂商 APK 提交到仓库。
同一接收端 `AUDI3260 / DELPHI / CL35_MIB3H_AU_AS_G4x_2102500PROD`：

| 阶段 | CarDeck 成功场次 | 本次覆盖与边界 |
| --- | --- | --- |
| MFi | 17:04:38.844 首试 20B；39.258 收到 AA03 | 合成 CSM 接收端测试验证首试、应答、成功记忆优先；没有伪称是真车认证 |
| 热点凭据 | 17:04:39.389 收到 5703 | 合成会话验证收到凭据，且无需 4301 |
| 绑定/公告 | 17:04:40.104 绑定 true；40.108 公告控制服务 | 测试验证先绑定后公告；绑定失败不得继续发现 |
| 服务发现 | 17:04:40.235 发现 10.174.189.1:7000 | DNS 报文测试覆盖压缩域名、SRV/A 附加记录、多报文和畸形输入 |
| 配对 | 17:04:40.741 完成 | 保留现有 Rust 实现，待 Hypercar 实车核对 |
| SETUP | 17:04:41.267 初始 SETUP；41.617 主屏流 | 保留现有实现，待实车核对 |
| RECORD | 17:04:41.651 激活 | 仅此阶段后保存已验证热点，待实车核对 |
| 真正出帧 | 17:04:42.893 首帧；随后持续发送 | vivo 手机取源已验证真实 H.264；送到 MIB3 的 AirPlay 仍待实车验证，待机动画不计数 |

Hypercar 早上失败日志先发 32B，随后 20/16B 均静默，未收到 AA03 或 5703。
因此有充分证据修正首次挑战顺序和已有热点发现条件；这些证据不能证明修复后的 Hypercar 已在车机上成功。
CarDeck 同场发送了 EA 开会话，本次未据此扩大修改其他车型/EA 默认行为。

## 本地检查

构建环境：macOS，JDK 21（源码/字节码目标 17），Android SDK 35，NDK r27c，Rust stable。

```bash
cd rust-core
cargo check --locked
cargo test --locked
cargo ndk -t arm64-v8a -o ../app/src/main/jniLibs build --release --locked
cd ..
bash ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

- Kotlin 单测：29 项通过，无失败或跳过。覆盖 vivo 显式选择/缺失、其他来源、入口和服务兜底、
  关闭/取消后不再拉起、端口冲突、Jovi 渲染依赖、已有端口仍初始化渲染、自动来源跳过不完整依赖、
  已连接热点、绑定失败、发现顺序、MFi 会话/历史及配置迁移。
- Lint：0 errors，31 warnings；未把警告整体关闭或生成忽略基线。
  修复了检查发现的 3 处蓝牙权限访问，保留依赖更新和既有 UI 等警告。
- Rust：`check` 通过；`test` 通过但该 crate 当前为 **0 个测试**，不作为协议正确性的证明。
- arm64 Rust release 交叉编译通过。Debug APK 和 R8 Release 构建通过；本地 Release **未签名**。
- CI YAML 可解析；签名检查用空材料、显式发布缺材料、完整占位材料三种输入验证。
- APK 版本、原生库、签名及 SHA-256 见下方产物记录。

## vivo 组件安装及手机配置

后续已补充公开文档对照与独立视频解码，见[文档与视频链路复核](VIVO-DOCUMENT-VERIFICATION.md)。
该复核也记录了 Jovi 原厂用户协议提示，不能把视频数据非零等同于车载桌面功能已验收。

首次提交时手机离线；用户随后重新开启无线调试并解锁，已完成以下手机端验证：

- 原签名安装 `com.baidu.carlife.vivo / 8.8.4 / 273`，UID 10424；Activity 冷启动成功。
- 7240、8240、9240、9241、9242、9340 均属于 UID 10424。
- 只启动 Activity：握手成功，但 45 秒内 0 帧。
- 绑定已安装 Jovi 的 `CarLifeMixingService` 后，Jovi 创建 `vivo_car_screen` 虚拟屏，收到真实 H.264。
- 首次仅计数探针使用生产参数（未开启 `carlife_log_open`）：45 秒内完成 1 次握手、326 条视频数据事件、410,900 字节。
  请求分辨率由组件协商为 1280×720。
- 测试探针直接复用仓库 `rust-core/src/carlife.rs`，通过 ADB 转发连接六个通道，只计数，未保存视频内容。
  这是手机取源验证，未连接 MIB3，也未验证 AirPlay 输出、声音或触控。
- 全程未授予 `PROJECT_MEDIA`、未禁用 Jovi；原厂渲染通过 Binder/Surface 工作。

原厂 APK SHA-256：`17cc63fb15a437a3d585c0db17598227c02adfefe2c62b7888b6bd895d96867b`。
证书 SHA-256：`509e03e376b676b25adc41c3dbda6dfa5d0ff12dba1c8c0ca32ad110eea78667`。
私有原始记录仅保存在本地忽略目录 `release/validation-2026-10-27/phone-test/`。

恢复无线 ADB 后使用 `tools/install-vivo-carlife.py`：

```bash
python3 tools/install-vivo-carlife.py \
  --adb /opt/homebrew/bin/adb --server-port 5038 --serial '<当前调试地址:端口>' \
  --install
```

工具从手机已安装的 `com.vivo.car.networking` 提取 `assets/carlife_273.apk`，先核对
`com.baidu.carlife.vivo / 8.8.4 / 273`、原签名有效性和 SHA-256，缺失才安装。
保留原签名；已安装时核对签名，不覆盖不同签名的组件。结果和 APK 仅保存在本机缓存。
没有禁用、卸载或修改 Jovi 系统包。

在 Hypercar 设置中独立确认：唤醒包名 `com.baidu.carlife.vivo`；CarLife 联动 **开**；
实验性 CarLife 优先启动 **关**。源选择本身不会自动修改两个开关。

```bash
adb -P 5038 -s '<当前地址:端口>' shell am start -W \
  -n com.baidu.carlife.vivo/com.baidu.carlife.CarlifeActivity
# vivo 即使已监听，也需要初始化 Jovi 渲染入口
adb -P 5038 -s '<当前地址:端口>' shell am start-foreground-service \
  -n com.baidu.carlife.vivo/com.baidu.carlife.service.CarlifeConnectService \
  --es bindPackageName com.vivo.car.networking \
  --es bindClassName com.vivo.vivo_car_service.CarLifeMixingService \
  --ei usb_type 13 --ez mix_manual true
```

此前只核对入口而没有启动的限制，现已通过上述真实手机测试补齐。仍不能仅凭端口或握手判出帧。
手机原 Hypercar 为非 debuggable 安装，`allowBackup=false`；旧 APK 已保存。
原包证书与测试包不同；用户明确确认接受清除旧 Hypercar 私有数据并重装。
已卸载旧版并安装 `0.1.1 / 20000` 优化测试包（约 4.3 MB，使用本机持久化 debug 签名）。
手机内安装 APK 的 SHA-256 与本地优化测试包完全相同。原 APK 保留在本地备份目录。

重启应用后确认：vivo 来源、CarLife 联动开启、实验性 CarLife 优先启动关闭、Audi MIB3 档案、
目标车机 Audi_MMI_4809、Shizuku 就绪。附近设备与通知权限已就绪；未额外授予麦克风/精确定位。
Jovi、vivo 车载桌面保持启用，CarDeck 仍保留。临时六通道 ADB 转发已全部移除。

此次安装的是测试签名包；后续覆盖升级需使用相同签名材料，不能直接用另一个 CI 临时 debug 证书覆盖。

## 实车验收

停稳车辆后，保留 Jovi 组件启用，连接蓝牙和车机热点，再启动 Hypercar：

1. 先看首次 AA02=20B、AA03 有应答，再看 5703；若无签名，先定位认证，不继续泛授权限。
2. 已连热点也应出现 `bindProcessToNetwork=true`、CTRL 注册成功、MDNS 端点。
   未收到 4301/ctrl 邀请不能单独判失败。
3. 核对 pair-setup/verify、初始与主屏 SETUP、RECORD；失败导出本次完整日志。
4. 手机取源已有证据；实车仍需复核 vivo UID、CarLife 握手与持续真实帧，待机画面不能证明 vivo 出帧。
5. 最后验车机显示、声音、触控和连续运行/断开重连。这些仍是 **待验收项**。

## 产物记录

| 产物 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `HyperCar-0.1.1-test-debug.apk` | 57009779 | `23d2b6df2d4afef198f51c7b6e52323428580819c8dc7f4a42572aa45dce5698` |
| `HyperCar-0.1.1-test-optimized.apk` | 4295137 | `2978ba69123a28c86dfd9a46e13a94289ed12af5b63b9ea43ecde80abd061c1a` |
| `HyperCar-0.1.1-release-unsigned.apk` | 4283790 | `6f91f51e443d259a8adb3a45179ddbbfab3eeed52ad56c623b435a7fcdb243c9` |

测试包证书 SHA-256：`01020c4ff672bdef9550f9c740285fdaf1f4ae0d70e9296050c0815d1570d5af`（Android Debug，仅供测试）。

APK 内 `lib/arm64-v8a/libhypercar_core.so` SHA-256：`a5eaf16619895221b8214f220d12ccb4221ff74f27edd00d7cd6b99073aac113`；ELF 架构 AArch64 已核对。

优化测试包来自同次 R8 Release 构建并用本机 debug key 签名，未公开发布为正式 Release。原始 Debug 包、优化测试包与未签名 Release 均保留。

产物保存在本地忽略目录 `release/validation-2026-10-27/`，含 APK、verification.json、校验和、签名输出、单测 XML 和 Lint HTML。厂商 APK、原始手机日志、截图与签名私钥不入库。
