package io.github.hypercar.net

/** iAP2 安全类型：0=open、1=WEP、2=WPA/WPA2、3=WPA3 过渡、4=WPA3。 */
object CarHotspotSecurity {
    enum class Mode(val shellName: String) { OPEN("open"), WEP("wep"), WPA2("wpa2"), WPA3("wpa3") }

    fun resolve(securityType: Int?, passphrase: String): Mode = when (securityType) {
        1 -> Mode.WEP
        2, 3 -> Mode.WPA2
        4 -> Mode.WPA3
        // 缺少类型的旧端点沿用有密码=WPA2；密码长度不能证明是 WEP。
        else -> if (passphrase.isEmpty()) Mode.OPEN else Mode.WPA2
    }
}
