package io.github.hypercar.iap2

import java.io.Closeable

/** RFCOMM discovery bootstraps Wi-Fi; it must not restart beside an activated AirPlay session. */
internal class BluetoothBootstrapGate {
    private var accepting = true
    private var pending: Closeable? = null

    val isOpen: Boolean get() = synchronized(this) { accepting }

    @Synchronized fun register(socket: Closeable): Boolean {
        if (!accepting || pending != null) return false
        pending = socket
        return true
    }

    @Synchronized fun release(socket: Closeable) {
        if (pending === socket) pending = null
    }

    /** Only short, nonblocking ownership updates belong here; never socket.connect/read/write. */
    @Synchronized fun accept(claim: () -> Boolean): Boolean = accepting && claim()

    /** Closes an in-flight dial, but deliberately leaves the established iAP2 channel alive. */
    fun finish() {
        val old = synchronized(this) {
            accepting = false
            pending.also { pending = null }
        }
        old?.let { runCatching { it.close() } }
    }

    fun cancelPending() {
        val old = synchronized(this) { pending.also { pending = null } }
        old?.let { runCatching { it.close() } }
    }
}
