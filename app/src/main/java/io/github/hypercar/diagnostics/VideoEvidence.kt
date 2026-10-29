package io.github.hypercar.diagnostics

import java.io.*
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

internal object VideoEvidence {
    const val MAX_BYTES = 8 * 1024 * 1024
    const val MAX_BATCH = 128L * 1024 * 1024
    val magic = byteArrayOf(72,67,86,49,0,0,0,0)
    data class Packet(val ptsUs: Long, val data: ByteArray)
    fun read(file: File): List<Packet> {
        require(file.length() in 8..MAX_BYTES.toLong()) { "视频样本大小非法" }
        return DataInputStream(file.inputStream().buffered()).use { input ->
            val head=ByteArray(8); input.readFully(head); require(head.contentEquals(magic)) { "视频样本版本不支持" }
            buildList {
                while (input.available()>0) {
                    val pts=input.readLong(); val length=input.readInt()
                    require(pts>=0 && length in 1..4*1024*1024 && size<90) { "样本记录超过限制" }
                    add(Packet(pts,ByteArray(length).also(input::readFully)))
                }
                require(isNotEmpty()) { "没有真实来源视频帧" }
            }
        }
    }
    fun write(file: File, packets: List<Packet>) {
        require(packets.isNotEmpty() && packets.size<=90)
        require(8L+packets.sumOf { 12L+it.data.size }<=MAX_BYTES)
        file.parentFile?.mkdirs()
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.write(magic)
            packets.forEach { require(it.data.size in 1..4*1024*1024); out.writeLong(it.ptsUs); out.writeInt(it.data.size); out.write(it.data) }
        }
    }
    fun nals(data:ByteArray): List<ByteArray> {
        fun start(at:Int):Int = if(at+3<=data.size && data[at]==0.toByte() && data[at+1]==0.toByte()) {
            if(data[at+2]==1.toByte()) 3 else if(at+4<=data.size && data[at+2]==0.toByte() && data[at+3]==1.toByte()) 4 else 0
        } else 0
        val result=mutableListOf<ByteArray>(); var p=0;var from=-1
        while(p<data.size) { val n=start(p);if(n>0) {if(from>=0 && p>from) result.add(data.copyOfRange(from,p));from=p+n;p+=n} else p++ }
        if(from>=0 && from<data.size) result.add(data.copyOfRange(from,data.size))
        return result
    }
    fun annex(nals:List<ByteArray>) = ByteArrayOutputStream().use { out -> nals.forEach {out.write(byteArrayOf(0,0,0,1));out.write(it)};out.toByteArray() }
    fun sha(file:File):String=MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().use { input-> val b=ByteArray(16384);while(true){val n=input.read(b);if(n<0)break;digest.update(b,0,n)} };digest.digest().joinToString(""){"%02x".format(it)}
    }
    private val allowed=setOf("json","txt","png","packets")
    fun files(root:File):List<File> {
        require(!Files.isSymbolicLink(root.toPath()))
        val canonical=root.canonicalFile
        val result=mutableListOf<File>()
        root.walkTopDown().onEnter { dir-> require(!Files.isSymbolicLink(dir.toPath()));true }.forEach { f ->
            require(!Files.isSymbolicLink(f.toPath())) { "诊断包不允许符号链接" }
            require(f.canonicalFile.toPath().startsWith(canonical.toPath())) { "诊断文件越界" }
            if(f.isFile) {require(f.extension in allowed && !f.name.contains("identity",true) && !f.name.contains("key",true)) { "诊断目录含非证据文件" };result.add(f)}
        }
        require(result.sumOf { it.length() }<=MAX_BATCH) { "诊断批次超过128MiB" }
        return result.sortedBy { it.relativeTo(root).path }
    }
    fun reserve(root:File, bytes:Long=36L*1024*1024) {require(files(root).sumOf {it.length()}+bytes<=MAX_BATCH) { "本批次空间不足，请导出并新建批次" }}
    fun archive(root:File, output:OutputStream) {
        val evidence=files(root);require(evidence.any{it.name=="batch.json"}) { "缺少批次摘要" }
        val manifest=JSONArray()
        ZipOutputStream(output).use { zip ->
            evidence.forEach { f ->
                val name=f.relativeTo(root).invariantSeparatorsPath
                val digest=sha(f)
                zip.putNextEntry(ZipEntry(name));f.inputStream().use{it.copyTo(zip)};zip.closeEntry()
                require(digest==sha(f)) { "导出期间证据发生变化" }
                manifest.put(JSONObject().put("path",name).put("bytes",f.length()).put("sha256",digest))
            }
            zip.putNextEntry(ZipEntry("manifest.json"));zip.write(JSONObject().put("files",manifest).toString(2).toByteArray());zip.closeEntry()
        }
    }
}
