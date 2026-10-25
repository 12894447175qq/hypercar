<#
.SYNOPSIS
  抓「AirPlay 发送端 → 本机接收端（WinPlay）」的完整交互，零安装（用系统自带 pktmon）。

.DESCRIPTION
  为什么要这个：手机 App 与「CarPlay 盒子」都是 AirPlay **发送端**。
  让盒子把画面推给本机 WinPlay，就能在 PC 侧完整看到它怎么谈：
  pair-verify、/info、SETUP、RTP 心跳……这些正是我们和车机之间卡住的那部分。

  为什么用 pktmon 而不是 Wireshark：系统自带，不需要装 Npcap。
  pktmon 产出 .etl，脚本会顺手转成 .pcapng（Wireshark 直接打开）。

  抓什么：TCP 7000/7001/7100（AirPlay 控制面）+ UDP 5353（mDNS）。

.PARAMETER Seconds
  抓多少秒（默认 120）。期间请在盒子上操作：让它开始向接收端推流。

.PARAMETER OutDir
  输出目录，默认 %LOCALAPPDATA%\hypercar-hub\captures。

.EXAMPLE
  # 必须「以管理员身份」运行 PowerShell
  powershell -NoProfile -File tools\capture-airplay.ps1 -Seconds 90
#>
param(
  [int]$Seconds = 120,
  [string]$OutDir = '',
  # 不过滤、抓全部流量（截断到 128 字节/包）。第一次抓盒子交互建议开这个：
  # 我们不知道它到底往哪个端口连，也不知道它是不是靠 mDNS 找接收端。
  [switch]$All,
  # 只抓与某个 IP 的全部流量（盒子 IP），**不截断**——这样请求头/体都完整。
  # 例：-BoxIp 192.168.137.188
  [string]$BoxIp = ''
)

$ErrorActionPreference = 'Stop'

function Write-Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }

# pktmon 要和内核驱动通信，普通用户会直接「拒绝访问」
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
  Write-Host '✗ 需要管理员权限：pktmon 要和 PktMon 驱动通信，普通用户会「拒绝访问」。' -ForegroundColor Red
  Write-Host '  右键 PowerShell → 以管理员身份运行，再执行本脚本。' -ForegroundColor Yellow
  exit 1
}

if (-not $OutDir) {
  $OutDir = Join-Path $env:LOCALAPPDATA 'hypercar-hub\captures'
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$etl = Join-Path $OutDir "airplay-$stamp.etl"
$pcap = Join-Path $OutDir "airplay-$stamp.pcapng"

Write-Step '清掉旧过滤器（避免上次的残留）'
& pktmon filter remove | Out-Null

if ($BoxIp) {
  Write-Step "只抓 $BoxIp 的全部流量（不截断，头部完整）"
  & pktmon filter add "box-ip" -i $BoxIp | Out-Null
  & pktmon filter list
} elseif ($All) {
  Write-Step '不过滤：抓全部流量（每包截断 128 字节）'
} else {
  Write-Step '配置过滤器：AirPlay 控制面 + mDNS'
  foreach ($port in 7000, 7001, 7100) {
    & pktmon filter add "airplay-$port" -t TCP -p $port | Out-Null
  }
  & pktmon filter add 'mdns-5353' -t UDP -p 5353 | Out-Null
  & pktmon filter list
}

Write-Step "开始抓包 ${Seconds}s → $etl"
Write-Host '   现在去盒子上操作：让它连上接收端 / 开始推流。' -ForegroundColor Yellow
if ($All -and -not $BoxIp) { & pktmon start --capture --pkt-size 128 --file $etl | Out-Null } else { & pktmon start --capture --pkt-size 0 --file $etl | Out-Null }

Start-Sleep -Seconds $Seconds

Write-Step '停止抓包'
& pktmon stop | Out-Null

if (Test-Path $etl) {
  Write-Step '转成 pcapng（Wireshark 可直接打开）'
  & pktmon etl2pcap $etl -o $pcap | Out-Null
}

Write-Step '清理过滤器'
& pktmon filter remove | Out-Null

if (Test-Path $pcap) {
  $size = (Get-Item $pcap).Length
  Write-Host "✓ 抓包完成：$pcap（$([math]::Round($size/1KB,1)) KB）" -ForegroundColor Green
  Write-Host ''
  Write-Host 'Wireshark 里值得先看的过滤器：' -ForegroundColor Cyan
  Write-Host '  tcp.port == 7000            # AirPlay 控制面（pair-verify / SETUP / RECORD）'
  Write-Host '  rtsp                        # RTSP 请求与响应'
  Write-Host '  http.request                # 它有没有先 GET/POST /info（我们卡过 455）'
  Write-Host '  dns || mdns                 # 它是靠 Bonjour 找接收端，还是写死 IP'
  Write-Host '  tcp.flags.syn == 1          # 它到底往哪个 IP:端口 发起连接（最关键的线索）'
} else {
  Write-Host "抓包文件没生成，检查 pktmon 是否可用：pktmon filter list" -ForegroundColor Red
  exit 1
}
