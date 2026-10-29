package io.github.hypercar.diagnostics

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class AutomaticVideoEvidenceTest {
    @Test fun ordinarySampleCanBeExportedWithoutRunningTheManualPreflight() {
        val root=Files.createTempDirectory("hc-auto-capture").toFile()
        try {
            val source=AutomaticVideoEvidence.prepare(root,"batch-auto-123-gen1","test")
            VideoEvidence.write(File(source,"source.packets"),listOf(VideoEvidence.Packet(1,byteArrayOf(0,0,0,1,0x65))))
            val bytes=java.io.ByteArrayOutputStream();VideoEvidence.archive(source.parentFile,bytes)
            assertTrue(bytes.size()>0)
            assertTrue(runCatching{AutomaticVideoEvidence.prepare(root,"batch-auto-123-gen1","test")}.isFailure)
            assertTrue(File(source,"source.packets").isFile)
        }finally{root.deleteRecursively()}
    }
    @Test fun unsafeNamesCannotCreateOrReplaceOtherFiles() {
        val root=Files.createTempDirectory("hc-auto-boundary").toFile()
        try {assertTrue(runCatching{AutomaticVideoEvidence.prepare(root,"../escape","test")}.isFailure);assertTrue(root.listFiles()!!.isEmpty())}
        finally{root.deleteRecursively()}
    }
}
