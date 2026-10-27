package io.github.hypercar.net

/** Do not adopt a home Wi-Fi / Apple TV merely because it exposes AirPlay. */
object CarHotspotPolicy {
    fun matches(ssid: String?, targetName: String?, negotiatedSsid: String?, trustedSsid: String?): Boolean {
        if (ssid.isNullOrBlank() || ssid == "<unknown ssid>") return false
        if (ssid == negotiatedSsid || ssid == trustedSsid) return true
        val name = targetName?.takeIf { it.isNotBlank() } ?: return false
        return ssid in setOf(name, "${name}_5GHz", "${name}_2.4GHz")
    }
}
