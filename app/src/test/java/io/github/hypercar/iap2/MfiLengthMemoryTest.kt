package io.github.hypercar.iap2

import org.junit.Assert.*
import org.junit.Test

class MfiLengthMemoryTest {
    @Test fun onlyObservedLegacyAudiHistoryIsRepairedOnce() {
        val receiver = "Audi_MMI_4809 AUDI 3260 DELPHI"
        val other = "Audi_MMI_3315 AUDI 3315 DELPHI"
        MfiLengthMemory.recordSuccess(null, receiver, 32)
        MfiLengthMemory.recordHardRejected(null, receiver, 20)
        MfiLengthMemory.recordSuccess(null, other, 32)
        assertTrue(MfiLengthMemory.repairLegacyAudiHistory(null, receiver))
        assertEquals(MfiLengthMemory.Entry(), MfiLengthMemory.load(null, receiver))
        assertFalse(MfiLengthMemory.repairLegacyAudiHistory(null, other))
        assertEquals(32, MfiLengthMemory.load(null, other).succeeded)
        MfiLengthMemory.recordSuccess(null, receiver, 32)
        assertFalse(MfiLengthMemory.repairLegacyAudiHistory(null, receiver))
        assertEquals(32, MfiLengthMemory.load(null, receiver).succeeded)
    }
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
