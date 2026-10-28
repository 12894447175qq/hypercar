package io.github.hypercar.iap2

import org.junit.Assert.*
import org.junit.Test

class EndpointOriginTest {
    private fun endpoint(origin: String, addresses: List<String> = emptyList(), port: Int = 0) =
        CarPlayEndpoint(null, null, port, null, null, null, addresses, null, origin = origin)

    @Test fun laterCredentialsDoNotRelabelMdnsAddressAsIapEndpoint() {
        val mdns = endpoint("mdns", listOf("192.0.2.1"), 7000)
        val credentials = endpoint("iap2_5703_credentials").copy(ssid = "test-car", passphrase = "example")
        val merged = mdns.merge(credentials)
        assertEquals("mdns", merged.origin)
        assertEquals(mdns.ipAddresses, merged.ipAddresses)
        assertEquals(7000, merged.airPlayPort)
        assertEquals("test-car", merged.ssid)
        assertEquals("example", merged.passphrase)
    }

    @Test fun actualIapAddressReplacesProbeOriginAndPreservesCredentials() {
        val probe = endpoint("tcp_probe", listOf("192.0.2.1"), 7000).copy(ssid = "test-car")
        val iap = endpoint("iap2_4301", listOf("192.0.2.2"), 7100)
        val merged = probe.merge(iap)
        assertEquals("iap2_4301", merged.origin)
        assertEquals(iap.ipAddresses, merged.ipAddresses)
        assertEquals(7100, merged.airPlayPort)
        assertEquals("test-car", merged.ssid)
    }

    @Test fun inviteAndUnknownInputsAreNotAssumedToBeIap4301() {
        val invite = CarPlayEndpoint(null, null, 7000, null, null, null, listOf("192.0.2.1"), null, fromInvite = true)
        assertEquals("ctrl_invite", invite.origin)
        val unknown = CarPlayEndpoint(null, null, 7000, null, null, null, listOf("192.0.2.2"), null)
        assertEquals("unknown", unknown.origin)
        assertNotEquals("iap2_4301", unknown.origin)
    }
}
