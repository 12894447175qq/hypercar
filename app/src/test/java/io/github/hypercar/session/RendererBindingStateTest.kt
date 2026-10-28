package io.github.hypercar.session

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RendererBindingStateTest {
    @Test fun lostAfterReadyNotifiesRecoveryOnceAndKeepsTheLossObservable() = runTest {
        val lost = mutableListOf<String>()
        val state = RendererBindingState(lost::add)
        assertTrue(state.connected())
        state.ready.await()
        state.unavailable("service disconnected")
        state.unavailable("binding died")
        assertEquals(listOf("service disconnected"), lost)
        assertFalse(state.isActive)
        state.ready.await() // Already-ready initialization cannot be the recovery notification.
    }
    @Test fun localCloseAndLateCallbacksNeverScheduleRecoveryOrReviveTheLease() {
        val state = RendererBindingState { error("local close must not schedule a retry") }
        assertTrue(state.connected())
        assertTrue(state.close())
        assertFalse(state.close())
        state.unavailable("late service callback")
        assertFalse(state.connected())
        assertFalse(state.isActive)
    }
    @Test fun initialFailureIsDeliveredToInitializerWithoutAnIndependentRetry() = runTest {
        val state = RendererBindingState { error("initializer owns initial failure") }
        state.unavailable("null binding")
        assertEquals("null binding", runCatching { state.ready.await() }.exceptionOrNull()?.message)
        assertFalse(state.isActive)
    }
    @Test fun transientReconnectionCanReportANewLoss() {
        var losses = 0
        val state = RendererBindingState { losses++ }
        state.connected(); state.unavailable("first loss")
        assertTrue(state.connected())
        state.unavailable("second loss")
        assertEquals(2, losses)
    }
}
