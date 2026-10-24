# iPhone HCI 蓝牙抓包（sysdiagnose）操作手册

真机联调时，「我们的实现对不对」只有**同一个车机对真 iPhone 的行为**能回答。
iPhone 不开放 HCI 口给第三方，但可以用苹果官方的蓝牙调试描述文件让 `bluetoothd`
把 HCI 缓冲池写进 `sysdiagnose`，取出 `bluetoothd-hci-latest.pklg`
（Apple PacketLogger 格式）后用 Wireshark 逐帧看。

本文是**从零到拿到 `.pklg`** 的完整步骤；抓完之后怎么读，见
[NEGOTIATION-GAPS.md §10](NEGOTIATION-GAPS.md#10-真-iphone-hci-对照2026-10-24同一台-bmw-mgu-55)。

---

## 步骤 1：安装苹果官方蓝牙调试描述文件

必须在 **iPhone 自带的 Safari** 里打开（Chrome / 微信内置浏览器不行：`.mobileconfig`
的下载和「已下载描述文件」入口只有 Safari 会正确接管）。

1. 用 Safari 访问苹果官方页面：
   <https://developer.apple.com/bug-reporting/profiles-and-logs/>
   登录**普通 Apple ID 即可**，不需要付费开发者账号。
2. 找到 **Bluetooth** 那一项（标题类似 *Bluetooth for iOS*），点下载 `.mobileconfig`。
   页面打不开时，也可以在 GitHub 搜镜像仓库 `apple-profiles` 取同一个文件。
3. 下载完成手机会提示「**已下载描述文件**」。
4. 打开 **设置** → 顶部「**已下载描述文件**」→ 右上角「**安装**」→ 输入锁屏密码。
   装完**不需要重启**，也不会出现新的 App 图标。

> 装完在 **设置 → 通用 → VPN 与设备管理** 里能看到这条描述文件，
> 名字里带 *Bluetooth*。联调结束后建议在同一位置「移除描述文件」——
> 它会持续记录 HCI 缓冲，多少占空间。

## 步骤 2：上车复现握手，并记下时间点

为了抓到**最完整的 iAP2 + pair-setup 初次握手**，建议先清掉旧配对：

1. iPhone：**设置 → 通用 → CarPlay** → 选中那台车 → 「**忽略这辆车**」。
2. 车机（宝马）：在 iD8.5 的移动设备列表里**删除**这台 iPhone 的配对记录。
3. 重新发起无线 CarPlay 连接，等车机带鱼屏点亮、进入 CarPlay 界面。
4. **记下进入 CarPlay 那一刻的精确时间（精确到分钟，例如 14:25）**——
   后面在时间轴里对齐「哪一段是我们关心的握手」全靠它。

> 只做「重新连接」不清配对，抓到的会是 `pair-verify` 而不是 `pair-setup`；
> 两者报文完全不同，排查「车机为什么发 0x4301 / 为什么回 0xEA03」时前者没用。

## 步骤 3：按键触发 sysdiagnose 打包

进入 CarPlay 界面后再等约 **2~3 分钟**，让缓冲池积累（太早抓会缺少后续心跳）。

同时按住 **音量+、音量−、侧边电源键** 约 **1~1.5 秒**：

- 感觉到**明显震动一下**即可松手；
- 若弹出「滑动来关机」，说明按久了，点**取消**重来——取消不影响已触发的打包。

震动意味着系统已在后台打包**系统级诊断数据 + 蓝牙 HCI 缓冲池**。

## 步骤 4：导出 `.pklg` 到电脑

1. 等 **2~3 分钟**让打包完成（文件有几十到几百 MB，别急着找）。
2. iPhone：**设置 → 隐私与安全性 → 分析与改进 → 分析数据**。
3. 顶部搜索框输入 `sysdiagnose`，找到当天日期命名的
   `sysdiagnose_YYYY.MM.DD_...tar.gz`（时间戳最近的即刚才那次）。
4. 点右上角**分享**，用 **AirDrop / 微信文件传输助手 / 数据线**传到 Windows 解压。
5. 解压后进入 **`logs/Bluetooth/`**，能看到：

```
logs/Bluetooth/bluetoothd-hci-latest.pklg          ← 就是它（可能有带时间戳的同名文件）
logs/Bluetooth/bluetoothd-hci-YYYY-MM-DD-*.pklg    ← 同一次抓包的滚动分片
```

## 步骤 5：用 Wireshark 打开

直接 `File → Open` 打开 `.pklg`（Wireshark 原生支持 Apple PacketLogger 格式），
过滤建议：

| 目的 | 显示过滤器 |
|---|---|
| 只看 iAP2 报文 | `btl2cap` + 按 `frame contains 40:40` 逐条看，或按 CID 过滤 |
| 看链路建立/断开 | `btl2cap.cid` / `btstack` 事件（视版本） |
| 看某条具体报文 | `btl2cap.payload contains 40:40:00:0e`（例：`0x4301` 邀请） |

> macOS 上更顺手的是苹果自家 **PacketLogger**（Additional Tools for Xcode 里），
> 它会把 iAP2 解成结构化参数；Wireshark 只能看原始字节。

## 本项目解析这文件的要点

`.pklg` 不是 pcap，记录容器是：

```
[len u32][ts_sec u32][ts_usec u32][type u8][data…]
```

- `type` 决定 `data` 的语义（HCI 命令/ACL/SCO 事件等）；
- iAP2 帧在 ACL 里的形态是 `40 40 | len(u16 BE) | msgID(u16 BE) | params…`
  （`len` 只覆盖 msgID+params 之后的部分）；
- `ts` 存的是**本地时间当 UTC**，换算回本地要减时区；
- 参数按 `[len u16][id u16][value]` 或 TLV8（`<type><len><value>`）解析。

以上规则就是
[docs/NEGOTIATION-GAPS.md §10.1](NEGOTIATION-GAPS.md#101-数据来源与解析方式)
里那份对照表的生成方式。

## 隐私与合规

- `sysdiagnose` 包裹整个系统：**包含设备名、WiFi 历史、位置、进程列表、账号标识**。
  只把 `logs/Bluetooth/*.pklg` 取出分析，原包分析完就删。
- **`.tar.gz` 与 `.pklg` 一律不入库**（`.gitignore` 已排除 `*.pklg`、`sysdiagnose*`）。
- 写进文档/issue 的片段必须掩码：设备名、SSID、PSK、蓝牙 MAC、传输 UUID、
  车机标识串、IP 末段——本项目文档里这些一律是 `*`。
