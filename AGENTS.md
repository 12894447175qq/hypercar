# Hypercar 工作约定

## 本地 APK 打包

- 用户只需要本地车测包时，不反复询问密钥、密码或签名方案，不把手机重连作为出包前置条件。
- 有 `keystore.properties` 就复用其配置；没有时，本地 Gradle 构建自动使用 Android 测试签名。
  缺失的默认测试密钥由构建工具生成，以后继续复用；不要每次重建或覆盖密钥文件。
- 本地构建号只控制版本，不表示正式发布。正式 CI 构建使用
  `-Phypercar.requireReleaseSigning=true`，继续保留配置与证书校验，不能伪装测试包为正式包。
- 签名与已安装版本不同时，先完成可安装测试包，再准确标注覆盖限制；不要以此中断日常打包并反复提问。
  不据此推导卸载、清数据或上传私钥的授权；已有用户授权按其实际适用范围执行。
- keystore、密码和 `keystore.properties` 留在本地或受保护的 Secrets 中，不加入 Git。
- 首次检出以及修改 Rust 后必须重编译 arm64 原生库；单独 Gradle 打包不负责生成它。
  Windows 可运行 `tools/build-local.cmd -Release -SkipHub`，无需签名配置。
- 改动构建签名逻辑后，执行 `python3 tools/tests/local_signing_smoke.py --build-apk`。
  测试需要已有 arm64 库和未配置 `keystore.properties` 的工作区，临时配置会清理。

## 车载验证

- 保留 vivo/Jovi 来源和六端口 UID 归属检查，不禁用原厂组件。
- 区分本地检查、手机安装和实车显示/声音/触控/重连，不把构建通过或日志帧数当作实车验收。
