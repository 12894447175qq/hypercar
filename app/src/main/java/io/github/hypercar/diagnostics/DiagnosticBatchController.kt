package io.github.hypercar.diagnostics

import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

internal enum class VideoStrategy(val wire:String,val title:String) {
    A("original","A 原始转发"), B("level40","B Level 4.0 兼容"), C("inline_headers","C 关键帧携带参数"),
    D("level40_inline","D 两项组合"), E("hardware_transcode","E 硬件重编码")
}
internal data class DiagnosticTrial(val policy:VideoStrategy,val status:String="未执行",val detail:String="",val display:String="未确认",val localPassed:Boolean=false)
internal data class DiagnosticBatchState(
    val id:String="", val busy:Boolean=false,val stage:String="尚未开始",val sourceConfirmed:Boolean=false,
    val sourcePreview:String?=null,val message:String="",val current:VideoStrategy?=null,
    val trials:List<DiagnosticTrial> = VideoStrategy.entries.map(::DiagnosticTrial),
    val audio:String="未确认",val touch:String="未确认",val reconnect:String="未确认",val selected:VideoStrategy?=null,
    val exportPath:String?=null,val caseId:String?=null,val restored:Boolean=false,
)
internal interface DiagnosticPorts {
    suspend fun claim()
    suspend fun probe(directory:File)
    suspend fun replay(input:File,directory:File,policy:VideoStrategy)
    suspend fun decode(sample:File,metadata:File,directory:File):JSONObject
    suspend fun transcode(input:File,metadata:File,directory:File):File
    suspend fun connect(policy:VideoStrategy,directory:File,audio:Boolean)
    suspend fun stop()
    fun release()
    fun connectionHealthy():Boolean = true
    fun logs():List<String>
    fun save(policy:VideoStrategy)
    suspend fun export(directory:File):String
}

/** UI decisions are separate from transport counters. A timed-out trial can never pass. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DiagnosticBatchController(private val root:File,private val version:String,private val ports:DiagnosticPorts,private val scope:CoroutineScope) {
    private val mutable=MutableStateFlow(DiagnosticBatchState());val state=mutable.asStateFlow()
    private var operation:Job?=null;private var verdict:CompletableDeferred<String>?=null
    private var claimed=false
    private val directory:File get()=File(root,state.value.id)
    init {
        root.mkdirs()
        root.listFiles()?.filter{it.isDirectory && it.name.startsWith("batch-")}?.maxByOrNull{it.lastModified()}?.let { d ->
            runCatching {
                val data=JSONObject(File(d,"batch.json").readText())
                val entries=data.optJSONArray("trials") ?: JSONArray()
                val trials=VideoStrategy.entries.map {policy ->
                    val saved=(0 until entries.length()).map{entries.getJSONObject(it)}.firstOrNull{it.optString("policy")==policy.wire}
                    DiagnosticTrial(policy,saved?.optString("status")?:"未执行",saved?.optString("detail").orEmpty(),saved?.optString("display")?:"未确认",saved?.optBoolean("localPassed",false)==true)
                }
                File(d,"interrupted-state.json").writeText(data.toString(2))
                mutable.value=DiagnosticBatchState(id=d.name,stage="历史批次已中断；可导出或新建",message="不会自动重连；原始结果保存在诊断包中",sourcePreview=File(d,"source/preview/first.png").takeIf{it.isFile}?.path,
                    trials=trials,audio=data.optString("audio","未确认"),touch=data.optString("touch","未确认"),reconnect=data.optString("reconnect","未确认"),restored=true)
                data.put("restoredAfterProcessExit",true).put("activeOutcome","中断/未确认")
                File(d,"batch.json").writeText(data.toString(2))
            }
        }
    }
    @Synchronized private fun update(block:(DiagnosticBatchState)->DiagnosticBatchState) {
        mutable.value=block(state.value)
        if(state.value.id.isNotEmpty())persist()
    }
    private fun persist() {
        val s=state.value;directory.mkdirs()
        val json=JSONObject().put("id",s.id).put("version",version).put("stage",s.stage).put("busy",s.busy)
            .put("source","com.baidu.carlife.vivo").put("sourceConfirmed",s.sourceConfirmed).put("message",s.message)
            .put("audio",s.audio).put("touch",s.touch).put("reconnect",s.reconnect).put("selected",s.selected?.wire)
            .put("trials",JSONArray(s.trials.map {JSONObject().put("policy",it.policy.wire).put("status",it.status).put("detail",it.detail).put("display",it.display).put("localPassed",it.localPassed)}))
        val temp=File(directory,"batch.json.tmp");temp.writeText(json.toString(2))
        java.nio.file.Files.move(temp.toPath(),File(directory,"batch.json").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
    @Synchronized private fun execute(block:suspend ()->Unit) {
        if(operation?.isActive==true)return
        update{it.copy(busy=true,exportPath=null)}
        operation=scope.launch(start=CoroutineStart.ATOMIC) {
            try {block()}
            catch(e:CancellationException){update{it.copy(stage="已取消",message="已完成证据保留，未观察结果不计通过")};throw e}
            catch(e:Exception){update{it.copy(stage="阻塞",message=e.message?:e.javaClass.simpleName)}}
            finally {
                withContext(NonCancellable) {
                    if(claimed) {
                        runCatching {ports.stop()}.onFailure{update{s->s.copy(message=s.message+"；资源退出失败：${it.message}")}}
                        ports.release();claimed=false
                    }
                    if(state.value.id.isNotEmpty())runCatching{File(directory,"session.txt").writeText(ports.logs().joinToString("\n").takeLast(4*1024*1024))}
                    verdict=null;update{it.copy(busy=false,current=null,caseId=null)}
                }
            }
        }
    }
    private suspend fun own() {ports.claim();claimed=true}
    fun startPreflight()=execute {
        own()
        root.listFiles()?.filter{it.isDirectory && it.name.matches(Regex("batch-[a-zA-Z0-9-]+"))}?.sortedByDescending{it.lastModified()}?.drop(2)?.forEach{VideoEvidence.files(it);it.deleteRecursively()}
        val id="batch-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
        update{DiagnosticBatchState(id=id,busy=true,stage="检查真实 vivo 来源")}
        val source=File(directory,"source");VideoEvidence.reserve(directory)
        ports.probe(source);ports.stop()
        val sourceDecoded=ports.decode(File(source,"source.packets"),File(source,"source.json"),File(source,"preview"))
        check(sourceDecoded.getInt("frames")>0)
        update{it.copy(sourcePreview=File(source,"preview/first.png").path)}
        for(policy in VideoStrategy.entries) {
            currentCoroutineContext().ensureActive();VideoEvidence.reserve(directory)
            update{it.copy(stage="本地传输与解码：${policy.title}",current=policy)}
            val case=File(directory,"local-${policy.wire}");case.mkdirs()
            try {
                val input=if(policy==VideoStrategy.E) ports.transcode(File(source,"source.packets"),File(source,"source.json"),case) else File(source,"source.packets")
                ports.replay(input,case,policy);ports.stop()
                val received=ports.decode(File(case,"transport.packets"),File(case,"transport.json"),File(case,"preview"))
                check(received.getInt("frames")==sourceDecoded.getInt("frames")) {"接收端解码帧数不一致"}
                if(policy!=VideoStrategy.E) {
                    fun hashes(j:JSONObject):List<String> = j.getJSONArray("pixels").let {a->(0 until a.length()).map{a.getJSONObject(it).getString("sha256")}}
                    check(hashes(received)==hashes(sourceDecoded)) {"接收端解码像素与原始来源不一致"}
                }
                trial(policy){it.copy(status="本地通过",localPassed=true,detail="真实TCP解密与手机解码完成；实车未确认")}
            } catch(e:CancellationException){throw e}
            catch(e:Exception){trial(policy){it.copy(status="本地阻塞",detail=e.message.orEmpty())}}
            finally {ports.stop()}
        }
        update{it.copy(stage="请确认预览内容",message="只有预览确为 Jovi 桌面才进入车测；原厂协议提示请在原厂页面处理")}
    }
    @Synchronized private fun trial(policy:VideoStrategy,block:(DiagnosticTrial)->DiagnosticTrial)=update{it.copy(trials=it.trials.map {t->if(t.policy==policy)block(t) else t})}
    fun confirmSource(normal:Boolean) {if(!state.value.restored && !state.value.busy && state.value.sourcePreview!=null)update{it.copy(sourceConfirmed=normal,stage=if(normal)"可开始车测" else "来源画面阻塞",message=if(normal)"到车旁后启动轮测；仅测试已通过本地解码的方案，每轮可能需要重新选择 Apple CarPlay" else "请导出证据，来源提示/黑屏不能当正常桌面")}}
    fun startVehicleTrials() {
        if(!state.value.sourceConfirmed || state.value.trials.none{it.localPassed})return
        execute {
            own()
            for(policy in VideoStrategy.entries) {
                currentCoroutineContext().ensureActive()
                if(!state.value.trials.first{it.policy==policy}.localPassed)continue
                VideoEvidence.reserve(directory)
                val answer=CompletableDeferred<String>();verdict=answer
                val token=UUID.randomUUID().toString()
                update{it.copy(stage="连接车机：${policy.title}",current=policy,caseId=token,message="蓝牙及车机热点就绪后，在车机选择 Apple CarPlay；请停车操作")}
                val case=File(directory,"car-${policy.wire}-${System.currentTimeMillis()}")
                try {
                    ports.connect(policy,case,false)
                    trial(policy){it.copy(status="等待实车观察")}
                    update{it.copy(stage="观察车机：${policy.title}")}
                    val result=withTimeoutOrNull(90_000){
                        while(!answer.isCompleted){check(ports.connectionHealthy()) {"观察期间连接已断开"};delay(100)}
                        answer.await()
                    }?:"未观察/超时"
                    trial(policy){it.copy(status=if(result=="正常 Jovi")"用户确认显示" else "车测未通过",display=result)}
                }catch(e:CancellationException){trial(policy){it.copy(status="已取消",detail="本轮未完成观察")};throw e}
                catch(e:Exception){trial(policy){it.copy(status="连接失败",detail=e.message.orEmpty())}}
                finally{withContext(NonCancellable){
                    verdict=null;ports.stop()
                    case.mkdirs();val result=state.value.trials.first{it.policy==policy}
                    File(case,"outcome.json").writeText(JSONObject().put("status",result.status).put("display",result.display).put("detail",result.detail).toString(2))
                }}
                delay(3_000)
            }
            update{it.copy(stage="轮测完成",message="选择已显示 Jovi 的方案，验证声音、触控和重连")}
        }
    }
    @Synchronized fun observe(caseId:String?,value:String) {
        if(caseId!=null && caseId==state.value.caseId && state.value.stage.startsWith("观察车机") && value in listOf("正常 Jovi","Hypercar 等待页","黑屏","卡住/断开"))verdict?.complete(value)
    }
    fun validate(policy:VideoStrategy,reconnect:Boolean=false) {
        if(state.value.restored || !state.value.sourceConfirmed || state.value.trials.none{it.policy==policy && it.localPassed && it.display=="正常 Jovi"})return
        execute {
            own();VideoEvidence.reserve(directory)
            update{it.copy(selected=policy,current=policy,stage=if(reconnect)"重连验证" else "音视频与触控验证",message="观察3分钟，手动点击车机界面并播放声音；完成后记录结果并停止",
                audio=if(reconnect && it.selected==policy)it.audio else "未确认",touch=if(reconnect && it.selected==policy)it.touch else "未确认",reconnect="未确认")}
            val case=File(directory,"validation-${System.currentTimeMillis()}");case.mkdirs()
            val record=JSONObject().put("policy",policy.wire).put("connectionStartedAt",System.currentTimeMillis());var connectedAt:Long?=null
            try {
                ports.connect(policy,case,true);connectedAt=System.currentTimeMillis();record.put("connectedAt",connectedAt)
                if(reconnect)update{it.copy(reconnect="协议重新连通，显示待确认")}
                repeat(180) {delay(1_000);check(ports.connectionHealthy()) {"稳定观察期间连接断开，声音和触控结果请单独记录"}}
                record.put("protocolStableForThreeMinutes",true)
                update{it.copy(stage="稳定观察结束",message="已连接 ${System.currentTimeMillis()-connectedAt}ms；显示、声音及触控仍以用户记录为准")}
            } catch(e:Exception) {record.put("endedReason",e.message?:e.javaClass.simpleName);throw e}
            finally {record.put("endedAt",System.currentTimeMillis()).put("connectedDurationMs",connectedAt?.let{System.currentTimeMillis()-it});File(case,"validation.json").writeText(record.toString(2))}
        }
    }
    fun acceptance(kind:String,ok:Boolean) {val value=if(ok)"用户确认正常" else "用户确认异常";update {when(kind){"audio"->it.copy(audio=value);"touch"->it.copy(touch=value);"reconnect"->it.copy(reconnect=value);else->it}}}
    fun saveSelected() {val s=state.value;val p=s.selected?:return;if(s.trials.any{it.policy==p && it.display=="正常 Jovi"}){ports.save(p);update{it.copy(message="已保存 ${p.title}，其他设置未修改")}}}
    fun resetPolicy() {if(!state.value.busy){ports.save(VideoStrategy.A);update{it.copy(message="已恢复普通连接的原始视频策略")}}}
    fun cancel() {operation?.cancel()}
    fun export()=execute {check(state.value.id.isNotEmpty());File(directory,"session.txt").writeText(ports.logs().joinToString("\n").takeLast(4*1024*1024));update{it.copy(stage="正在导出")};val path=ports.export(directory);update{it.copy(exportPath=path,stage="已导出")}}
}
