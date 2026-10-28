package io.github.hypercar.core

import io.github.hypercar.autowake.ConnectionKeepAliveGate
import io.github.hypercar.autowake.CarBluetoothTarget
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConnectionKeepAliveTest {
    @Test fun connectionWaitsForServiceAndCancelledStartCannotReviveIt() = runTest {
        val gate = ConnectionKeepAliveGate()
        val first = gate.begin()
        val connecting = async { first.ready.await() }
        runCurrent()
        assertFalse(connecting.isCompleted)
        assertTrue(gate.release(first.id))
        assertFalse(gate.ready(first.id))
        runCurrent()
        assertTrue(connecting.isCancelled)
        assertNull(gate.currentId())
    }
    @Test fun oldServiceStopAndFailureCannotAffectReplacement() = runTest {
        val gate = ConnectionKeepAliveGate()
        val old = gate.begin()
        assertTrue(gate.ready(old.id))
        assertTrue(gate.release(old.id))
        val replacement = gate.begin()
        assertFalse(gate.release(old.id))
        gate.failed(old.id, IllegalStateException("late failure"))
        assertFalse(replacement.ready.isCompleted)
        assertTrue(gate.ready(replacement.id))
        replacement.ready.await()
        assertTrue(gate.isCurrent(replacement.id))
    }
    @Test fun serviceFailureReachesTheConnectionInsteadOfSilentDegradedStartup() = runTest {
        val gate = ConnectionKeepAliveGate()
        val ticket = gate.begin()
        gate.failed(ticket.id, IllegalStateException("foreground rejected"))
        assertEquals("foreground rejected", runCatching { ticket.ready.await() }.exceptionOrNull()?.message)
        assertTrue(gate.release(ticket.id))
    }
    @Test fun audiAutoWakeHonorsProfileAndExplicitTargetWithoutMatchingHeadphones() {
        val mac = "54:F8:2A:90:4C:40"
        assertTrue(CarBluetoothTarget.matches(mac, "Audi_MMI_4809", null, "audi_mib3"))
        assertFalse(CarBluetoothTarget.matches(mac, "Bluetooth headphones", null, "audi_mib3"))
        assertFalse(CarBluetoothTarget.matches(mac, "Audi_MMI_4809", null, "bmw_id85"))
        assertTrue(CarBluetoothTarget.matches(mac, "BMW 123", null, "bmw_id85"))
        assertTrue(CarBluetoothTarget.matches(mac, null, mac.lowercase(), "generic"))
        assertFalse(CarBluetoothTarget.matches("AA:BB:CC:DD:EE:FF", "Audi_MMI_4809", mac, "audi_mib3"))
        assertFalse(CarBluetoothTarget.matches("02:00:00:00:00:00", "Audi", null, "audi_mib3"))
    }
}
