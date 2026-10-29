package io.github.hypercar.session

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class SourceLaunchResult(val exit:Int,val stdout:String,val stderr:String)
internal class SourceActivityStartException(val startCode:Int?,message:String):IllegalStateException(message)

/** Foreground launch retains the visible caller's Android identity. Shell is only a background path. */
internal suspend fun launchSourceActivity(foreground:suspend ()->Boolean,shell:suspend ()->SourceLaunchResult):String {
    currentCoroutineContext().ensureActive()
    if(foreground())return "foreground_activity"
    currentCoroutineContext().ensureActive()
    val result=shell()
    val text=(result.stdout+"\n"+result.stderr).trim()
    val code=Regex("unknown error code\\s+(-?\\d+)",RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()
    if(result.exit!=0 || Regex("(?im)^Error(?: type \\d+)?[: ]|unknown error code").containsMatchIn(text)) {
        val reason=if(code==102)"系统中止了来源 Activity 启动（102）；请在手机前台打开 Hypercar 后重试，仍失败时手动打开原厂 CarLife。此错误不表示定位权限缺失" else "来源 Activity 启动失败"
        throw SourceActivityStartException(code,"$reason：${text.take(240)}")
    }
    return "shizuku"
}
