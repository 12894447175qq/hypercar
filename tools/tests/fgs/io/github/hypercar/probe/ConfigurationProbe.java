package io.github.hypercar.probe;

import android.app.Activity;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;

/** Restore only the already-selected car/source, through this app's own preference API. */
public final class ConfigurationProbe extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context context = getTargetContext();
            if (context.getPackageManager().getPackageInfo(context.getPackageName(), 0).getLongVersionCode() != 50002) throw new AssertionError("unexpected target version");
            if (!context.getPackageManager().getApplicationInfo("com.baidu.carlife.vivo", 0).enabled ||
                !context.getPackageManager().getApplicationInfo("com.vivo.car.networking", 0).enabled) throw new AssertionError("original source is not enabled");
            List<BluetoothDevice> matches = new ArrayList<>();
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null) for (BluetoothDevice device : adapter.getBondedDevices()) {
                if ("Audi_MMI_4809".equals(device.getName())) matches.add(device);
            }
            SharedPreferences prefs = context.getSharedPreferences("hypercar_settings", Context.MODE_PRIVATE);
            SharedPreferences.Editor edit = prefs.edit()
                .putString("head_unit_profile", "audi_mib3")
                .putString("carlife_package", "com.baidu.carlife.vivo")
                .putBoolean("carlife_enabled", true)
                .putBoolean("debug_log_enabled", true);
            if (matches.size() == 1) {
                edit.putString("target_car_mac", matches.get(0).getAddress()).putString("target_car_name", "Audi_MMI_4809");
            }
            if (!edit.commit()) throw new AssertionError("settings commit failed");
            result.putBoolean("ok", true);
            result.putString("profile", prefs.getString("head_unit_profile", ""));
            result.putString("source", prefs.getString("carlife_package", ""));
            result.putBoolean("targetSelected", matches.size() == 1);
            result.putInt("matchingBondedCars", matches.size());
            result.putBoolean("noCarConnectionRequested", true);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putBoolean("ok", false);
            result.putString("error", error.toString());
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
