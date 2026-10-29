package io.github.hypercar.diagnostics

import java.io.File
import java.nio.file.Files

/** Never evict an unexported capture to make room for another failed attempt. */
internal object DiagnosticRetention {
    fun prune(root:File,current:File,maxBytes:Long=384L*1024*1024) {
        val batches=root.listFiles().orEmpty().filter {it.isDirectory && !Files.isSymbolicLink(it.toPath()) && it.name.matches(Regex("batch-[a-zA-Z0-9-]+"))}
        val protected=batches.filter{d->!File(d,"exported.json").isFile && d.walkTopDown().onEnter{!Files.isSymbolicLink(it.toPath())}.any{it.isFile && !Files.isSymbolicLink(it.toPath()) && (it.extension=="packets" && it.length()>8 || it.name=="decode.json")}}.toSet()
        val recent=batches.filter{it!=current && it !in protected}.sortedByDescending{it.lastModified()}.take(2).toSet()
        batches.filter{it!=current && it !in protected && it !in recent}.forEach {d->
            // Reuse the ZIP boundary checks before deleting only our own evidence directory.
            VideoEvidence.files(d);check(d.deleteRecursively()) {"旧诊断批次清理失败"}
        }
        val kept=batches.filter{it.exists()}
        val bytes=kept.sumOf{VideoEvidence.files(it).sumOf(File::length)}
        check(bytes+VideoEvidence.MAX_BATCH<=maxBytes && protected.size<12) {"保留的未导出诊断样本已达到容量；请先导出历史批次，再开始新预检（成功样本未删除）"}
    }
}
