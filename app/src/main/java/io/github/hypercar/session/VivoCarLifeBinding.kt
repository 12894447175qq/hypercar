package io.github.hypercar.session

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.app.ActivityManager
import android.app.KeyguardManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import io.github.hypercar.diagnostics.ConnectionTrace
import io.github.hypercar.shizuku.ShizukuTools
import io.github.hypercar.shizuku.shellQuote
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Mirrors the original Jovi integration for CarLife SDK 271+: bind, retain, unbind. */
internal class VivoCarLifeBinding private constructor(
    private val context: Context,
    private val source: CarLifeSource,
    private val trace: ConnectionTrace,
) : CarLifeRendererLease {
    private val main = Handler(Looper.getMainLooper())
    private val ready = CompletableDeferred<Unit>()
    private val closed = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private val bindAction = "io.github.hypercar.BIND_VIVO_CARLIFE.${UUID.randomUUID()}"
    // Only accessed on the main thread.
    private var bindingAttempted = false
    override val isActive: Boolean get() = connected.get() && !closed.get()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (closed.get()) { trace.event("bind.late_callback", "component=$name action=$bindAction"); return }
            connected.set(true)
            trace.event("bind.connected", "component=$name binderAlive=${service.isBinderAlive} action=$bindAction")
            ready.complete(Unit)
        }

        override fun onNullBinding(name: ComponentName) = unavailable("原厂服务没有提供绑定接口")
        override fun onBindingDied(name: ComponentName) = unavailable("原厂服务绑定失效")
        override fun onServiceDisconnected(name: ComponentName) = unavailable("原厂服务连接断开")
    }

    private fun unavailable(reason: String) {
        connected.set(false)
        if (!closed.get()) {
            trace.event("bind.unavailable", "reason=$reason action=$bindAction")
            ready.completeExceptionally(IllegalStateException(reason))
        }
    }

    private fun bind(): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed.get()) { trace.event("bind.skipped", "closed=true"); return false }
        val component = requireNotNull(ComponentName.unflattenFromString(source.service!!))
        val intent = Intent().setComponent(component)
            // Intent extras do not distinguish ServiceConnection bind records.
            // The source Activity also binds this component without renderer extras;
            // reusing that Binder skips onBind/connectStart and yields zero video.
            // A distinct action also makes a new session initialize after unbind.
            .setAction(bindAction)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            .putExtra("bindPackageName", CarLifeSource.JOVI)
            .putExtra("bindClassName", CarLifeSource.JOVI_CAST_SERVICE)
            .putExtra("manufacturer", "vivo")
            .putExtra("usb_type", 13)
            .putExtra("mix_manual", true)
        @Suppress("DEPRECATION")
        val joviVersion = context.packageManager.getPackageInfo(CarLifeSource.JOVI, 0).versionName
        intent.putExtra("bindVersion", joviVersion)
        trace.event("bind.request", "component=$component action=$bindAction flags=${intent.flags} renderer=${CarLifeSource.JOVI}/${CarLifeSource.JOVI_CAST_SERVICE} bindVersion=$joviVersion usb_type=13 mix_manual=true thread=${Thread.currentThread().name}")
        // Context may retain its ServiceConnection dispatcher even when binding
        // returns false; close() must also clean up that failed attempt.
        bindingAttempted = true
        val started = SystemClock.elapsedRealtime()
        return context.bindService(intent, connection, Context.BIND_AUTO_CREATE).also {
            trace.event("bind.return", "accepted=$it durationMs=${SystemClock.elapsedRealtime() - started} action=$bindAction")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        trace.event("unbind.request", "connected=${connected.get()} action=$bindAction thread=${Thread.currentThread().name}")
        connected.set(false)
        val release = Runnable {
            if (bindingAttempted) {
                bindingAttempted = false
                runCatching { context.unbindService(connection) }
                    .onSuccess { trace.event("unbind.done", "action=$bindAction") }
                    .onFailure {
                        if (it is IllegalArgumentException) trace.event("unbind.not_registered", "action=$bindAction reason=${it.message}")
                        else trace.failure("unbind", it)
                    }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) release.run() else main.post(release)
    }

    companion object {
        suspend fun connect(context: Context, source: CarLifeSource, log: (String) -> Unit, parentId: String? = null, stillValid: () -> Boolean): CarLifeRendererLease {
            require(source.usesBoundRenderer)
            val trace = ConnectionTrace("vivo", log, SystemClock::elapsedRealtime, parentId)
            trace.event("initialize.begin", "source=${source.enginePackage} shizuku=${ShizukuTools.isReady}")
            withContext(Dispatchers.IO) { logEnvironment(context, source, trace) }
            try {
              var attempts = 0
              return acquireVivoRenderer(
                acquire = {
                    trace.event("bind.attempt", "number=${++attempts}")
                    attempt(context, source, trace, stillValid)
                },
                bootstrap = {
                    currentCoroutineContext().ensureActive()
                    if (!stillValid()) throw CancellationException("CarLife session already closed")
                    trace.event("wake.begin", "reason=cold_bind_rejected component=${source.activity} backend=${if (ShizukuTools.isReady) "shizuku" else "context"}")
                    if (ShizukuTools.isReady) {
                        // Preserve the existing background wake capability. Do not
                        // perform shell work on Main or start a detached SDK FGS.
                        val result = withContext(Dispatchers.IO) {
                            currentCoroutineContext().ensureActive()
                            if (!stillValid()) throw CancellationException("CarLife session already closed")
                            ShizukuTools.exec("am start -W --user 0 -n ${shellQuote(requireNotNull(source.activity))}", 8_000)
                        }
                        trace.event("wake.shizuku.result", "exit=${result.code} stdout=${result.out.replace('\n', '|')} stderr=${result.err.replace('\n', '|')}")
                        val output = result.out + "\n" + result.err
                        check(result.code == 0 && !Regex("(?im)^Error(?: type \\d+)?[: ]|unknown error code").containsMatchIn(output)) {
                            "原厂入口启动失败：${output.trim().take(240)}"
                        }
                    } else {
                        withContext(Dispatchers.Main.immediate) {
                            if (!stillValid()) throw CancellationException("CarLife session already closed")
                            context.startActivity(Intent().setComponent(
                                requireNotNull(ComponentName.unflattenFromString(source.activity!!)))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            trace.event("wake.context.requested", "activityStartNotConfirmed=true")
                        }
                    }
                    trace.event("wake.retry_wait", "delayMs=3100 reason=original_sdk_throttle")
                },
              ).also { trace.event("initialize.bound", "active=${it.isActive} videoAndDesktopNotVerified=true") }
            } catch (cancelled: CancellationException) {
                trace.event("initialize.cancelled", "reason=${cancelled.message}")
                throw cancelled
            } catch (error: Exception) {
                trace.failure("initialize", error)
                throw error
            }
        }

        private fun logEnvironment(context: Context, source: CarLifeSource, trace: ConnectionTrace) {
            runCatching {
                val process = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }
                val keyguard = context.getSystemService(KeyguardManager::class.java)
                trace.event("environment", "sdk=${android.os.Build.VERSION.SDK_INT} interactive=${context.getSystemService(PowerManager::class.java)?.isInteractive} keyguardLocked=${keyguard?.isKeyguardLocked} deviceLocked=${keyguard?.isDeviceLocked} processImportance=${process.importance}")
            }.onFailure { trace.failure("environment", it) }
            for (pkg in listOf(source.enginePackage, CarLifeSource.JOVI)) {
                runCatching {
                    @Suppress("DEPRECATION") val info = context.packageManager.getPackageInfo(pkg, 0)
                    val app = info.applicationInfo
                    val stopped = app?.let { (it.flags and android.content.pm.ApplicationInfo.FLAG_STOPPED) != 0 }
                    trace.event("package", "package=$pkg version=${info.versionName}/${info.longVersionCode} uid=${app?.uid} targetSdk=${app?.targetSdkVersion} enabled=${app?.enabled} stopped=$stopped")
                }.onFailure { trace.failure("package.$pkg", it) }
            }
            val grants = listOf("android.permission.ACCESS_COARSE_LOCATION", CarLifeSource.FINE_LOCATION, CarLifeSource.BACKGROUND_LOCATION)
                .joinToString(",") { permission -> "$permission=${context.packageManager.checkPermission(permission, source.enginePackage) == android.content.pm.PackageManager.PERMISSION_GRANTED}" }
            trace.event("source.permissions", grants)
            trace.event("jovi.integration_scope", "rendererBindingOnly=true originalAuthorizationAndDeviceSessionState=not_observable desktopRequiresVisualCheck=true")
        }

        private suspend fun attempt(context: Context, source: CarLifeSource, trace: ConnectionTrace, stillValid: () -> Boolean): CarLifeRendererLease? {
            val binding = VivoCarLifeBinding(context.applicationContext, source, trace)
            try {
                val accepted = withContext(Dispatchers.Main.immediate) {
                    if (!stillValid()) throw CancellationException("CarLife session already closed")
                    binding.bind()
                }
                if (!accepted) { binding.close(); return null }
                trace.event("bind.wait_callback", "timeoutMs=8000")
                check(withTimeoutOrNull(8_000) { binding.ready.await(); true } == true) { "等待 vivo CarLife 服务绑定超时" }
                return binding
            } catch (error: Throwable) {
                trace.event("bind.attempt_failed", "type=${error.javaClass.name} message=${error.message}")
                binding.close()
                throw error
            }
        }
    }
}
