package io.github.hypercar.core

object ProjectLinks {
    const val REPOSITORY = "12894447175qq/hypercar"
    const val PROJECT = "https://github.com/$REPOSITORY"
    const val RELEASE_API = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    const val PROFILES = "https://raw.githubusercontent.com/$REPOSITORY/main/profiles/headunits.json"
    fun profilesUrl(configured: String): String = when (configured.trim()) {
        "", "https://raw.githubusercontent.com/fw867/hypercar/main/profiles/headunits.json" -> PROFILES
        else -> configured.trim()
    }
}
