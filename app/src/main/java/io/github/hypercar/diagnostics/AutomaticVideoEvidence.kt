package io.github.hypercar.diagnostics

import java.io.File
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** Short, bounded samples of the source already being projected; no phone-screen capture or upload. */
internal object AutomaticVideoEvidence {
    fun prepare(root:File,id:String,version:String):File {
        require(id.matches(Regex("batch-auto-[a-zA-Z0-9-]+")))
        val batch=File(root,id)
        require(!batch.exists()) {"自动采样批次已存在"}
        check(batch.mkdirs()) {"无法创建自动视频证据目录"}
        try {
            DiagnosticRetention.prune(root,batch)
            File(batch,"batch.json").writeText(JSONObject().put("id",id).put("version",version)
                .put("automatic",true).put("source","com.baidu.carlife.vivo")
                .put("stage","普通连接自动视频采样；画面内容未确认").put("sourceConfirmed",false)
                .put("startedAt",System.currentTimeMillis()).toString(2))
            return File(batch,"source").apply {check(mkdirs())}
        }catch(e:Exception){batch.deleteRecursively();throw e}
    }

    /** Analyze at most one closed source sample per log export. Never start/restart a connection. */
    suspend fun auditForLog(
        root:File,
        canDecode:()->Boolean,
        decode:suspend (File,File,File)->JSONObject,
        trace:(String)->Unit,
    ) {
        val batches=root.listFiles().orEmpty().filter{it.isDirectory && it.name.matches(Regex("batch-auto-[a-zA-Z0-9-]+"))}
            .sortedByDescending{it.name}.take(16)
        if(batches.isEmpty()){trace("status=no_automatic_sample sourceDesktopVerified=false vehicleDisplayVerified=false");return}
        var attempted=false
        for(batch in batches) {
            val prefix="batch=${batch.name}"
            try {
                val source=File(batch,"source")
                val sample=File(source,"source.packets");val metadata=File(source,"source.json")
                if(!metadata.isFile){trace("$prefix status=sample_not_closed");continue}
                val capture=JSONObject(metadata.readText())
                if(!capture.optBoolean("closed") || !capture.isNull("error")) {
                    trace("$prefix status=sample_incomplete error=${LogSanitizer.clean(capture.optString("error"))}");continue
                }
                val captured=capture.optInt("frames")
                if(captured<=0){trace("$prefix status=no_source_frames sourceDesktopVerified=false");continue}
                require(sample.length() in 9..VideoEvidence.MAX_BYTES.toLong()) {"来源样本大小非法"}
                val sampleHash=VideoEvidence.sha(sample);val metadataHash=VideoEvidence.sha(metadata)
                val summaryFile=File(source,"content-summary.json")
                var summary=runCatching{JSONObject(summaryFile.readText())}.getOrNull()?.takeIf {
                    it.optInt("schema")==1 && it.optString("sampleSha256")==sampleHash && it.optString("metadataSha256")==metadataHash
                }
                val cached=summary!=null
                if(summary==null) {
                    if(attempted){trace("$prefix status=deferred_export_budget");continue}
                    if(!canDecode()){trace("$prefix status=deferred_active_session sourceDesktopVerified=false");continue}
                    attempted=true
                    val result=decode(sample,metadata,File(source,"preview"))
                    val frames=result.getInt("frames");val black=result.getInt("nearBlackFrames")
                    require(frames in 1..captured && black in 0..frames) {"解码结果数量与样本不一致"}
                    val classification=when {
                        frames!=captured->"partial_decode"
                        black==frames->"all_near_black"
                        else->"contains_non_black"
                    }
                    summary=JSONObject().put("schema",1).put("sampleSha256",sampleHash).put("metadataSha256",metadataHash)
                        .put("sampleFrames",captured).put("decodedFrames",frames).put("nearBlackFrames",black)
                        .put("classification",classification).put("decoder",result.optString("decoder"))
                        .put("sourceDesktopVerified",false).put("vehicleDisplayVerified",false)
                    summaryFile.writeText(summary.toString(2))
                }
                trace("$prefix status=decoded cached=$cached sampleSha256=$sampleHash sampleFrames=${summary.getInt("sampleFrames")} decodedFrames=${summary.getInt("decodedFrames")} nearBlackFrames=${summary.getInt("nearBlackFrames")} classification=${summary.getString("classification")} decoder=${summary.optString("decoder")} sourceDesktopVerified=false vehicleDisplayVerified=false")
            }catch(e:CancellationException){throw e}
            catch(e:Exception){trace("$prefix status=audit_failed error=${LogSanitizer.clean(e.message?:e.javaClass.simpleName)} sourceDesktopVerified=false vehicleDisplayVerified=false")}
        }
    }
}
