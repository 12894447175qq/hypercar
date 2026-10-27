package io.github.hypercar.session

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CarLifeSourceTest {
    @Test fun explicitVivoWinsEvenWithCarWithInstalled() {
        val source = CarLifeSource.resolve(CarLifeSource.VIVO) { true }!!
        assertEquals(CarLifeSource.VIVO, source.enginePackage)
        assertEquals("com.baidu.carlife.vivo/com.baidu.carlife.CarlifeActivity", source.activity)
    }
    @Test fun missingVivoDoesNotSwitchSources() {
        assertNull(CarLifeSource.resolve(CarLifeSource.VIVO) { it != CarLifeSource.VIVO })
    }
    @Test fun otherSourcesKeepTheirOwnEngine() {
        for (pkg in listOf(CarLifeSource.PUBLIC, CarLifeSource.XIAOMI, "com.hihonor.carlink")) {
            assertEquals(pkg, CarLifeSource.resolve(pkg) { true }!!.enginePackage)
        }
        assertEquals(CarLifeSource.XIAOMI, CarLifeSource.resolve("com.miui.carlink") { true }!!.enginePackage)
        assertNull(CarLifeSource.resolve("com.miui.carlink") { it != CarLifeSource.XIAOMI })
        assertNull(CarLifeSource.resolve("com.foo; reboot") { true })
    }
    @Test fun vivoWakeAndFallbackUseOnlyVivo() = runTest {
        val commands = mutableListOf<String>()
        CarLifeWakeSession(CarLifeSource(CarLifeSource.VIVO)).wake({ commands.add(it) }, { false })
        assertEquals(listOf(
            "am start -n com.baidu.carlife.vivo/com.baidu.carlife.CarlifeActivity",
            "am start-foreground-service -n com.baidu.carlife.vivo/com.baidu.carlife.service.CarlifeConnectService",
        ), commands)
    }
    @Test fun listenerAvoidsRestartAndFallback() = runTest {
        val commands = mutableListOf<String>()
        CarLifeWakeSession(CarLifeSource(CarLifeSource.PUBLIC)).wake({ commands.add(it) }, { true })
        assertEquals(1, commands.size)
    }
    @Test fun cancelledWakeNeverStartsServiceOrRelaunchesClosedSession() = runTest {
        val session = CarLifeWakeSession(CarLifeSource(CarLifeSource.VIVO))
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
        val session = CarLifeWakeSession(CarLifeSource(CarLifeSource.VIVO))
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
}
