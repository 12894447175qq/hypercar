package io.github.hypercar.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * **调试用**广播入口：`adb shell am broadcast -a io.github.hypercar.PROBE_CAST -p io.github.hypercar`
 *
 * 触发一次 CarLife 投屏 AIDL 探测（register / getDisplayController / startDisplay），
 * 结果写进 `files/cast_probe.txt`（adb 可直接 cat），不用连车机、不依赖界面操作。
 */
class DebugProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching {
            HyperCarRepo.init(context)
            if (intent.action == "io.github.hypercar.PROBE_EXPERIMENT") {
                // 一键打开「CarLife 优先启动」实验并立刻连接，省得手动翻设置页
                io.github.hypercar.settings.SettingsStore.carlifeFirst = true
                HyperCarRepo.connect(context)
            } else if (intent.action == "io.github.hypercar.PROBE_CONNECT") {
                HyperCarRepo.connect(context)
            } else if (intent.action == "io.github.hypercar.PROBE_DISCONNECT") {
                HyperCarRepo.disconnect()
            } else {
                java.io.File(context.getExternalFilesDir(null), "cast_probe.txt").writeText("")
                HyperCarRepo.startCarLifeDisplayProbe()
            }
        }
    }
}
