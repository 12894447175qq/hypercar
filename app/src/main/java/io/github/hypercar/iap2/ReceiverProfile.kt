package io.github.hypercar.iap2

/**
 * 车机画像（目前只收口「WiFi 凭据交换代际」wifiGen）。
 *
 * 来源与依据：cardeck 0.1.14（commit cbaffc4，2026-10-22 奥迪 MMI 3315 真车定谳）——
 *
 *  - **清单只能证明「有」，不能证明「没有」**：奥迪 3315 的可收清单只有 5 项、不含
 *    `0x5702`，但它实际完整支持 `0x5702 → 0x5703`，而且**要靠收到 0x5702 才把 AirPlay
 *    服务拉起来**（按「清单没声明就跳过」的那版：0 次 0x5702 → 端口 7000 全程
 *    ECONNREFUSED、`_airplay._tcp` 不广播、4 轮会话全灭）。
 *  - 只有「两清单都完整（合计 ≥10 项）却仍无 0x57xx」这一种形态（BMW iD9）才判 NEW、
 *    跳过 0x5702；清单稀疏（LEGACY/UNKNOWN）**一律发**——车机对未声明消息照常处理
 *    （同日日志里 0x4E0D/0x4E0B/0x4E0E 三条不在清单里，车机逐条 ACK 到位），
 *    catplay / xcertplay / amineross-showcase / wiomoc-iap2 四个车机实现都把 0x5702
 *    列进无线清单。
 */
object ReceiverProfile {
    /** WiFi 凭据交换协议代际。 */
    enum class WifiGen { LEGACY, NEW, UNKNOWN }

    /**
     * 纯函数：按 0x1D01 的收/发消息清单判定凭据交换代际。
     *
     * @param sendIds 车机声明「会发送」的消息（null = 未提供清单）
     * @param receiveIds 车机声明「可接收」的消息（null = 未提供清单）
     */
    fun classifyWifiGen(
        sendIds: Set<Int>?,
        receiveIds: Set<Int>?,
    ): WifiGen =
        when {
            sendIds == null && receiveIds == null -> WifiGen.UNKNOWN

            sendIds?.contains(Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION) == true ||
                receiveIds?.contains(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION) == true -> WifiGen.LEGACY

            // 清单齐且相当完整仍无 0x57xx = BMW iD9 形态（两清单均 10+ 项）
            (sendIds?.size ?: 0) + (receiveIds?.size ?: 0) >= 10 -> WifiGen.NEW

            // 稀疏清单不构成「新协议」证据：保守按旧协议（0x5703 有超时兜底）
            else -> WifiGen.UNKNOWN
        }

    fun describe(gen: WifiGen): String =
        when (gen) {
            WifiGen.LEGACY -> "LEGACY（清单明写 0x5702/0x5703）"
            WifiGen.NEW -> "NEW（清单完整但无 0x57xx，BMW iD9 形态）——跳过 0x5702"
            WifiGen.UNKNOWN -> "UNKNOWN（清单稀疏/未提供）——仍发 0x5702（清单不完整不能反证不支持）"
        }
}
