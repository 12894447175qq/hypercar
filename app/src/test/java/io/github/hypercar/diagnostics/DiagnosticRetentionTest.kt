package io.github.hypercar.diagnostics

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class DiagnosticRetentionTest {
    @Test fun repeatedFailuresNeverEvictTheOnlyDecodedSample() {
        val root=Files.createTempDirectory("hc-retain").toFile()
        try {
            val good=File(root,"batch-good").apply{mkdirs()}
            File(good,"source/preview").mkdirs();File(good,"source/preview/decode.json").writeText("{\"frames\":63}")
            repeat(6) {n->
                val current=File(root,"batch-failed-$n").apply{mkdirs();setLastModified(1000L+n)}
                DiagnosticRetention.prune(root,current)
            }
            assertTrue(good.isDirectory)
            assertTrue(root.listFiles()!!.size<=4)
        }finally{root.deleteRecursively()}
    }
    @Test fun unexportedCapturesAreProtectedAndBudgetStopsNewWork() {
        val root=Files.createTempDirectory("hc-retain-budget").toFile()
        try {
            repeat(4){i->val d=File(root,"batch-$i/source");d.mkdirs();File(d,"source.packets").writeBytes(ByteArray(20))}
            val current=File(root,"batch-current").apply{mkdirs()}
            assertTrue(runCatching{DiagnosticRetention.prune(root,current,maxBytes=70)}.isFailure)
            repeat(4){assertTrue(File(root,"batch-$it/source/source.packets").isFile)}
        }finally{root.deleteRecursively()}
    }
    @Test fun unsafeBatchIsNeverRecursivelyDeleted() {
        val root=Files.createTempDirectory("hc-retain-link").toFile()
        val external=Files.createTempDirectory("hc-outside").toFile()
        try {
            File(external,"keep.txt").writeText("keep")
            Files.createSymbolicLink(File(root,"batch-link").toPath(),external.toPath())
            DiagnosticRetention.prune(root,File(root,"batch-current").apply{mkdirs()})
            assertTrue(File(external,"keep.txt").isFile)
        }finally{File(root,"batch-link").delete();root.deleteRecursively();external.deleteRecursively()}
    }
}
