package io.github.hypercar.autowake

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.hypercar.HyperCarApp
import io.github.hypercar.R
import io.github.hypercar.diagnostics.ConnectionTrace
import io.github.hypercar.session.HyperCarRepo

/** Connection-scoped foreground protection shared by manual, automatic and retry entrypoints. */
class HyperCarService : Service() {
    private val trace = ConnectionTrace("fgs", HyperCarRepo::log, android.os.SystemClock::elapsedRealtime)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private var activeTicket: Long? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ticket = intent?.getLongExtra(EXTRA_TICKET, 0L) ?: 0L
        trace.event("service.command", "action=${intent?.action} ticket=$ticket startId=$startId")
        if (intent?.action == ACTION_STOP) {
            if (activeTicket == ticket && leases.currentId() == null) stopProtection(startId)
            else trace.event("service.stale_stop", "ticket=$ticket active=$activeTicket requested=${leases.currentId()}")
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_KEEP_ALIVE || !leases.isCurrent(ticket)) {
            trace.event("service.stale_start", "ticket=$ticket requested=${leases.currentId()}")
            if (leases.currentId() == null) stopProtection(startId)
            return START_NOT_STICKY
        }
        activeTicket = ticket
        try {
            check(startForegroundSafe()) { "连接保活服务未能进入前台" }
            acquireLocks()
            isRunning = true
            if (!leases.ready(ticket) && leases.currentId() == null) stopProtection(startId)
        } catch (error: Exception) {
            trace.failure("service.start", error)
            leases.failed(ticket, error)
            stopProtection(startId)
        }
        // A restarted process must not hold locks without an owning connection.
        return START_NOT_STICKY
    }

    private fun stopProtection(startId: Int) {
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        releaseLocks()
        stopSelf(startId)
    }

    override fun onDestroy() {
        val ticket = activeTicket
        trace.event("service.destroy", "running=$isRunning ticket=$ticket")
        isRunning = false
        releaseLocks()
        if (ticket != null && leases.isCurrent(ticket)) {
            leases.failed(ticket, IllegalStateException("连接保活服务已停止"))
            HyperCarRepo.onKeepAliveLost(ticket)
        }
        super.onDestroy()
    }

    /** voice=0，由原厂 CarLife 使用手机麦克风；仅申请连接保活类型。 */
    private fun startForegroundSafe(): Boolean {
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        return try {
            trace.event("foreground.request", "types=$types microphoneRelay=false")
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), types)
            trace.event("foreground.promoted", "types=$types")
            true
        } catch (error: Throwable) {
            trace.failure("foreground.promote", error)
            Log.e(TAG, "连接保活服务未能进入前台，停止本轮启动", error)
            false
        }
    }
    /** 息屏/WiFi 保活锁：CPU 唤醒锁维持会话线程与心跳；WiFi 低延迟锁避免激进省电断流。 */
    private fun acquireLocks() {
        if (wakeLock?.isHeld == true && wifiLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HyperCar:session").apply {
            setReferenceCounted(false)
            acquire()
        }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "HyperCar:wifi").apply {
            setReferenceCounted(false)
            acquire()
        }
        trace.event("locks.acquired", "cpu=${wakeLock?.isHeld} wifi=${wifiLock?.isHeld}")
    }

    private fun releaseLocks() {
        trace.event("locks.release_requested", "cpu=${wakeLock?.isHeld} wifi=${wifiLock?.isHeld}")
        runCatching { wakeLock?.release() }.onFailure { trace.failure("locks.cpu.release", it) }
        trace.event("locks.cpu.after_release", "held=${wakeLock?.isHeld}")
        wakeLock = null
        runCatching { wifiLock?.release() }.onFailure { trace.failure("locks.wifi.release", it) }
        trace.event("locks.wifi.after_release", "held=${wifiLock?.isHeld}")
        wifiLock = null
    }

    /** 渠道已在 HyperCarApp 创建（CHANNEL_LINK + R.string.notification_channel_link）。 */
    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, HyperCarApp.CHANNEL_LINK)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.notification_link_title))
            .setContentText("正在保持与车机的连接")
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    companion object {
        private const val TAG = "HyperCarService"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_KEEP_ALIVE = "io.github.hypercar.action.KEEP_ALIVE"
        private const val ACTION_STOP = "io.github.hypercar.action.RELEASE_KEEP_ALIVE"
        private const val EXTRA_TICKET = "io.github.hypercar.extra.KEEP_ALIVE_TICKET"
        private val leases = ConnectionKeepAliveGate()

        @Volatile var isRunning: Boolean = false
            private set

        fun request(context: Context, retained: ConnectionKeepAliveGate.Ticket? = null): ConnectionKeepAliveGate.Ticket {
            if (retained != null && isRunning && leases.isCurrent(retained.id)) return retained
            val ticket = leases.begin()
            try {
                context.startForegroundService(Intent(context, HyperCarService::class.java)
                    .setAction(ACTION_KEEP_ALIVE).putExtra(EXTRA_TICKET, ticket.id))
            } catch (error: Exception) {
                leases.release(ticket.id)
                throw IllegalStateException("连接保活服务启动失败，请打开 Hypercar 后重试：${error.javaClass.simpleName}", error)
            }
            return ticket
        }

        fun release(context: Context, ticket: ConnectionKeepAliveGate.Ticket) {
            if (!leases.release(ticket.id) || !isRunning) return
            // The service validates both the stopped ticket and any newer request.
            runCatching {
                context.startService(Intent(context, HyperCarService::class.java)
                    .setAction(ACTION_STOP).putExtra(EXTRA_TICKET, ticket.id))
            }.onFailure { Log.e(TAG, "释放保活服务失败", it) }
        }
    }
}
