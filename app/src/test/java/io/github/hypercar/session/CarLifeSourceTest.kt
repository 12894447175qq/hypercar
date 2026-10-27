package io.github.hypercar.session

import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CarLifeSourceTest {
    private class Lease : CarLifeRendererLease {
        var active = true
        var closes = 0
        override val isActive: Boolean get() = active
        override fun close() { closes++; active = false }
    }
    @Test fun explicitVivoWinsEvenWithCarWithInstalled() {
        val source = CarLifeSource.resolve(CarLifeSource.VIVO) { true }!!
        assertEquals(CarLifeSource.VIVO, source.enginePackage)
        assertEquals("com.baidu.carlife.vivo/com.baidu.carlife.CarlifeActivity", source.activity)
    }
    @Test fun missingVivoDoesNotSwitchSources() {
        assertNull(CarLifeSource.resolve(CarLifeSource.VIVO) { it != CarLifeSource.VIVO })
    }
    @Test fun vivoRequiresTheOriginalJoviRenderer() {
        assertNull(CarLifeSource.resolve(CarLifeSource.VIVO) { it != CarLifeSource.JOVI })
    }
    @Test fun automaticSelectionSkipsAnIncompleteVivoSource() {
        assertEquals(CarLifeSource.XIAOMI, CarLifeSource.resolve("") {
            it in setOf(CarLifeSource.VIVO, CarLifeSource.XIAOMI)
        }!!.enginePackage)
    }
    @Test fun otherSourcesKeepTheirOwnEngine() {
        for (pkg in listOf(CarLifeSource.PUBLIC, CarLifeSource.XIAOMI, "com.hihonor.carlink")) {
            assertEquals(pkg, CarLifeSource.resolve(pkg) { true }!!.enginePackage)
        }
        assertEquals(CarLifeSource.XIAOMI, CarLifeSource.resolve("com.miui.carlink") { true }!!.enginePackage)
        assertNull(CarLifeSource.resolve("com.miui.carlink") { it != CarLifeSource.XIAOMI })
        assertNull(CarLifeSource.resolve("com.foo; reboot") { true })
    }
    @Test fun vivoCannotAccidentallyUseTheShellActivityOrFgsPath() = runTest {
        val commands = mutableListOf<String>()
        val session = CarLifeWakeSession(CarLifeSource(CarLifeSource.VIVO))
        val error = runCatching { session.wake({ commands.add(it) }, { false }) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(commands.isEmpty())
        assertFalse(session.wasUsed)
    }
    @Test fun vivoBindingIsRetainedUntilDisconnectAndReleasedOnce() = runTest {
        val session = CarLifeWakeSession(CarLifeSource(CarLifeSource.VIVO))
        val lease = Lease()
        assertTrue(session.bindRenderer { lease })
        assertTrue(session.wasUsed)
        assertTrue(session.hasRenderer)
        session.close()
        session.close()
        assertEquals(1, lease.closes)
        assertFalse(session.hasRenderer)
    }
    @Test fun existingVivoRendererIsNotBoundTwice() = runTest {
        val session = CarLifeWakeSession(CarLifeSource(CarLifeSource.VIVO))
        val lease = Lease()
        assertTrue(session.bindRenderer { lease })
        assertTrue(session.bindRenderer { error("must reuse this session's connection") })
        assertEquals(0, lease.closes)
    }
    @Test fun closedSessionCannotInitializeRenderer() = runTest {
        val session = CarLifeWakeSession(CarLifeSource(CarLifeSource.VIVO))
        session.close()
        assertFalse(session.bindRenderer { error("closed connection must not launch Jovi") })
    }
    @Test fun closeWhileBindingCannotRetainALateConnection() = runTest {
        val session = CarLifeWakeSession(CarLifeSource(CarLifeSource.VIVO))
        val pending = CompletableDeferred<CarLifeRendererLease?>()
        val lease = Lease()
        var accepted = true
        launch { accepted = session.bindRenderer { pending.await() } }
        runCurrent()
        session.close()
        pending.complete(lease)
        runCurrent()
        assertFalse(accepted)
        assertFalse(session.wasUsed)
        assertEquals(1, lease.closes)
    }
    @Test fun cancellationAfterAcquireStillReleasesTheConnection() = runTest {
        val session = CarLifeWakeSession(CarLifeSource(CarLifeSource.VIVO))
        val lease = Lease()
        val job = launch {
            session.bindRenderer {
                currentCoroutineContext().cancel()
                lease
            }
        }
        runCurrent()
        assertTrue(job.isCancelled)
        assertFalse(session.hasRenderer)
        assertEquals(1, lease.closes)
    }
    @Test fun unavailableBindingIsNotReportedAsInitialized() = runTest {
        val session = CarLifeWakeSession(CarLifeSource(CarLifeSource.VIVO))
        assertFalse(session.bindRenderer { null })
        assertFalse(session.hasRenderer)
        assertFalse(session.wasUsed)
    }
    @Test fun warmRendererDoesNotLaunchTheSourceActivity() = runTest {
        val lease = Lease()
        assertSame(lease, acquireVivoRenderer({ lease }, { error("warm binding must not relaunch the source") }))
    }
    @Test fun coldRendererBootstrapsOnceAndWaitsForTheOriginalSdkThrottle() = runTest {
        val lease = Lease()
        val events = mutableListOf<String>()
        var attempts = 0
        launch {
            assertSame(lease, acquireVivoRenderer(
                { events.add("bind"); if (++attempts == 1) null else lease },
                { events.add("bootstrap") },
            ))
        }
        runCurrent()
        assertEquals(listOf("bind", "bootstrap"), events)
        advanceTimeBy(3_099)
        assertEquals(1, attempts)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("bind", "bootstrap", "bind"), events)
    }
    @Test fun cancellingBootstrapNeverRetriesTheBinding() = runTest {
        var attempts = 0
        var bootstraps = 0
        val job = launch { acquireVivoRenderer({ attempts++; null }, { bootstraps++ }) }
        runCurrent()
        job.cancel()
        advanceTimeBy(5_000)
        assertEquals(1, attempts)
        assertEquals(1, bootstraps)
    }
    @Test fun rejectedBootstrapDoesNotLoopOrClaimSuccess() = runTest {
        var attempts = 0
        var bootstraps = 0
        val error = runCatching { acquireVivoRenderer({ attempts++; null }, { bootstraps++ }) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals(2, attempts)
        assertEquals(1, bootstraps)
    }
    @Test fun deadBindingCanBeReplacedWithoutKillingTheEngine() = runTest {
        val session = CarLifeWakeSession(CarLifeSource(CarLifeSource.VIVO))
        val old = Lease()
        assertTrue(session.bindRenderer { old })
        old.active = false
        val next = Lease()
        assertTrue(session.bindRenderer { next })
        assertEquals(1, old.closes)
        assertEquals(0, next.closes)
        session.releaseRenderer()
        assertEquals(1, next.closes)
        assertTrue(session.isOpen)
    }
    @Test fun vivoPermissionGateNamesOnlyMissingOriginalComponentGrants() {
        val source = CarLifeSource(CarLifeSource.VIVO)
        assertEquals(listOf(CarLifeSource.FINE_LOCATION, CarLifeSource.BACKGROUND_LOCATION),
            source.missingRendererPermissions { false })
        assertEquals(listOf(CarLifeSource.BACKGROUND_LOCATION),
            source.missingRendererPermissions { it == CarLifeSource.FINE_LOCATION })
        assertTrue(source.missingRendererPermissions { true }.isEmpty())
        for (pkg in listOf(CarLifeSource.PUBLIC, CarLifeSource.XIAOMI)) {
            assertTrue(CarLifeSource(pkg).missingRendererPermissions { false }.isEmpty())
        }
    }
    @Test fun listenerAvoidsRestartAndFallback() = runTest {
        val commands = mutableListOf<String>()
        CarLifeWakeSession(CarLifeSource(CarLifeSource.PUBLIC)).wake({ commands.add(it) }, { true })
        assertEquals(1, commands.size)
    }
    @Test fun cancelledWakeNeverStartsServiceOrRelaunchesClosedSession() = runTest {
        val session = CarLifeWakeSession(CarLifeSource(CarLifeSource.PUBLIC))
        val commands = mutableListOf<String>()
        val job = launch { session.wake({ commands.add(it) }, { false }) }
        runCurrent()
        session.close()
        job.cancel()
        advanceTimeBy(5_000)
        session.wake({ commands.add(it) }, { false })
        assertEquals(1, commands.size)
    }
    @Test fun closedOldSessionNeverRunsItsFallbackEvenIfJobWasNotCancelled() = runTest {
        val session = CarLifeWakeSession(CarLifeSource(CarLifeSource.PUBLIC))
        val commands = mutableListOf<String>()
        launch { session.wake({ commands.add(it) }, { false }) }
        runCurrent()
        session.close()
        advanceTimeBy(5_000)
        assertEquals(1, commands.size)
    }
    @Test fun foreignOrUnverifiableListenersAreNotAdopted() {
        val table = """
            sl local_address rem_address st tx_queue rx tr tm retr uid
            0: 0100007F:1C48 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10234 0 123
            1: 00000000:2030 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10235 0 456
        """.trimIndent()
        val listeners = CarLifePorts.parse(table)
        assertTrue(CarLifePorts.owned(listeners, 7240, 10234))
        assertFalse(CarLifePorts.owned(listeners, 7240, 10235))
        assertFalse(CarLifePorts.owned(listeners, 8240, 10234))
        assertFalse(CarLifePorts.owned(CarLifePorts.parse("Permission denied"), 7240, 10234))
    }
    @Test fun timeWaitIsNotAListenerAndReadFailureIsNotForeignOwnership() {
        val table = """
            sl local_address rem_address st tx_queue rx tr tm retr uid
            0: 0100007F:1C48 0100007F:1234 06 00000000:00000000 00:00000000 00000000 0 0 123
        """.trimIndent()
        assertTrue(CarLifePorts.readable(table))
        assertEquals("06", CarLifePorts.sockets(table).single().state)
        assertTrue(CarLifePorts.parse(table).isEmpty())
        assertFalse(CarLifePorts.readable("cat: Permission denied"))
        assertTrue(CarLifePorts.readable("sl local_address rem_address st"))
    }
}
