package io.github.hypercar.headunit

import io.github.hypercar.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 远程车机档案：从 GitHub 拉一份 JSON，**新增车型不用发版**。
 *
 * 为什么需要：内置档案（BMW / WinPlay / Audi）改一次就得重新出包、装 APK。
 * 但车机档案例行是"小改一个布尔"——让它走远程配置文件，用户点一下就能拿到新车型，
 * 我这边改完 JSON 直接 push 即可。
 *
 * 设计取舍：
 *  - **覆盖语义**：远程同 id 的档案会**覆盖**内置的（修内置的错不用发版）；
 *    远程独有的 id 追加在后面。内置档案永远存在，断网也不会"一个都没有"。
 *  - **缓存优先**：上次拉到的 JSON 存 [SettingsStore]，启动时直接用它，联网失败不影响可用性。
 *  - **无依赖**：用 `HttpURLConnection` + `org.json`（都是系统自带），不引 OkHttp。
 *
 * 文件格式见仓库 `profiles/headunits.json`，字段与 [HeadUnitProfile] 一一对应，
 * 除 `id/label` 外都可省略（省略即取默认值 = 真 iPhone 实测行为）。
 */
object RemoteProfiles {

    /** 档案列表地址（raw.githubusercontent.com，改 `main` 为其它分支即可灰度）。 */
    const val DEFAULT_URL =
        "https://raw.githubusercontent.com/fw867/hypercar/main/profiles/headunits.json"

    /** 设置页可改下载源（自建镜像/私有分支），空则用 [DEFAULT_URL]。 */
    fun url(): String =
        SettingsStore.remoteProfilesUrl.ifBlank { DEFAULT_URL }

    /**
     * 解析结果缓存：设置页每次重组都会调 [cached]，重复 parse 几百字节
     * 虽然不贵，但没必要——按原始 JSON 串做一次记忆化。
     */
    private var memoRaw: String? = null
    private var memoList: List<HeadUnitProfile> = emptyList()

    /** 上次拉取到的远程档案（解析失败时返回空表，不抛异常）。 */
    fun cached(): List<HeadUnitProfile> {
        val raw = SettingsStore.remoteHeadUnitProfilesJson
        if (raw.isBlank()) return emptyList()
        if (raw == memoRaw) return memoList
        val parsed = runCatching { parse(raw) }.getOrDefault(emptyList())
        memoRaw = raw
        memoList = parsed
        return parsed
    }

    /** 远程档案里是否有这个 id（用于 UI 打「远程」角标）。 */
    fun isRemote(id: String): Boolean = cached().any { it.id == id }

    /**
     * 把内置表与远程表合并：内置保持原顺序（远程可覆盖同 id），远程独有项追加。
     */
    fun merge(builtin: List<HeadUnitProfile>, remote: List<HeadUnitProfile>): List<HeadUnitProfile> {
        if (remote.isEmpty()) return builtin
        val remoteById = remote.associateBy { it.id }
        val merged = builtin.map { remoteById[it.id] ?: it }
        val builtinIds = builtin.map { it.id }.toSet()
        return merged + remote.filter { it.id !in builtinIds }
    }

    /** 从 [url] 拉取并落盘；返回本次拿到的档案数（失败抛异常，由调用方展示）。 */
    suspend fun refresh(): Int = withContext(Dispatchers.IO) {
        val body = httpGet(url())
        // 先解析再落盘：解析不了就保持旧缓存，避免把设置页搞成空列表
        val list = parse(body)
        require(list.isNotEmpty()) { "配置里没有任何档案" }
        SettingsStore.remoteHeadUnitProfilesJson = body
        SettingsStore.remoteProfilesFetchedAt = System.currentTimeMillis()
        memoRaw = null // 让 cached() 重新解析
        list.size
    }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ---------------------------------------------------------------- 序列化

    /**
     * 兼容两种顶层形态：`[ {...}, {...} ]` 或 `{ "profiles": [ ... ] }`
     * （带 `version`/`updated` 之类元信息时用后者更自然）。
     */
    fun parse(raw: String): List<HeadUnitProfile> {
        val trimmed = raw.trim()
        val array = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> JSONObject(trimmed).optJSONArray("profiles") ?: JSONArray()
        }
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let { readProfile(it) }
        }
    }

    private fun readProfile(o: JSONObject): HeadUnitProfile? {
        val id = o.optString("id").trim()
        if (id.isEmpty()) return null
        val defaults = HeadUnitProfiles.BMW_ID85 // 只借默认值，不借身份
        return HeadUnitProfile(
            id = id,
            label = o.optString("label").ifBlank { id },
            model = o.optString("model", ""),
            notes = o.optString("notes", ""),
            verified = o.optBoolean("verified", false),
            validatesDeviceId = o.optBoolean("validatesDeviceId", defaults.validatesDeviceId),
            sendCarPlayAvailability =
                o.optBoolean("sendCarPlayAvailability", defaults.sendCarPlayAvailability),
            openEaSessions = o.optBoolean("openEaSessions", defaults.openEaSessions),
            sendTransportIdentifier =
                o.optBoolean("sendTransportIdentifier", defaults.sendTransportIdentifier),
            replyPowerUpdates = o.optBoolean("replyPowerUpdates", defaults.replyPowerUpdates),
            preferMdnsAirplay = o.optBoolean("preferMdnsAirplay", defaults.preferMdnsAirplay),
            fallbackAirplayPorts = readPorts(o, defaults.fallbackAirplayPorts),
            touchDisplay = o.optString("touchDisplay", "").ifBlank { defaults.touchDisplay },
            keepIap2AliveOnly = o.optBoolean("keepIap2AliveOnly", defaults.keepIap2AliveOnly),
            pairingHint = o.optString("pairingHint", "").ifBlank { null },
        )
    }

    private fun readPorts(o: JSONObject, fallback: List<Int>): List<Int> {
        val arr = o.optJSONArray("fallbackAirplayPorts") ?: return fallback
        val ports = (0 until arr.length()).map { arr.optInt(it) }.filter { it in 1..65535 }
        return ports.ifEmpty { fallback }
    }

    /** 把档案写回 JSON（导出/调试用；也方便「复制当前配置到剪贴板」这类扩展）。 */
    fun encode(list: List<HeadUnitProfile>): String {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("label", p.label)
                    put("model", p.model)
                    put("notes", p.notes)
                    put("verified", p.verified)
                    put("validatesDeviceId", p.validatesDeviceId)
                    put("sendCarPlayAvailability", p.sendCarPlayAvailability)
                    put("openEaSessions", p.openEaSessions)
                    put("sendTransportIdentifier", p.sendTransportIdentifier)
                    put("replyPowerUpdates", p.replyPowerUpdates)
                    put("preferMdnsAirplay", p.preferMdnsAirplay)
                    put("fallbackAirplayPorts", JSONArray(p.fallbackAirplayPorts))
                    p.touchDisplay?.let { put("touchDisplay", it) }
                    put("keepIap2AliveOnly", p.keepIap2AliveOnly)
                    p.pairingHint?.let { put("pairingHint", it) }
                },
            )
        }
        return JSONObject().put("profiles", arr).toString(2)
    }
}
