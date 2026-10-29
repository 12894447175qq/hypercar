package io.github.hypercar.session

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticTakeoverTest {
    @Test fun retirementFailureCannotLeaveDiagnosticOwnershipStuck()=runTest {
        val owned=AtomicBoolean(false);val lease=DiagnosticTakeover(Any(),owned)
        val failure=IllegalStateException("old task did not exit");var released=false
        val result=runCatching{lease.acquire({throw failure},{error("not ready")},{released=true})}.exceptionOrNull()
        assertSame(failure,result);assertTrue(released);assertFalse(owned.get())
    }
    @Test fun connectedSessionIsRetiredAutomaticallyBeforeDiagnosticPreparation()=runTest {
        val owned=AtomicBoolean(false);val lease=DiagnosticTakeover(Any(),owned)
        val events=mutableListOf<String>();val retired=CompletableDeferred<Unit>()
        val work=launch {lease.acquire({events.add("retire");retired.await()},{events.add("prepare")},{events.add("release")})}
        runCurrent()
        assertTrue(owned.get());assertEquals(listOf("retire"),events)
        assertFalse(lease.automatic {error("automatic connect must not steal takeover")})
        assertFalse(lease.automatic {error("ACL loss must not cancel the batch")})
        retired.complete(Unit);runCurrent();assertTrue(work.isCompleted)
        assertEquals(listOf("retire","prepare"),events)
        lease.release {events.add("release")};assertFalse(owned.get())
        assertTrue(lease.automatic {events.add("ordinary")})
    }
    @Test fun duplicateClaimCannotTearDownTheExistingDiagnostic()=runTest {
        val owned=AtomicBoolean(true);val lease=DiagnosticTakeover(Any(),owned)
        assertTrue(runCatching{lease.acquire({error("not owner")},{error("not owner")},{error("not owner")})}.isFailure)
        assertTrue(owned.get())
    }
    @Test fun cancellationCompletesRetirementBeforeReleasingAutomaticEvents()=runTest {
        val owned=AtomicBoolean(false);val lease=DiagnosticTakeover(Any(),owned)
        val events=mutableListOf<String>();var retireCalls=0
        val work=launch {lease.acquire({retireCalls++;if(retireCalls==1)awaitCancellation() else {delay(50);events.add("retired")}}, {error("cancelled prepare")}, {events.add("released")})}
        runCurrent();work.cancel();runCurrent();assertTrue(owned.get())
        advanceUntilIdle();assertFalse(owned.get());assertEquals(listOf("retired","released"),events)
    }
    @Test fun keepaliveFailureRollsBackOwnershipAndPreservesOriginalError()=runTest {
        val owned=AtomicBoolean(false);val lease=DiagnosticTakeover(Any(),owned);var retires=0;var released=0
        val error=runCatching {lease.acquire({retires++},{error("foreground unavailable")},{released++})}.exceptionOrNull()
        assertEquals("foreground unavailable",error?.message);assertEquals(2,retires);assertEquals(1,released);assertFalse(owned.get())
    }
}
