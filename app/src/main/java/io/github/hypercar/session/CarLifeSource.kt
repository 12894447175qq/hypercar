package io.github.hypercar.session

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicBoolean

/** A source is captured for one connection. A vendor choice never falls back to another engine. */
data class CarLifeSource(val wakePackage: String, val enginePackage: String = wakePackage) {
    val activity: String? get() = enginePackage.takeIf { it in engines }?.let { "$it/com.baidu.carlife.CarlifeActivity" }
    val service: String? get() = enginePackage.takeIf { it in engines }?.let { "$it/com.baidu.carlife.service.CarlifeConnectService" }

    companion object {
        const val VIVO = "com.baidu.carlife.vivo"
        const val XIAOMI = "com.baidu.carlife.xiaomi"
        const val PUBLIC = "com.baidu.carlife"
        private val engines = setOf(PUBLIC, VIVO, XIAOMI)
        val candidates = listOf(PUBLIC, VIVO, XIAOMI, "com.miui.carlink", "com.xiaomi.carlink", "com.hihonor.carlink")
        fun resolve(configured: String, installed: (String) -> Boolean): CarLifeSource? {
            val pkg = configured.trim().ifBlank { candidates.firstOrNull(installed) ?: return null }
            if (!Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+").matches(pkg) || !installed(pkg)) return null
            val engine = if (pkg == "com.miui.carlink") XIAOMI else pkg
            return if (installed(engine)) CarLifeSource(pkg, engine) else null
        }
    }
}

/** Delayed service fallback belongs to this connection, including cancellation and cleanup. */
class CarLifeWakeSession(val source: CarLifeSource) {
    private val open = AtomicBoolean(true)
    private val used = AtomicBoolean(false)
    val isOpen: Boolean get() = open.get()
    val wasUsed: Boolean get() = used.get()
    fun markUsed() { if (isOpen) used.set(true) }
    fun close() { open.set(false) }

    suspend fun wake(exec: suspend (String) -> Unit, listening: suspend () -> Boolean) {
        currentCoroutineContext().ensureActive()
        if (!isOpen) return
        markUsed()
        val activity = source.activity
        if (activity == null) {
            exec("monkey -p ${source.wakePackage} -c android.intent.category.LAUNCHER 1")
            return
        }
        exec("am start -n $activity")
        delay(3_000)
        currentCoroutineContext().ensureActive()
        if (isOpen && !listening()) source.service?.let { exec("am start-foreground-service -n $it") }
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
