package io.github.hypercar.diagnostics

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One session owns both codecs. Decoder renders directly into encoder's input Surface. */
internal class HardwareVideoTranscoder(
    private val sourceWidth:Int, private val sourceHeight:Int,
    private val targetWidth:Int, private val targetHeight:Int, private val fps:Int,
    private val output:(VideoEvidence.Packet)->Boolean, private val failed:(String)->Unit,
    private val trace:(String)->Unit = {},
) : AutoCloseable {
    private val queue=ArrayBlockingQueue<VideoEvidence.Packet>(64)
    private val acceptedInputs=java.util.concurrent.atomic.AtomicLong()
    private val inputBytes=java.util.concurrent.atomic.AtomicLong()
    private val highWater=AtomicInteger()
    private val bytes=AtomicInteger();private val closed=AtomicBoolean();private val ending=AtomicBoolean()
    private val keyframe=AtomicBoolean();private var worker:Thread?=null
    @Synchronized fun start() {check(worker==null);worker=Thread(::run,"hc-video-transcode").apply {start()}}
    fun offer(packet:VideoEvidence.Packet):Boolean {
        if(closed.get() || ending.get())return false
        val size=packet.data.size
        if(size>4*1024*1024) {trace("input_rejected reason=packet_size packetBytes=$size queueBytes=${bytes.get()}");failed("硬件转码单包超过4MiB");closed.set(true);return false}
        if(bytes.addAndGet(size)>8*1024*1024) {bytes.addAndGet(-size);trace("input_rejected reason=byte_budget queueBytes=${bytes.get()}");failed("硬件转码输入超过8MiB队列上限");closed.set(true);return false}
        if(!queue.offer(packet)) {bytes.addAndGet(-size);trace("input_rejected reason=queue_full queue=${queue.size} queueBytes=${bytes.get()}");failed("硬件转码队列已满，停止以免丢失参考帧");closed.set(true);return false}
        acceptedInputs.incrementAndGet();inputBytes.addAndGet(size.toLong());highWater.accumulateAndGet(queue.size,::maxOf)
        return true
    }
    fun matchesDimensions(sw:Int,sh:Int,tw:Int,th:Int)=sourceWidth==sw && sourceHeight==sh && targetWidth==tw && targetHeight==th
    fun finishInput() {trace("input_end_requested inputs=${acceptedInputs.get()} bytes=${inputBytes.get()}");ending.set(true)}
    fun requestKeyframe() {if(!closed.get()){trace("keyframe_requested");keyframe.set(true)}}
    fun awaitStopped(timeoutMs:Long):Boolean {worker?.join(timeoutMs);return worker?.isAlive!=true}
    override fun close() {if(!closed.getAndSet(true))trace("stop_requested queue=${queue.size} inputs=${acceptedInputs.get()}");worker?.interrupt();queue.clear();bytes.set(0)}

    private fun run() {
        var decoder:MediaCodec?=null;var encoder:MediaCodec?=null;var surface:android.view.Surface?=null
        val started=System.nanoTime();var phase="await_source";var outcome="stopped"
        var fed=0;var rendered=0;var outputFrames=0;var firstOutputMs:Long?=null
        var sampledAt=started;var lastOutput=started
        fun snapshot()="phase=$phase inputs=${acceptedInputs.get()} fed=$fed decodedToSurface=$rendered output=$outputFrames queue=${queue.size} queueBytes=${bytes.get()} highWater=${highWater.get()} outputIdleMs=${(System.nanoTime()-lastOutput)/1_000_000}"
        try {
            trace("requested source=${sourceWidth}x$sourceHeight target=${targetWidth}x$targetHeight fps=$fps mime=video/avc profile=Baseline level=4.0 bitrate=4000000")
            require(targetWidth in 2..4096 && targetHeight in 2..4096)
            val first=queue.poll(10,java.util.concurrent.TimeUnit.SECONDS)?:error("10秒内没有来源关键帧")
            bytes.addAndGet(-first.data.size)
            val nals=VideoEvidence.nals(first.data);val sps=nals.firstOrNull {it[0].toInt() and 31==7}?:error("来源缺少SPS")
            val pps=nals.firstOrNull {it[0].toInt() and 31==8}?:error("来源缺少PPS")
            require(nals.any{it[0].toInt() and 31==5}) {"转码必须从关键帧开始"}
            val encoderFormat=MediaFormat.createVideoFormat("video/avc",targetWidth,targetHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE,4_000_000);setInteger(MediaFormat.KEY_FRAME_RATE,fps.coerceIn(15,60))
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1)
                setInteger(MediaFormat.KEY_PROFILE,MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL,MediaCodecInfo.CodecProfileLevel.AVCLevel4)
            }
            val decoderFormat=MediaFormat.createVideoFormat("video/avc",sourceWidth,sourceHeight).apply {
                setByteBuffer("csd-0",ByteBuffer.wrap(VideoEvidence.annex(listOf(sps))))
                setByteBuffer("csd-1",ByteBuffer.wrap(VideoEvidence.annex(listOf(pps))))
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,4*1024*1024)
            }
            fun hardware(format:MediaFormat,encode:Boolean):String {
                var checked=0
                val selected=MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {info ->
                    if(info.isEncoder!=encode || !info.isHardwareAccelerated || info.supportedTypes.none{it.equals("video/avc",true)})false else {
                        val result=runCatching {info.getCapabilitiesForType("video/avc").isFormatSupported(format)}
                        if(checked++<12)trace("candidate role=${if(encode)"encoder" else "decoder"} name=${info.name} formatSupported=${result.getOrDefault(false)} error=${result.exceptionOrNull()?.let{CodecFailure.describe(it)}}")
                        result.getOrDefault(false)
                    }
                }
                return selected?.name ?: error("硬件${if(encode)"编码" else "解码"}器不支持请求的尺寸/格式，已检查 $checked 个硬件候选")
            }
            phase="select_codecs"
            val encoderName=hardware(encoderFormat,true);val decoderName=hardware(decoderFormat,false)
            trace("configure decoder=$decoderName encoder=$encoderName source=${sourceWidth}x$sourceHeight target=${targetWidth}x$targetHeight fps=$fps surface=true")
            phase="configure_encoder"
            val enc=MediaCodec.createByCodecName(encoderName);encoder=enc
            enc.configure(encoderFormat,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);surface=enc.createInputSurface();enc.start()
            phase="configure_decoder"
            val dec=MediaCodec.createByCodecName(decoderName);decoder=dec
            dec.configure(decoderFormat,surface,null,0);dec.start()
            val di=MediaCodec.BufferInfo();val ei=MediaCodec.BufferInfo()
            var pending:VideoEvidence.Packet?=first;var inputEnded=false;var surfaceEnded=false
            var header=emptyList<ByteArray>()
            while(!closed.get()) {
                if(keyframe.compareAndSet(true,false)) {phase="request_keyframe";enc.setParameters(Bundle().apply {putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME,0)});trace("keyframe_request_submitted outputNotYetVerified=true")}
                if(pending==null && !inputEnded) pending=queue.poll()?.also {bytes.addAndGet(-it.data.size)}
                if(pending!=null || ending.get() && queue.isEmpty() && !inputEnded) {
                    phase="decoder_input"
                    val index=dec.dequeueInputBuffer(1_000)
                    if(index>=0) {
                        val p=pending
                        if(p==null) {dec.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEnded=true}
                        else {val b=requireNotNull(dec.getInputBuffer(index));require(b.capacity()>=p.data.size);b.clear();b.put(p.data);dec.queueInputBuffer(index,0,p.data.size,p.ptsUs,0);fed++;pending=null}
                    }
                }
                phase="decoder_output"
                val d=dec.dequeueOutputBuffer(di,1_000)
                if(d>=0) {
                    val eos=di.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                    dec.releaseOutputBuffer(d,di.size>0)
                    if(di.size>0){rendered++;if(rendered==1)trace("first_surface_frame ptsUs=${di.presentationTimeUs}")}
                    if(eos && !surfaceEnded) {enc.signalEndOfInputStream();surfaceEnded=true}
                }
                phase="encoder_output"
                val e=enc.dequeueOutputBuffer(ei,1_000)
                if(e==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    trace("actual_output_format=${enc.outputFormat}")
                    header=(0..1).flatMap {lane->enc.outputFormat.getByteBuffer("csd-$lane")?.let {b-> val v=ByteArray(b.remaining());b.duplicate().get(v);VideoEvidence.nals(v)}?:emptyList()}
                } else if(e>=0) {
                    try {
                        if(ei.size>0) {
                            val b=requireNotNull(enc.getOutputBuffer(e)).duplicate();b.position(ei.offset);b.limit(ei.offset+ei.size)
                            val data=ByteArray(ei.size);b.get(data);val ns=VideoEvidence.nals(data)
                            check(ns.isNotEmpty()) {"硬件编码器返回非AnnexB格式，停止并保留错误"}
                            if(ei.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG!=0) header=ns
                            else {
                                val au=if(ns.any{it[0].toInt() and 31==5}) VideoEvidence.annex(header+ns.filter{it[0].toInt() and 31 !in listOf(7,8)}) else data
                                phase="submit_encoded_frame"
                                check(output(VideoEvidence.Packet(ei.presentationTimeUs,au))) {"转码输出会话已结束或队列已满"}
                                outputFrames++;lastOutput=System.nanoTime()
                                if(outputFrames==1){firstOutputMs=(System.nanoTime()-started)/1_000_000;trace("first_encoded_frame elapsedMs=$firstOutputMs bytes=${au.size} idr=${ns.any{it[0].toInt() and 31==5}}") }
                            }
                        }
                        if(ei.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0) {check(outputFrames>0);outcome="end_of_stream";return}
                    } finally {enc.releaseOutputBuffer(e,false)}
                }
                if(System.nanoTime()-sampledAt>=5_000_000_000L){trace("progress ${snapshot()}");sampledAt=System.nanoTime()}
                // A static source may be silent; only outstanding work times out.
                check(!(pending!=null || queue.isNotEmpty() || inputEnded) || System.nanoTime()-lastOutput<10_000_000_000L) {"硬件转码10秒未输出"}
            }
        } catch(e:Exception) {
            if(!closed.get()){outcome="failed";trace("failure ${snapshot()}");CodecFailure.trace(e,trace);failed("phase=$phase fed=$fed decoded=$rendered encoded=$outputFrames ${CodecFailure.describe(e)}")}
        } finally {
            trace("summary outcome=$outcome ${snapshot()} firstOutputMs=$firstOutputMs elapsedMs=${(System.nanoTime()-started)/1_000_000} vehicleDisplayVerified=false")
            closed.set(true)
            runCatching{decoder?.stop()}.onFailure{trace("decoder_stop_failed ${CodecFailure.describe(it)}")}
            runCatching{decoder?.release()}.onFailure{trace("decoder_release_failed ${CodecFailure.describe(it)}")}
            runCatching{encoder?.stop()}.onFailure{trace("encoder_stop_failed ${CodecFailure.describe(it)}")}
            runCatching{encoder?.release()}.onFailure{trace("encoder_release_failed ${CodecFailure.describe(it)}")}
            runCatching{surface?.release()}.onFailure{trace("surface_release_failed ${CodecFailure.describe(it)}")}
            queue.clear()
        }
    }
}
