# 真机日志保存、导出与排查

本轮日志增强版本基线为 `0.1.1 / 50000`。记录的是实际执行的步骤、返回值和异常，
不会仅凭服务绑定或编码帧数量把 Jovi 桌面、车机显示标记为成功。

## 保存位置与保留范围

打开 Hypercar「诊断」中的记录开关后，关键日志逐条写入应用私有文件并 flush：

```text
/data/user/0/io.github.hypercar/files/hypercar-session.log
/data/user/0/io.github.hypercar/files/hypercar-session.log.1
```

每份最多约 1.5 MB，保留当前和上一份，合计约 3 MB；长时间不退出应用也会轮转。
历史版本遗留的超大文件会按完整 UTF-8 行保留尾部。容量轮转不是按日期永久保留。
重启进程和同签名覆盖更新会保留日志；清空应用数据、卸载或点击“清空”会删除保留日志。
测试完成后应先导出，再清理。

界面为控制刷新成本仅显示最近 300 行；“导出”读取保留文件，包含更早的记录，
不再只导出界面尾部。文件写入失败会显示错误计数，导出文件头也会注明该计数。
记录开关暂停后停止常规文件记录，不会为了导出擅自重新打开记录。

## 重点补充的场景

| 优先级 | 场景 | 新增信息及用途 |
| --- | --- | --- |
| P0 | 蓝牙连上但 Hypercar 无反应 | `acl.received/ignored/handoff`：是否收到广播、自动连接开关、目标过滤方式、缺少设备信息、是否成功转交；蓝牙地址仅保留尾部 |
| P0 | 后台启动被系统拒绝 | `foreground.*`：服务 action、startId、前台类型、录音权限状态、失败栈、回退结果 |
| P0 | vivo 来源初始化失败或重复绑定 | `initialize/bind/wake/unbind`：来源/Jovi 版本和 UID、启用/停止状态、屏幕/锁屏、进程重要性、定位授权、绑定 action、回调、8 秒超时、Shizuku 返回及 3.1 秒重试 |
| P0 | 原厂提示页一直不消失 | 明确记录仅初始化了渲染服务、原厂完整授权/设备会话状态不可经此公共入口观察；需要结合原厂日志和实际画面，不能伪造授权成功 |
| P1 | 手机已连接热点但车机无显示 | `wifi.*`、端口归属、网络句柄、绑定结果和失败类型；原有 iAP2/MFi、公告、发现、配对、SETUP、RECORD 日志保留并带本轮连接编号 |
| P1 | 握手成功但零视频/断流 | 首批来源帧、每 5 秒帧数/FPS/码率/未增长时长/音频状态；12 秒零帧时自动采样源码应用、Jovi 服务、权限、屏幕、编码器和系统日志 |
| P1 | 断开后仍耗电、取消后再拉起 | `cleanup.*`、`unbind.*`、`locks.*`：取消原因、清理请求、异常、CPU/Wi-Fi 锁释放后的状态；不把“清空引用”当作所有厂商资源均已释放 |

每轮连接、来源检查、vivo 绑定和前台服务有各自 TRACE 编号，包含进程随机标记和父编号，
例如 `TRACE[vivo-xxxxxxxx-2 parent=source-check-xxxxxxxx-1] +250ms bind.connected`。
`+Nms` 使用单调时钟，文件行首另外保留手机的实际时间，便于与车机或 CarDeck 日志对齐。

异常包含类型、消息、cause 和调用栈；超过 128 行时明确标记截断。
保留 R8 行号，并在本地测试产物旁保存匹配的 `mapping.txt`，分析混淆调用栈时必须配套使用。

高频状态每 5 秒采样，跳过时不构建详情字符串；不在每一帧或每行代码后刷日志。
密码、热点连接命令参数、配对码、token、会话秘密等会在写入和导出时隐藏。
日志中不会新增媒体帧、音频内容或密钥载荷的完整转储。

自动现场采样只执行读取命令，已经去掉附带的服务绑定/启动显示动作。
显式的小米投屏初始化入口保留，自动取证不再以它改变现场。
Jovi 原厂进程没有重新打包或修改，无法在保持原签名的同时对其内部每行代码注入日志。

## 真机测试后怎样交给后续会话

1. 确认「诊断 → 记录」开启，然后复现一次连接问题，记住大致时间和车机表现。
2. 手机端点「诊断 → 导出」。导出到 `下载/HyperCar/hypercar-log-年月日-时分秒-毫秒.txt`。
3. 将导出的文件交给后续会话，或连接无线 ADB 后让助手读取手机该目录。
   同时提供测试的大致时间、当时是否锁屏，以及车机完全无反应/出现图标/黑屏等实际表现。
4. 排查顺序为触发与目标过滤 → 系统前台启动 → vivo 初始化 → MFi → 热点绑定与发现
   → 配对/SETUP/RECORD → 来源首帧和实际画面 → 清理与重连，避免用下游加权限解释上游失败。

## 无需切换手机界面的 ADB 导出

新增 `LogExportReceiver` 由 `android.permission.DUMP` 保护，只允许具备该权限的调用方
（如 ADB shell）触发，普通第三方应用不能借此导出日志。该入口不启动或重建车载连接。

先运行 `adb -P 5038 devices -l` 获取当前 `transport_id`，替换以下 `TRANSPORT_ID`：

```bash
adb -P 5038 -t TRANSPORT_ID shell am broadcast --user 0 --include-stopped-packages \
  -a io.github.hypercar.EXPORT_LOGS \
  -n io.github.hypercar/.session.LogExportReceiver
adb -P 5038 -t TRANSPORT_ID shell ls -lt /sdcard/Download/HyperCar/
adb -P 5038 -t TRANSPORT_ID pull /sdcard/Download/HyperCar/实际文件名.txt .
```

本机使用 5038 是为了避开其他软件占用的 ADB 服务；不应沿用旧的手机 IP、配对端口或配对码。
示例显式包含停止状态的目标包，避免刚安装或被强行停止的应用漏收广播；参见
[Android Intent 标志说明](https://developer.android.com/reference/android/content/Intent#FLAG_INCLUDE_STOPPED_PACKAGES)。
导出包含保留的历史记录，文件头记录应用版本、设备系统、行数和文件写入失败计数。
ADB 连通不代表已完成车机测试；导出成功也不代表 Jovi 桌面或 MIB3 上屏已通过。

## 本轮验证

新增自动回归覆盖日志跨进程保留、超过 300 行的完整读取、运行中轮转、UTF-8 边界、
旧超大文件迁移、清空历史、超大单条记录、采样节流、连接编号及异常秘密字段隐藏。
45 项单元测试已通过，Lint 为 0 Error / Fatal（仍有 31 Warning、2 Information）。
测试包包含 arm64 原生库，使用与手机既有测试版一致的本地签名；匹配的 R8 映射保留在本地验证目录。
手机最终覆盖及导出回读尚待 ADB 恢复后完成。初次取到的文件头为旧版 `10050`，已排除，
不计为本版导出成功。复测必须确认文件为本次新生成、版本头为 `50000`，且包含本次 TRACE。

2026-10-27 本地产物：

| 项目 | 结果 |
| --- | --- |
| APK | `HyperCar-0.1.1-v50000-diagnostic-test.apk`，4,332,001 字节 |
| APK SHA-256 | `15c5543bdf2512d748564ebc622d509adafe2971edea2467f097920f75dd53c8` |
| 签名证书 SHA-256 | `01020c4ff672bdef9550f9c740285fdaf1f4ae0d70e9296050c0815d1570d5af` |
| R8 mapping SHA-256 | `7828f439a08d5cbe29a71e069ba15da3b602193b9a8e9afb255c8a08e2331cf2` |
| 当前本地检查 | 8 个 suite、45 项通过，0 失败/错误/跳过；Lint 0 Error / Fatal |
| 手机状态 | 日志功能候选版 `40000` 已安装；最终 `50000` 尚待恢复 ADB 后覆盖 |
| 实车状态 | 尚未测试 MIB3 上屏、音频、触控、后台蓝牙触发及实际功耗 |

APK、映射和详细元数据保存在本地忽略目录
`release/validation-2026-10-27/diagnostic-logging/v50000/`。
源文件、日志、应用版本和映射应配套核对；旧文件存在、广播返回成功或全文中出现一个版本数字，
都不能代替对新导出文件头的确认。
