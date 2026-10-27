# vivo/Jovi 文档与视频链路复核

验证日期：2026-10-27。应用基线：`aea7c14eeb8c19e6167608b98d0ab571f51a1db5`。

手机安装的 Hypercar 为 `0.1.1 / 20000`，APK SHA-256：
`2978ba69123a28c86dfd9a46e13a94289ed12af5b63b9ea43ecde80abd061c1a`，
与此前安装的优化测试包一致。来源为 `com.baidu.carlife.vivo / 8.8.4 / 273`，
Jovi 为 `com.vivo.car.networking / 6.0.8.3 / 6083`。

## 资料适用范围

| 来源 | 本次用途 | 不能据此推导的结论 |
| --- | --- | --- |
| [vivo CastKit 官方说明](https://developers.vivo.com/product/d/castKit) | 对照系统虚拟屏、反控、Jovi SDK 与 CarLife 合作的架构 | 没有公开本次 `CarLifeMixingService` 内部服务参数的兼容承诺 |
| [普通应用接入标准](https://developers.vivo.com/doc/d/2a49ec4f4ae54806b4c083e39f4a6714) | 确定要求面向运行于 Jovi 的普通应用 | Hypercar 是协议桥接端，不应因为没有 UCAR 应用入口就判失败 |
| [历史 CarLife 协议头文件](https://github.com/674809/carlife/blob/master/CarLife-Vehicle-Lib/LibSource/include/CTranRecvPackageProcess.h) | 独立对照握手与视频消息编号、视频头长度 | 历史源码不是当前 vivo 私有接口的完整规范 |
| [编码参数 protobuf](https://github.com/674809/carlife/blob/master/CarLife-Vehicle-Lib/LibSource/protobuf/proto/CarlifeVideoEncoderInfoProto.proto) | 对照 width、height、frameRate 的字段编号 | 请求的帧率不代表组件实际产帧率 |

MiAuto 使用 ICCOA CarLink 接收路线，其核心 SDK 未随源码提供，未用作这条
CarLife → AirPlay 桥接链路已经兼容 MIB3 的证据。

## 已执行的检查

| 检查 | 结果 | 覆盖范围 |
| --- | --- | --- |
| 10 个握手/视频消息 ID 对照 | PASS（静态一致性） | HU version、version match、HU/MD info、encoder init/done/start/pause、video data/heartbeat |
| 编码参数 protobuf | PASS（静态一致性） | width=1、height=2、frameRate=3；与实际源码的序列化字段一致 |
| 原厂服务入口存在性 | PASS（静态） | Manifest 导出 `CarLifeMixingService`，本机字节码读取 bindPackageName/bindClassName；不等同于公开 SDK API |
| 视频通道归属 | PASS | 六个通道均由所选 vivo CarLife UID 持有 |
| 手机端取源 | PASS | 本次 20 秒：1 次握手、143 条视频消息、189,717 字节；回报实际尺寸 1280×720 |
| FFmpeg 严格解码 | PASS | `-xerror -err_detect explode`，成功解码 143 帧 1280×720；不再仅依据非空消息计数 |
| Hypercar 视频转发 | PASS（本地） | 直接复用应用的 screen.rs/crypto.rs，经本地 TCP 接收，用 Python cryptography 独立解密 |
| 转发前后画面一致性 | PASS（本地） | 两份码流均解码 143 帧，每帧像素哈希一致，码流 SHA-256 也相同 |
| 加密负向对照 | PASS（本地） | 修改包头 AAD 或密文均触发认证失败 |
| Jovi 车载桌面功能 | BLOCKED | 本次首帧和末帧均提示先在手机端同意智能车载用户协议；用户确认手机没有出现可同意的首次使用按钮 |
| 后续启动/取源复测 | FAIL | 出现服务启动 Not found、握手成功但 0 视频，以及 Activity 启动返回 102；不能把早期成功样本当成稳定可用 |
| MIB3 配对及上屏 | NOT RUN | 用户不在车机旁；本地接收器不模拟车机的全部鉴权和协议要求 |
| 音频、触控、重连 | NOT RUN | 视频采样不覆盖这些路径 |
| 实际耗电 | NOT RUN | 无同条件的实车 A/B 功耗采样，不能据此声称比 CarDeck 省电 |

解码工具为 FFmpeg 7.1，由隔离安装的 PyPI `imageio-ffmpeg==0.6.0` 提供。
本地接收器使用公开的合成测试密钥，不使用真实车机的配对秘密。

本次 source.h264 和 roundtrip.h264 的 SHA-256 均为：
`310162cf3d8989ae1e5de47fbb6615077fafc6dfd3b9199c4c8a353da48b6f69`。

## 实际画面与首次使用门槛

解码后的内容是 Jovi / 百度 CarLife 的原厂提示页，显示：
“请先在手机端点击同意智能车载用户协议，以继续使用车载投屏”。

这证明视频载荷可解码，但还没有验证地图、音乐等车载桌面功能。
协议需要用户在手机原厂界面阅读并自行决定是否同意；验证程序没有代点同意、
修改协议接受标记或绕过该流程。

该提示页来自车载虚拟屏，不是手机屏幕截图。手机端实际查看过的是连接说明和
Jovi 关于页；关于页可见协议/隐私政策入口，但尚未定位到本次首次授权的确认页。
用户后来明确反馈没有看到同意按钮。因此实验目录中的“after-consent”旧名称
不表示真实完成了原厂授权，现已改为 `retry-without-native-consent`。

后续复测记录：

1. 组件仍安装且版本未变，`query-services` 仍能解析到 `CarlifeConnectService`，
   但启动服务返回 `Not found; no service started`；显式指定主用户 0 也失败。
   该错误不能直接解释为组件未安装。
2. 把已有 CarLife Activity 带回前台后，曾有一次相同服务启动成功。
   这只是观察，尚未完成同条件的前后台因果验证。
3. 随后的 20 秒复测完成 1 次握手并回报 1280×720，但视频消息为 0。
   该轮没有完整的逐时屏幕状态记录，不能仅据此认定协议接受状态或锁屏是根因。
4. 冷启动复测开始时记录到手机已解锁，但 Activity 返回
   `Activity not started, unknown error code 102`，因此没有进入采样。
   尚未确定系统拦截的具体策略原因，没有修改 vivo 系统策略。

这些结果要求继续调查原厂初始化入口与启动条件；当前不能宣称 Hypercar 已能正常
提供 Jovi 桌面，也不能把“收到有效 H.264”写成“应用功能全部通过”。

## 生命周期与证据位置

20 秒采样结束后已移除本轮六个 ADB 转发、停止选定的 CarLife 进程并返回 Hypercar。
没有禁用 Jovi 或 vivo 车载桌面。核查时 Jovi mixing 服务已退出，手机可进入休眠；
DisplayManager 中仍能看到原厂 carlauncher 的子虚拟屏。该观察不能单独证明其耗电，
也不能据此把所有厂商资源均已释放写成 PASS；需继续核对原厂首次使用及正常退出流程。

本地私有证据目录：`release/validation-2026-10-27/doc-validation/`。

- `protocol-contract.json`：独立协议编号与 schema 对照。
- `phone-package-verification.json`、`port-ownership.json`：安装版本、APK 哈希及通道归属。
- `capture.json`、`source.index.tsv`、`capture.log`：原始取源计数与消息边界。
- `source.h264`、`screen.wire`、`roundtrip.h264`：本地样本和封装后的传输数据。
- `source.framemd5`、`roundtrip.framemd5`、`decode-verification.json`：解码与一致性结果。
- `first-frame.png`、`last-frame.png`：实际画面的检查证据。
- `probe/src/main.rs`、`validate_video.py`：本次执行的探针和独立接收/解码校验器。

视频、截图、原始日志、厂商 APK、反编译文本和签名私钥不提交到仓库。
此次验证没有修改手机已安装 APK 或业务实现。
