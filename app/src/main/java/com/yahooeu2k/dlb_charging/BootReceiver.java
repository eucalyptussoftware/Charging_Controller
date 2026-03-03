package com.yahooeu2k.dlb_charging;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "DlbCharging.Boot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            // Initialize Logger
            SharedPreferences prefs = context.getSharedPreferences("dlb_power", Context.MODE_PRIVATE);
            boolean fileLoggingEnabled = prefs.getBoolean("file_logging_enabled", false);
            AppLogger.enableFileLogging(context, fileLoggingEnabled);

            AppLogger.i(TAG, "Boot completed, starting services...");

            // Start BleService if enabled (it checks its own schedule)
            // Ideally we check prefs here to avoid starting if completely disabled, 
            // but the service logic handles "outside schedule" effectively.
            Intent bleIntent = new Intent(context, BleService.class);
            
            // Start MqttService if enabled
            SharedPreferences mqttPrefs = context.getSharedPreferences(MqttConstants.PREFS_NAME, Context.MODE_PRIVATE);
            boolean mqttEnabled = mqttPrefs.getBoolean(MqttConstants.KEY_ENABLED, true);
            Intent mqttIntent = new Intent(context, MqttService.class);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(bleIntent);
                if (mqttEnabled) context.startForegroundService(mqttIntent);
            } else {
                context.startService(bleIntent);
                if (mqttEnabled) context.startService(mqttIntent);
            }
        }
    }
}
