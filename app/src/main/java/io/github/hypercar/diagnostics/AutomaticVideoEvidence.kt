package io.github.hypercar.diagnostics

import java.io.File
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
}
