package io.github.hypercar.diagnostics

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticBatchControllerTest {
    @Test fun failedPreflightsPreserveAndCanExportEarlierSuccessfulSource()=runTest {
        val root=Files.createTempDirectory("hc-keep-source").toFile()
        try {
            val ports=Ports();val c=DiagnosticBatchController(root,"test",ports,this)
            c.startPreflight();advanceUntilIdle();val good=c.state.value.id
            ports.failProbe=true
            repeat(5){c.startPreflight();advanceUntilIdle()}
            assertTrue(File(root,"$good/source/source.packets").isFile)
            assertTrue(c.history().contains(good));val current=c.state.value.id
            c.exportHistory(good);advanceUntilIdle()
            assertEquals(current,c.state.value.id);assertEquals("test.zip",c.state.value.exportPath)
            assertTrue(File(root,"$good/exported.json").isFile)
        }finally{root.deleteRecursively()}
    }
    @Test fun failedClaimHasEvidenceWithoutStoppingAnUnownedConnection()=runTest {
        val root=Files.createTempDirectory("hc-claim-log").toFile()
        try {
            val ports=Ports().apply{failClaim=true};val controller=DiagnosticBatchController(root,"test",ports,this)
            controller.startPreflight();advanceUntilIdle()
            assertFalse(controller.state.value.busy);assertEquals(0,ports.stops);assertEquals(0,ports.releases)
            val text=File(root,"${controller.state.value.id}/timeline.txt").readText()
            assertTrue(text.contains("resource.claim.failed"));assertTrue(text.contains("another connection owns resources"))
        }finally{root.deleteRecursively()}
    }
    @Test fun releaseFailureStillFinishesTheOperationAndIsNotSilentlyLost()=runTest {
        val root=Files.createTempDirectory("hc-cleanup-log").toFile()
        try {
            val ports=Ports().apply{failRelease=true};val controller=DiagnosticBatchController(root,"test",ports,this)
            controller.startPreflight();advanceUntilIdle()
            assertFalse(controller.state.value.busy);assertEquals("收尾异常",controller.state.value.stage)
            val events=File(root,"${controller.state.value.id}/timeline.txt").readLines().map(::JSONObject)
            assertTrue(events.any{it.getString("event")=="resource.release.failed"})
            assertTrue(events.any{it.getString("event")=="operation.finished"})
        }finally{root.deleteRecursively()}
    }
    @Test fun failedSourceStepIsPersistedWithCauseEvenWhenOrdinaryLogIsEmpty()=runTest {
        val root=Files.createTempDirectory("hc-stage-log").toFile()
        try {
            val ports=Ports().apply{failProbe=true}
            val controller=DiagnosticBatchController(root,"test",ports,this)
            controller.startPreflight();advanceUntilIdle()
            val batch=File(root,controller.state.value.id)
            assertTrue("critical steps must have their own durable log",File(batch,"timeline.txt").isFile)
            val events=File(batch,"timeline.txt").readLines().map(::JSONObject)
            val failed=events.first{it.getString("event")=="source.probe.failed"}
            assertTrue(failed.getJSONObject("data").getLong("elapsedMs")>=0)
            assertTrue(failed.toString().contains("IllegalArgumentException"))
            assertFalse(failed.toString().contains("secret-value"))
            assertTrue(events.any{it.getString("event")=="operation.finished"})
            assertTrue(batch.walkTopDown().filter{it.isFile && it.extension in setOf("txt","json")}.none{it.readText().contains("secret-value")})
        }finally{root.deleteRecursively()}
    }
    @Test fun interruptedPreflightNeverRunsUnverifiedPoliciesOnTheCar()=runTest {
        val root=Files.createTempDirectory("hc-partial-preflight").toFile()
        try {
            val ports=Ports().apply{blockReplay=VideoStrategy.C}
            val c=DiagnosticBatchController(root,"test",ports,this)
            c.startPreflight();runCurrent();c.cancel();advanceUntilIdle()
            assertEquals(listOf(VideoStrategy.A,VideoStrategy.B),c.state.value.trials.filter{it.localPassed}.map{it.policy})
            c.confirmSource(true);c.startVehicleTrials();runCurrent()
            for(policy in listOf(VideoStrategy.A,VideoStrategy.B)) {
                assertEquals(policy,c.state.value.current)
                c.observe(c.state.value.caseId,"Hypercar 等待页");advanceTimeBy(3_100);runCurrent()
            }
            assertFalse(c.state.value.busy)
            assertEquals(listOf(VideoStrategy.A,VideoStrategy.B),ports.cars.map{it.first})
        }finally{root.deleteRecursively()}
    }
    @Test fun immediateCancelBeforeDispatcherStartsNeverLeavesBusyState()=runTest {
        val root=Files.createTempDirectory("hc-immediate-cancel").toFile()
        try {
            val c=DiagnosticBatchController(root,"test",Ports().apply{blockProbe=true},this)
            c.startPreflight();c.cancel();advanceUntilIdle()
            assertFalse("An immediately cancelled UI action must release the busy state",c.state.value.busy)
        }finally{root.deleteRecursively()}
    }
    private class Ports:DiagnosticPorts {
        var blockReplay:VideoStrategy?=null
        var failProbe=false
        var failClaim=false
        var failRelease=false
        var claims=0;var releases=0;var stops=0;var blockProbe=false;var saved:VideoStrategy?=null
        val cars=mutableListOf<Pair<VideoStrategy,Boolean>>()
        override suspend fun claim(){claims++;if(failClaim)error("another connection owns resources")}
        override suspend fun probe(directory:File){directory.mkdirs();if(failProbe)throw IllegalStateException("source failed password=secret-value",IllegalArgumentException("underlying failure"));if(blockProbe)awaitCancellation();File(directory,"source.packets").writeText("test fixture")}
        override suspend fun replay(input:File,directory:File,policy:VideoStrategy){assertTrue(input.isFile);directory.mkdirs();if(policy==blockReplay)awaitCancellation()}
        override suspend fun decode(sample:File,metadata:File,directory:File):JSONObject {
            directory.mkdirs();File(directory,"first.png").writeText("test fixture")
            return JSONObject().put("frames",1).put("pixels",JSONArray().put(JSONObject().put("sha256","same-pixels")))
        }
        override suspend fun transcode(input:File,metadata:File,directory:File)=input
        override suspend fun connect(policy:VideoStrategy,directory:File,audio:Boolean){cars.add(policy to audio)}
        override suspend fun stop(){stops++}
        override fun release(){releases++;if(failRelease)error("resource release failed")}
        override fun logs()=if(failProbe)emptyList()else listOf("test log")
        override fun save(policy:VideoStrategy){saved=policy}
        override suspend fun export(directory:File)="test.zip"
    }
    @Test fun allCasesRequireHumanDisplayAndOnlyConfirmedPolicyCanPersist()=runTest {
        val root=Files.createTempDirectory("hc-controller").toFile()
        try {
            val ports=Ports();val c=DiagnosticBatchController(root,"test",ports,this)
            c.startVehicleTrials();runCurrent();assertEquals(0,ports.claims)
            c.startPreflight();advanceUntilIdle();assertEquals(5,c.state.value.trials.count{it.status=="本地通过"})
            assertFalse(c.state.value.sourceConfirmed)
            c.confirmSource(true);c.startVehicleTrials();runCurrent()
            for((index,policy) in VideoStrategy.entries.withIndex()) {
                assertEquals(policy,c.state.value.current)
                c.observe(c.state.value.caseId,if(index==2)"正常 Jovi" else "Hypercar 等待页");runCurrent();advanceTimeBy(3_100);runCurrent()
            }
            assertFalse(c.state.value.busy);assertEquals(VideoStrategy.entries,ports.cars.map{it.first});assertTrue(ports.cars.all{!it.second})
            c.validate(VideoStrategy.A);runCurrent();assertEquals(5,ports.cars.size)
            c.validate(VideoStrategy.C);runCurrent();assertTrue(ports.cars.last().second)
            c.cancel();advanceUntilIdle();c.saveSelected();assertEquals(VideoStrategy.C,ports.saved)
            assertEquals("未确认",c.state.value.audio);assertEquals(ports.claims,ports.releases)
        }finally{root.deleteRecursively()}
    }
    @Test fun cancellationAndLateAnswersCannotStartOrPassAnotherCase()=runTest {
        val root=Files.createTempDirectory("hc-cancel").toFile()
        try {
            val ports=Ports().apply{blockProbe=true};val c=DiagnosticBatchController(root,"test",ports,this)
            c.startPreflight();runCurrent();c.startPreflight();assertEquals(1,ports.claims)
            c.cancel();advanceUntilIdle();c.observe("expired-case","正常 Jovi")
            assertFalse(c.state.value.busy);assertTrue(c.state.value.trials.none{it.display=="正常 Jovi"});assertEquals(1,ports.releases);assertTrue(ports.stops>0)
            val restored=DiagnosticBatchController(root,"test",ports,this)
            assertTrue(restored.state.value.stage.contains("中断"));assertFalse(restored.state.value.sourceConfirmed)
            restored.confirmSource(true);assertFalse(restored.state.value.sourceConfirmed)
            restored.startVehicleTrials();runCurrent();assertTrue(ports.cars.isEmpty())
        }finally{root.deleteRecursively()}
    }
}
