# vivo 来源启动修复与剩余桌面阻塞

后续日志增强版本为 `0.1.1 / 50000`，文件保存、导出及真机验证状态见[真机日志说明](DIAGNOSTIC-LOGGING.md)。
本文件保留 `30000` 阶段的手机样本和原厂初始化分析；渲染服务绑定通过不表示原厂桌面通过。

日期：2026-10-27。本轮代码起点为 `725c113d2de1ac20cbb128c404d8b845d1a64310`。

**结论：修复版已在手机中安装，应用持有的 vivo 服务绑定和 H.264 取源已验证；
定位授权后仍显示原厂协议提示，Jovi 车载桌面尚未通过。当前不能宣称已解决全部阻塞或已兼容实车。**

## 手机和安装产物

| 项目 | 本轮实际值 |
| --- | --- |
| 手机 | vivo/iQOO V2329A，Android 16 / API 36 |
| Hypercar | `io.github.hypercar`，`0.1.1 / 30000`，覆盖本轮之前的 `20000`，保留应用数据 |
| APK 大小 | 4,311,521 字节，R8 优化，本地测试签名 |
| APK SHA-256 | `fdd0be9ec495b3801f740a828ef2753d676783a5a82f395a2836100b20484ed9` |
| 签名证书 SHA-256 | `01020c4ff672bdef9550f9c740285fdaf1f4ae0d70e9296050c0815d1570d5af` |
| 原生库 | 包含 `arm64-v8a/libhypercar_core.so`，SHA-256 `a5eaf16619895221b8214f220d12ccb4221ff74f27edd00d7cd6b99073aac113` |
| vivo 来源 | `com.baidu.carlife.vivo / 8.8.4 / 273`，UID 10424，原签名未修改 |
| 来源 APK SHA-256 | `17cc63fb15a437a3d585c0db17598227c02adfefe2c62b7888b6bd895d96867b` |
| Jovi | 已安装更新版 `com.vivo.car.networking / 6.0.8.3 / 6083` |
| Jovi APK SHA-256 | `8d4d9d8998276dd9e5d2f5a1eed4612987d51661e130b1e168f2a23febae0c6e` |

手机中已安装 Hypercar 的 APK 哈希与上述本地签名产物一致。
这不是正式签名 Release；本轮不自动发布 Release。

## 本轮授权及实际改动

用户明确允许为 `com.baidu.carlife.vivo` 补齐并保留定位权限。
记录授权前后状态后，仅执行该组件的大致、精确、后台定位授权；三项均从
`granted=false` 变为 `granted=true`，定位 AppOps 读回允许。系统定位总开关原本已开启。
没有据此扩大授予联系人、电话、短信、录音等权限。

没有修改 `CARLIFE_PERMISSION_AUTH` 等协议接受标记，也没有代点原厂协议同意按钮。
没有禁用、卸载或强停 `com.vivo.car.networking` / `com.vivo.carlauncher`，没有修改 vivo 启动策略。
冷启动对照只重置了所选百度 SDK 的进程状态。来源选择保留 vivo；未打开实验性 CarLife 优先启动。

## 启动和零视频问题

当前 Jovi 原始 APK 的 `po.a0`（CarlifeHelper）对 SDK 271 及以上使用
`Context.bindService`。本机 SDK 为 273。
新实现由 Hypercar 为本轮连接持有 `ServiceConnection`，收到回调后才确认绑定完成，
退出或取消时释放自己的绑定。绑定拒绝、超时、断开不再只写一行日志后继续假设初始化成功。

还观察到来源 Activity 自己先绑定过同一服务；普通 Intent 的第二次绑定复用了它的 Binder。
原厂服务在 `onBind` 中读取 Jovi 渲染参数并调用 `connectStart`，复用旧 Binder 会跳过这一步。
这一轮实际完成 CarLife 握手但 20 秒视频为零，且 Jovi mixing 服务没有启动。

Android 的绑定缓存和 Intent 比较语义见
[绑定服务文档](https://developer.android.com/develop/background-work/services/bound-services) 与
[Intent.filterEquals](https://developer.android.com/reference/android/content/Intent#filterEquals(android.content.Intent))：
extras 不区分绑定 Intent。新实现为每轮绑定设置不同 action，使渲染参数实际进入 `onBind`；
不是仅检查 7240 是否监听。真机服务记录已核实 Hypercar 持有不同于来源 Activity 的绑定。

冷绑定曾被系统拒绝。修复版使用显式 Intent 包含已停止包，并在绑定请求被拒绝时，
最多打开一次所选原厂 Activity，再等待 3.1 秒后重试绑定；原厂 `connectStart` 有 3 秒重复启动节流。
取消期间不再进行延迟重试。冷启动 Activity 兜底在 Shizuku 可用时保留原有 shell 唤醒能力，
无 Shizuku 时使用普通 Context 启动，受系统后台启动限制；不会启动一个脱离会话的 SDK 前台服务。
其他来源保留原逻辑。本轮未做真实蓝牙后台触发验收。

这修复了一类初始化/绑定错误。本轮最终冷启动能够取帧，但没有定位旧 `102` 对应的具体系统策略，
不能把这些结果写成所有 vivo 启动限制都已解决。

## 为什么定位授权后提示仍然存在

之前把提示简单归因于“定位不足”或“用户没有同意协议”都不充分。本轮进一步读取当前原始 APK，
并用 DEX 指令交叉核对，确认以下差别：

1. 原厂完整 `wd.m`（Carlifeboot）流程处理原厂授权、定位权限、连接设备事件，随后设置
   `po.a0` 的进程内权限状态。协议同意回调也由这条原厂流程处理。
2. `po.a0.l()` 的备用权限检查存在参数顺序不一致：调用时传入“包名、权限名”，
   而 `b8.j0.b()` 按“权限名、包名”解释，实际把权限字符串传给 `getPackageInfo`。
   这不是仅凭反编译变量名推测，已核对实际 invoke 寄存器和被调用方法的参数使用。
   原厂完整启动流程中的另一处检查顺序正确，并会设置前述进程状态。
3. mixing 服务启动桌面的路径还读取 Jovi 的 `DeviceAttachEv`，为空时直接返回。
   目前 Hypercar 直接启动渲染服务，没有实现原厂完整 boot/设备会话初始化。

因此，ADB 定位授权不能代替原厂完整启动流程。上述源码能确认这两个初始化缺口，
但还没有运行时证据把当前提示唯一归因于其中某一个节点。
没有通过修改原厂 APK、伪造协议完成事件、写接受标记或注入进程字段来掩盖这个问题。

## 验证结果及边界

| 检查 | 结果 | 实际证据与限制 |
| --- | --- | --- |
| Kotlin 回归 | PASS | 7 个 suite，共 38 项，0 失败、0 错误、0 跳过 |
| Android Lint | PASS | 0 Error / Fatal；31 Warning、2 Information 仍存在 |
| Release APK | PASS | 包含 arm64 原生库；签名校验、版本、哈希、已安装 APK 对照均完成 |
| 来源选择及取消 | PASS（单元测试） | vivo 显式选择、依赖缺失、冷启动有限重试、取消后不重试、迟到绑定释放、旧来源、端口 UID 冲突 |
| 原始普通绑定对照 | FAIL | Activity 的旧 Binder 被复用；1 次握手，20 秒 0 视频 |
| 修复版冷启动取源 | PASS | 从 Hypercar 设置页“检查 vivo 来源”触发；六通道同属所选 UID；1 次握手，20 秒 143 条视频、209,812 字节 |
| 严格解码 | PASS | FFmpeg 7.1 严格模式解码 143 帧，1280×720 |
| 本地封装、加密及转发 | PASS | 使用生产 ScreenSender/crypto，经本地 TCP 接收、Python 独立解密；143 帧像素哈希逐帧一致 |
| 加密负向对照 | PASS | 篡改 AAD 包头或密文均认证失败 |
| 实际车载桌面 | BLOCKED | 解码首帧、末帧均为 Jovi / CarLife 原厂协议提示，尚未看到车载桌面 |
| 追加采样 | 有限证据 | 新的 Hypercar 绑定中另采到 26 帧并通过同样解码/转发校验；UI 自动触发脚本遇到前台变化，不能据此宣称受控连续重启稳定通过 |
| 退出清理 | 部分已核查 | 本轮转发均移除；冷启动采样并自动释放后，所选 SDK 和 Jovi mixing 的 active service 均不再出现；不据此推断所有厂商资源或耗电均已验证 |
| MIB3、音频、触控及功耗 | NOT RUN | 用户不在车旁；本地接收器不等于车机配对、上屏或功耗测试 |

冷启动样本和解密还原样本的 SHA-256 均为
`4f88a063c7b1fa0d314ab5fe753a780ce522b1613649fd013dc9fea0e7350bc2`。
本地接收器继续使用公开的合成测试密钥，未使用车机真实配对秘密。
Rust 协议源码本轮未改；APK 原生库哈希与上一轮已验证库一致，不把旧 Rust 检查记为本轮重跑。

## 下一步应解决的内容

先确定能够通过原厂正常授权界面进入完整 Carlifeboot 的入口或受支持 SDK 流程，
核对真实设备会话、原厂授权回调和桌面启动是否同时发生；当前没有验证一个
Hypercar 可用的完整外部入口。仅打开协议阅读页或继续授予权限不能算完成。
找到入口后，用户应在原厂界面自行决定是否同意，再对照原厂启动与 Hypercar 初始化的差异适配。

验收必须看到实际车载桌面，随后复核持续帧、退出和重连。
手机端通过后，仍需在 MIB3 上检查 20B MFi、AA03、5703、热点绑定、服务发现、配对、SETUP 和 RECORD。
CarDeck 的成功日志提供这些阶段的对照，不证明本次 Hypercar/Jovi 已实车成功。

## 私有证据位置

本地目录：`release/validation-2026-10-27/vivo-binding-fix/`，由 Git 忽略。

- `location-permissions.json`：授权前后状态及每条执行结果。
- `apk-metadata.json`、`apk-signature.txt`、`phone-package-verification.json`：构建与手机安装对照。
- `warm-bound-capture/`：普通绑定复用导致的零视频对照。
- `cold-distinct-bound-capture/`：正式冷启动样本、服务绑定、通道归属、画面、严格解码和转发校验。
- `repeat-distinct-bound-capture/`：追加采样，按上表限制使用。
- `capture_bound_source.py`：仅观察应用绑定，采样结束移除自己的转发；不通过 shell 启动 Jovi。

厂商 APK、反编译及 DEX 文本保存在电脑的构建缓存，未提交到仓库。
配对码、手机 UI/视频原始样本、私有日志、签名私钥也未提交。
