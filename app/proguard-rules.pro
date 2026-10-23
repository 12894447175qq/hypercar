# Compose + Shizuku + Kotlin metadata 兼容规则
-dontwarn org.jetbrains.annotations.**
-keep class kotlin.Metadata { *; }
-keep class dev.rikka.shizuku.** { *; }
