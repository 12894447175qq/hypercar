package io.github.hypercar.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Shell-only export without switching Activities or altering an active car session. */
class LogExportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "io.github.hypercar.EXPORT_LOGS") return
        val pending = goAsync()
        try {
            HyperCarRepo.init(context)
            HyperCarRepo.log("LOG: 收到 ADB 文件导出请求，不切换界面或重建车载连接")
            HyperCarRepo.exportLogs { pending.finish() }
        } catch (error: Exception) {
            android.util.Log.e("HyperCar", "日志导出入口失败", error)
            pending.finish()
        }
    }
}
