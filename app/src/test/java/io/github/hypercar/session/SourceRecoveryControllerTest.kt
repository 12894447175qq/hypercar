package io.github.hypercar.session

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SourceRecoveryControllerTest {
    @Test fun lossStopsOldSessionBeforeDelayedRestartAndKeepsForegroundProtection() = runTest {
        val recovery = SourceRecoveryController(this)
        val events = mutableListOf<String>()
        recovery.lost({ true }, { false }, { true }, {
            events.add("stop:$it"); recovery.cancel() // production cleanup callback
        }, { events.add("restart") }, { _, _ -> })
        advanceTimeBy(249); assertTrue(events.isEmpty())
        advanceTimeBy(1); runCurrent(); assertEquals(listOf("stop:true"), events)
        advanceTimeBy(3_099); assertEquals(1, events.size)
        advanceTimeBy(1); runCurrent(); assertEquals(listOf("stop:true", "restart"), events)
    }
    @Test fun userDisconnectDuringBackoffNeverRestarts() = runTest {
        val recovery = SourceRecoveryController(this)
        var stops = 0
        recovery.lost({ true }, { false }, { true }, { stops++ }, { error("cancelled recovery restarted") }, { _, _ -> })
        advanceTimeBy(250); runCurrent()
        recovery.cancel(); advanceUntilIdle()
        assertEquals(1, stops)
    }
    @Test fun replacementConnectionInvalidatesOldRecovery() = runTest {
        val recovery = SourceRecoveryController(this)
        var sameOwner = true
        recovery.lost({ true }, { false }, { sameOwner }, {}, { error("old owner restarted") }, { _, _ -> })
        advanceTimeBy(250); runCurrent(); sameOwner = false; advanceUntilIdle()
    }
    @Test fun transientServiceReconnectionDoesNotTearDownHealthySession() = runTest {
        val recovery = SourceRecoveryController(this)
        var active = false
        recovery.lost({ true }, { active }, { true }, { error("healthy source stopped") }, { error("healthy source restarted") }, { _, _ -> })
        active = true; advanceUntilIdle()
    }
    @Test fun repeatedLossHasFiniteBudgetAndFinalFailureReleasesKeepAlive() = runTest {
        val recovery = SourceRecoveryController(this)
        val retain = mutableListOf<Boolean>()
        var restarts = 0
        repeat(4) {
            recovery.lost({ true }, { false }, { true }, { retain.add(it); recovery.cancel() }, { restarts++ }, { _, _ -> })
            advanceUntilIdle()
        }
        assertEquals(listOf(true, true, true, false), retain)
        assertEquals(3, restarts)
        recovery.reset()
        recovery.lost({ true }, { false }, { true }, { retain.add(it) }, { restarts++ }, { _, _ -> })
        advanceUntilIdle()
        assertEquals(4, restarts)
        assertTrue(retain.last())
    }
}
