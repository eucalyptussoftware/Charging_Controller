package com.yahooeu2k.dlb_charging;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import android.widget.Toast;

/**
 * Static manifest-registered receiver that intercepts BLE pairing requests
 * and auto-supplies the PIN from SharedPreferences.
 * Must be a system app with BLUETOOTH_PRIVILEGED for this to work.
 */
public class PairingReceiver extends BroadcastReceiver {
    private static final String TAG = "DlbCharging.Pairing";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!BluetoothDevice.ACTION_PAIRING_REQUEST.equals(intent.getAction())) return;

        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        int pairingVariant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1);

        Log.i(TAG, "=== PAIRING REQUEST RECEIVED ===");
        Log.i(TAG, "Device: " + (device != null ? device.getName() + " (" + device.getAddress() + ")" : "null"));
        Log.i(TAG, "Pairing variant: " + pairingVariant
                + " (PIN=0, PASSKEY=1, CONFIRM=2, CONSENT=3, DISPLAY_PASSKEY=4, DISPLAY_PIN=5)");

        SharedPreferences prefs = context.getSharedPreferences("dlb_charging", Context.MODE_PRIVATE);
        String pin = prefs.getString("pairing_pin", "");

        if (pin.isEmpty() || device == null) {
            Log.w(TAG, "No PIN configured or device is null, can't auto-pair");
            return;
        }

        Log.i(TAG, "Auto-supplying PIN: " + pin);

        try {
            // For classic PIN pairing (variant 0)
            if (pairingVariant == 0 || pairingVariant == 5) {
                boolean success = device.setPin(pin.getBytes());
                Log.i(TAG, "setPin() returned: " + success);
                if (success) {
                    Toast.makeText(context, "Auto-entering PIN...", Toast.LENGTH_SHORT).show();
                    abortBroadcast(); // Prevent system dialog
                    Log.i(TAG, "Broadcast aborted — system dialog suppressed");
                } else {
                    Toast.makeText(context, "Failed to set PIN!", Toast.LENGTH_LONG).show();
                }
            }
            // For BLE passkey entry (variant 1)
            else if (pairingVariant == 1) {
                // Use reflection for setPasskey (hidden API)
                try {
                    int passkey = Integer.parseInt(pin);
                    java.lang.reflect.Method setPasskey = device.getClass()
                            .getMethod("setPasskey", int.class);
                    setPasskey.invoke(device, passkey);
                    Log.i(TAG, "setPasskey(" + passkey + ") called via reflection");
                } catch (Exception e) {
                    // Fallback to setPin
                    device.setPin(pin.getBytes());
                    Log.i(TAG, "setPasskey failed, fell back to setPin()", e);
                }
            }
            // For numeric comparison (variant 2) or consent (variant 3)
            else {
                device.setPairingConfirmation(true);
                Log.i(TAG, "setPairingConfirmation(true) called");
            }

            abortBroadcast(); // Prevent system dialog
            Log.i(TAG, "Broadcast aborted — system dialog suppressed");
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception during pairing", e);
        } catch (Exception e) {
            Log.e(TAG, "Error during pairing", e);
        }
    }
}
