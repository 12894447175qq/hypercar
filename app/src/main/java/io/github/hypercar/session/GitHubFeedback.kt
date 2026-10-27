package io.github.hypercar.session

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.hypercar.headunit.HeadUnitProfiles
import io.github.hypercar.settings.SettingsStore
import java.net.URLEncoder

/**
 * 「一键反馈到 GitHub Issues」的纯逻辑部分。
 *
 * 设计取舍（**不内嵌任何 token**）：
 *  - 用 GitHub 的 **新建 Issue 预填 URL**（`/issues/new?title=…&body=…`）+ 系统浏览器打开，
 *    零鉴权、零后端，APK 里不会有任何可被反编译的写权限凭据；
 *  - 日志太长塞不进 URL（GitHub 与浏览器对 query 长度都有上限），所以：
 *    正文只带**环境块 + 最近若干行**（按 URL 预算动态截断），**完整日志复制进剪贴板**，
 *    用户在正文里粘贴一下就补全；
 *  - 同时把「导出到 下载/HyperCar/」的动作留给调用方先做一遍，Issue 里可以再拖文件附件。
 *
 * 若以后要"真正无人值守"（连浏览器都不点），只能二选一：让用户自己填一个只读仓库的
 * fine-grained token（存本机、走 REST `/issues`），或者搭一个像 cardeck-bridge 那样
 * 的中转服务。两者都超出"零配置一键反馈"，先不做。
 */
object GitHubFeedback {

    /** 反馈目标仓库。 */
    const val REPO = io.github.hypercar.core.ProjectLinks.REPOSITORY

    /** `issues/new` 基础地址。 */
    const val NEW_ISSUE_URL = "https://github.com/$REPO/issues/new"

    /**
     * 预填 URL 的安全长度。GitHub 侧没有公开的硬限制，但 8KB 以上的 URL 在部分浏览器/
     * WebView/GitHub App 里会被截断或拒绝，这里留足余量。
     */
    /**
     * 预填 URL 的安全长度。
     *
     * **实测（2026-10-25，`Invoke-WebRequest` 对 `…/issues/new?title=…&body=…` 探边）**：
     * URL ≈ 6.9k 字符仍然正常（302 到登录页），**≈ 7.1k 起 GitHub 直接 500**
     * （就是页面上那句 "Looks like something went wrong!"）。
     * 而且未登录时会先跳 `login?return_to=<整条 URL 再做一次百分号编码>`，等于翻倍；
     * 所以这里压到 **2.2k**（登录往返后 ~4.4k，仍远低于 7k），正文只留环境与现象，
     * 完整日志走剪贴板 + 导出的 .txt 附件。
     */
    private const val MAX_URL_CHARS = 2_200

    /** 准备好的一次反馈。 */
    data class Prepared(
        /** 可直接用浏览器打开的预填 Issue 地址。 */
        val url: String,
        /** 复制到剪贴板的内容（完整日志 + 环境块）。 */
        val clipboard: String,
        /** 预填 URL 的长度（排查"又 500 了"时能直接看出来）。 */
        val urlChars: Int,
    )

    /**
     * 组装反馈内容：标题、环境块、剪贴板全文。
     *
     * **正文里不放日志**：一条中文日志行 URL 编码后要 ~500 字符，塞两行就把预算吃光，
     * 而且 7k 以上直接 500。日志走剪贴板 + 导出的 .txt 附件，正文只留环境与现象。
     *
     * @param logs 诊断页当前持有的日志（**旧 → 新**顺序，与页面显示相反）。
     */
    fun prepare(
        context: Context,
        logs: List<String>,
    ): Prepared {
        val profile = HeadUnitProfiles.current()
        val car = SettingsStore.targetCarName?.takeIf { it.isNotBlank() } ?: profile.label
        val version = DeviceInfo.appVersion(context)
        val title = "[反馈] ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · $car · $version"
            .replace('\n', ' ')
            .take(100)

        // 正文刻意做"短"：环境三行 + 现象占位 + 日志说明，约 300 字符。
        val body =
            buildString {
                append("**环境**\n")
                append("- HyperCar：$version\n")
                append("- 设备：${DeviceInfo.systemSummary()}\n")
                append("- 车机：$car（档案 ${profile.id}）\n\n")
                append("**现象**：<!-- 一句话：连不上 / 图标不出现 / 黑屏 / 触摸 / 声音 -->\n\n")
                append("**日志**：完整日志已在剪贴板（点正文末尾粘贴），也可把「导出」生成的 .txt 拖进来。\n\n")
                append("> 日志可能含车机名/SSID/蓝牙 MAC，介意可自行打码。")
            }
        val fullUrl = "$NEW_ISSUE_URL?title=${encode(title)}&body=${encode(body)}"
        // 防御：自定义 ROM 的 Build.DISPLAY/INCREMENTAL 可能特别长，超过预算就退化成最短正文
        // （GitHub 在 ~7.1k 会 500，这里宁可少写环境，也不能让按钮点了报错）。
        val url =
            if (fullUrl.length <= MAX_URL_CHARS) {
                fullUrl
            } else {
                val compact =
                    "**环境**\n- HyperCar：$version\n" +
                        "- 设备：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}" +
                        "｜Android ${android.os.Build.VERSION.RELEASE}（SDK ${android.os.Build.VERSION.SDK_INT}）\n" +
                        "- 车机：$car（档案 ${profile.id}）\n\n" +
                        "**日志**：完整日志已在剪贴板，粘贴在正文末尾（也可拖入导出的 .txt）。"
                "$NEW_ISSUE_URL?title=${encode(title)}&body=${encode(compact)}"
            }

        val clipboard =
            buildString {
                appendLine(DeviceInfo.fullSummary(context))
                appendLine("目标车机：$car（档案 ${profile.id}）")
                appendLine("—".repeat(24))
                logs.forEach { appendLine(it) }
            }

        return Prepared(url = url, clipboard = clipboard, urlChars = url.length)
    }

    /** 用系统浏览器/ GitHub App 打开预填地址；返回是否成功拉起（没有可用应用时为 false）。 */
    fun open(
        context: Context,
        url: String,
    ): Boolean =
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess

    /**
     * 百分号编码。空格用 `%20` 而不是 `+`：`+` 在 query 里虽然等价于空格，
     * 但 GitHub 预填/部分浏览器会把 `+` 原样显示在正文里（预填后满屏加号）。
     */
    private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
}
