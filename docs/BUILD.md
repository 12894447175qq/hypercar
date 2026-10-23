# 编译指南

## 0. 环境

- JDK 17、Android SDK（compileSdk 35）、NDK（仅 cargo-ndk 需要）
- Rust：`rustup`（stable，≥1.75；let-else/async 闭包等语法用到）
- 目标机：arm64-v8a（宝马场景只出 64 位包）

## 1. 编译 Rust 核心层

```bash
rustup target add aarch64-linux-android
cargo install cargo-ndk

cd rust-core
# 产出 libhypercar_core.so 到 app/src/main/jniLibs/arm64-v8a/
cargo ndk -t arm64-v8a -o ../app/src/main/jniLibs build --release
```

宿主机快速自检（不装 NDK 也能编译检查，注意 JNI/android_logger 相关目标差异）：

```bash
cd rust-core && cargo check
cargo test   # 纯函数（bplist/tlv8/srp/封帧）可在宿主跑单测
```

## 2. 编译 Android 应用

```bash
# 首次：生成 wrapper（仓库未带 gradle wrapper jar 时）
gradle wrapper --gradle-version 8.7

./gradlew :app:assembleDebug        # 调试包
./gradlew :app:assembleRelease      # 正式包（需 keystore.properties）
```

安装（先杀进程再装，避免确认弹窗落到后台）：

```bash
adb shell am force-stop io.github.hypercar
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 3. 前置条件（手机端）

1. **Shizuku**：已安装并启动、已授权给 HyperCar（静默入网/真实蓝牙 MAC 都靠它）。
2. **权限**：通知、附近设备（蓝牙）、（可选）悬浮窗——悬浮窗是 Android 12+ 后台
   FGS 启动的豁免来源，**不给则上车自动连接会被系统拦**（手动打开 App 连接一次不受影响）。
3. **本机 CarLife 系 app**：百度 CarLife（≤6.7.2 或 CarWith 内置的 CarLife 连接）、
   小米 CarWith、荣耀车联任一；在 HyperCar 设置里选对唤醒包名。
4. **车机侧**：2023 宝马 X6 ID8.5 与手机完成系统级蓝牙配对；车机开启无线 CarPlay/投屏。

## 4. 首次连接（手动）

1. 手机连上车机热点（或让 iAP2 流程下发 0x5703 自动连）。
2. 打开 HyperCar → 「启动连接」→ 观察状态卡逐步推进。
3. Rust 日志出现 `✓ RECORD 完成` 后会自动广播唤醒 CarLife；CarLife 里选择
   「无线连接/车机连接」入口（不同 app 入口名不同）。
4. 车机屏出现 CarLife 画面即全链路打通；触摸/旋钮应有响应。

## 5. CI 与发布（.github/workflows/android-ci.yml）

每次 push 自动出**正式签名**包（CarDeck 同款机制 + Rust 环节）：

- 构建顺序：判定发布 → JDK 17 → **Rust 交叉编译**（dtolnay/rust-toolchain +
  nttld/setup-ndk r27c + cargo-ndk → `app/src/main/jniLibs/arm64-v8a/libhypercar_core.so`，
  target 与 cargo 目录均有缓存）→ 签名配置 → Gradle Release → 签名校验
  （并核对 APK 内确有 .so）→ 单测 →（可选）发布 Release。
- 版本号自动累计：`appVersionName`（app/build.gradle.kts，发布时只手改这一行）+
  run_number → `0.1.0.<N>`、`versionCode = 1*10000 + N`，每次推送 +1 可覆盖安装。
- **发布条件**：提交标题**末尾**带 `[release]` 或 `[publish]`（或 Actions 页手动
  Run workflow 勾选 publish）→ 构建并跑完单测后自动发 GitHub Release（tag `v<版本>`）。
- 必需 Secrets：`KEYSTORE_BASE64 / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD`
  （可选 `EXPECTED_CERT_SHA256` 指纹比对）。**没配 KEYSTORE_BASE64 流水线直接失败**，
  绝不退回 debug 签名；Gradle 侧第二道闸：传了 `-Phypercar.buildNumber` 却没有
  keystore.properties 也直接构建失败。
- 签名是不可逆决定：首个公开版本就必须用正式 keystore（debug 证书发出后换证书
  需要所有用户卸载重装）；keystore 与密码只存本地/密码管理器。

## 6. 排查

- App 内「诊断」页有 Rust/iAP2/WiFi 全量日志（等价于 cardeck 的 main_debug.log）。
- 常见失败：
  - `Shizuku 未就绪` → 启动 Shizuku 并重新授权。
  - `0x5702 后无 0x5703` → 车机未开无线 CarPlay 窗口；在车机上点一次手机图标。
  - `会话 SETUP 被拒 403` → 车机按 deviceID 过滤；确认设置里的目标车机与本机蓝牙 MAC 匹配。
  - `CarLife 一直 Waiting` → 唤醒包名不对，或 CarLife app 版本过新（≥7.2 与第三方
    车机端不兼容，需用 CarWith 的 CarLife 连接通道）。
