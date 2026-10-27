package io.github.hypercar.net

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CarWifiBootstrapTest {
    @Test fun alreadyConnectedAudiHotspotIsRecognized() {
        assertTrue(CarHotspotPolicy.matches("Audi_MMI_4809", "Audi_MMI_4809", null, null))
        assertTrue(CarHotspotPolicy.matches("Audi_MMI_4809_5GHz", "Audi_MMI_4809", null, null))
        assertTrue(CarHotspotPolicy.matches("DIRECT-BMW", null, "DIRECT-BMW", null))
        assertTrue(CarHotspotPolicy.matches("custom-car", null, null, "custom-car"))
        assertFalse(CarHotspotPolicy.matches("home", "Audi_MMI_4809", null, null))
        assertFalse(CarHotspotPolicy.matches(null, "Audi_MMI_4809", null, null))
    }
    @Test fun mDnsBeforeInvitationCanAdvanceAfterBindAndAdvertisement() = runTest {
        val events = mutableListOf<String>()
        val endpoint = CarWifiBootstrap<String, String>(
            bind = { events.add("bind:$it"); true },
            advertise = { events.add("ctrl:$it"); true },
            discover = { events.add("mdns:$it"); "10.174.189.1:7000" },
        ).resolve("car-wifi")
        assertEquals("10.174.189.1:7000", endpoint)
        assertEquals(listOf("bind:car-wifi", "ctrl:car-wifi", "mdns:car-wifi"), events)
    }
    @Test fun failedBindingNeverAdvertisesOrDiscoversOnCellular() = runTest {
        val endpoint = CarWifiBootstrap<String, String>(
            bind = { false },
            advertise = { error("must not publish") },
            discover = { error("must not discover") },
        ).resolve("unavailable-wifi")
        assertNull(endpoint)
    }
}
