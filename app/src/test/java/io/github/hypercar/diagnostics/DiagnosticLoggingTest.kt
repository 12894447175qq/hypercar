package io.github.hypercar.diagnostics

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticLoggingTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun traceRetainsOwnerAndMonotonicElapsedTime() {
        var now = 100L
        val lines = mutableListOf<String>()
        val first = ConnectionTrace("connect", lines::add, { now })
        val child = ConnectionTrace("vivo", lines::add, { now }, first.id)
        now += 250
        child.event("bound", "uid=10424")
        assertTrue(lines.single().contains("parent=${first.id}"))
        assertTrue(lines.single().contains("+250ms bound"))
        assertNotEquals(first.id, child.id)
    }

    @Test fun highFrequencySamplesDoNotBuildOrWriteSkippedDetails() {
        var now = 0L
        var builds = 0
        val lines = mutableListOf<String>()
        val trace = ConnectionTrace("stream", lines::add, { now })
        repeat(500) { trace.sample("stats") { builds++; "frames=$it" }; now += 10 }
        assertEquals(1, builds)
        now = 5_000
        trace.sample("stats") { builds++; "frames=500" }
        assertEquals(2, builds)
        assertEquals(2, lines.size)
    }

    @Test fun secretsAreRemovedFromFieldsCommandsAndExceptionCauses() {
        val input = "psk=abc password=\"two words\" pairing_code=123456 token=qwerty Authorization: Bearer abc.def"
        val safe = LogSanitizer.clean(input)
        for (secret in listOf("abc", "two words", "123456", "qwerty", "abc.def")) assertFalse(safe.contains(secret))
        assertEquals("cmd wifi connect-network [arguments redacted]", LogSanitizer.clean("cmd wifi connect-network 'ssid' wpa2 'a'\\''b'"))
        val lines = mutableListOf<String>()
        ConnectionTrace("failure", lines::add).failure("bind", IllegalStateException("password=RAW_PASSWORD_123", IllegalArgumentException("token=RAW_TOKEN_456")))
        assertTrue(lines.any { it.contains("Caused by") })
        assertFalse(lines.any { it.contains("RAW_PASSWORD_123") || it.contains("RAW_TOKEN_456") })
    }

    @Test fun persistentLogSurvivesReopeningAndExportsMoreThanUiTail() {
        val file = File(temporary.root, "session.log")
        RollingLogFile(file, 100_000).use { log -> repeat(400) { log.append("stage-$it") } }
        RollingLogFile(file, 100_000).use { log ->
            log.append("next-process")
            val lines = log.snapshotLines()
            assertEquals(401, lines.size)
            assertEquals("stage-0", lines.first())
            assertEquals("next-process", lines.last())
        }
    }

    @Test fun longSessionRotatesWithUtf8ByteLimitAndKeepsNewestHistory() {
        val file = File(temporary.root, "session.log")
        RollingLogFile(file, 512).use { log ->
            repeat(80) { log.append("$it ${"中文".repeat(12)}") }
            assertTrue(file.length() <= 512)
            assertTrue(File(file.path + ".1").length() <= 512)
            val lines = log.snapshotLines()
            assertTrue(lines.last().startsWith("79 "))
            assertFalse(lines.any { it.contains('\uFFFD') })
            assertTrue(lines.size < 80)
        }
    }

    @Test fun clearRemovesRetainedHistoryAndOversizedRecordsStayBounded() {
        val file = File(temporary.root, "session.log")
        RollingLogFile(file, 512).use { log ->
            repeat(30) { log.append("old-$it ${"x".repeat(100)}") }
            log.clear()
            log.append("new-session")
            log.append("x".repeat(2_000))
            assertEquals("new-session", log.snapshotLines().first())
            assertTrue(log.snapshotLines().last().contains("oversized record omitted"))
            assertTrue(file.length() <= 512)
            assertFalse(File(file.path + ".1").exists())
        }
    }

    @Test fun olderOversizedFilesAreMigratedWithoutSplittingUtf8Lines() {
        val file = File(temporary.root, "session.log")
        file.writeText((0..200).joinToString("\n", postfix = "\n") { "$it 中文旧日志" })
        RollingLogFile(file, 512).use { log ->
            log.append("new-process")
            assertTrue(file.length() <= 512)
            assertTrue(log.snapshotLines().any { it.contains("200 中文旧日志") })
            assertFalse(log.snapshotLines().any { it.contains('\uFFFD') })
        }
    }

    @Test fun binaryAndMultilineMessagesRemainOnePrintablePersistedRecord() {
        val file = File(temporary.root, "binary.log")
        val input = "UDP payload=end\u0000\r\npassword=secret\u001b"
        val safe = LogSanitizer.clean(input)
        assertTrue(safe.contains("\\u0000\\r\\n"))
        assertFalse(safe.contains("secret"))
        assertFalse(safe.any { it.code < 32 && it != '\t' })
        RollingLogFile(file, 2048).use { log -> log.append(safe) }
        RollingLogFile(file, 2048).use { log ->
            assertEquals(listOf(safe), log.snapshotLines())
        }
    }

    @Test fun engineStartAndMediaEvidenceCannotClaimVehicleDisplay() {
        val evidence = MediaEvidence()
        evidence.begin(7)
        evidence.state("starting")
        assertTrue(evidence.summary().contains("engineStarted=true"))
        assertTrue(evidence.summary().contains("recordCompleted=false"))
        evidence.state("connected")
        evidence.state("carlife_active")
        evidence.stats(42, true)
        assertTrue(evidence.summary().contains("recordCompleted=true"))
        assertTrue(evidence.summary().contains("sourceFrames=42"))
        assertTrue(evidence.summary().contains("vehicleDisplay=unverified"))
        evidence.begin(8)
        assertTrue(evidence.summary().contains("recordCompleted=false"))
        assertTrue(evidence.summary().contains("sourceFrames=0"))
        assertTrue(evidence.summary().contains("lastAudioSending=false"))
    }
}
