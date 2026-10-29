package io.github.hypercar.session

internal object VideoPolicySelection {
    fun resolve(saved:String?,profile:String,source:String):String = saved
        ?: if(profile=="audi_mib3" && source==CarLifeSource.VIVO) "level40_inline" else "original"
}
