package io.github.hypercar.iap2

import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class BluetoothBootstrapGateTest {
    private class Connection : Closeable {
        var closed = 0
        override fun close() { closed++ }
    }

    @Test fun wifiHandoffKeepsEstablishedBluetoothButStopsLaterAcquisition() {
        val gate = BluetoothBootstrapGate()
        val established = Connection()
        assertTrue(gate.register(established))
        gate.release(established)
        assertTrue(gate.accept { true })
        gate.finish() // AirPlay RECORD, followed by the log's later RFCOMM disconnect.
        assertEquals(0, established.closed)
        repeat(20) {
            assertFalse(gate.register(Connection()))
            assertFalse(gate.accept { error("new session replaced active Wi-Fi") })
        }
        assertFalse(gate.isOpen)
    }

    @Test fun handoffClosesBlockedSocketWithoutWaitingForNetworkThread() {
        val gate = BluetoothBootstrapGate()
        ServerSocket(0).use { server ->
            Socket("127.0.0.1", server.localPort).use { dial ->
                server.accept().use {
                    assertTrue(gate.register(dial))
                    val reading = CountDownLatch(1)
                    val exited = CountDownLatch(1)
                    val accepted = AtomicBoolean(false)
                    val worker = Thread {
                        reading.countDown()
                        try { dial.getInputStream().read() } catch (_: Exception) { }
                        finally { gate.release(dial) }
                        accepted.set(gate.accept { true })
                        exited.countDown()
                    }.also { it.start() }
                    assertTrue(reading.await(1, TimeUnit.SECONDS))
                    gate.finish()
                    assertTrue("handoff must unblock the worker", exited.await(2, TimeUnit.SECONDS))
                    worker.join(1_000)
                    assertTrue(dial.isClosed)
                    assertFalse(accepted.get())
                }
            }
        }
    }

    @Test fun bluetoothLossDuringBootstrapAllowsFreshAttemptButLateCompletionCannotReleaseIt() {
        val gate = BluetoothBootstrapGate()
        val first = Connection()
        val replacement = Connection()
        assertTrue(gate.register(first))
        gate.cancelPending()
        assertEquals(1, first.closed)
        assertTrue(gate.register(replacement))
        gate.release(first) // Cancelled worker completes after a new acquisition.
        gate.finish()
        assertEquals(1, replacement.closed)
        assertEquals(1, first.closed)
    }

    @Test fun failedDialAndNewConnectionGenerationRemainUsable() {
        val gate = BluetoothBootstrapGate()
        val failed = Connection()
        assertTrue(gate.register(failed))
        gate.release(failed)
        assertTrue(gate.register(Connection()))
        gate.finish()
        gate.finish()
        assertFalse(gate.isOpen)
        assertTrue(BluetoothBootstrapGate().register(Connection()))
    }

    @Test fun closeFailureDoesNotReopenGate() {
        val gate = BluetoothBootstrapGate()
        assertTrue(gate.register(Closeable { error("socket close failure") }))
        gate.finish()
        assertFalse(gate.accept { true })
        assertFalse(gate.register(Connection()))
    }
}
