package io.github.hypercar.session

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Reserve against ordinary/ACL starts before retiring their resources; cancellation must finish retirement. */
internal class DiagnosticTakeover(private val monitor:Any,private val owned:AtomicBoolean) {
    suspend fun acquire(retire:suspend ()->Unit,prepare:suspend ()->Unit,rollback:()->Unit) {
        synchronized(monitor) {check(owned.compareAndSet(false,true)) {"已有诊断正在运行"}}
        try {
            retire()
            currentCoroutineContext().ensureActive()
            prepare()
        } catch(error:Throwable) {
            withContext(NonCancellable) {
                runCatching {retire()}.onFailure {if(it!==error)error.addSuppressed(it)}
                runCatching {release(rollback)}.onFailure {if(it!==error)error.addSuppressed(it)}
            }
            throw error
        }
    }
    fun release(cleanup:()->Unit) = synchronized(monitor) {
        try {cleanup()} finally {owned.set(false)}
    }
    /** Bluetooth observation is not a user's cancel action. */
    fun automatic(action:()->Unit):Boolean = synchronized(monitor) {
        if(owned.get())false else {action();true}
    }
}
