package io.github.hypercar.session

import kotlinx.coroutines.CompletableDeferred

/** Android callbacks can arrive after initialization, after loss, or after local close. */
internal class RendererBindingState(private val onLost: (String) -> Unit) {
    val ready = CompletableDeferred<Unit>()
    @Volatile var isActive = false
        private set
    @Volatile var isClosed = false
        private set

    @Synchronized fun connected(): Boolean {
        if (isClosed) return false
        isActive = true
        ready.complete(Unit)
        return true
    }
    fun unavailable(reason: String) {
        val notify = synchronized(this) {
            if (isClosed) return
            val wasActive = isActive
            isActive = false
            ready.completeExceptionally(IllegalStateException(reason))
            wasActive
        }
        if (notify) onLost(reason)
    }
    @Synchronized fun close(): Boolean {
        if (isClosed) return false
        isClosed = true
        isActive = false
        ready.cancel()
        return true
    }
}
