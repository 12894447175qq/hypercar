package io.github.hypercar.diagnostics

import java.io.BufferedWriter
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Keeps a bounded current file and one previous file, including during a long running process. */
internal class RollingLogFile(private val file: File, private val maxBytes: Long) : AutoCloseable {
    private val previous = File(file.path + ".1")
    private var writer: BufferedWriter? = null
    private var bytes = 0L

    init { require(maxBytes >= 512) }

    @Synchronized
    fun append(line: String) {
        val originalBytes = line.toByteArray(Charsets.UTF_8).size + 1L
        val record = if (originalBytes > maxBytes) {
            "[LOG oversized record omitted: bytes=$originalBytes limit=$maxBytes]"
        } else line
        val recordBytes = record.toByteArray(Charsets.UTF_8).size + 1L
        open()
        if (bytes + recordBytes > maxBytes) {
            close()
            if (file.exists()) Files.move(file.toPath(), previous.toPath(), StandardCopyOption.REPLACE_EXISTING)
            open()
        }
        requireNotNull(writer).apply { write(record); newLine(); flush() }
        bytes += recordBytes
    }

    private fun open() {
        if (writer != null) return
        file.parentFile?.mkdirs()
        trimExisting(previous)
        trimExisting(file)
        bytes = file.length()
        // Use append mode; opening must not truncate the previous process's log.
        writer = file.bufferedWriterInAppendMode()
    }

    /** Migrate oversized files left by older versions; start after a complete UTF-8 line. */
    private fun trimExisting(target: File) {
        if (target.length() <= maxBytes) return
        val tail = java.io.RandomAccessFile(target, "r").use { input ->
            input.seek((input.length() - (maxBytes - 128)).coerceAtLeast(0))
            input.readLine() // discard the first potentially partial UTF-8 line
            ByteArray((input.length() - input.filePointer).toInt()).also { input.readFully(it) }
        }
        val temporary = File(target.path + ".trim")
        temporary.outputStream().use { output ->
            output.write("[LOG older oversized history truncated]\n".toByteArray(Charsets.UTF_8))
            output.write(tail)
            if (tail.isNotEmpty() && tail.last() != '\n'.code.toByte()) output.write('\n'.code)
        }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun File.bufferedWriterInAppendMode(): BufferedWriter =
        java.io.FileOutputStream(this, true).bufferedWriter(Charsets.UTF_8)

    @Synchronized
    fun snapshotLines(): List<String> {
        writer?.flush()
        if (writer == null) { trimExisting(previous); trimExisting(file) }
        return listOf(previous, file).filter { it.exists() }.flatMap { it.readLines(Charsets.UTF_8) }
    }

    @Synchronized
    fun clear() {
        close()
        Files.deleteIfExists(file.toPath())
        Files.deleteIfExists(previous.toPath())
        bytes = 0
    }

    @Synchronized
    override fun close() {
        writer?.close()
        writer = null
    }
}
