package io.github.hypercar.session

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SourceActivityLaunchTest {
    @Test fun visibleAppStartsSourceWithoutShellIdentity()=runTest {
        assertEquals("foreground_activity",launchSourceActivity({true},{error("must not use shell")}) )
    }
    @Test fun backgroundUsesSingleShellRequestAndChecksItsResult()=runTest {
        var calls=0
        assertEquals("shizuku",launchSourceActivity({false},{calls++;SourceLaunchResult(0,"Status: ok\nComplete","")}))
        assertEquals(1,calls)
    }
    @Test fun abortedActivityIsNotMistakenForMissingLocationOrSuccess()=runTest {
        val failure=runCatching {launchSourceActivity({false},{SourceLaunchResult(1,"Error: Activity not started, unknown error code 102","")})}.exceptionOrNull()
        assertTrue(failure is SourceActivityStartException)
        assertEquals(102,(failure as SourceActivityStartException).startCode)
        assertTrue(failure.message!!.contains("系统中止"))
    }
    @Test fun shellSuccessExitWithErrorBodyStillFails()=runTest {
        assertTrue(runCatching {launchSourceActivity({false},{SourceLaunchResult(0,"Error type 3: not found","")})}.isFailure)
    }
    @Test fun foregroundSecurityFailureIsNotBypassedWithShell()=runTest {
        assertTrue(runCatching {launchSourceActivity({throw SecurityException("denied")},{error("no fallback")})}.exceptionOrNull() is SecurityException)
    }
}
