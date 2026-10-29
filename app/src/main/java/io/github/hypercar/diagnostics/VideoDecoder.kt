package io.github.hypercar.diagnostics

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/** Real decoder output; PNGs never come from UI screenshots or standby fixtures. */
internal object VideoDecoder {
    suspend fun decode(sample:File, metadata:File, output:File,trace:(String)->Unit={}):JSONObject {
        val started=System.nanoTime()
        val report=JSONObject().put("sample",sample.name).put("sampleBytes",sample.length()).put("startedAt",System.currentTimeMillis()).put("phase","read_sample")
        output.mkdirs()
        trace("begin sample=${sample.name} bytes=${sample.length()}")
        try {
            return decodeFrames(sample,metadata,output,trace,report).also {report.put("status","decoded").put("result",it)}
        } catch(e:Exception) {
            report.put("status",if(e is kotlinx.coroutines.CancellationException)"cancelled" else "failed")
                .put("error",DiagnosticJournal.errorDetails(e)).put("codecError",CodecFailure.describe(e))
            CodecFailure.trace(e,trace);throw e
        } finally {
            report.put("endedAt",System.currentTimeMillis()).put("elapsedMs",(System.nanoTime()-started)/1_000_000)
            trace("end phase=${report.optString("phase")} status=${report.optString("status")} fed=${report.optInt("fed")} decoded=${report.optInt("frames")} elapsedMs=${report.optLong("elapsedMs")}")
            runCatching {File(output,"decode-attempt.json").writeText(report.toString(2))}
                .onFailure {trace("attempt_report_write_failed ${CodecFailure.describe(it)}")}
        }
    }
    private suspend fun decodeFrames(sample:File,metadata:File,output:File,trace:(String)->Unit,report:JSONObject):JSONObject {
        val packets=VideoEvidence.read(sample)
        report.put("inputFrames",packets.size).put("phase","read_codec_metadata")
        val summary=JSONObject(metadata.readText())
        require(summary.optBoolean("closed") && summary.isNull("error")) { "来源样本未完整关闭或写入失败" }
        val codecInfo=summary.getJSONObject("codec")
        val w=codecInfo.getInt("width");val h=codecInfo.getInt("height")
        require(w in 2..4096 && h in 2..4096 && w.toLong()*h<=8_847_360) { "解码画布超过诊断内存限制" }
        val nals=VideoEvidence.nals(packets.first().data)
        val format=MediaFormat.createVideoFormat("video/avc",w,h).apply {
            setByteBuffer("csd-0",ByteBuffer.wrap(VideoEvidence.annex(listOf(nals.first {it[0].toInt() and 31==7}))))
            setByteBuffer("csd-1",ByteBuffer.wrap(VideoEvidence.annex(listOf(nals.first {it[0].toInt() and 31==8}))))
            setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,4*1024*1024)
        }
        report.put("phase","create_decoder").put("width",w).put("height",h)
        val decoder=MediaCodec.createDecoderByType("video/avc")
        report.put("decoder",decoder.name)
        trace("configure decoder=${decoder.name} width=$w height=$h inputFrames=${packets.size}")
        val hashes=JSONArray();var fed=0;var ended=false;var frames=0;var firstPts=0L
        output.mkdirs();val info=MediaCodec.BufferInfo();val deadline=System.nanoTime()+30_000_000_000L
        try {
            report.put("phase","configure_decoder");decoder.configure(format,null,null,0)
            report.put("phase","start_decoder");decoder.start()
            trace("started decoder=${decoder.name}")
            while(!ended) {
                currentCoroutineContext().ensureActive()
                check(System.nanoTime()<deadline) { "解码超过30秒" }
                if(fed<=packets.size) {
                    report.put("phase","dequeue_input")
                    val index=decoder.dequeueInputBuffer(2_000)
                    if(index>=0) {
                        if(fed==packets.size) decoder.queueInputBuffer(index,0,0,packets.last().ptsUs+1,MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        else { val p=packets[fed];val buffer=requireNotNull(decoder.getInputBuffer(index));check(buffer.capacity()>=p.data.size);buffer.clear();buffer.put(p.data);decoder.queueInputBuffer(index,0,p.data.size,p.ptsUs,0) }
                        fed++;report.put("fed",fed.coerceAtMost(packets.size))
                        if(fed==1)trace("first_input ptsUs=${packets.first().ptsUs} bytes=${packets.first().data.size}")
                    }
                }
                report.put("phase","dequeue_output")
                val index=decoder.dequeueOutputBuffer(info,2_000)
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    report.put("outputFormat",decoder.outputFormat.toString());trace("output_format=${decoder.outputFormat}")
                }
                if(index>=0) {
                    try {
                        if(info.size>0) {
                            report.put("phase","read_output_image")
                            val image=decoder.getOutputImage(index)?:error("解码器不支持读取真实输出图像")
                            image.use { img ->
                                val crop=img.cropRect;val width=crop.width();val height=crop.height()
                                val planes=img.planes;require(planes.size==3)
                                val digest=MessageDigest.getInstance("SHA-256")
                                val channels=Array(3){lane ->
                                    val scale=if(lane==0)1 else 2;val cw=width/scale;val ch=height/scale;val plane=planes[lane]
                                    ByteArray(cw*ch).also { bytes->
                                        val b=plane.buffer.duplicate();val base=b.position()
                                        for(y in 0 until ch) for(x in 0 until cw) bytes[y*cw+x]=b.get(base+(y+crop.top/scale)*plane.rowStride+(x+crop.left/scale)*plane.pixelStride)
                                        digest.update(bytes)
                                    }
                                }
                                hashes.put(JSONObject().put("ptsUs",info.presentationTimeUs).put("sha256",digest.digest().joinToString(""){"%02x".format(it)}).put("width",width).put("height",height))
                                if(frames==0 || frames==packets.size-1) {
                                    val pw=minOf(640,width);val ph=(height.toLong()*pw/width).toInt().coerceAtLeast(1)
                                    val pixels=IntArray(pw*ph)
                                    for(y in 0 until ph) for(x in 0 until pw) {
                                        val sx=x*width/pw;val sy=y*height/ph
                                        val yy=(channels[0][sy*width+sx].toInt() and 255)-16
                                        val u=(channels[1][sy/2*(width/2)+sx/2].toInt() and 255)-128
                                        val v=(channels[2][sy/2*(width/2)+sx/2].toInt() and 255)-128
                                        val rr=((298*yy+409*v+128) shr 8).coerceIn(0,255)
                                        val gg=((298*yy-100*u-208*v+128) shr 8).coerceIn(0,255)
                                        val bb=((298*yy+516*u+128) shr 8).coerceIn(0,255)
                                        pixels[y*pw+x]=(255 shl 24) or (rr shl 16) or (gg shl 8) or bb
                                    }
                                    val bitmap=Bitmap.createBitmap(pixels,pw,ph,Bitmap.Config.ARGB_8888)
                                    File(output,if(frames==0)"first.png" else "last.png").outputStream().use {check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))};bitmap.recycle()
                                }
                            }
                            if(frames==0){firstPts=info.presentationTimeUs;trace("first_decoded_image ptsUs=$firstPts")};frames++
                            report.put("frames",frames).put("lastPtsUs",info.presentationTimeUs)
                        }
                        ended=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                    } finally {decoder.releaseOutputBuffer(index,false)}
                }
            }
            report.put("phase","end_of_stream")
            check(frames>0) { "解码结果没有图像" }
            return JSONObject().put("decoder",decoder.name).put("frames",frames).put("inputFrames",packets.size).put("firstPtsUs",firstPts).put("pixels",hashes).put("vehicleDisplayVerified",false)
                .also {File(output,"decode.json").writeText(it.toString(2))}
        } catch(e:Exception) {report.put("primaryError",CodecFailure.describe(e));throw e}
        finally {
            runCatching{decoder.stop()}.onFailure{trace("decoder_stop_failed ${CodecFailure.describe(it)}")}
            try{decoder.release()}catch(e:Exception){
                report.put("releaseError",CodecFailure.describe(e));trace("decoder_release_failed ${CodecFailure.describe(e)}")
                if(!report.has("primaryError"))throw e
            }
        }
    }
}
