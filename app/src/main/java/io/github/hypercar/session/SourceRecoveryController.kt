package io.github.hypercar.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/** Bounded source-loss recovery; shutdown is complete before any replacement is started. */
internal class SourceRecoveryController(private val scope: CoroutineScope) {
    @Volatile private var pending: Job? = null
    private val attempts = AtomicInteger()
    fun cancel() { pending?.cancel(); pending = null }
    fun reset() { cancel(); attempts.set(0) }

    @Synchronized fun lost(
        isCurrent: () -> Boolean,
        sourceActive: () -> Boolean,
        stillWanted: () -> Boolean,
        stop: (retainKeepAlive: Boolean) -> Unit,
        restart: () -> Unit,
        log: (String, String) -> Unit,
    ) {
        if (pending?.isActive == true) return
        pending = scope.launch {
            delay(250)
            if (!isCurrent() || sourceActive()) return@launch
            // stop() also calls cancel(); detach this worker before shutting down old sockets.
            pending = null
            val attempt = attempts.incrementAndGet()
            val retry = attempt <= 3
            log("begin", "attempt=$attempt restartWholeSession=true revalidatePorts=true")
            stop(retry)
            if (!retry || !stillWanted()) {
                log("stopped", "attempt=$attempt retry=$retry")
                return@launch
            }
            pending = scope.launch {
                delay(listOf(3_100L, 5_000L, 8_000L)[attempt - 1])
                if (stillWanted()) {
                    log("restart", "attempt=$attempt")
                    restart()
                }
            }
        }
    }
}
