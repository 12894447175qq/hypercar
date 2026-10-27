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
    @Test fun vivoWakeAndFallbackUseOnlyVivo() = runTest {
        val commands = mutableListOf<String>()
        CarLifeWakeSession(CarLifeSource(CarLifeSource.VIVO)).wake({ commands.add(it) }, { false })
        assertEquals(listOf(
            "am start -n com.baidu.carlife.vivo/com.baidu.carlife.CarlifeActivity",
            "am start-foreground-service -n com.baidu.carlife.vivo/com.baidu.carlife.service.CarlifeConnectService" +
                " --es bindPackageName com.vivo.car.networking" +
                " --es bindClassName com.vivo.vivo_car_service.CarLifeMixingService --ei usb_type 13 --ez mix_manual true",
        ), commands)
    }
    @Test fun vivoInitializesRendererEvenWhenItsPortsAreAlreadyOpen() = runTest {
        val commands = mutableListOf<String>()
        CarLifeWakeSession(CarLifeSource(CarLifeSource.VIVO)).wake({ commands.add(it) }, { true })
        assertEquals(2, commands.size)
        assertTrue(commands.last().contains("CarLifeMixingService"))
        assertFalse(commands.any { it.contains("xiaomi") })
    }
    @Test fun existingVivoSocketPreparationDoesNotRestartActivity() = runTest {
        val commands = mutableListOf<String>()
        val session = CarLifeWakeSession(CarLifeSource(CarLifeSource.VIVO))
        session.prepareRenderer { commands.add(it) }
        assertEquals(1, commands.size)
        assertTrue(commands.single().startsWith("am start-foreground-service"))
    }
    @Test fun closedSessionCannotInitializeRenderer() = runTest {
        val session = CarLifeWakeSession(CarLifeSource(CarLifeSource.VIVO))
        session.close()
        session.prepareRenderer { error("closed connection must not launch Jovi") }
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
