package io.github.hypercar.diagnostics

import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Critical milestones survive ordinary-log pause/rotation. Protocol detail is bounded per case. */
internal class DiagnosticJournal(
    private val root: File,
    private val version: String,
    private val eventLimit: Long = 1024 * 1024,
    private val protocolLimit: Long = 256 * 1024,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : AutoCloseable {
    private val process = UUID.randomUUID().toString().take(8)
    private val started = clock()
    private val timeline = File(root, "timeline.txt")
    private val summaryFile = File(root, "journal-summary.json")
    private val recovered = runCatching { JSONObject(summaryFile.readText()) }.getOrElse { JSONObject() }
    private var sequence = recovered.optLong("sequence", 0)
    private var omitted = recovered.optLong("omittedEvents", 0)
    private var storageFailures = recovered.optLong("storageFailures",0)
    private var storageFailure: String? = recovered.optString("storageError").takeIf { it.isNotBlank() && it!="null" }
    private val cases = recovered.optJSONObject("cases") ?: JSONObject()
    private var directory = root
    private var caseId: String? = null
    private var policy: String? = null
    private var generation: Long? = null
    private var protocol: RollingLogFile? = null
    private val samples = HashMap<String, Long>()
    private var lastEvent: JSONObject? = null

    init {
        require(eventLimit >= 1024 && protocolLimit >= 512);root.mkdirs()
        var partialTail=false
        protect {
            if(timeline.isFile)java.io.RandomAccessFile(timeline,"r").use { input ->
                val length=input.length();input.seek((length-64*1024).coerceAtLeast(0))
                val tail=ByteArray((length-input.filePointer).toInt());input.readFully(tail)
                tail.toString(Charsets.UTF_8).lineSequence().forEach { line ->
                    runCatching {JSONObject(line).optLong("sequence")}.getOrNull()?.let{sequence=maxOf(sequence,it)}
                }
                partialTail=length>0 && tail.lastOrNull()!=10.toByte()
                // Preserve the incomplete row for analysis, but separate all future valid records.
                if(partialTail && length<eventLimit)timeline.appendBytes(byteArrayOf(10))
            }
        }
        record("journal.opened",mapOf("previousLastEvent" to recovered.optJSONObject("lastEvent"),"partialTailObserved" to partialTail))
    }

    @Synchronized fun target(next: File = root, id: String? = null, selectedPolicy: String? = null) {
        require(next.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()))
        require(!Files.isSymbolicLink(next.toPath()))
        protect { protocol?.close() }
        protocol = null; directory = next; directory.mkdirs()
        caseId = id; policy = selectedPolicy; generation = null; samples.clear()
        record("case.selected", mapOf("directory" to relativeDirectory()))
    }

    @Synchronized fun nativeStarted(value: Long) { generation = value; samples.clear() }

    @Synchronized fun record(event: String, data: Map<String, Any?> = emptyMap(), error: Throwable? = null) {
        val now = clock()
        val entry = JSONObject().put("schema", 1).put("sequence", ++sequence)
            .put("wallTimeMs", System.currentTimeMillis()).put("monotonicMs", now)
            .put("process", process).put("elapsedMs", (now - started).coerceAtLeast(0))
            .put("batch", root.name).put("version", version).put("event", safe(event))
            .put("case", caseId ?: JSONObject.NULL).put("policy", policy ?: JSONObject.NULL)
            .put("generation", generation ?: JSONObject.NULL)
            .put("data", JSONObject().also { obj -> data.entries.take(32).forEach { (k,v) -> obj.put(safe(k), if(k.lowercase() in SENSITIVE_FIELDS) "<redacted>" else safeValue(v)) } })
        if (error != null) entry.put("error", errorDetails(error))
        lastEvent = entry
        val context = caseSummary()
        context.put("lastEvent", event).put("lastSequence", sequence)
        if (error != null || event.endsWith(".failed")) {
            if (!context.has("firstFailure")) context.put("firstFailure", entry)
            context.put("lastFailure", entry)
        }
        var bytes = (entry.toString() + "\n").toByteArray(Charsets.UTF_8)
        if (bytes.size > 16 * 1024) {
            entry.put("data", JSONObject().put("omittedOversizeData", true))
            bytes = (entry.toString() + "\n").toByteArray(Charsets.UTF_8)
        }
        protect {
            if (timeline.length() + bytes.size <= eventLimit) timeline.appendBytes(bytes) else omitted++
        }
        checkpoint()
    }

    /** Used before the ordinary pause/folding gates. No recursive call to the app logger. */
    @Synchronized fun protocol(message: String) {
        val line = "${System.currentTimeMillis()} +${clock()-started}ms case=${caseId ?: "batch"} generation=${generation ?: 0} ${safe(message)}"
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        val context = caseSummary()
        context.put("protocolLines", context.optLong("protocolLines") + 1)
        context.put("protocolBytesObserved", context.optLong("protocolBytesObserved") + bytes.size)
        context.put("middleMayBeRotated", context.optLong("protocolBytesObserved") > protocolLimit * 2)
        protect {
            val first = File(directory, "protocol-start.txt")
            if (first.length() + bytes.size <= 32 * 1024) first.appendBytes(bytes)
            val writer = protocol ?: RollingLogFile(File(directory, "protocol.txt"), protocolLimit).also { protocol = it }
            writer.append(line)
        }
    }

    @Synchronized fun sample(event: String, intervalMs: Long = 5000, data: () -> Map<String, Any?>) {
        val now = clock(); val previous = samples[event]
        if (previous == null || now < previous || now - previous >= intervalMs) {
            samples[event] = now; record(event, data())
        }
    }

    @Synchronized fun checkpoint() {
        protect {
            val summary = JSONObject().put("schema",1).put("sequence",sequence).put("omittedEvents",omitted)
                .put("eventByteLimit",eventLimit).put("protocolBytesPerSegment",protocolLimit)
                .put("protocolSegmentsPerCase",2).put("protocolStartBytesPerCase",32*1024)
                .put("storageError",storageFailure ?: JSONObject.NULL).put("storageFailures",storageFailures).put("cases",cases)
                .put("lastEvent",lastEvent ?: JSONObject.NULL)
            val temp = File(root,"journal-summary.json.tmp");temp.writeText(summary.toString(2))
            Files.move(temp.toPath(),summaryFile.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    @Synchronized fun storageError(): String? = storageFailure

    /** Called only after nativeIsIdle, including when cancelled Rust callbacks were suppressed. */
    fun auditSamples(caseDirectory:File,names:List<String>) {
        for(name in names) {
            val sample=File(caseDirectory,"$name.packets");val meta=File(caseDirectory,"$name.json")
            val facts=linkedMapOf<String,Any?>("name" to name,"exists" to sample.isFile,"fileBytes" to sample.length(),"metadataExists" to meta.isFile)
            try {
                if(meta.isFile) {
                    val data=JSONObject(meta.readText())
                    for(key in listOf("frames","bytes","closed","limited","error","codec"))facts[key]=data.opt(key).takeUnless{it==JSONObject.NULL}
                }
                record("capture.audit",facts)
            } catch(error:Exception){record("capture.audit.failed",facts,error)}
        }
    }

    @Synchronized override fun close() { protect { protocol?.close() };protocol=null;checkpoint() }

    private fun relativeDirectory() = directory.relativeTo(root).invariantSeparatorsPath.ifEmpty { "." }
    private fun caseSummary(): JSONObject {
        val name = relativeDirectory()
        return cases.optJSONObject(name) ?: JSONObject().also { cases.put(name,it) }
    }
    private fun protect(action: () -> Unit) {
        try { action() } catch(e:Exception) {
            storageFailure = safe("${e.javaClass.name}: ${e.message}")
            // A failed diagnostic write must not change connection state or recurse into this writer.
            storageFailures++
            if(storageFailures==1L || storageFailures%100==0L)System.err.println("HyperCar diagnostic storage failure count=$storageFailures: $storageFailure")
        }
    }
    private fun safeValue(value: Any?): Any = when(value) {
        null -> JSONObject.NULL
        is Boolean, is Number -> value
        else -> safe(value.toString())
    }
    private fun safe(value:String)=LogSanitizer.clean(value)

    companion object {
        private val SENSITIVE_FIELDS=setOf("password","passwd","psk","passphrase","token","access_token","refresh_token","session_key","sessionkey","shared_secret","private_key","pairing_code","pairingcode","密码","配对码")
        fun errorDetails(error:Throwable):JSONObject {
            val causes=JSONArray();var current:Throwable?=error
            repeat(4) {
                val e=current ?: return@repeat
                causes.put(JSONObject().put("type",e.javaClass.name).put("message",LogSanitizer.clean(e.message.orEmpty()))
                    .put("stack",JSONArray(e.stackTrace.take(12).map{LogSanitizer.clean(it.toString())}))
                    .put("stackFrames",e.stackTrace.size).put("stackOmitted",(e.stackTrace.size-12).coerceAtLeast(0)))
                current=e.cause.takeUnless{it===e}
            }
            return JSONObject().put("causes",causes).put("remainingCausesOmitted",current!=null)
        }
    }
}
