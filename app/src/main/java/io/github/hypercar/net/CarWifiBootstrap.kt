package io.github.hypercar.net

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Bind before advertising/discovery. A valid discovered endpoint can advance without an invitation. */
class CarWifiBootstrap<N, E>(
    private val bind: suspend (N) -> Boolean,
    private val advertise: (N) -> Boolean,
    private val discover: suspend (N) -> E?,
) {
    suspend fun resolve(network: N): E? {
        currentCoroutineContext().ensureActive()
        if (!bind(network)) return null
        currentCoroutineContext().ensureActive()
        if (!advertise(network)) return null
        currentCoroutineContext().ensureActive()
        return discover(network)
    }
}
