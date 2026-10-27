package io.github.hypercar.session

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class NativeSessionGateTest {
    @Test fun delayedOldCallbackCannotActivateOrStopReplacement() {
        val gate = NativeSessionGate()
        val old = gate.begin()
        var state = "old"
        gate.invalidate()
        assertFalse(gate.ifCurrent(old) { state = "active" })
        val replacement = gate.begin()
        assertFalse(gate.ifCurrent(old) { state = "stopped" })
        gate.invalidateIfCurrent(old)
        assertTrue(gate.ifCurrent(replacement) { state = "replacement" })
        assertEquals("replacement", state)
    }
    @Test fun stopCannotOvertakeAnInFlightStartAndLateRetryCannotReplaceCurrentGeneration() {
        val gate = NativeSessionGate()
        val inStart = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val stopping = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        var generation = 0L
        val starter = thread {
            gate.startIf({ true }) {
                generation = it
                inStart.countDown()
                assertTrue(releaseStart.await(2, TimeUnit.SECONDS))
                true
            }
        }
        assertTrue(inStart.await(2, TimeUnit.SECONDS))
        val stopper = thread {
            stopping.countDown()
            gate.stop { stopped.countDown() }
        }
        assertTrue(stopping.await(2, TimeUnit.SECONDS))
        assertFalse(stopped.await(30, TimeUnit.MILLISECONDS))
        releaseStart.countDown()
        starter.join(2_000)
        stopper.join(2_000)
        assertTrue(stopped.await(1, TimeUnit.SECONDS))
        assertFalse(gate.isCurrent(generation))
        val replacement = gate.begin()
        assertFalse(gate.startIf({ false }) { error("expired retry must not call JNI") })
        assertTrue(gate.isCurrent(replacement))
    }
}
