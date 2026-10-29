package io.github.hypercar.diagnostics

import android.media.MediaCodec

internal object CodecFailure {
    fun describe(error:Throwable):String {
        val vendor=if(error is MediaCodec.CodecException) runCatching {
            " diagnostic=${error.diagnosticInfo} errorCode=${error.errorCode} recoverable=${error.isRecoverable} transient=${error.isTransient}"
        }.getOrDefault("") else ""
        return LogSanitizer.clean("type=${error.javaClass.name} message=${error.message}$vendor")
    }
    fun trace(error:Throwable,sink:(String)->Unit) {
        sink(describe(error))
        val lines=error.stackTraceToString().lineSequence().toList()
        lines.take(32).forEach {sink("stack ${LogSanitizer.clean(it)}")}
        if(lines.size>32)sink("stack_truncated omittedLines=${lines.size-32}")
    }
}
