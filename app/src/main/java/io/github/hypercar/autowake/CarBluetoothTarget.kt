package io.github.hypercar.autowake

/** An explicit target always wins; fallback names follow the selected car profile. */
object CarBluetoothTarget {
    fun keywords(profile: String): List<String> = when (profile) {
        "audi_mib3" -> listOf("Audi", "奥迪")
        else -> listOf("BMW", "MINI", "宝马")
    }
    fun matches(address: String?, name: String?, target: String?, profile: String): Boolean {
        if (address.isNullOrBlank() || address.equals("02:00:00:00:00:00", true)) return false
        if (!target.isNullOrBlank()) return address.equals(target, true)
        return name != null && keywords(profile).any { name.contains(it, true) }
    }
}
