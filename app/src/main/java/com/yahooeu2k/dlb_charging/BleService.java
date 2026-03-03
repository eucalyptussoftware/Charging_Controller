package com.yahooeu2k.dlb_charging;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.PowerManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Foreground service that:
 * 1. Scans for the Emerald IHD device (by advertised service UUID)
 * 2. Connects and enables notifications
 * 3. Performs the application-level pairing handshake
 * 4. Polls for live power data every 5 seconds
 * 5. Broadcasts intents with the current wattage
 */
public class BleService extends Service {

    private static final String TAG = "DlbCharging";
    private static final String CHANNEL_ID = "dlb_charging_channel";
    private static final int NOTIFICATION_ID = 1;

    // Diagnostic
    private final String instanceId = java.util.UUID.randomUUID().toString().substring(0, 4);

    // State
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeCharacteristic;
    private BluetoothGattCharacteristic readCharacteristic;

    private Handler handler;
    private PowerManager.WakeLock wakeLock;
    private ScheduleManager scheduleManager;

    private boolean isConnected = false;
    private boolean pairingComplete = false;
    private int pairingAttempts = 0;
    private int lastPowerWatts = -1;
    private BroadcastReceiver bondReceiver;
    private BroadcastReceiver pairingReceiver;
    private BroadcastReceiver mqttPowerReceiver;
    private ChargingController chargingController;

    private final Runnable safetyRunnable = new Runnable() {
        @Override
        public void run() {
            if (chargingController != null) {
                chargingController.checkSafetyTimeout();
            }
            // Check every 30 seconds
            if (handler != null) {
                handler.postDelayed(this, 30000);
            }
        }
    };

    private static final long SCHEDULE_CHECK_INTERVAL_MS = 60000; // 1 minute

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        AppLogger.i(TAG, "BleService CREATED [" + instanceId + "]");

        handler = new Handler(Looper.getMainLooper());
        scheduleManager = new ScheduleManager(this);
        scheduleManager.initDefaults();
        chargingController = ChargingController.getInstance(this);

        BluetoothManager btManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (btManager != null) {
            bluetoothAdapter = btManager.getAdapter();
        }

        // Acquire partial wake lock to keep scanning/polling alive
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DlbCharging:ble");
            wakeLock.acquire(60 * 60 * 1000L); // 1 hour max
        }

        createNotificationChannel();

        // Register pairing request handler to auto-supply PIN
        pairingReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (BluetoothDevice.ACTION_PAIRING_REQUEST.equals(intent.getAction())) {
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    int pairingType = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1);
                    AppLogger.i(TAG, "[" + instanceId + "] Pairing request received! Type=" + pairingType + " device="
                            + (device != null ? device.getName() : "null"));

                    SharedPreferences prefs = getSharedPreferences("dlb_charging", MODE_PRIVATE);
                    String pin = prefs.getString("pairing_pin", "");

                    if (!pin.isEmpty() && device != null) {
                        // CRITICAL: Only supply the PIN if the OS explicitly requests
                        // PAIRING_VARIANT_PIN (0).
                        if (pairingType == 0) {
                            AppLogger.i(TAG, "[" + instanceId + "] Auto-supplying PIN: " + pin);
                            try {
                                device.setPin(pin.getBytes());
                                abortBroadcast(); // Prevent system dialog
                            } catch (Exception e) {
                                AppLogger.e(TAG, "[" + instanceId + "] Failed to set PIN", e);
                            }
                        } else {
                            AppLogger.w(TAG, "[" + instanceId + "] Pairing variant is not PIN (" + pairingType
                                    + "), ignoring auto-supply");
                        }
                    } else {
                        AppLogger.w(TAG, "[" + instanceId + "] No PIN configured, letting system handle pairing");
                    }
                }
            }
        };
        IntentFilter pairingFilter = new IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST);
        pairingFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        registerReceiver(pairingReceiver, pairingFilter);

        bondReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) {
                    int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (state == BluetoothDevice.BOND_BONDED) {
                        AppLogger.i(TAG,
                                "[" + instanceId + "] Bonded with " + (device != null ? device.getName() : "null"));
                        if (isConnected) {
                            if (writeCharacteristic == null || readCharacteristic == null) {
                                AppLogger.i(TAG, "[" + instanceId
                                        + "] Bonded, but services not discovered. Resuming discoverServices...");
                                handler.postDelayed(() -> {
                                    if (gatt == null)
                                        return;
                                    try {
                                        boolean started = gatt.discoverServices();
                                        AppLogger.i(TAG, "discoverServices() resumed: " + started);
                                    } catch (SecurityException e) {
                                        AppLogger.e(TAG, "discoverServices permission denied", e);
                                    }
                                }, 600);
                            } else if (!pairingComplete) {
                                startPairingHandshake();
                            }
                        }
                    } else if (state == BluetoothDevice.BOND_NONE) {
                        AppLogger.w(TAG, "[" + instanceId + "] Bond lost or failed with "
                                + (device != null ? device.getName() : "null"));
                        pairingComplete = false;
                        disconnect();
                    }
                }
            }
        };
        registerReceiver(bondReceiver, new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED));

        // Listen for MQTT power updates to also feed into charging controller
        mqttPowerReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (MqttConstants.ACTION_MQTT_POWER.equals(intent.getAction())) {
                    String payload = intent.getStringExtra(MqttConstants.EXTRA_PAYLOAD);
                    if (payload != null) {
                        try {
                            int watts = (int) Double.parseDouble(payload);
                            // Avoid extra logging spam unless debugging, but let's see if we get duplicates
                            // here
                            // AppLogger.d(TAG, "[" + instanceId + "] Received MQTT power broadcast: " +
                            // watts);
                            chargingController.onPowerUpdate(watts);
                        } catch (NumberFormatException e) {
                            AppLogger.w(TAG,
                                    "[" + instanceId + "] Could not parse MQTT power for charging: " + payload);
                        }
                    }
                }
            }
        };
        registerReceiver(mqttPowerReceiver, new IntentFilter(MqttConstants.ACTION_MQTT_POWER));

        // Start safety check loop
        handler.post(safetyRunnable);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification("Starting..."));
        AppLogger.i(TAG, "Service onStartCommand called");
        AppLogger.i(TAG, "Bluetooth adapter: " + (bluetoothAdapter != null ? "present" : "NULL"));
        AppLogger.i(TAG, "Bluetooth enabled: " + (bluetoothAdapter != null && bluetoothAdapter.isEnabled()));

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            AppLogger.e(TAG, "Bluetooth not available or disabled");
            updateNotification("Bluetooth disabled");
            return START_STICKY;
        }

        checkScheduleAndAct();
        return START_STICKY;
    }

    /**
     * Checks if we're within the configured schedule.
     * If yes: starts scanning/polling. If no: disconnects and waits.
     */
    private void checkScheduleAndAct() {
        boolean enabled = scheduleManager.isEnabled();
        boolean scheduleEnforced = scheduleManager.isScheduleEnforced();
        boolean timeWindowActive = scheduleManager.isWithinScheduleIgnoringEnabled();
        boolean forceMaxActive = chargingController.isForceMaxActive();

        boolean shouldRun = enabled && (!scheduleEnforced || timeWindowActive) && !forceMaxActive;

        AppLogger.i(TAG, "Schedule check: shouldRun=" + shouldRun
                + " (enabled=" + enabled
                + ", enforced=" + scheduleEnforced
                + ", timeActive=" + timeWindowActive
                + ", forceMax=" + forceMaxActive + ")"
                + ", isConnected=" + isConnected);

        if (shouldRun) {
            if (!isConnected) {
                AppLogger.i(TAG, "BLE Service enabled and active, starting scan");
                startScanning();
            } else {
                AppLogger.d(TAG, "BLE Service enabled and already connected");
            }
        } else {
            if (isConnected) {
                disconnect();
            }
            stopScanning();

            if (!enabled) {
                AppLogger.i(TAG, "BLE Service manually disabled — suspending");
                updateNotification("Service Disabled");
            } else {
                AppLogger.i(TAG, "BLE Outside schedule window — suspending");
                updateNotification("Outside schedule — waiting");
            }
        }
        // Re-check periodically
        handler.postDelayed(this::checkScheduleAndAct, SCHEDULE_CHECK_INTERVAL_MS);
    }

    @Override
    public void onDestroy() {
        AppLogger.i(TAG, "BleService DESTROYED [" + instanceId + "]");
        stopScanning();
        disconnect();
        handler.removeCallbacksAndMessages(null);

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        try {
            if (pairingReceiver != null)
                unregisterReceiver(pairingReceiver);
        } catch (Exception ignored) {
        }
        try {
            if (bondReceiver != null)
                unregisterReceiver(bondReceiver);
        } catch (Exception ignored) {
        }
        try {
            if (mqttPowerReceiver != null)
                unregisterReceiver(mqttPowerReceiver);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ── Scanning ───────────────────────────────────────────────────────────

    private void startScanning() {
        if (!scheduleManager.isEnabled()) {
            AppLogger.w(TAG, "BLE Service disabled, skipping scan");
            return;
        }

        // Extremely aggressive guard: Do NOT start scanning if we are connected OR if
        // a GATT client already exists. Concurrent scanning destroys Qualcomm driver
        // queues.
        if (isConnected || gatt != null) {
            AppLogger.w(TAG,
                    "Already connected or GATT exists (" + (gatt != null) + "), aborting runaway scan attempt");
            return;
        }

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            AppLogger.w(TAG, "Bluetooth not available/enabled, skipping scan");
            updateNotification("Bluetooth disabled");
            return;
        }

        // Stop any existing scan first to avoid SCAN_FAILED_ALREADY_STARTED (error 1)
        stopScanning();

        scanner = bluetoothAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            AppLogger.e(TAG, "BLE Scanner not available");
            return;
        }

        // Filter by service UUID (matches the Emerald app's startScan() logic)
        ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(BleConstants.IHD_SERVICE_UUID))
                .build();

        List<ScanFilter> filters = new ArrayList<>();
        filters.add(filter);

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        AppLogger.i(TAG, "Starting BLE scan for IHD service UUID: " + BleConstants.IHD_SERVICE_UUID);
        updateNotification("Scanning...");

        try {
            scanner.startScan(filters, settings, scanCallback);
        } catch (SecurityException e) {
            AppLogger.e(TAG, "BLE scan permission denied", e);
            updateNotification("Permission denied");
        }

        // Stop scanning after 30 seconds if nothing found
        handler.postDelayed(this::stopScanning, 30000);
    }

    private void stopScanning() {
        if (scanner != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (IllegalStateException e) {
                AppLogger.w(TAG, "BT adapter off, can't stop scan", e);
            } catch (Exception e) {
                AppLogger.w(TAG, "Error stopping scan", e);
            }
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = device.getName();
            AppLogger.i(TAG, "Found device: " + name + " (" + device.getAddress() + ")");

            stopScanning();
            // Guard again before connecting
            if (!scheduleManager.isEnabled()) {
                AppLogger.w(TAG, "BLE Service disabled during scan result, aborting connection");
                return;
            }
            connectToDevice(device);
        }

        @Override
        public void onScanFailed(int errorCode) {
            AppLogger.e(TAG, "Scan failed with error: " + errorCode);
            updateNotification("Scan failed (" + errorCode + ")");

            // Retry after 10 seconds
            handler.postDelayed(() -> startScanning(), 10000);
        }
    };

    // ── Connection ─────────────────────────────────────────────────────────

    private void connectToDevice(BluetoothDevice device) {
        if (!scheduleManager.isEnabled()) {
            AppLogger.w(TAG, "BLE Service disabled, skipping connection");
            return;
        }

        if (isConnected) {
            AppLogger.d(TAG, "Already connected, skipping connection attempt");
            return;
        }

        // Clean up any old GATT client before creating a new one to prevent leak
        // (Error: gatt not found inst)
        if (gatt != null) {
            try {
                gatt.close();
            } catch (Exception e) {
                AppLogger.w(TAG, "Error closing old gatt", e);
            }
            gatt = null;
        }

        // Cancel any pending startScanning() runnables triggered by previous
        // disconnects or failed scans.
        // DO NOT use removeCallbacksAndMessages(null) as it kills safetyRunnable too!
        handler.removeCallbacks(this::startScanning);
        handler.removeCallbacks(this::stopScanning);

        // Force stop LE scan before connecting. Concurrent scanning & connecting
        // on Android frequently causes discoverServices() to timeout silently after
        // 30s.
        stopScanning();

        AppLogger.i(TAG, "Connecting to " + device.getName() + " (" + device.getAddress() + ")...");
        updateNotification("Connecting to " + device.getName() + "...");

        try {
            // Mark as 'connecting' to immediately block other scan callbacks that arrive
            // concurrently
            isConnected = true;

            // Execute connectGatt immediately on the Main Thread. In Emerald-gradle, all
            // connection requests are tightly synchronized on a custom Handler.
            handler.post(() -> {
                if (gatt != null) {
                    try {
                        gatt.close();
                    } catch (Exception ignored) {
                    }
                }

                AppLogger.i(TAG, "Initiating connectGatt on Main Thread");
                try {
                    // CRITICAL: Emerald-gradle strictly uses the API 23 (4-parameter) connectGatt
                    // method
                    // across all Android versions to avoid triggering Android 8+ PHY/Extension
                    // negotiations
                    // that crash the peripheral driver. Do NOT use the 6-parameter API 26 overload.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        gatt = device.connectGatt(BleService.this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
                    } else {
                        gatt = device.connectGatt(BleService.this, false, gattCallback);
                    }
                } catch (SecurityException e) {
                    isConnected = false;
                    AppLogger.e(TAG, "Connect permission denied", e);
                }
            });

        } catch (Exception e) {
            isConnected = false;
            AppLogger.e(TAG, "Connection preparation failed", e);
        }
    }

    private void disconnect() {
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (Exception e) {
                AppLogger.w(TAG, "Error disconnecting", e);
            }
            gatt = null;
        }
        isConnected = false;
        pairingComplete = false;
        pairingAttempts = 0;
        broadcastConnectionState(false);
    }

    // ── GATT Callback ──────────────────────────────────────────────────────

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // Ensure the global gatt variable tracks the connected one, preventing race
                // leaks
                if (gatt != null && g != gatt) {
                    AppLogger.w(TAG, "Duplicate connection detected, closing stale gatt instance");
                    try {
                        g.close();
                    } catch (Exception ignored) {
                    }
                    return;
                }

                int bondState = g.getDevice().getBondState();
                AppLogger.i(TAG, "Connected to GATT server (status=" + status + ", bondState=" + bondState + ")");
                isConnected = true; // Reinforce true
                broadcastConnectionState(true);
                updateNotification("Connected. Checking security...");

                // CRITICAL: Emerald-gradle explicitly guards against executing discoverServices
                // when the device is actively in the middle of a pairing/bonding handshake.
                // Sending an L2CAP discoverServices packet while the baseband is doing an SMP
                // exchange fatally deadlocks the Qualcomm automotive BT driver for 30s.
                if (bondState == BluetoothDevice.BOND_BONDING) {
                    AppLogger.w(TAG,
                            "Device is actively BONDING! Skipping discoverServices. The broadcast receiver will trigger it when complete.");
                    return;
                }

                // Delay service discovery by 600ms on the main thread.
                // Instantaneous discovery requests immediately after BT_GATT_CONNECT_IND
                // starve the Qualcomm L2CAP queues on automotive basebands causing a
                // BT_GATT_DISCONNECT_IND 1.8s later.
                handler.postDelayed(() -> {
                    if (gatt == null)
                        return;
                    try {
                        AppLogger.i(TAG, "600ms buffer complete, initiating discoverServices. BondState="
                                + g.getDevice().getBondState());
                        boolean started = g.discoverServices();
                        AppLogger.i(TAG, "discoverServices() started: " + started);
                    } catch (SecurityException e) {
                        AppLogger.e(TAG, "discoverServices permission denied", e);
                    }
                }, 600);

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                AppLogger.w(TAG, "Disconnected from GATT server (status=" + status + ")");
                isConnected = false;
                pairingComplete = false;
                broadcastConnectionState(false);
                updateNotification("Disconnected. Reconnecting...");

                // Auto-reconnect after 5 seconds
                handler.postDelayed(() -> startScanning(), 5000);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                AppLogger.e(TAG, "Service discovery failed: " + status);
                return;
            }

            AppLogger.i(TAG, "Services discovered");

            // Find the IHD service
            BluetoothGattService service = g.getService(BleConstants.IHD_SERVICE_UUID);
            if (service == null) {
                // Fallback: scan all services for the characteristics
                for (BluetoothGattService s : g.getServices()) {
                    BluetoothGattCharacteristic w = s.getCharacteristic(BleConstants.WRITE_UUID);
                    BluetoothGattCharacteristic r = s.getCharacteristic(BleConstants.READ_UUID);
                    if (w != null && r != null) {
                        service = s;
                        break;
                    }
                }
            }

            if (service == null) {
                AppLogger.e(TAG, "IHD service not found on device");
                updateNotification("Service not found");
                return;
            }

            writeCharacteristic = service.getCharacteristic(BleConstants.WRITE_UUID);
            readCharacteristic = service.getCharacteristic(BleConstants.READ_UUID);

            if (writeCharacteristic == null || readCharacteristic == null) {
                AppLogger.e(TAG, "Required characteristics not found");
                updateNotification("Characteristics not found");
                return;
            }

            AppLogger.i(TAG, "Found Write and Read characteristics");

            // Enable notifications on the read characteristic
            enableNotifications(g, readCharacteristic);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                AppLogger.i(TAG, "Notifications enabled. Starting pairing handshake...");
                updateNotification("Pairing...");
                startPairingHandshake();
            } else {
                AppLogger.e(TAG, "Failed to enable notifications: " + status);
                if (status == 5 || status == 8 || status == 137) {
                    AppLogger.e(TAG, "Auth error! Bonding required.");
                    if (g.getDevice().getBondState() != BluetoothDevice.BOND_BONDING) {
                        AppLogger.i(TAG, "Initiating bond...");
                        g.getDevice().createBond();
                    }
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
            byte[] data = characteristic.getValue();
            if (data == null || data.length == 0)
                return;

            String hexData = BleConstants.bytesToHex(data);
            // AppLogger.d(TAG, "Notification: " + hexData);

            // Check for Pairing Code response
            if (hexData.startsWith(BleConstants.PAIRING_RESPONSE_PREFIX)) {
                AppLogger.i(TAG, "Pairing response received: " + hexData);
                pairingComplete = true;
                updateNotification("Paired. Reading power...");
                startPowerPolling();
                return;
            }

            // Check for Live Power response
            if (hexData.startsWith(BleConstants.POWER_RESPONSE_PREFIX)) {
                String valueHex = hexData.substring(BleConstants.POWER_RESPONSE_PREFIX.length());
                if (!valueHex.isEmpty()) {
                    try {
                        int watts = Integer.parseInt(valueHex, 16);
                        lastPowerWatts = watts;
                        AppLogger.i(TAG, "Power: " + watts + "W");
                        updateNotification("⚡ " + watts + " W");
                        broadcastPower(watts);
                        HistoryManager.getInstance(BleService.this).addPoint(watts, false);
                    } catch (NumberFormatException e) {
                        AppLogger.w(TAG, "Could not parse power value: " + valueHex);
                    }
                }
                return;
            }

            AppLogger.d(TAG, "Other notification: " + hexData);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            String uuid = characteristic.getUuid().toString();
            if (status == BluetoothGatt.GATT_SUCCESS) {
                AppLogger.i(TAG, "Write successful to " + uuid);
                if (uuid.equals(BleConstants.WRITE_UUID.toString())) {
                    byte[] val = characteristic.getValue();
                    if (val != null && BleConstants.bytesToHex(val)
                            .equals(BleConstants.bytesToHex(BleConstants.CMD_GET_PAIRING_CODE))) {
                        AppLogger.i(TAG, "Pairing code command written successfully");
                    }
                }
            } else {
                AppLogger.e(TAG, "Write failed to " + uuid + ", status: " + status);
                if (status == 5 || status == 8 || status == 137) { // GATT_INSUFFICIENT_AUTHENTICATION
                                                                   // /
                                                                   // AUTHENTICATION_TIMEOUT
                                                                   // /
                                                                   // ENCRYPTED_NO_MITM
                    AppLogger.e(TAG, "Auth error! Bonding required.");
                    if (gatt.getDevice().getBondState() != BluetoothDevice.BOND_BONDING) {
                        AppLogger.i(TAG, "Initiating bond...");
                        gatt.getDevice().createBond();
                    }
                }
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                AppLogger.i(TAG, "Read successful from " + characteristic.getUuid());
            } else {
                AppLogger.e(TAG, "Read failed, status: " + status);
                if (status == 5 || status == 8 || status == 137) {
                    AppLogger.e(TAG, "Auth error! Bonding required.");
                    if (gatt.getDevice().getBondState() != BluetoothDevice.BOND_BONDING) {
                        AppLogger.i(TAG, "Initiating bond...");
                        gatt.getDevice().createBond();
                    }
                }
            }
        }
    };

    // ── Notifications (BLE) ────────────────────────────────────────────────

    private void enableNotifications(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
        try {
            g.setCharacteristicNotification(characteristic, true);

            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(BleConstants.CCCD_UUID);
            if (descriptor != null) {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                g.writeDescriptor(descriptor);
                AppLogger.i(TAG, "Enabling notifications...");
            } else {
                AppLogger.w(TAG, "CCCD descriptor not found, attempting pairing anyway");
                startPairingHandshake();
            }
        } catch (SecurityException e) {
            AppLogger.e(TAG, "Notification enable permission denied", e);
        }
    }

    // ── Pairing Handshake ──────────────────────────────────────────────────

    private void startPairingHandshake() {
        pairingAttempts = 0;
        pairingComplete = false;
        sendPairingRequest();
    }

    private void sendPairingRequest() {
        if (pairingComplete)
            return;

        pairingAttempts++;
        if (pairingAttempts > BleConstants.PAIRING_TIMEOUT_SECONDS) {
            AppLogger.e(TAG, "Pairing handshake timed out");
            updateNotification("Pairing timed out");
            // Disconnect and retry
            disconnect();
            handler.postDelayed(this::startScanning, 5000);
            return;
        }

        AppLogger.i(TAG,
                "Sending pairing request (" + pairingAttempts + "/" + BleConstants.PAIRING_TIMEOUT_SECONDS + ")");
        writeCommand(BleConstants.CMD_GET_PAIRING_CODE);

        // Schedule next attempt in 1 second
        handler.postDelayed(this::sendPairingRequest, 1000);
    }

    // ── Power Polling ──────────────────────────────────────────────────────

    private void startPowerPolling() {
        // Only remove pairing callbacks, not all callbacks (schedule check needs to
        // stay)
        AppLogger.i(TAG, "Starting power polling every " + BleConstants.POWER_POLL_INTERVAL_MS + "ms");
        pollPower();
    }

    private void pollPower() {
        if (!isConnected || !pairingComplete)
            return;

        writeCommand(BleConstants.CMD_GET_LIVE_POWER);

        // Schedule next poll
        handler.postDelayed(this::pollPower, BleConstants.POWER_POLL_INTERVAL_MS);
    }

    // ── Write Command ──────────────────────────────────────────────────────

    private void writeCommand(byte[] command) {
        if (gatt == null || writeCharacteristic == null)
            return;

        try {
            writeCharacteristic.setValue(command);
            writeCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            gatt.writeCharacteristic(writeCharacteristic);
        } catch (SecurityException e) {
            AppLogger.e(TAG, "Write permission denied", e);
        }
    }

    // ── Broadcasting ───────────────────────────────────────────────────────

    private void broadcastPower(int watts) {
        Intent intent = new Intent(BleConstants.ACTION_LIVE_POWER);
        intent.putExtra(BleConstants.EXTRA_WATTS, watts);
        sendBroadcast(intent);

        // Feed power data into smart charging controller
        if (chargingController != null) {
            chargingController.onPowerUpdate(watts);
        }
    }

    private void broadcastConnectionState(boolean connected) {
        Intent intent = new Intent(BleConstants.ACTION_CONNECTION_STATE);
        intent.putExtra(BleConstants.EXTRA_CONNECTED, connected);
        sendBroadcast(intent);
    }

    // ── Foreground Notification ────────────────────────────────────────────

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Dlb Power Service",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shows live power consumption from BLE device");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent notifyIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notifyIntent, PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Dlb Power")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }
}
