package io.github.hypercar.probe;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import java.util.List;

/** Runs as the target app, without any Bluetooth device extra or connection request. */
public final class ForegroundProbe extends Instrumentation {
    private final String serviceName = "io.github.hypercar.autowake.HyperCarService";
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private boolean running(Context context, boolean foregroundOnly) {
        List<ActivityManager.RunningServiceInfo> services = context.getSystemService(ActivityManager.class).getRunningServices(100);
        for (ActivityManager.RunningServiceInfo service : services) {
            if (service.service.getClassName().equals(serviceName) && (!foregroundOnly || service.foreground)) return true;
        }
        return false;
    }
    private void waitFor(Context context, boolean expected, boolean foregroundOnly) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (running(context, foregroundOnly) != expected && System.nanoTime() < deadline) Thread.sleep(50);
        if (running(context, foregroundOnly) != expected) throw new AssertionError("foreground service state did not become " + expected);
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        Context context = getTargetContext();
        boolean started = false;
        try {
            if (context.getPackageManager().getPackageInfo(context.getPackageName(), 0).getLongVersionCode() != 50002) throw new AssertionError("unexpected target version");
            if (running(context, false)) throw new AssertionError("existing session present; left untouched");
            ComponentName component = new ComponentName(context.getPackageName(), serviceName);
            ServiceInfo info = context.getPackageManager().getServiceInfo(component, 0);
            if (info.getForegroundServiceType() != ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) throw new AssertionError("unexpected service types");
            PackageInfo pkg = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS);
            for (String permission : pkg.requestedPermissions) {
                if (permission.equals("android.permission.RECORD_AUDIO")) throw new AssertionError("unexpected microphone permission");
            }
            context.startForegroundService(new Intent("io.github.hypercar.VERIFY_SERVICE_ONLY").setComponent(component));
            started = true;
            waitFor(context, true, true);
            result.putBoolean("promoted", true);
            result.putInt("foregroundType", info.getForegroundServiceType());
            context.startService(new Intent("io.github.hypercar.action.AUTO_STOP").setComponent(component));
            waitFor(context, false, false);
            result.putBoolean("stopped", true);
            result.putBoolean("noCarConnectionRequested", true);
            result.putBoolean("ok", true);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putBoolean("ok", false);
            result.putString("error", error.toString());
            if (started) context.stopService(new Intent().setClassName(context.getPackageName(), serviceName));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
