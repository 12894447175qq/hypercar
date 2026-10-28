package io.github.hypercar.autowake

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/** One foreground-service lease per connection; late Android callbacks cannot revive it. */
class ConnectionKeepAliveGate {
    class Ticket internal constructor(val id: Long) {
        val ready = CompletableDeferred<Unit>()
    }
    private var sequence = 0L
    private var active: Ticket? = null

    @Synchronized fun begin(): Ticket {
        active?.ready?.cancel(CancellationException("Connection superseded"))
        return Ticket(++sequence).also { active = it }
    }
    @Synchronized fun currentId(): Long? = active?.id
    @Synchronized fun isCurrent(id: Long): Boolean = active?.id == id
    @Synchronized fun ready(id: Long): Boolean {
        val ticket = active?.takeIf { it.id == id } ?: return false
        ticket.ready.complete(Unit)
        return true
    }
    @Synchronized fun failed(id: Long, cause: Throwable) {
        active?.takeIf { it.id == id }?.ready?.completeExceptionally(cause)
    }
    @Synchronized fun release(id: Long): Boolean {
        val ticket = active?.takeIf { it.id == id } ?: return false
        active = null
        ticket.ready.cancel(CancellationException("Connection ended"))
        return true
    }
}
