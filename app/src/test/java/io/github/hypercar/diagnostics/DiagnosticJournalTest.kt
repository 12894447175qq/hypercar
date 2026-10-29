package io.github.hypercar.diagnostics

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DiagnosticJournalTest {
    @Test fun interruptedMetadataIsExportableButOtherTemporaryFilesAreNot() {
        val root=Files.createTempDirectory("hc-partial-metadata").toFile()
        try {
            File(root,"batch.json").writeText("{}")
            File(root,"source.json.tmp").writeText("{\"frames\":1")
            val output=ByteArrayOutputStream();VideoEvidence.archive(root,output)
            val entries=mutableMapOf<String,String>()
            ZipInputStream(ByteArrayInputStream(output.toByteArray())).use{zip->while(true){val e=zip.nextEntry?:break;entries[e.name]=String(zip.readBytes())}}
            assertEquals("{\"frames\":1",entries["source.json.tmp"])
            val files=JSONObject(entries.getValue("manifest.json")).getJSONArray("files")
            assertTrue((0 until files.length()).map{files.getJSONObject(it)}.first{it.getString("path")=="source.json.tmp"}.getBoolean("incompleteWrite"))
            File(root,"private.tmp").writeText("must not export")
            assertThrows(IllegalArgumentException::class.java){VideoEvidence.archive(root,ByteArrayOutputStream())}
        }finally{root.deleteRecursively()}
    }
    @Test fun incompleteLastEventCannotCorruptEventsAppendedAfterRestart() {
        val root=Files.createTempDirectory("hc-partial-event").toFile()
        try {
            File(root,"timeline.txt").writeText("{\"sequence\":9,\"event\":\"old\"}\n{\"sequence\":10")
            DiagnosticJournal(root,"test").apply{record("process.restored");close()}
            val parsed=File(root,"timeline.txt").readLines().mapNotNull{runCatching{JSONObject(it)}.getOrNull()}
            assertTrue(parsed.any{it.optString("event")=="journal.opened"})
            assertTrue(parsed.any{it.optString("event")=="process.restored"})
            assertTrue(parsed.last().getLong("sequence")>9)
        }finally{root.deleteRecursively()}
    }
    @Test fun eachCaseRetainsHandshakeAndLatestProtocolAndExportsWithoutSecrets() {
        val root=Files.createTempDirectory("hc-journal").toFile()
        try {
            File(root,"batch.json").writeText("{}")
            val journal=DiagnosticJournal(root,"test",protocolLimit=512)
            journal.target(File(root,"case-a"),"a","original");journal.nativeStarted(7)
            journal.record("source.failed",mapOf("password" to "secret-value","detail" to "token=secret-value"),IllegalStateException("password=secret-value",IllegalArgumentException("reason")))
            repeat(80){journal.protocol("packet-$it password=secret-value ${"x".repeat(80)}")}
            journal.target(File(root,"case-b"),"b","inline_headers");journal.protocol("only-b");journal.record("case.finished");journal.close()
            assertTrue(File(root,"case-a/protocol-start.txt").readText().contains("packet-0 "))
            assertTrue(File(root,"case-a/protocol.txt").readText().contains("packet-79 "))
            assertFalse(File(root,"case-b/protocol.txt").readText().contains("packet-"))
            val summary=JSONObject(File(root,"journal-summary.json").readText())
            assertTrue(summary.getJSONObject("cases").getJSONObject("case-a").getBoolean("middleMayBeRotated"))
            val output=ByteArrayOutputStream();VideoEvidence.archive(root,output)
            val entries=mutableMapOf<String,String>()
            ZipInputStream(ByteArrayInputStream(output.toByteArray())).use{zip->while(true){val e=zip.nextEntry?:break;entries[e.name]=String(zip.readBytes())}}
            assertTrue(entries.containsKey("case-a/protocol.txt.1"))
            assertTrue(entries.getValue("timeline.txt").contains("IllegalArgumentException"))
            assertTrue(entries.values.none{it.contains("secret-value")})
        }finally{root.deleteRecursively()}
    }
    @Test fun capacityAndProcessRestartRemainVisibleInsteadOfPretendingCompleteLogs() {
        val root=Files.createTempDirectory("hc-journal-cap").toFile()
        try {
            val journal=DiagnosticJournal(root,"test",eventLimit=1024)
            repeat(40){journal.record("progress.$it",mapOf("detail" to "a".repeat(300)))}
            journal.record("source.failed",error=IllegalStateException("last failure"));journal.close()
            val first=JSONObject(File(root,"journal-summary.json").readText())
            assertTrue(first.getLong("omittedEvents")>0);assertTrue(File(root,"timeline.txt").length()<=1024)
            assertTrue(first.toString().contains("last failure"))
            DiagnosticJournal(root,"test",eventLimit=1024).apply{record("process.restored");close()}
            val second=JSONObject(File(root,"journal-summary.json").readText())
            assertTrue(second.getLong("sequence")>first.getLong("sequence"))
            assertTrue(second.toString().contains("last failure"))
        }finally{root.deleteRecursively()}
    }
    @Test fun writeFailureIsExposedWithoutThrowingIntoConnectionCode() {
        val root=Files.createTempDirectory("hc-journal-write").toFile()
        try {
            File(root,"timeline.txt").mkdir()
            val journal=DiagnosticJournal(root,"test");journal.record("native.connected");journal.close()
            assertNotNull(journal.storageError())
            assertFalse(JSONObject(File(root,"journal-summary.json").readText()).isNull("storageError"))
        }finally{root.deleteRecursively()}
    }
    @Test fun telemetryIsLimitedAndFirstSampleOfNewGenerationIsNotLost() {
        val root=Files.createTempDirectory("hc-journal-sample").toFile()
        try {
            var now=0L;var calls=0;val journal=DiagnosticJournal(root,"test",clock={now})
            journal.nativeStarted(1)
            repeat(5000){now=it.toLong();journal.sample("media.snapshot"){calls++;mapOf("frames" to it)}}
            assertEquals(1,calls)
            now=5000;journal.sample("media.snapshot"){calls++;emptyMap()}
            journal.nativeStarted(2);journal.sample("media.snapshot"){calls++;emptyMap()};journal.close()
            assertEquals(3,calls)
            val records=File(root,"timeline.txt").readLines().map(::JSONObject).filter{it.getString("event")=="media.snapshot"}
            assertEquals(listOf(1L,1L,2L),records.map{it.getLong("generation")})
        }finally{root.deleteRecursively()}
    }
    @Test fun oversizedTranscodePacketReportsActualQueueSizeWithoutStartingCodecs() {
        val logs=mutableListOf<String>();val failures=mutableListOf<String>()
        val worker=HardwareVideoTranscoder(1280,720,1280,720,30,{true},{failures.add(it)},{logs.add(it)})
        assertFalse(worker.offer(VideoEvidence.Packet(0,ByteArray(4*1024*1024+1))))
        assertEquals(1,failures.size);assertTrue(logs.any{it.contains("queueBytes=0")});worker.close()
    }
}
