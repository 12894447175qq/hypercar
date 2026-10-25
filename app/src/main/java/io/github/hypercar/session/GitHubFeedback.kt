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
    const val REPO = "fw867/hypercar"

    /** `issues/new` 基础地址。 */
    const val NEW_ISSUE_URL = "https://github.com/$REPO/issues/new"

    /**
     * 预填 URL 的安全长度。GitHub 侧没有公开的硬限制，但 8KB 以上的 URL 在部分浏览器/
     * WebView/GitHub App 里会被截断或拒绝，这里留足余量。
     */
    private const val MAX_URL_CHARS = 7_600

    /** 准备好的一次反馈。 */
    data class Prepared(
        /** 可直接用浏览器打开的预填 Issue 地址。 */
        val url: String,
        /** 复制到剪贴板的内容（完整日志 + 环境块）。 */
        val clipboard: String,
        /** 预填正文里包含了多少行日志（其余在剪贴板里）。 */
        val prefilledLines: Int,
        /** 预填正文是否因长度限制截断过日志。 */
        val truncated: Boolean,
    )

    /**
     * 组装反馈内容：标题、环境块、日志尾部、剪贴板全文。
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
        val title = "[反馈] ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · $car · HyperCar $version"
            .replace('\n', ' ')
            .take(120)

        val header =
            buildString {
                appendLine("### 环境")
                appendLine("- HyperCar：$version")
                appendLine("- 设备：${DeviceInfo.systemSummary()}")
                appendLine("- 目标车机：$car（档案 ${profile.id}）")
                appendLine()
                appendLine("### 发生了什么")
                appendLine(
                    "<!-- 一句话说明：连不上 / CarPlay 图标不出现 / 黑屏 / 触摸不准 / 没声音 / 其他；" +
                        "什么时候开始的、能否复现、车机上有什么提示 -->",
                )
                appendLine()
                appendLine("### 日志（尾部）")
                appendLine("```text")
            }
        val footer =
            "\n```\n\n" +
                "> 剪贴板里是**完整日志**（开头还有一行环境自述）：在正文末尾粘贴一次即可。\n" +
                "> 也可以在「诊断」页点「导出」，把 `下载/HyperCar/*.txt` 直接拖进本 Issue 当附件。\n" +
                "> ⚠ 日志里可能含车机名称 / 热点 SSID / 蓝牙 MAC，介意可先自行打码；" +
                "提交前请留意不要带上私人信息。"

        // 按 URL 预算挑日志：从最新一行往前取，直到再放一行就超长。
        val budgetChars = MAX_URL_CHARS - (NEW_ISSUE_URL.length + encode(title).length + encode(header).length + encode(footer).length + 64)
        var used = 0
        val picked = ArrayList<String>()
        for (index in logs.indices.reversed()) {
            val line = logs[index]
            val cost = encode(line).length + 3 // '\n' 与保险
            if (used + cost > budgetChars) break
            picked.add(0, line)
            used += cost
        }
        val truncated = picked.size < logs.size

        val body = header + picked.joinToString("\n") + footer
        val url = "$NEW_ISSUE_URL?title=${encode(title)}&body=${encode(body)}"

        val clipboard =
            buildString {
                appendLine(DeviceInfo.fullSummary(context))
                appendLine("目标车机：$car（档案 ${profile.id}）")
                appendLine("—".repeat(24))
                logs.forEach { appendLine(it) }
            }

        return Prepared(url = url, clipboard = clipboard, prefilledLines = picked.size, truncated = truncated)
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

    private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8")
}
