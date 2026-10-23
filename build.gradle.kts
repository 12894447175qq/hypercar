// HyperCar — 顶层构建脚本
// 基于 fw867/cardeck 重构：砍掉 VirtualDisplay/EGL 双屏合成，
// CarLife(本机回环) → 零转码 → 宝马 AirPlay 直推；核心层 Rust。
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
