package io.github.hypercar.diagnostics

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeoutOrNull

class AutomaticVideoEvidenceTest {
    private fun sample(root:File,id:String,frames:Int=2,closed:Boolean=true):File {
        val source=AutomaticVideoEvidence.prepare(root,"batch-auto-$id","test")
        if(frames>0)VideoEvidence.write(File(source,"source.packets"),List(frames){VideoEvidence.Packet(it.toLong(),byteArrayOf(0,0,0,1,0x65))})
        File(source,"source.json").writeText(JSONObject().put("closed",closed).put("error",JSONObject.NULL).put("frames",frames).toString())
        return source
    }
    @Test fun ordinaryExportReportsBlackSourceAndReusesOnlyMatchingEvidence()=runTest {
        val root=Files.createTempDirectory("hc-auto-content").toFile()
        try {
            val source=sample(root,"123-gen1");val lines=mutableListOf<String>();var decodes=0
            val decode:suspend(File,File,File)->JSONObject={_,_,_->decodes++;JSONObject().put("frames",2).put("nearBlackFrames",2).put("decoder","test")}
            AutomaticVideoEvidence.auditForLog(root,{true},decode,lines::add)
            assertTrue(lines.single().contains("classification=all_near_black"))
            assertTrue(lines.single().contains("vehicleDisplayVerified=false"))
            lines.clear()
            AutomaticVideoEvidence.auditForLog(root,{false},{_,_,_->error("Cached evidence must not create a decoder")},lines::add)
            assertTrue(lines.single().contains("cached=true"));assertEquals(1,decodes)
            VideoEvidence.write(File(source,"source.packets"),List(2){VideoEvidence.Packet(it.toLong(),byteArrayOf(0,0,0,1,0x65,1))})
            lines.clear();AutomaticVideoEvidence.auditForLog(root,{true},decode,lines::add)
            assertTrue(lines.single().contains("cached=false"));assertEquals(2,decodes)
        }finally{root.deleteRecursively()}
    }
    @Test fun activeOrIncompleteSessionsCannotTriggerDecodeOrHideTheZeroFrameAttempt()=runTest {
        val root=Files.createTempDirectory("hc-auto-pending").toFile()
        try {
            sample(root,"100-gen1");sample(root,"101-gen2",closed=false);sample(root,"102-gen3",frames=0)
            val lines=mutableListOf<String>()
            AutomaticVideoEvidence.auditForLog(root,{false},{_,_,_->error("Active sessions must not be disturbed")},lines::add)
            assertTrue(lines.any{it.contains("batch-auto-102-gen3 status=no_source_frames")})
            assertTrue(lines.any{it.contains("batch-auto-101-gen2 status=sample_incomplete")})
            assertTrue(lines.any{it.contains("batch-auto-100-gen1 status=deferred_active_session")})
        }finally{root.deleteRecursively()}
    }
    @Test fun partialDecodeCannotEstablishBlackSourceAndOnlyOneNewSampleIsDecoded()=runTest {
        val root=Files.createTempDirectory("hc-auto-partial").toFile()
        try {
            sample(root,"100-gen1");sample(root,"101-gen2");val lines=mutableListOf<String>();var decodes=0
            AutomaticVideoEvidence.auditForLog(root,{true},{_,_,_->decodes++;JSONObject().put("frames",1).put("nearBlackFrames",1)},lines::add)
            assertEquals(1,decodes)
            assertTrue(lines.first().contains("classification=partial_decode"))
            assertTrue(lines.last().contains("status=deferred_export_budget"))
        }finally{root.deleteRecursively()}
    }
    @Test fun failedAndCancelledDecodesAreNeverCachedAsSuccessfulPictures()=runTest {
        val root=Files.createTempDirectory("hc-auto-failed").toFile()
        try {
            val source=sample(root,"123-gen1");val lines=mutableListOf<String>()
            AutomaticVideoEvidence.auditForLog(root,{true},{_,_,_->error("decoder failed")},lines::add)
            assertTrue(lines.single().contains("status=audit_failed"))
            assertFalse(File(source,"content-summary.json").exists())
            assertNull(withTimeoutOrNull(6_000){AutomaticVideoEvidence.auditForLog(root,{true},{_,_,_->awaitCancellation()},lines::add)})
            assertFalse(File(source,"content-summary.json").exists())
        }finally{root.deleteRecursively()}
    }
    @Test fun nonBlackPixelsDoNotProveAJoviDesktopOrCarDisplay()=runTest {
        val root=Files.createTempDirectory("hc-auto-nonblack").toFile()
        try {
            sample(root,"123-gen1");val lines=mutableListOf<String>()
            AutomaticVideoEvidence.auditForLog(root,{true},{_,_,_->JSONObject().put("frames",2).put("nearBlackFrames",0)},lines::add)
            assertTrue(lines.single().contains("classification=contains_non_black"))
            assertTrue(lines.single().contains("sourceDesktopVerified=false vehicleDisplayVerified=false"))
        }finally{root.deleteRecursively()}
    }
    @Test fun laterZeroFrameAttemptsDoNotHideEarlierPreservedVideo()=runTest {
        val root=Files.createTempDirectory("hc-auto-earlier").toFile()
        try {
            sample(root,"100-gen1")
            repeat(5){sample(root,"${101+it}-gen${it+2}",frames=0)}
            val lines=mutableListOf<String>()
            AutomaticVideoEvidence.auditForLog(root,{true},{_,_,_->JSONObject().put("frames",2).put("nearBlackFrames",2)},lines::add)
            assertTrue(lines.any{it.contains("batch=batch-auto-100-gen1 status=decoded")})
            assertTrue(lines.any{it.contains("status=no_source_frames")})
        }finally{root.deleteRecursively()}
    }
    @Test fun ordinarySampleCanBeExportedWithoutRunningTheManualPreflight() {
        val root=Files.createTempDirectory("hc-auto-capture").toFile()
        try {
            val source=AutomaticVideoEvidence.prepare(root,"batch-auto-123-gen1","test")
            VideoEvidence.write(File(source,"source.packets"),listOf(VideoEvidence.Packet(1,byteArrayOf(0,0,0,1,0x65))))
            val bytes=java.io.ByteArrayOutputStream();VideoEvidence.archive(requireNotNull(source.parentFile),bytes)
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
