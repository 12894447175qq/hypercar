# 真机日志保存、导出与排查

当前修复包为 `0.1.1.3 / 50003`，尚未安装或进行实车验证。新增修复与签名限制见[50003 清单](V50003-CHECKLIST.md)。
记录的是实际执行的步骤、返回值和异常，
不会仅凭服务绑定或编码帧数量把 Jovi 桌面、车机显示标记为成功。

## 当前安装与交付文件

本地目录 `release/v50003/` 保留本机签名测试 APK、供原打包机器重签的未签名 APK、
匹配的 `mapping.txt.gz` 及校验信息。当前 Mac 签名不能直接覆盖手机的 50002，
不能靠提高版本号绕过；必须使用手机 50002 的签名重签才能保留数据覆盖。
本轮没有操作手机或替用户卸载。

## 历史 50002 安装记录

本机交付目录为 `F:\project\artifacts\hypercar-release\20261027-v50002\`，其中的
`HyperCar-0.1.1.2-v50002-vivo-MIB3-test.apk` 为已安装的同一测试包。
APK 通过聊天文件链接交付，不写入 Git 历史，也未自动发布 Release。
版本、签名和校验值见[构建信息](artifacts/v50002.json)，三轮复核及完成/阻塞清单见[修复清单](V50002-CHECKLIST.md)。

此包使用本机保存的新测试签名，与原 Mac 包不同。用户已同意重新初始化 Hypercar 数据：
已备份原 50000 APK 和六份旧导出，仅卸载重装 `io.github.hypercar`；未卸载或清除
vivo CarLife / Jovi。安装后版本码已确认是 `50002`，已恢复 Audi MIB3、已配对车机、vivo 来源和诊断记录。
只恢复此前已允许的权限。vivo/Jovi 的版本、UID、启用状态和数据目录标识与安装前一致。
冷启动、应用环境的连接服务启停和新版本日志导出均通过，实车显示仍待验收。

对应的 R8 映射压缩保留为 `mapping.txt.gz`，供后续分析异常栈，不需要安装到手机。
vivo / Jovi 的已知阻塞和验收顺序见[测试状态](VIVO-MIB3.md)。

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
| P0 | 后台启动被系统拒绝 | `foreground.*`：服务 action、startId、前台类型、失败栈和结果；50002 使用 connectedDevice，原厂取麦，Hypercar 不再重复请求 microphone 类型 |
| P0 | vivo 来源初始化失败或重复绑定 | `initialize/bind/wake/unbind`：来源/Jovi 版本和 UID、启用/停止状态、屏幕/锁屏、进程重要性、定位授权、绑定 action、回调、8 秒超时、Shizuku 返回及 3.1 秒重试 |
| P0 | 原厂提示页一直不消失 | 明确记录仅初始化了渲染服务、原厂完整授权/设备会话状态不可经此公共入口观察；需要结合原厂日志和实际画面，不能伪造授权成功 |
| P1 | 手机已连接热点但车机无显示 | `wifi.*`、端口归属、网络句柄、绑定结果和失败类型；原有 iAP2/MFi、公告、发现、配对、SETUP、RECORD 日志保留并带本轮连接编号 |
| P1 | 握手成功但零视频/断流 | 首批来源帧、每 5 秒帧数/FPS/码率/未增长时长/音频状态；12 秒零帧时自动采样源码应用、Jovi 服务、权限、屏幕、编码器和系统日志 |
| P1 | 断开后仍耗电、取消后再拉起 | `cleanup.*`、`unbind.*`、`locks.*`：取消原因、清理请求、异常、CPU/Wi-Fi 锁释放后的状态；不把“清空引用”当作所有厂商资源均已释放 |

每轮连接、来源检查、vivo 绑定和前台服务有各自 TRACE 编号，包含进程随机标记和父编号，
例如 `TRACE[vivo-xxxxxxxx-2 parent=source-check-xxxxxxxx-1] +250ms bind.connected`。
`+Nms` 使用单调时钟，文件行首另外保留手机的实际时间，便于与车机或 CarDeck 日志对齐。

异常包含类型、消息、cause 和调用栈；超过 128 行时明确标记截断。
保留 R8 行号，并在安装包旁压缩保存匹配的 `mapping.txt.gz`，分析混淆调用栈时先解压并配套使用。

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

先运行 `adb devices -l` 获取当前 `transport_id`，替换以下 `TRANSPORT_ID`：

```bash
adb -t TRANSPORT_ID shell am broadcast --receiver-foreground --user 0 --include-stopped-packages \
  -a io.github.hypercar.EXPORT_LOGS \
  -n io.github.hypercar/.session.LogExportReceiver
adb -t TRANSPORT_ID shell ls -lt /sdcard/Download/HyperCar/
adb -t TRANSPORT_ID pull /sdcard/Download/HyperCar/实际文件名.txt .
```

本轮 ADB 使用默认端口 5037；若使用另一台电脑的自定义 ADB 服务，按该电脑配置处理。
不应沿用旧的手机 IP、配对端口或配对码。
示例显式包含停止状态的目标包，避免刚安装或被强行停止的应用漏收广播；参见
[Android Intent 标志说明](https://developer.android.com/reference/android/content/Intent#FLAG_INCLUDE_STOPPED_PACKAGES)。
导出包含保留的历史记录，文件头记录应用版本、设备系统、行数和文件写入失败计数。
ADB 连通不代表已完成车机测试；导出成功也不代表 Jovi 桌面或 MIB3 上屏已通过。

## 验证边界

50003 仅完成本地回归、Lint 和 arm64 压缩打包；未在本轮连接手机或实车。
当前签名不能覆盖已安装的 50002，详细构建统计见[50003 构建信息](artifacts/v50003.json)。

### 历史 50002 验证

v50002 的 54 项应用单元测试、12 项 Rust 回归均通过，正式压缩构建通过；Lint 为 0 errors / 27 warnings。
手机独立进程完成 100 次 TCP/100 次 UDP 描述符交接（句柄数 69→69）和 10 轮 JNI 启停。
最终 APK 的版本、签名及内含 arm64 库均核对，见[50002 历史构建信息](artifacts/v50002.json)。

当时已安装 50002 APK，并在应用自身环境验证 connectedDevice 服务提升成功、停止后 CPU/Wi-Fi 锁释放。
冷启动成功，测试采样未见 FATAL/JNI 错误。安装后导出文件头为 `50002`、文件写入失败数为 0；
该导出只覆盖无车环境的服务验证，不能代替原厂完整初始化或实车桌面、音频、触控和重连验收。

收到后续车测文件后，必须确认它是新生成，版本头与实际安装版本一致（本轮目标 `50003`），并包含本次 TRACE。
Android 16 曾将后台导出广播标为 DEFERRED；示例加入前台广播标志，仍应核对新文件，不能只看广播返回值。
v50000、v50001 的历史构建记录保留在各自构建信息中，不代表本轮验证结果。
