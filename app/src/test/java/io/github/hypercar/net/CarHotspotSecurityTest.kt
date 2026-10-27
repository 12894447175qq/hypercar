package io.github.hypercar.net

import org.junit.Assert.*
import org.junit.Test

class CarHotspotSecurityTest {
    @Test fun legalWpa2PasswordsAreNeverClassifiedAsWepByLength() {
        for (length in listOf(10, 13, 26)) {
            val password = "x".repeat(length)
            assertEquals(CarHotspotSecurity.Mode.WPA2, CarHotspotSecurity.resolve(2, password))
            assertEquals(CarHotspotSecurity.Mode.WPA2, CarHotspotSecurity.resolve(null, password))
        }
    }
    @Test fun negotiatedSecurityTypeControlsBothConnectionPaths() {
        assertEquals(CarHotspotSecurity.Mode.WEP, CarHotspotSecurity.resolve(1, "1234567890"))
        assertEquals(CarHotspotSecurity.Mode.WPA2, CarHotspotSecurity.resolve(3, "synthetic-only"))
        assertEquals(CarHotspotSecurity.Mode.WPA3, CarHotspotSecurity.resolve(4, "synthetic-only"))
        assertEquals(CarHotspotSecurity.Mode.OPEN, CarHotspotSecurity.resolve(0, ""))
    }
}
