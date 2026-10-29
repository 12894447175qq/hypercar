package io.github.hypercar.session

import android.app.Activity
import android.content.Intent
import java.lang.ref.WeakReference

/** Main-thread only; never retains a stopped Activity or treats a foreground service as a visible UI. */
internal object ForegroundSourceActivity {
    private var current=WeakReference<Activity>(null)
    fun resumed(activity:Activity){current=WeakReference(activity)}
    fun paused(activity:Activity){if(current.get()===activity)current.clear()}
    fun launch(intent:Intent):Boolean {
        val activity=current.get()?.takeUnless{it.isFinishing || it.isDestroyed}?:return false
        activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        return true // Request accepted; the caller still must verify a real renderer binding.
    }
}
