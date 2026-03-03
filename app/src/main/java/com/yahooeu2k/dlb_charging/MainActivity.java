package com.yahooeu2k.dlb_charging;

import android.Manifest;
import android.app.TimePickerDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
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
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "DlbCharging.Main";
    private static final int PERMISSION_REQUEST_CODE = 100;

    private TextView tvStatus;
    private TextView tvPower;
    private Switch swEnabled;
    private EditText etPin;
    private TextView btnPair;
    private android.bluetooth.BluetoothGatt pairingGatt;

    // MQTT UI Elements
    private TextView tvMqttPower;
    private TextView tvMqttStatus;
    private Switch swMqttEnabled;
    private SharedPreferences mqttPrefs;

    // Smart Charging UI Elements
    private TextView tvChargingAmps;
    private TextView tvChargingWatts;
    private Switch swChargingEnabled;
    private Switch swEcoMode; // Eco Mode Switch
    private TextView tvChargingStatusIndicator; // Status Indicator (Green/Red)
    private android.widget.CheckBox swFileLogging; // New file logging checkbox
    private Spinner spinnerOverConsumption;
    private Spinner spinnerSafetyCurrent;
    private Spinner spinnerChgCurrent;
    private boolean isSpinnerChgCurrentUpdating = false;
    private boolean chgCurrentInitialized = false;
    private ChargingController chargingController;

    private Switch swScheduleMode;

    // Eco Mode Schedule UI Elements
    private Switch swEcoSchedule;
    private View layoutEcoSchedule;
    private Spinner spinnerEcoStartHour;
    private Spinner spinnerEcoEndHour;
    private Spinner spinnerEcoTolerance;
    private int ecoStartHour = 10, ecoStartMinute = 0;
    private int ecoEndHour = 15, ecoEndMinute = 0;

    // Day buttons
    private TextView dayMon, dayTue, dayWed, dayThu, dayFri, daySat, daySun;
    private boolean[] daySelected = new boolean[7];

    private TextView btnStartTime, btnEndTime, btnApply;

    private int startHour = 10, startMinute = 0;
    private int endHour = 15, endMinute = 0;

    private ScheduleManager scheduleManager;
    private PowerGraphView powerGraphView;
    private AmpsGraphView ampsGraphView;

    private TextView tvChargeBattery;
    private TextView tvChargeVoltage;
    private TextView tvChargeCurrentPower;
    private TextView tvChargeWorkCurrent;
    private TextView tvChargePlugState;
    private VehiclePropertyObserver vehiclePropertyObserver;
    private VehiclePropertyObserver.VehiclePropertyListener vehicleListener;
    private int mCurrentAcChargingState = -1; // -1=unknown, 0=off, 1=on

    private float mCurrentBatteryKwh = -1.0f;
    private float mCurrentBatteryPct = -1.0f;

    private void updateBatteryText() {
        if (tvChargeBattery == null)
            return;
        if (mCurrentBatteryKwh >= 0 && mCurrentBatteryPct >= 0) {
            tvChargeBattery.setText(String.format(Locale.getDefault(),
                    "Battery: %.1f kWh / %.1f%%", mCurrentBatteryKwh, mCurrentBatteryPct));
        } else if (mCurrentBatteryKwh >= 0) {
            tvChargeBattery.setText(String.format(Locale.getDefault(),
                    "Battery: %.1f kWh", mCurrentBatteryKwh));
        }
    }

    // Receiver for live updates
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null)
                return;

            switch (intent.getAction()) {
                case BleConstants.ACTION_LIVE_POWER:
                    int watts = intent.getIntExtra(BleConstants.EXTRA_WATTS, -1);
                    tvPower.setText(watts + " W");
                    updateGraph();
                    break;

                case BluetoothDevice.ACTION_BOND_STATE_CHANGED:
                    int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                    if (state == BluetoothDevice.BOND_BONDED) {
                        tvStatus.setText("Bonded Successfully");
                    } else if (state == BluetoothDevice.BOND_NONE) {
                        tvStatus.setText("Bond Failed");
                        if (btnPair != null && btnPair.getText().toString().startsWith(".")) {
                            btnPair.setText("PAIR");
                        }
                    }
                    break;

                case BleConstants.ACTION_CONNECTION_STATE:
                    boolean connected = intent.getBooleanExtra(BleConstants.EXTRA_CONNECTED, false);
                    tvStatus.setText(connected ? "Connected" : "Disconnected");
                    tvStatus.setTextColor(connected ? 0xFF00D4FF : 0xFF666666);
                    break;

                case MqttConstants.ACTION_MQTT_POWER:
                    String payload = intent.getStringExtra(MqttConstants.EXTRA_PAYLOAD);
                    if (payload != null) {
                        try {
                            double val = Double.parseDouble(payload);
                            tvMqttPower.setText((int) val + " W");
                            updateGraph();
                        } catch (NumberFormatException e) {
                            tvMqttPower.setText(payload);
                        }
                    }
                    break;

                case MqttConstants.ACTION_CONNECTION_STATE:
                    boolean mqttConnected = intent.getBooleanExtra(MqttConstants.EXTRA_CONNECTED, false);
                    tvMqttStatus.setText(mqttConnected ? "Connected" : "Disconnected");

                    // Update Pill UI
                    if (mqttConnected) {
                        tvMqttStatus.setBackgroundResource(R.drawable.bg_mqtt_status_connected);
                        tvMqttStatus.setTextColor(0xFFFFFFFF); // White text
                    } else {
                        tvMqttStatus.setBackgroundResource(R.drawable.bg_mqtt_status_disconnected);
                        tvMqttStatus.setTextColor(0xFFFFFFFF); // White text (keep white for contrast on grey pill)
                    }
                    break;

                case BleConstants.ACTION_CHARGING_STATE:
                    int amps = intent.getIntExtra(BleConstants.EXTRA_CHARGING_AMPS, 5);
                    boolean chargingEnabled = intent.getBooleanExtra(BleConstants.EXTRA_CHARGING_ENABLED, false);
                    boolean isChargingActive = intent.getBooleanExtra(BleConstants.EXTRA_IS_CHARGING_ACTIVE, false);

                    tvChargingAmps.setText(amps + " A");
                    tvChargingWatts.setText((amps * 240) + " W");
                    tvChargingAmps.setTextColor(chargingEnabled ? 0xFF00FF88 : 0xFF666666);

                    // Update Status Indicator
                    if (tvChargingStatusIndicator != null) {
                        if (isChargingActive) {
                            tvChargingStatusIndicator.setText("Charging");
                            tvChargingStatusIndicator.setBackgroundResource(R.drawable.bg_status_charging);
                        } else {
                            tvChargingStatusIndicator.setText("Not Charging");
                            tvChargingStatusIndicator.setBackgroundResource(R.drawable.bg_status_not_charging);
                        }
                    }

                    // Sync Eco Switch if updated externally
                    if (swEcoMode != null && intent.hasExtra("eco_mode_enabled")) {
                        boolean ecoEnabled = intent.getBooleanExtra("eco_mode_enabled", false);
                        if (swEcoMode.isChecked() != ecoEnabled) {
                            swEcoMode.setChecked(ecoEnabled);
                        }
                    }

                    // We also update graph here to ensure it catches latest state
                    updateAmpsGraph();
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        androidx.appcompat.app.AppCompatDelegate
                .setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO);
        setContentView(R.layout.activity_main);

        scheduleManager = new ScheduleManager(this);
        scheduleManager.initDefaults();

        mqttPrefs = getSharedPreferences(MqttConstants.PREFS_NAME, MODE_PRIVATE);
        chargingController = ChargingController.getInstance(this);

        // Bind views
        tvStatus = findViewById(R.id.tv_status);
        tvPower = findViewById(R.id.tv_power);
        swEnabled = findViewById(R.id.sw_enabled);

        tvMqttPower = findViewById(R.id.tv_main_mqtt_power);
        tvMqttStatus = findViewById(R.id.tv_mqtt_status_main);
        swMqttEnabled = findViewById(R.id.sw_mqtt_enabled);

        tvChargeBattery = findViewById(R.id.tv_charge_battery);
        tvChargeVoltage = findViewById(R.id.tv_charge_voltage);
        tvChargeCurrentPower = findViewById(R.id.tv_charge_current_power);
        tvChargeWorkCurrent = findViewById(R.id.tv_charge_work_current);
        tvChargePlugState = findViewById(R.id.tv_charge_plug_state);

        vehiclePropertyObserver = ((DlbApplication) getApplication()).getVehiclePropertyObserver();
        vehicleListener = new VehiclePropertyObserver.VehiclePropertyListener() {
            @Override
            public void onPropertyUpdated(int propId, int value) {
                runOnUiThread(() -> {
                    if (propId == VehiclePropertyObserver.PROP_CHG_CURRENT) {
                        AppLogger.i(TAG, "UI Received PROP_CHG_CURRENT: " + value);
                    } else if (propId == VehiclePropertyObserver.PROP_BATTERY_LVL
                            || propId == VehiclePropertyObserver.PROP_BATTERY_PCT) {
                        AppLogger.d(TAG, "UI Received BATTERY prop " + propId + ": " + value);
                    }

                    if (propId == VehiclePropertyObserver.PROP_BATTERY_LVL) {
                        mCurrentBatteryKwh = Float.intBitsToFloat(value) / 1000.0f;
                        updateBatteryText();
                    } else if (propId == VehiclePropertyObserver.PROP_BATTERY_PCT) {
                        mCurrentBatteryPct = Float.intBitsToFloat(value);
                        updateBatteryText();
                    } else if (propId == VehiclePropertyObserver.PROP_CHG_CURRENT_POWER) {
                        float pwr = Float.intBitsToFloat(value);
                        tvChargeCurrentPower.setText(String.format(Locale.getDefault(), "Power: %.1f kW", pwr));
                    } else if (propId == VehiclePropertyObserver.PROP_CHG_WORK_CURRENT) {
                        float wc = Float.intBitsToFloat(value);
                        tvChargeWorkCurrent
                                .setText(String.format(Locale.getDefault(), "Work Current: %.1f A", wc));
                    } else if (propId == VehiclePropertyObserver.PROP_PLUG_STATE) {
                        String plugStateStr;
                        switch (value) {
                            case 10:
                                plugStateStr = "Not connected";
                                break;
                            case 18:
                                plugStateStr = "Connected";
                                break;
                            default:
                                plugStateStr = "Unknown (" + value + ")";
                                break;
                        }
                        tvChargePlugState.setText("Plug: " + plugStateStr);
                    } else if (propId == VehiclePropertyObserver.PROP_CHARGE_FUNC_AC) {
                        mCurrentAcChargingState = value;
                    } else if (propId == VehiclePropertyObserver.PROP_CHG_VOLTAGE) {
                        float volts = Float.intBitsToFloat(value);
                        if (tvChargeVoltage != null) {
                            tvChargeVoltage
                                    .setText(String.format(Locale.getDefault(), "Voltage: %.1f V", volts));
                        }
                    } else if (propId == VehiclePropertyObserver.PROP_CHG_CURRENT) {
                        // Only update spinner once per session (first load / resume)
                        if (!chgCurrentInitialized && spinnerChgCurrent != null) {
                            float ampsFloat = Float.intBitsToFloat(value);
                            int amps = Math.round(ampsFloat);
                            int chgPos = amps - 5;
                            AppLogger.i(TAG,
                                    "PROP_CHG_CURRENT initial sync: float=" + ampsFloat + ", pos=" + chgPos);
                            if (chgPos >= 0 && chgPos < 28) {
                                // Only set the suppress-flag if the position is actually
                                // changing; if it's already correct setSelection() is a
                                // no-op and onItemSelected() won't fire to clear the flag,
                                // which would silently swallow the next user interaction.
                                if (spinnerChgCurrent.getSelectedItemPosition() != chgPos) {
                                    isSpinnerChgCurrentUpdating = true;
                                    spinnerChgCurrent.setSelection(chgPos);
                                }
                                chgCurrentInitialized = true;
                            } else {
                                AppLogger.w(TAG, "PROP_CHG_CURRENT out of bounds! Value: " + value);
                            }
                        }
                    }
                });
            }

            @Override
            public void onConnectionStateChanged(boolean connected) {
                // Optional: update UI based on VHAL connection state
            }
        };

        // ChargingController will fetch VehiclePropertyObserver directly from
        // DlbApplication

        swScheduleMode = findViewById(R.id.sw_schedule_mode);
        swScheduleMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (buttonView.isPressed())
                applySettings();
        });

        powerGraphView = findViewById(R.id.power_graph);
        ampsGraphView = findViewById(R.id.amps_graph);

        dayMon = findViewById(R.id.day_mon);
        dayTue = findViewById(R.id.day_tue);
        dayWed = findViewById(R.id.day_wed);
        dayThu = findViewById(R.id.day_thu);
        dayFri = findViewById(R.id.day_fri);
        daySat = findViewById(R.id.day_sat);
        daySun = findViewById(R.id.day_sun);

        btnStartTime = findViewById(R.id.btn_start_time);
        btnEndTime = findViewById(R.id.btn_end_time);
        btnApply = findViewById(R.id.btn_apply);
        etPin = findViewById(R.id.et_pin);
        btnPair = findViewById(R.id.btn_pair);

        // Load saved PIN
        String savedPin = getSharedPreferences("dlb_charging", MODE_PRIVATE)
                .getString("pairing_pin", "");
        etPin.setText(savedPin);

        // Pair button
        btnPair.setOnClickListener(v -> pairDevice());

        // Load saved schedule into UI
        loadScheduleToUI();

        // Day button click handlers
        setupDayButton(dayMon, 0);
        setupDayButton(dayTue, 1);
        setupDayButton(dayWed, 2);
        setupDayButton(dayThu, 3);
        setupDayButton(dayFri, 4);
        setupDayButton(daySat, 5);
        setupDayButton(daySun, 6);

        // Time picker buttons
        btnStartTime.setOnClickListener(v -> {
            new TimePickerDialog(this, (view, hourOfDay, minute) -> {
                startHour = hourOfDay;
                startMinute = minute;
                btnStartTime.setText(String.format(Locale.getDefault(), "%02d:%02d", hourOfDay, minute));
            }, startHour, startMinute, true).show();
        });

        btnEndTime.setOnClickListener(v -> {
            new TimePickerDialog(this, (view, hourOfDay, minute) -> {
                endHour = hourOfDay;
                endMinute = minute;
                btnEndTime.setText(String.format(Locale.getDefault(), "%02d:%02d", hourOfDay, minute));
            }, endHour, endMinute, true).show();
        });

        // Apply button
        btnApply.setOnClickListener(v -> applySettings());

        // Smart Charging UI
        tvChargingAmps = findViewById(R.id.tv_charging_amps);
        tvChargingWatts = findViewById(R.id.tv_charging_watts);
        swChargingEnabled = findViewById(R.id.sw_charging_enabled);
        spinnerOverConsumption = findViewById(R.id.spinner_over_consumption);

        // Set up tolerance spinner
        String[] toleranceLabels = { "0 W", "100 W", "200 W", "300 W", "400 W", "500 W" };
        ArrayAdapter<String> toleranceAdapter = new ArrayAdapter<>(this,
                R.layout.spinner_item, toleranceLabels);
        toleranceAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spinnerOverConsumption.setAdapter(toleranceAdapter);

        // Load saved tolerance value
        int savedTolerance = chargingController.getOverConsumptionToleranceW();
        for (int i = 0; i < ChargingController.TOLERANCE_OPTIONS.length; i++) {
            if (ChargingController.TOLERANCE_OPTIONS[i] == savedTolerance) {
                spinnerOverConsumption.setSelection(i);
                break;
            }
        }

        spinnerOverConsumption.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                chargingController.setOverConsumptionToleranceW(
                        ChargingController.TOLERANCE_OPTIONS[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        // Force Max Charge UI
        btnForceMax = findViewById(R.id.btn_force_max);
        tvForceCountdown = findViewById(R.id.tv_force_countdown);
        tvForceEndTime = findViewById(R.id.tv_force_end_time);

        btnForceMax.setOnClickListener(v -> {
            boolean newState = btnForceMax.isChecked();
            chargingController.setForceMaxMode(newState);
            updateForceMaxUI();
            if (newState)
                forceMaxHandler.post(forceMaxRunnable);
            else
                forceMaxHandler.removeCallbacks(forceMaxRunnable);
        });

        // Load saved charging enabled state
        swChargingEnabled.setChecked(chargingController.isEnabled());
        swChargingEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (buttonView.isPressed()) {
                chargingController.setEnabled(isChecked);
            }
        });

        // Update charging display
        tvChargingAmps.setText(chargingController.getCurrentAmps() + " A");
        tvChargingWatts.setText(chargingController.getEstimatedChargingWatts() + " W");
        tvChargingAmps.setTextColor(chargingController.isEnabled() ? 0xFF00FF88 : 0xFF666666);

        // Update charging display
        tvChargingAmps.setText(chargingController.getCurrentAmps() + " A");
        tvChargingWatts.setText(chargingController.getEstimatedChargingWatts() + " W");
        tvChargingAmps.setTextColor(chargingController.isEnabled() ? 0xFF00FF88 : 0xFF666666);

        // Status Indicator
        tvChargingStatusIndicator = findViewById(R.id.tv_charging_status_indicator);
        // Initial state update (will be refreshed by broadcast)

        // Eco Mode Switch
        swEcoMode = findViewById(R.id.sw_eco_mode);
        layoutEcoSchedule = findViewById(R.id.layout_eco_schedule);
        swEcoSchedule = findViewById(R.id.sw_eco_schedule);
        spinnerEcoStartHour = findViewById(R.id.spinner_eco_start_hour);
        spinnerEcoEndHour = findViewById(R.id.spinner_eco_end_hour);

        if (swEcoMode != null) {
            swEcoMode.setChecked(chargingController.isEcoModeEnabled());
            layoutEcoSchedule.setVisibility(chargingController.isEcoModeEnabled() ? View.VISIBLE : View.GONE);
            findViewById(R.id.layout_eco_tolerance)
                    .setVisibility(chargingController.isEcoModeEnabled() ? View.VISIBLE : View.GONE);

            swEcoMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (buttonView.isPressed()) {
                    chargingController.setEcoModeEnabled(isChecked);
                    layoutEcoSchedule.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                    findViewById(R.id.layout_eco_tolerance).setVisibility(isChecked ? View.VISIBLE : View.GONE);
                }
            });
        }

        // Eco Mode Tolerance Spinner
        spinnerEcoTolerance = findViewById(R.id.spinner_eco_tolerance);
        if (spinnerEcoTolerance != null) {
            ArrayAdapter<String> ecoToleranceAdapter = new ArrayAdapter<>(this,
                    R.layout.spinner_item, toleranceLabels); // Re-use the same labels as standard tolerance
            ecoToleranceAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
            spinnerEcoTolerance.setAdapter(ecoToleranceAdapter);

            int savedEcoTolerance = chargingController.getEcoToleranceW();
            for (int i = 0; i < ChargingController.TOLERANCE_OPTIONS.length; i++) {
                if (ChargingController.TOLERANCE_OPTIONS[i] == savedEcoTolerance) {
                    spinnerEcoTolerance.setSelection(i);
                    break;
                }
            }

            spinnerEcoTolerance.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    chargingController.setEcoToleranceW(ChargingController.TOLERANCE_OPTIONS[position]);
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
        }

        swEcoSchedule.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (buttonView.isPressed()) {
                applySettings();
            }
        });

        // Eco Time spinners
        String[] hoursArray = new String[24];
        for (int i = 0; i < 24; i++) {
            hoursArray[i] = String.format(Locale.getDefault(), "%02d:00", i);
        }
        ArrayAdapter<String> ecoTimeAdapter = new ArrayAdapter<>(this,
                R.layout.spinner_item, hoursArray);
        ecoTimeAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);

        if (spinnerEcoStartHour != null) {
            spinnerEcoStartHour.setAdapter(ecoTimeAdapter);
            spinnerEcoStartHour.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (ecoStartHour != position) {
                        ecoStartHour = position;
                        ecoStartMinute = 0;
                        applySettings();
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
        }

        if (spinnerEcoEndHour != null) {
            spinnerEcoEndHour.setAdapter(ecoTimeAdapter);
            spinnerEcoEndHour.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (ecoEndHour != position) {
                        ecoEndHour = position;
                        ecoEndMinute = 0;
                        applySettings();
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
        }

        // Load Eco Schedule
        SharedPreferences chargingPrefs = getSharedPreferences("charging_controller", MODE_PRIVATE);
        swEcoSchedule.setChecked(chargingPrefs.getBoolean("eco_schedule_mode", false));
        ecoStartHour = chargingPrefs.getInt("eco_start_hour", 10);
        ecoStartMinute = chargingPrefs.getInt("eco_start_minute", 0);
        ecoEndHour = chargingPrefs.getInt("eco_end_hour", 15);
        ecoEndMinute = chargingPrefs.getInt("eco_end_minute", 0);

        if (spinnerEcoStartHour != null)
            spinnerEcoStartHour.setSelection(ecoStartHour);
        if (spinnerEcoEndHour != null)
            spinnerEcoEndHour.setSelection(ecoEndHour);

        // MQTT Dashboard button
        TextView btnMqtt = findViewById(R.id.btn_mqtt_dashboard);
        if (btnMqtt != null) {
            btnMqtt.setOnClickListener(v -> {
                Intent intent = new Intent(this, MqttActivity.class);
                startActivity(intent);
            });
        }

        // Request permissions
        checkPermissions();

        // Update build info
        TextView tvBuildInfo = findViewById(R.id.tv_build_info);
        if (tvBuildInfo != null) {
            tvBuildInfo
                    .setText(getString(R.string.build_info, BuildConfig.VERSION_NAME, BuildConfig.BUILD_TIME_STRING));
        }

        // Auto-save when switch is toggled
        swEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (buttonView.isPressed())
                applySettings();
        });

        swMqttEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (buttonView.isPressed())
                applySettings();
        });

        // Reset Data Button
        findViewById(R.id.btn_reset_data).setOnClickListener(v -> resetData());

        // Test Data Button
        findViewById(R.id.btn_test_data).setOnClickListener(v -> addTestData());

        // File Logging Switch
        swFileLogging = findViewById(R.id.sw_file_logging);
        boolean fileLoggingEnabled = getSharedPreferences("dlb_power", MODE_PRIVATE)
                .getBoolean("file_logging_enabled", false);
        swFileLogging.setChecked(fileLoggingEnabled);
        AppLogger.enableFileLogging(this, fileLoggingEnabled);

        swFileLogging.setOnCheckedChangeListener((buttonView, isChecked) -> {
            AppLogger.enableFileLogging(MainActivity.this, isChecked);
            getSharedPreferences("dlb_power", MODE_PRIVATE)
                    .edit()
                    .putBoolean("file_logging_enabled", isChecked)
                    .apply();
        });

        // Auto-add test data only if empty on first run
        if (HistoryManager.getInstance(this).getData().isEmpty()) {
            addTestData();
        }

        // Safety Timeout UI
        Switch swSafety = findViewById(R.id.sw_safety_timeout);
        swSafety.setChecked(chargingController.isSafetyTimeoutEnabled());
        swSafety.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (buttonView.isPressed()) {
                chargingController.setSafetyTimeoutEnabled(isChecked);
            }
        });

        // Safety Current Spinner
        spinnerSafetyCurrent = findViewById(R.id.spinner_safety_current);
        String[] safetyAmpsOptions = new String[28]; // 5 to 32
        for (int i = 0; i < 28; i++) {
            safetyAmpsOptions[i] = (5 + i) + "A";
        }
        ArrayAdapter<String> safetyAdapter = new ArrayAdapter<>(this,
                R.layout.spinner_item, safetyAmpsOptions);
        safetyAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spinnerSafetyCurrent.setAdapter(safetyAdapter);

        // Set selection
        int currentSafetyAmps = chargingController.getSafetyFallbackAmps();
        int safePos = currentSafetyAmps - 5;
        if (safePos >= 0 && safePos < safetyAmpsOptions.length) {
            spinnerSafetyCurrent.setSelection(safePos);
        }

        spinnerSafetyCurrent.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String text = (String) parent.getItemAtPosition(position);
                // Parse "5A" -> 5
                try {
                    int amps = Integer.parseInt(text.replace("A", ""));
                    chargingController.setSafetyFallbackAmps(amps);
                } catch (NumberFormatException e) {
                    AppLogger.e(TAG, "Error parsing safety amps: " + text);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        // Charging Current Spinner
        spinnerChgCurrent = findViewById(R.id.spinner_chg_current);
        if (spinnerChgCurrent != null) {
            String[] chgAmpsOptions = new String[28]; // 5 to 32
            for (int i = 0; i < 28; i++) {
                chgAmpsOptions[i] = (5 + i) + "A";
            }
            ArrayAdapter<String> chgAdapter = new ArrayAdapter<>(this,
                    R.layout.spinner_item, chgAmpsOptions);
            chgAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
            spinnerChgCurrent.setAdapter(chgAdapter);

            // We do NOT set the default selection here based on charging controller amps.
            // Instead, we leave it at 0 (or wait for VHAL) so we don't accidentally write a
            // bad state
            // on startup if the user touches it before VHAL loads, or if onItemSelected
            // triggers early.
            // When VHAL push stream connects, it will populate this to the correct hardware
            // limit.

            spinnerChgCurrent.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    // Suppress the callback when we programmatically set the selection
                    // (either on first load from VHAL, or on resume re-sync).
                    if (isSpinnerChgCurrentUpdating) {
                        isSpinnerChgCurrentUpdating = false;
                        return;
                    }

                    // chgCurrentInitialized is false until the first VHAL value arrives.
                    // While it's false we haven't confirmed the real hardware state yet,
                    // so ignore user interaction to avoid overwriting an unknown value.
                    if (!chgCurrentInitialized) {
                        return;
                    }

                    String text = (String) parent.getItemAtPosition(position);
                    try {
                        int amps = Integer.parseInt(text.replace("A", ""));
                        if (vehiclePropertyObserver != null) {
                            AppLogger.i(TAG, "User selected new charge limit: " + amps + "A, sending to VHAL");
                            // PROP_CHG_CURRENT is a float property - must send as float
                            vehiclePropertyObserver.setVehicleProperty(
                                    VehiclePropertyObserver.PROP_CHG_CURRENT, (float) amps);
                        }
                    } catch (NumberFormatException e) {
                        AppLogger.e(TAG, "Error parsing chg amps: " + text);
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
        }

        // Start services on app launch (if permissions granted)
        if (checkPermissions()) {
            Intent bleIntent = new Intent(this, BleService.class);
            Intent mqttIntent = new Intent(this, MqttService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(bleIntent);
                startForegroundService(mqttIntent);
            } else {
                startService(bleIntent);
                startService(mqttIntent);
            }
        }
    }

    private void resetData() {
        HistoryManager.getInstance(this).clear();
        AmpsHistoryManager.getInstance(this).clear();
        updateGraph();
        updateAmpsGraph();
        Toast.makeText(this, "Data Reset", Toast.LENGTH_SHORT).show();
    }

    private void addTestData() {
        HistoryManager hm = HistoryManager.getInstance(this);
        AmpsHistoryManager ahm = AmpsHistoryManager.getInstance(this);
        if (hm.getData().isEmpty()) {
            long now = System.currentTimeMillis();
            for (int i = 0; i < 24; i++) {
                long time = now - (24 - i) * 3600000L;
                int bleWatts = (int) (1000 + 500 * Math.sin(i * 0.5));
                int mqttWatts = (int) (1200 + 400 * Math.cos(i * 0.5));
                int amps = 5 + (i % 27); // 5 to 32 A cycle
                hm.addPoint(time, bleWatts, false); // BLE
                hm.addPoint(time, mqttWatts, true); // MQTT
                ahm.addPoint(time, amps);
            }
            updateGraph();
            updateAmpsGraph();
        }
    }

    private void setupDayButton(TextView btn, int index) {
        btn.setOnClickListener(v -> {
            daySelected[index] = !daySelected[index];
            updateDayButtonVisual(btn, daySelected[index]);
        });
    }

    private void updateDayButtonVisual(TextView btn, boolean selected) {
        if (selected) {
            btn.setBackgroundResource(R.drawable.day_button_bg_selected);
            btn.setTextColor(0xFFFFFFFF);
        } else {
            btn.setBackgroundResource(R.drawable.day_button_bg);
            btn.setTextColor(0xFF888888);
        }
    }

    private void updateGraph() {
        if (powerGraphView != null) {
            powerGraphView.setData(HistoryManager.getInstance(this).getData());
        }
    }

    private void updateAmpsGraph() {
        if (ampsGraphView != null) {
            ampsGraphView.setData(AmpsHistoryManager.getInstance(this).getData());
        }
    }

    // Force Max UI
    private android.widget.ToggleButton btnForceMax;
    private TextView tvForceCountdown;
    private TextView tvForceEndTime;
    private final Handler forceMaxHandler = new Handler(Looper.getMainLooper());
    private final Runnable forceMaxRunnable = new Runnable() {
        @Override
        public void run() {
            updateForceMaxUI();
            if (chargingController.isForceMaxActive()) {
                forceMaxHandler.postDelayed(this, 10000); // Update every 10s
            }
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(BleConstants.ACTION_LIVE_POWER);
        filter.addAction(BleConstants.ACTION_CONNECTION_STATE);
        filter.addAction(MqttConstants.ACTION_MQTT_POWER);
        filter.addAction(MqttConstants.ACTION_CONNECTION_STATE);
        filter.addAction(BleConstants.ACTION_CHARGING_STATE);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        registerReceiver(receiver, filter);

        // Reset so the spinner re-syncs with the vehicle on each resume
        chgCurrentInitialized = false;

        if (vehiclePropertyObserver != null) {
            vehiclePropertyObserver.addListener(vehicleListener);
        }

        // Refresh UI
        loadScheduleToUI();
        updateGraph();
        updateAmpsGraph();
        updateForceMaxUI();
        if (chargingController.isForceMaxActive()) {
            forceMaxHandler.post(forceMaxRunnable);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(receiver);
        forceMaxHandler.removeCallbacks(forceMaxRunnable);
        if (vehiclePropertyObserver != null) {
            vehiclePropertyObserver.removeListener(vehicleListener);
        }
    }

    private void updateForceMaxUI() {
        if (btnForceMax == null)
            return;

        boolean active = chargingController.isForceMaxActive();
        // Avoid infinite loop if invoked from listener
        if (btnForceMax.isChecked() != active) {
            btnForceMax.setChecked(active);
        }

        if (active) {
            tvForceCountdown.setVisibility(View.VISIBLE);
            tvForceEndTime.setVisibility(View.VISIBLE);

            long now = System.currentTimeMillis();
            long end = chargingController.getForceMaxEndTime();
            long remaining = end - now;

            if (remaining > 0) {
                long hours = remaining / 3600000;
                long minutes = (remaining % 3600000) / 60000;
                tvForceCountdown.setText(String.format(Locale.getDefault(), "Ends in %02d:%02d", hours, minutes));

                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd MMM HH:mm", Locale.getDefault());
                tvForceEndTime.setText("at " + sdf.format(new java.util.Date(end)));
            } else {
                // Should have expired by logic, but just in case
                tvForceCountdown.setText("Expiring...");
            }

            // Visual indication on button
            btnForceMax.setBackgroundResource(R.drawable.cyan_button_bg);
            // Cyan is fine, maybe change color? Keep simple.
        } else {
            tvForceCountdown.setVisibility(View.GONE);
            tvForceEndTime.setVisibility(View.GONE);
            // btnForceMax.setBackgroundResource(R.drawable.day_button_bg); // Optional:
            // change style when off
        }
    }

    private void loadScheduleToUI() {
        swEnabled.setChecked(scheduleManager.isEnabled());
        swMqttEnabled.setChecked(mqttPrefs.getBoolean(MqttConstants.KEY_ENABLED, true));
        swScheduleMode.setChecked(scheduleManager.isScheduleEnforced());

        daySelected[0] = scheduleManager.isMonday();
        daySelected[1] = scheduleManager.isTuesday();
        daySelected[2] = scheduleManager.isWednesday();
        daySelected[3] = scheduleManager.isThursday();
        daySelected[4] = scheduleManager.isFriday();
        daySelected[5] = scheduleManager.isSaturday();
        daySelected[6] = scheduleManager.isSunday();

        startHour = scheduleManager.getStartHour();
        startMinute = scheduleManager.getStartMinute();
        endHour = scheduleManager.getEndHour();
        endMinute = scheduleManager.getEndMinute();

        btnStartTime.setText(String.format(Locale.getDefault(), "%02d:%02d", startHour, startMinute));
        btnEndTime.setText(String.format(Locale.getDefault(), "%02d:%02d", endHour, endMinute));
    }

    @Override
    protected void onStart() {
        super.onStart();
        updateDayButtonVisual(dayMon, daySelected[0]);
        updateDayButtonVisual(dayTue, daySelected[1]);
        updateDayButtonVisual(dayWed, daySelected[2]);
        updateDayButtonVisual(dayThu, daySelected[3]);
        updateDayButtonVisual(dayFri, daySelected[4]);
        updateDayButtonVisual(daySat, daySelected[5]);
        updateDayButtonVisual(daySun, daySelected[6]);
    }

    private void applySettings() {
        boolean enabled = swEnabled.isChecked();
        boolean mqttEnabled = swMqttEnabled.isChecked();
        boolean scheduleEnforced = swScheduleMode.isChecked();

        scheduleManager.save(
                enabled,
                scheduleEnforced,
                daySelected[0], daySelected[1], daySelected[2],
                daySelected[3], daySelected[4], daySelected[5], daySelected[6],
                startHour, startMinute, endHour, endMinute);

        mqttPrefs.edit().putBoolean(MqttConstants.KEY_ENABLED, mqttEnabled).apply();

        Intent bleIntent = new Intent(this, BleService.class);
        Intent mqttIntent = new Intent(this, MqttService.class);

        stopService(bleIntent);
        stopService(mqttIntent);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(bleIntent);
            startForegroundService(mqttIntent);
        } else {
            startService(bleIntent);
            startService(mqttIntent);
        }

        // Save Eco Schedule
        getSharedPreferences("charging_controller", MODE_PRIVATE)
                .edit()
                .putBoolean("eco_schedule_mode", swEcoSchedule.isChecked())
                .putInt("eco_start_hour", ecoStartHour)
                .putInt("eco_start_minute", ecoStartMinute)
                .putInt("eco_end_hour", ecoEndHour)
                .putInt("eco_end_minute", ecoEndMinute)
                .apply();

        String pin = etPin.getText().toString().trim();
        getSharedPreferences("dlb_power", MODE_PRIVATE)
                .edit().putString("pairing_pin", pin).apply();

        Toast.makeText(this, "Settings applied", Toast.LENGTH_SHORT).show();
    }

    private boolean checkPermissions() {
        String[] permissions = {
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
        };
        boolean allGranted = true;
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        if (!allGranted) {
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
            return false;
        }
        return true;
    }

    // ── Pairing Logic ──────────────────────────────────────────────────────

    private void pairDevice() {
        String pin = etPin.getText().toString().trim();
        if (pin.isEmpty()) {
            Toast.makeText(this, "Enter PIN first", Toast.LENGTH_SHORT).show();
            return;
        }

        getSharedPreferences("dlb_power", MODE_PRIVATE)
                .edit().putString("pairing_pin", pin).apply();

        BluetoothManager btManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = btManager != null ? btManager.getAdapter() : null;

        if (adapter == null || !adapter.isEnabled()) {
            Toast.makeText(this, "Bluetooth is off", Toast.LENGTH_SHORT).show();
            return;
        }

        btnPair.setText("...");
        tvStatus.setText("Scanning...");

        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null)
            return;

        ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(BleConstants.IHD_SERVICE_UUID))
                .build();
        List<ScanFilter> filters = new ArrayList<>();
        filters.add(filter);
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        Handler handler = new Handler(Looper.getMainLooper());

        ScanCallback scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice device = result.getDevice();
                try {
                    scanner.stopScan(this);
                } catch (Exception ignored) {
                }
                tvStatus.setText("Found: " + device.getName());

                device.connectGatt(MainActivity.this, false, new android.bluetooth.BluetoothGattCallback() {
                    @Override
                    public void onConnectionStateChange(android.bluetooth.BluetoothGatt gatt, int status,
                            int newState) {
                        if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                            runOnUiThread(() -> tvStatus.setText("Discovering..."));
                            pairingGatt = gatt;
                            gatt.discoverServices();
                        } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                            runOnUiThread(() -> {
                                tvStatus.setText("Disconnected");
                                btnPair.setText("PAIR");
                            });
                        }
                    }

                    // ... Simplified pairing logic for this rewrite focus ...
                    // Assuming existing logic was sound, just stripped for brevity in this manual
                    // rewrite
                    // actually I should include the pairing logic as it was critical for the user
                    @Override
                    public void onServicesDiscovered(android.bluetooth.BluetoothGatt gatt, int status) {
                        if (status == android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                            android.bluetooth.BluetoothGattService service = gatt
                                    .getService(BleConstants.IHD_SERVICE_UUID);
                            if (service != null) {
                                android.bluetooth.BluetoothGattCharacteristic writeChar = service
                                        .getCharacteristic(BleConstants.WRITE_UUID);
                                if (writeChar != null) {
                                    writeChar.setValue(BleConstants.CMD_GET_PAIRING_CODE);
                                    writeChar.setWriteType(
                                            android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                                    gatt.writeCharacteristic(writeChar);
                                    runOnUiThread(() -> tvStatus.setText("Handshake Sent"));
                                }
                            }
                        }
                    }

                    @Override
                    public void onCharacteristicWrite(android.bluetooth.BluetoothGatt gatt,
                            android.bluetooth.BluetoothGattCharacteristic characteristic, int status) {
                        if (status == 5 || status == 8 || status == 137) {
                            runOnUiThread(() -> tvStatus.setText("Bonding..."));
                            device.createBond();
                        }
                    }
                });
            }

            @Override
            public void onScanFailed(int errorCode) {
                btnPair.setText("PAIR");
                tvStatus.setText("Scan Fail");
            }
        };

        scanner.startScan(filters, settings, scanCallback);
        handler.postDelayed(() -> {
            try {
                scanner.stopScan(scanCallback);
            } catch (Exception ignored) {
            }
            if (btnPair.getText().equals("..."))
                btnPair.setText("PAIR");
        }, 15000);
    }
}
