package io.github.hypercar.session

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicBoolean

/** A source is captured for one connection. A vendor choice never falls back to another engine. */
data class CarLifeSource(val wakePackage: String, val enginePackage: String = wakePackage) {
    val activity: String? get() = enginePackage.takeIf { it in engines }?.let { "$it/com.baidu.carlife.CarlifeActivity" }
    val service: String? get() = enginePackage.takeIf { it in engines }?.let { "$it/com.baidu.carlife.service.CarlifeConnectService" }
    val usesBoundRenderer: Boolean get() = enginePackage == VIVO

    /** Jovi's complete CarLife boot flow requires these source permissions. */
    fun missingRendererPermissions(granted: (String) -> Boolean): List<String> =
        if (usesBoundRenderer) listOf(FINE_LOCATION, BACKGROUND_LOCATION).filterNot(granted) else emptyList()

    companion object {
        const val VIVO = "com.baidu.carlife.vivo"
        const val XIAOMI = "com.baidu.carlife.xiaomi"
        const val PUBLIC = "com.baidu.carlife"
        const val JOVI = "com.vivo.car.networking"
        const val JOVI_CAST_SERVICE = "com.vivo.vivo_car_service.CarLifeMixingService"
        const val FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
        const val BACKGROUND_LOCATION = "android.permission.ACCESS_BACKGROUND_LOCATION"
        private val engines = setOf(PUBLIC, VIVO, XIAOMI)
        val candidates = listOf(PUBLIC, VIVO, XIAOMI, "com.miui.carlink", "com.xiaomi.carlink", "com.hihonor.carlink")
        fun resolve(configured: String, installed: (String) -> Boolean): CarLifeSource? {
            val pkg = configured.trim()
            if (pkg.isBlank()) return candidates.firstNotNullOfOrNull { resolve(it, installed) }
            if (!Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+").matches(pkg) || !installed(pkg)) return null
            val engine = if (pkg == "com.miui.carlink") XIAOMI else pkg
            if (engine == VIVO && !installed(JOVI)) return null
            return if (installed(engine)) CarLifeSource(pkg, engine) else null
        }
    }
}

/** A real renderer connection, owned and released by exactly one session. */
interface CarLifeRendererLease : AutoCloseable {
    val isActive: Boolean
}

/** A cold vendor component can require its own Activity to initialize before binding. */
internal suspend fun acquireVivoRenderer(
    acquire: suspend () -> CarLifeRendererLease?,
    bootstrap: suspend () -> Unit,
): CarLifeRendererLease {
    currentCoroutineContext().ensureActive()
    acquire()?.let { return it }
    currentCoroutineContext().ensureActive()
    bootstrap()
    // The original SDK ignores connectStart calls less than three seconds apart.
    delay(3_100)
    currentCoroutineContext().ensureActive()
    return checkNotNull(acquire()) {
        "系统仍未接受 vivo CarLife 服务绑定；请先打开来源应用完成初始化，并检查系统的应用启动限制"
    }
}

/** Delayed service fallback belongs to this connection, including cancellation and cleanup. */
class CarLifeWakeSession(val source: CarLifeSource) {
    private val open = AtomicBoolean(true)
    private val used = AtomicBoolean(false)
    private val rendererLock = Any()
    private var renderer: CarLifeRendererLease? = null
    val isOpen: Boolean get() = open.get()
    val wasUsed: Boolean get() = used.get()
    val hasRenderer: Boolean get() = synchronized(rendererLock) { renderer?.isActive == true }
    fun markUsed() { if (isOpen) used.set(true) }
    fun close() {
        synchronized(rendererLock) { open.set(false) }
        releaseRenderer()
    }

    fun releaseRenderer() {
        val old = synchronized(rendererLock) { renderer.also { renderer = null } }
        old?.close()
    }

    suspend fun bindRenderer(acquire: suspend () -> CarLifeRendererLease?): Boolean {
        currentCoroutineContext().ensureActive()
        if (!isOpen || !source.usesBoundRenderer) return false
        if (hasRenderer) return true
        releaseRenderer()
        val lease = acquire() ?: return false
        var retained = false
        try {
            currentCoroutineContext().ensureActive()
            synchronized(rendererLock) {
                if (isOpen && renderer == null) {
                    renderer = lease
                    used.set(true)
                    retained = true
                }
            }
            return isOpen && hasRenderer
        } finally {
            // Includes cancellation or close while bindService was completing.
            if (!retained) lease.close()
        }
    }

    suspend fun wake(exec: suspend (String) -> Unit, listening: suspend () -> Boolean) {
        currentCoroutineContext().ensureActive()
        if (!isOpen) return
        check(!source.usesBoundRenderer) { "vivo CarLife requires a bound renderer, not an Activity/FGS shell launch" }
        markUsed()
        val activity = source.activity
        if (activity == null) {
            exec("monkey -p ${source.wakePackage} -c android.intent.category.LAUNCHER 1")
            return
        }
        exec("am start -n $activity")
        delay(3_000)
        currentCoroutineContext().ensureActive()
        if (!isOpen) return
        if (!listening()) source.service?.let { exec("am start-foreground-service -n $it") }
    }
}

/** /proc/net/tcp{,6}: inspect LISTEN UID without consuming CarLife's single accept(). */
object CarLifePorts {
    data class Listener(val port: Int, val uid: Int, val address: String)
    fun parse(table: String): List<Listener> = table.lineSequence().mapNotNull { line ->
        val cols = line.trim().split(Regex("\\s+"))
        if (cols.size < 8 || cols[3] != "0A") return@mapNotNull null
        val port = cols[1].substringAfterLast(':').toIntOrNull(16) ?: return@mapNotNull null
        val uid = cols[7].toIntOrNull() ?: return@mapNotNull null
        Listener(port, uid, cols[1].substringBefore(':'))
    }.toList()

    fun owned(listeners: List<Listener>, port: Int, expectedUid: Int): Boolean {
        // Accept only wildcard/loopback listeners. A listener on another interface cannot serve 127.0.0.1.
        val local = listeners.filter { it.port == port && it.address in setOf(
            "00000000", "0100007F", "00000000000000000000000000000000", "0000000000000000FFFF00000100007F",
        ) }
        return local.isNotEmpty() && local.all { it.uid == expectedUid }
    }
}
