package io.github.hypercar.diagnostics

import java.util.concurrent.atomic.AtomicLong
import java.util.UUID

/** Correlates low-frequency milestones; sampled detail is built only when it will be logged. */
internal class ConnectionTrace(
    kind: String,
    private val sink: (String) -> Unit,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val parent: String? = null,
) {
    val id = "$kind-$processTag-${sequence.incrementAndGet()}"
    private val started = clock()
    private val samples = HashMap<String, Long>()

    fun decorate(message: String): String =
        "TRACE[$id${parent?.let { " parent=$it" }.orEmpty()}] +${(clock() - started).coerceAtLeast(0)}ms $message"

    fun event(stage: String, detail: String = "") {
        sink(decorate("$stage ${LogSanitizer.clean(detail)}".trimEnd()))
    }

    fun sample(stage: String, intervalMs: Long = 5_000, detail: () -> String) {
        val now = clock()
        val due = synchronized(samples) {
            val last = samples[stage]
            if (last == null || now < last || now - last >= intervalMs) {
                samples[stage] = now
                true
            } else false
        }
        if (due) event(stage, detail())
    }

    fun failure(stage: String, error: Throwable) {
        event("$stage.error", "type=${error.javaClass.name} message=${error.message}")
        val lines = error.stackTraceToString().lineSequence().toList()
        lines.take(128).forEach { event("$stage.stack", it) }
        if (lines.size > 128) event("$stage.stack", "[truncated ${lines.size - 128} additional lines]")
    }

    companion object {
        private val sequence = AtomicLong()
        private val processTag = UUID.randomUUID().toString().take(8)
    }
}

internal object LogSanitizer {
    private val wifiCommand = Regex("(?is)(cmd\\s+wifi\\s+connect-network).*")
    private val credentials = Regex(
        """(?i)(["']?(?:psk|password|passwd|passphrase|token|access_token|refresh_token|session_key|sessionKey|shared_secret|private_key|pairing_code|pairingCode|配对码|密码)["']?\s*[:=]\s*)(?:"[^"]*"|'[^']*'|[^\s,;}]+)""",
    )
    private val bearer = Regex("(?i)(Authorization\\s*[:=]\\s*Bearer\\s+)\\S+")

    fun clean(message: String): String {
        val safe = bearer.replace(credentials.replace(wifiCommand.replace(message) {
            "${it.groupValues[1]} [arguments redacted]"
        }) { "${it.groupValues[1]}<redacted>" }) { "${it.groupValues[1]}<redacted>" }
        return if (safe.length <= 2_048) safe else safe.take(2_048) + " [truncated chars=${safe.length}]"
    }
}
