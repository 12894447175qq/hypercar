package io.github.hypercar.iap2

import org.junit.Assert.*
import org.junit.Test

class MfiLengthMemoryTest {
    @Test fun audiPrefers20OthersRetain32() {
        assertEquals(listOf(20, 32, 16), MfiLengthMemory.baseline(20))
        assertEquals(listOf(32, 20, 16), MfiLengthMemory.baseline(32))
    }
    @Test fun successAlwaysWinsOverProfilePreference() {
        val memory = MfiLengthMemory.Entry(succeeded = 32)
        assertEquals(32, MfiLengthMemory.orderAttempts(MfiLengthMemory.baseline(20), memory).first())
    }
    @Test fun allSilentHistoryGetsOneFreshWindowWithNewPreference() {
        val receiver = "audi-test-all-silent"
        listOf(16, 20, 32).forEach { MfiLengthMemory.recordSilent(null, receiver, it) }
        assertTrue(MfiLengthMemory.forPreference(null, receiver, 20).silent.isEmpty())
        listOf(16, 20, 32).forEach { MfiLengthMemory.recordSilent(null, receiver, it) }
        assertEquals(setOf(16, 20, 32), MfiLengthMemory.forPreference(null, receiver, 20).silent)
    }
    @Test fun preferenceMigrationPreservesSuccessAndHardRejection() {
        val receiver = "test-success"
        MfiLengthMemory.recordSuccess(null, receiver, 32)
        MfiLengthMemory.recordHardRejected(null, receiver, 16)
        val memory = MfiLengthMemory.forPreference(null, receiver, 20)
        assertEquals(32, memory.succeeded)
        assertEquals(setOf(16), memory.rejected)
    }
}
