# 50008 综合诊断包验证记录

- 应用源码提交：`baac71fbf31c4c475052dcdfc530498aabf9525a`。
- APK：`release/v50008/HyperCar-0.1.1.8-v50008-vivo-MIB3-mac-test.apk`，0.1.1.8 / 50008，4,858,374 字节。
- APK SHA-256：`53276fd160855d704557502b9c5c3cbd01385190fd7c42fcbd9203adf439f820`。
- 与此前本机 50007 签名相同；全部原生库为 arm64，ELF 与 APK 对齐均满足 16 KiB。
- 包内源码提交、重编译原生库哈希、13 个 JNI 导出及 DEX 回调签名均已核对；测试合成视频未混入 APK。
- 详细机器可读记录：`docs/artifacts/v50008.json`；安装包旁保留混淆映射、说明、哈希清单。

## 已实现

真实 vivo 来源预检、手机解码图片、同源 A/B/C/D 视频兼容策略、E 硬件 Surface 重编码、生产视频泵及独立 TCP 接收解密、逐轮用户画面记录、声音/触控/重连验证、策略保存和回退、带 SHA-256 清单的 ZIP 导出。

补齐控制响应中的 iAP 载荷、控制完成后的有限资源重申、关键帧恢复；保留六端口 UID 核验、vivo/Jovi 和前台保活。批次独占连接，旧原生、连接及编解码任务退出后才启动下一轮；采样截止后停止额外视频复制，来源断开后不混入等待页。

## 本地验证

| 检查 | 结果 |
| --- | --- |
| `:app:testDebugUnitTest` | 20 个测试类、89 项通过，失败/错误/跳过均 0 |
| `:app:lintDebug` | 0 错误，30 项既有警告、2 项提示 |
| `cargo test --locked --manifest-path rust-core/Cargo.toml` | 55 项通过 |
| `cargo check --locked --manifest-path rust-core/Cargo.toml` | 通过 |
| `cargo ndk -t arm64-v8a -P 31 -o ../app/src/main/jniLibs build --release --locked` | 通过 |
| `:app:assembleRelease` | 混淆、资源压缩及正式构建变体打包通过；使用既有本地测试签名 |
| 独立 FFmpeg 解码 | 合成来源与四个真实 TCP/加解密输出，各 9 帧，逐帧像素相同，解码错误 0 |
| 归档 | 真文件 → ZIP → 清单校验；截断样本、非法路径、符号链接拒绝 |
| 生命周期 | 立即取消红绿测试、旧代次/旧观察隔离、轮次顺序、未通过本地验证的方案禁止车测、恢复后只查看/导出 |
| 控制与来源恢复 | 真 RTSP/TCP 检查资源重申、取消、控制失败保留媒体；真 CarLife CMD socket 检查有界 PAUSE/START 及取消后不再启动 |

取消回归曾暴露 macOS 在关闭仍有未读反馈响应的 socket 时返回 `ConnectionReset`，测试已接受“已取消后的重置”作为终止方式；仍检查取消前异常、后续媒体突变及音频未发布，未用它掩盖运行时故障。

## 待设备验收

本次没有连接手机、Android 运行环境或 MIB3。以下不能标为已通过：当前 vivo/Jovi 画面内容、手机 MediaCodec 硬件能力、手机上的预览和 MediaStore ZIP 导出、真实车机显示/声音/触控/稳定性/重连。

Windows Hub 同步了配置默认值，但 Windows GUI 依赖不能在本机 macOS 编译验收。未执行独立代理审查；本次为普通 implement 流程自检。未发布 GitHub Release，未上传签名材料。

按 [综合诊断操作说明](COMPREHENSIVE-DIAGNOSTICS.md) 完成同包预检和一次车测，导出 ZIP 后才能给出真实设备结论。
