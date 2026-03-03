package com.yahooeu2k.dlb_charging;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Smart charging controller that adjusts EV charging current (5–10A)
 * based on live power consumption data from a solar-equipped house.
 *
 * Logic:
 * - Power data = grid consumption in watts (always >= 0, 0 = solar covers
 * everything)
 * - If grid consumption <= overConsumptionTolerance → ramp up by 1A
 * - If grid consumption > overConsumptionTolerance → ramp down by 1A
 * - Ramp by ±1A per power update (~10s) to avoid oscillation
 * - Sends broadcast intent com.yahooeu2k.dlb_charging.power with int "value"
 * (5–32)
 */
public class ChargingController {

    private static final String TAG = "DlbCharging.Charging";
    private static final String PREFS_NAME = "charging_controller";
    private static final String KEY_ENABLED = "charging_enabled";
    private static final String KEY_FORCE_MAX_MODE = "force_max_mode";
    private static final String KEY_FORCE_MAX_END_TIME = "force_max_end_time";
    private static final String KEY_ECO_MODE = "eco_mode_enabled";
    private static final String KEY_ECO_SCHEDULE_MODE = "eco_schedule_mode";
    private static final String KEY_ECO_START_HOUR = "eco_start_hour";
    private static final String KEY_ECO_START_MINUTE = "eco_start_minute";
    private static final String KEY_ECO_END_HOUR = "eco_end_hour";
    private static final String KEY_ECO_END_MINUTE = "eco_end_minute";
    private static final String KEY_ECO_ACTIVELY_CHARGING = "eco_actively_charging";
    private static final String KEY_ECO_TOLERANCE = "eco_tolerance_watts";

    private boolean forceMaxMode = false;
    private long forceMaxEndTime = 0;
    private boolean ecoModeEnabled = false;

    private boolean ecoScheduleEnabled = false;
    private int ecoStartHour = 10;
    private int ecoStartMinute = 0;
    private int ecoEndHour = 15;
    private int ecoEndMinute = 0;

    // Safety Timeout
    private static final String KEY_SAFETY_TIMEOUT = "safety_timeout_enabled";
    private static final String KEY_SAFETY_AMPS = "safety_fallback_amps";
    private boolean safetyTimeoutEnabled = true;
    private int safetyFallbackAmps = 5;
    private long lastDataTimestamp = System.currentTimeMillis();
    private boolean hasReceivedData = false;

    private boolean ecoModeIsActivelyCharging = true; // Tracks if we've explicitly enabled it
    private int ecoToleranceW = 100;

    private void setEcoModeIsActivelyCharging(boolean active) {
        if (this.ecoModeIsActivelyCharging != active) {
            this.ecoModeIsActivelyCharging = active;
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_ECO_ACTIVELY_CHARGING, active).apply();
        }
    }

    // Constant definitions
    public static final int[] TOLERANCE_OPTIONS = { 0, 100, 200, 300, 400, 500 };
    private static final int MIN_AMPS = 5;
    private static final int MAX_AMPS = 32;
    private static final int VOLTS = 230;
    private static final String KEY_TOLERANCE = "over_consumption_tolerance";
    private static final String KEY_CURRENT_AMPS = "current_amps";

    // State
    private Context context;
    private boolean enabled = false;
    private int overConsumptionToleranceW = 200;
    private int currentAmps = MIN_AMPS;
    private int lastSentAmps = -1;

    private long lastRampUpTime = 0;
    private static final long RAMP_UP_DELAY_MS = 30000; // 30 seconds
    private int ampsAtLastRampUp = -1;
    private int consumptionAtLastRampUp = -1;

    // Oscillation prevention
    private int lastDropFromAmps = -1;
    private long lastDropTime = 0;
    private static final long OSCILLATION_COOLDOWN_MS = 5 * 60 * 1000; // 5 minutes

    // Eco Mode smart backoff resume
    private long lastEcoStopTime = 0;
    private int ecoBackoffLevel = 1; // Current backoff multiplier (1-5)
    private static final long ECO_BACKOFF_BASE_MS = 60 * 1000; // 1 minute base
    private static final int ECO_BACKOFF_MAX = 5; // Max 5 minutes

    // Eco Mode stop evaluation
    private long ecoStopEvaluationStartTime = 0;
    private static final long ECO_STOP_EVALUATION_DELAY_MS = 60 * 1000; // 60 seconds

    // Listener to auto-update when prefs change (e.g. from UI)
    private SharedPreferences.OnSharedPreferenceChangeListener prefsListener;

    private static ChargingController instance;

    private VehiclePropertyObserver getVehiclePropertyObserver() {
        if (context instanceof DlbApplication) {
            return ((DlbApplication) context).getVehiclePropertyObserver();
        }
        return null;
    }

    public static synchronized ChargingController getInstance(Context context) {
        if (instance == null) {
            instance = new ChargingController(context.getApplicationContext());
        }
        return instance;
    }

    private ChargingController(Context context) {
        this.context = context.getApplicationContext();
        loadPrefs();
        registerPrefsListener();
    }

    private void registerPrefsListener() {
        prefsListener = (sharedPreferences, key) -> {
            if (KEY_ENABLED.equals(key) || KEY_TOLERANCE.equals(key) || KEY_SAFETY_TIMEOUT.equals(key)
                    || KEY_SAFETY_AMPS.equals(key) || KEY_ECO_MODE.equals(key)
                    || KEY_ECO_SCHEDULE_MODE.equals(key) || KEY_ECO_START_HOUR.equals(key)
                    || KEY_ECO_START_MINUTE.equals(key) || KEY_ECO_END_HOUR.equals(key)
                    || KEY_ECO_END_MINUTE.equals(key) || KEY_ECO_TOLERANCE.equals(key)) {
                AppLogger.i(TAG, "Preference changed: " + key + ", reloading...");
                loadPrefs();
                // If enabled changed, broadcast immediately
                if (KEY_ENABLED.equals(key)) {
                    broadcastChargingState();
                }
            }
        };
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(prefsListener);
    }

    private void loadPrefs() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean wasEnabled = enabled;
        enabled = prefs.getBoolean(KEY_ENABLED, false);
        overConsumptionToleranceW = prefs.getInt(KEY_TOLERANCE, 200);
        currentAmps = prefs.getInt(KEY_CURRENT_AMPS, MIN_AMPS);
        safetyTimeoutEnabled = prefs.getBoolean(KEY_SAFETY_TIMEOUT, true);
        safetyFallbackAmps = prefs.getInt(KEY_SAFETY_AMPS, MIN_AMPS);
        ecoModeEnabled = prefs.getBoolean(KEY_ECO_MODE, false);
        ecoModeIsActivelyCharging = prefs.getBoolean(KEY_ECO_ACTIVELY_CHARGING, true);
        ecoToleranceW = prefs.getInt(KEY_ECO_TOLERANCE, 100);

        ecoScheduleEnabled = prefs.getBoolean(KEY_ECO_SCHEDULE_MODE, false);
        ecoStartHour = prefs.getInt(KEY_ECO_START_HOUR, 10);
        ecoStartMinute = prefs.getInt(KEY_ECO_START_MINUTE, 0);
        ecoEndHour = prefs.getInt(KEY_ECO_END_HOUR, 15);
        ecoEndMinute = prefs.getInt(KEY_ECO_END_MINUTE, 0);

        forceMaxMode = prefs.getBoolean(KEY_FORCE_MAX_MODE, false);
        forceMaxEndTime = prefs.getLong(KEY_FORCE_MAX_END_TIME, 0);

        // Auto-disable if time expired
        if (forceMaxMode && System.currentTimeMillis() > forceMaxEndTime) {
            AppLogger.i(TAG, "Force Max Mode expired during load");
            forceMaxMode = false;
            // logic will resume normal amps on next update or user action
        }

        // Clamp fallback
        safetyFallbackAmps = Math.max(MIN_AMPS, Math.min(MAX_AMPS, safetyFallbackAmps));

        // Clamp in case prefs are stale
        currentAmps = Math.max(MIN_AMPS, Math.min(MAX_AMPS, currentAmps));

        if (wasEnabled != enabled) {
            AppLogger.i(TAG, "Enabled state changed: " + wasEnabled + " -> " + enabled);
        }
    }

    // Removed savePrefs() to prevent full state overwrites

    public boolean isForceMaxActive() {
        if (!forceMaxMode)
            return false;
        if (System.currentTimeMillis() > forceMaxEndTime) {
            // Expired, toggle off
            setForceMaxMode(false);
            return false;
        }
        return true;
    }

    public void setForceMaxMode(boolean active) {
        if (this.forceMaxMode == active)
            return;

        this.forceMaxMode = active;
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        if (active) {
            // Enable for 24 hours
            this.forceMaxEndTime = System.currentTimeMillis() + (24 * 60 * 60 * 1000L);
            this.currentAmps = MAX_AMPS; // Force to 32A immediately
            AppLogger.i(TAG, "Force Max Mode ACTIVATED until " + new java.util.Date(forceMaxEndTime));
            // Should we save currentAmps too? Yes, for force max.
            editor.putInt(KEY_CURRENT_AMPS, currentAmps);
        } else {
            this.forceMaxEndTime = 0;
            AppLogger.i(TAG, "Force Max Mode DEACTIVATED");
            // Optionally reset amps or let next cycle handle it.
            // Let's safe drop to min to be sure, or keep as is until next read?
            // Safer to just leave it, normal logic will pick it up on next power update.
        }

        editor.putBoolean(KEY_FORCE_MAX_MODE, forceMaxMode)
                .putLong(KEY_FORCE_MAX_END_TIME, forceMaxEndTime)
                .apply();

        broadcastChargingState(); // Notify UI

        if (active) {
            // Send the high amps command immediately
            sendChargingIntent(MAX_AMPS);
        }
    }

    public long getForceMaxEndTime() {
        return forceMaxEndTime;
    }

    // ... existing fields ...

    private boolean isEcoScheduleActive() {
        if (!ecoScheduleEnabled)
            return true; // If schedule disabled, Eco mode is always active

        java.util.Calendar cal = java.util.Calendar.getInstance();
        int currentHour = cal.get(java.util.Calendar.HOUR_OF_DAY);
        int currentMinute = cal.get(java.util.Calendar.MINUTE);

        int currentTotalMins = currentHour * 60 + currentMinute;
        int startTotalMins = ecoStartHour * 60 + ecoStartMinute;
        int endTotalMins = ecoEndHour * 60 + ecoEndMinute;

        if (startTotalMins <= endTotalMins) {
            return currentTotalMins >= startTotalMins && currentTotalMins < endTotalMins;
        } else {
            // Fallback for overnight schedules
            return currentTotalMins >= startTotalMins || currentTotalMins < endTotalMins;
        }
    }

    /**
     * Called on each power data update (~every 10 seconds).
     * 
     * @param consumptionWatts current grid consumption in watts (always >= 0)
     */
    public void onPowerUpdate(int consumptionWatts) {
        // Treat small fluctuations (up to 20W) as background noise (0W consumption)
        if (consumptionWatts <= 20) {
            consumptionWatts = 0;
        }

        lastDataTimestamp = System.currentTimeMillis(); // Update timestamp
        hasReceivedData = true;

        if (isForceMaxActive()) {
            // Ignore logic, just ensure we stick to MAX
            if (currentAmps != MAX_AMPS) {
                currentAmps = MAX_AMPS;
                sendChargingIntent(currentAmps);
                broadcastChargingState();
            }
            return;
        }

        // Eco Mode Logic
        if (ecoModeEnabled && isEcoScheduleActive()) {
            // 1. Handling "Resuming from Stop"
            if (lastSentAmps == 0 || !ecoModeIsActivelyCharging) { // We are stopped or haven't explicitly started
                long now = System.currentTimeMillis();
                if (consumptionWatts <= 10) {
                    // Check cooldown with smart backoff
                    long currentCooldown = ECO_BACKOFF_BASE_MS * ecoBackoffLevel;
                    if (now - lastEcoStopTime >= currentCooldown) {
                        // Resume
                        sendEcoModeIntent(1); // Turn ON (Allow charging)
                        setEcoModeIsActivelyCharging(true);
                        currentAmps = MIN_AMPS; // Reset to min
                        ecoStopEvaluationStartTime = 0; // Reset evaluation phase

                        // Force send current amps too so car knows what level to resume at
                        sendChargingIntent(currentAmps);

                        lastSentAmps = currentAmps; // Now we are at 5
                        broadcastChargingState();
                        AppLogger.i(TAG,
                                "Eco Mode RESUME: Consumption " + consumptionWatts
                                        + "W <= 10W, Backoff level=" + ecoBackoffLevel
                                        + " (cooldown=" + (currentCooldown / 1000) + "s)");
                    } else {
                        AppLogger.d(TAG, "Eco Mode RESUME prevented: Backoff level="
                                + ecoBackoffLevel + " (wait "
                                + ((currentCooldown - (now - lastEcoStopTime)) / 1000) + "s)");
                    }
                }
                return; // Don't run standard logic while stopped
            }

            // 2. We are NOT stopped (Active Charging).
            // Logic falls through to STANDARD LOGIC below to allow ramping down to min.
            // We checks for "Stop" condition AFTER standard logic.
        } else if (ecoModeEnabled && !isEcoScheduleActive() && !ecoModeIsActivelyCharging) {
            // Eco Mode is enabled but outside the schedule window, and was previously
            // stopped by Eco Mode
            AppLogger.i(TAG, "Eco Mode Schedule ended: Resuming normal charging state.");
            sendEcoModeIntent(1); // Force vehicle back to charging permitted state
            setEcoModeIsActivelyCharging(true);
            ecoStopEvaluationStartTime = 0; // Reset evaluation phase
            if (currentAmps < MIN_AMPS) {
                currentAmps = MIN_AMPS;
            }
            sendChargingIntent(currentAmps);
            lastSentAmps = currentAmps;
        }

        if (!enabled)
            return;

        // ... existing logic ...

        int previousAmps = currentAmps;

        int activeTolerance = (ecoModeEnabled && isEcoScheduleActive()) ? ecoToleranceW : overConsumptionToleranceW;

        if (consumptionWatts <= activeTolerance) {
            // Within tolerance — solar covers the load, slowly ramp up (+1A)
            if (currentAmps < MAX_AMPS) {
                long now = System.currentTimeMillis();
                if (now - lastRampUpTime >= RAMP_UP_DELAY_MS) {

                    int targetAmps = currentAmps + 1;
                    int expectedPowerIncreaseW = 250; // Conservative estimate for 1A at 230-240V
                    boolean likelyToOscillate = false;

                    if (consumptionWatts > 0) {
                        if (consumptionWatts + expectedPowerIncreaseW > activeTolerance) {
                            likelyToOscillate = true;
                            AppLogger.i(TAG,
                                    "Oscillation prevented: " + consumptionWatts + "W + " + expectedPowerIncreaseW
                                            + "W > " + activeTolerance + "W. Holding at " + currentAmps
                                            + "A.");
                        }
                    } else if (expectedPowerIncreaseW > activeTolerance) {
                        // If consumption is 0, we can't tell the true export margin.
                        // We must test it, but apply a cooldown if it previously failed (caused a
                        // drop).
                        if (targetAmps == lastDropFromAmps && (now - lastDropTime < OSCILLATION_COOLDOWN_MS)) {
                            likelyToOscillate = true;
                            AppLogger.i(TAG, "Oscillation prevented: recently dropped from " + targetAmps
                                    + "A. Cooldown active. Holding at " + currentAmps + "A.");
                        }
                    }

                    if (likelyToOscillate) {
                        lastRampUpTime = now; // Reset timer so we delay the next attempt and don't spam logs
                    } else {
                        // Saturation Check logic
                        boolean saturated = false;
                        // Only check if we are sustaining the same amps as we ramped to previously
                        if (currentAmps == ampsAtLastRampUp && consumptionAtLastRampUp > 0) {
                            int delta = consumptionWatts - consumptionAtLastRampUp;
                            // Expectation: If we increased amps, power consumption should have risen.
                            // If delta is negligible (< 100W) despite adding 1A (~230W), suspect
                            // saturation.
                            if (delta < 100) {
                                saturated = true;
                            }
                        }

                        if (saturated) {
                            AppLogger.i(TAG, "Saturation Detected: Amps " + currentAmps + "A, deltaP="
                                    + (consumptionWatts - consumptionAtLastRampUp) + "W. Holding current.");
                            lastRampUpTime = now; // Reset timer to delay next check/attempt
                        } else {
                            // Proceed to ramp up
                            consumptionAtLastRampUp = consumptionWatts;
                            ampsAtLastRampUp = currentAmps + 1;

                            currentAmps++;
                            lastRampUpTime = now;

                            // Reset backoff on successful ramp above MIN
                            if (ecoModeEnabled && ecoBackoffLevel > 1 && currentAmps > MIN_AMPS) {
                                AppLogger.i(TAG, "Eco backoff RESET: Ramped to " + currentAmps
                                        + "A, solar confirmed. Backoff " + ecoBackoffLevel + " → 1");
                                ecoBackoffLevel = 1;
                            }

                            AppLogger.i(TAG, "Ramping UP: consumption=" + consumptionWatts
                                    + "W <= tolerance=" + activeTolerance
                                    + "W → " + currentAmps + "A");
                        }
                    }
                } else {
                    // Update valid but throttled to ensure gradual ramp
                    AppLogger.d(TAG, "Ramp UP throttled (wait " + (RAMP_UP_DELAY_MS - (now - lastRampUpTime)) + "ms)");
                }
            }
        } else {
            // Over tolerance
            int excessWatts = consumptionWatts - activeTolerance;
            // Calculate amps to shed (round up to ensure we get within tolerance)
            int ampsToDrop = (int) Math.ceil((double) excessWatts / VOLTS);

            int targetAmps = Math.max(MIN_AMPS, currentAmps - ampsToDrop);
            if (targetAmps < currentAmps) {
                lastDropFromAmps = currentAmps; // Record the amps we dropped from
                lastDropTime = System.currentTimeMillis();

                AppLogger.i(TAG, "FAST DROP: consumption=" + consumptionWatts
                        + "W, excess=" + excessWatts
                        + "W, dropping " + ampsToDrop + "A → " + targetAmps + "A");
                currentAmps = targetAmps;
            }
        }

        // Only send intent and broadcast state if amps actually changed
        if (currentAmps != lastSentAmps) {
            sendChargingIntent(currentAmps);
            broadcastChargingState();
            lastSentAmps = currentAmps;
            // Only save currentAmps
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putInt(KEY_CURRENT_AMPS, currentAmps).apply();

            AppLogger.i(TAG, "Charging current changed: " + previousAmps + "A → " + currentAmps + "A"
                    + " (grid=" + consumptionWatts + "W, tolerance=" + activeTolerance + "W)");
        } else {
            AppLogger.d(TAG, "No change: " + currentAmps + "A (grid=" + consumptionWatts
                    + "W, tolerance=" + activeTolerance + "W)");
        }

        AppLogger.d(TAG, "Eco Logic Check: Enabled=" + ecoModeEnabled + ", Amps=" + currentAmps + ", Min=" + MIN_AMPS
                + ", Cons=" + consumptionWatts + ", LastSent=" + lastSentAmps);

        // POST-STANDARD CHECKS for Eco Mode
        if (ecoModeEnabled && isEcoScheduleActive()) {
            boolean conditionMet = false;

            // eco mode stop logic only kicks in after it organically ramps down all the way
            // to the minimum limit
            if (currentAmps == MIN_AMPS) {
                if (consumptionWatts > ecoToleranceW) {
                    conditionMet = true;
                }
            }

            if (conditionMet) {
                long now = System.currentTimeMillis();
                if (ecoStopEvaluationStartTime == 0) {
                    ecoStopEvaluationStartTime = now;
                    AppLogger.i(TAG, "Eco Mode STOP condition met. Start evaluation 60s delay.");
                } else if (now - ecoStopEvaluationStartTime >= ECO_STOP_EVALUATION_DELAY_MS) {
                    if (ecoModeIsActivelyCharging || lastSentAmps != 0) {
                        sendEcoModeIntent(0); // OFF
                        setEcoModeIsActivelyCharging(false);
                        lastSentAmps = 0;
                        lastEcoStopTime = now; // Record time we stopped for cooldown
                        ecoStopEvaluationStartTime = 0; // Reset for next time
                        ecoBackoffLevel = Math.min(ecoBackoffLevel + 1, ECO_BACKOFF_MAX);
                        broadcastChargingState();
                        AppLogger.i(TAG, "Eco Mode STOP: Condition sustained for 60s & Consumption > " + ecoToleranceW
                                + "W. Backoff level now=" + ecoBackoffLevel
                                + " (next cooldown=" + (ECO_BACKOFF_BASE_MS * ecoBackoffLevel / 1000) + "s)");
                    }
                } else {
                    AppLogger.d(TAG, "Eco Mode STOP evaluation: Wait "
                            + ((ECO_STOP_EVALUATION_DELAY_MS - (now - ecoStopEvaluationStartTime)) / 1000) + "s");
                }
            } else {
                // Condition relieved, reset evaluation
                if (ecoStopEvaluationStartTime != 0) {
                    ecoStopEvaluationStartTime = 0;
                    AppLogger.i(TAG, "Eco Mode STOP condition relieved. Reset evaluation delay.");
                }
            }
        } else {
            ecoStopEvaluationStartTime = 0; // Reset if outside schedule timeframe
        }

        // Record amps history synchronously with power history
        int plottedAmps = lastSentAmps >= 0 ? lastSentAmps : currentAmps;
        AmpsHistoryManager.getInstance(context).addPoint(plottedAmps);
    }

    /**
     * Periodic check for data timeout. call this from a background thread or
     * scheduled handler.
     * returns true if timeout occurred and value was reset.
     */
    public boolean checkSafetyTimeout() {
        if (!enabled || !safetyTimeoutEnabled)
            return false;
        if (ecoModeEnabled && isEcoScheduleActive())
            return false; // Safety timeout not applicable in Eco Mode

        // Ensure we've actually verified a connection at least once before timing out
        if (!hasReceivedData)
            return false;

        long age = System.currentTimeMillis() - lastDataTimestamp;
        if (age > 5 * 60 * 1000) { // 5 minutes
            if (currentAmps > safetyFallbackAmps) {
                AppLogger.w(TAG,
                        "Safety Timeout! No data for " + (age / 1000) + "s. Dropping to " + safetyFallbackAmps + "A.");
                currentAmps = safetyFallbackAmps;
                sendChargingIntent(currentAmps);
                broadcastChargingState();
                lastSentAmps = currentAmps;
                // Only save currentAmps
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putInt(KEY_CURRENT_AMPS, currentAmps).apply();
                return true;
            }
        }
        return false;
    }

    private void sendChargingIntent(int amps) {
        VehiclePropertyObserver vpo = getVehiclePropertyObserver();
        if (vpo != null) {
            vpo.setVehicleProperty(
                    VehiclePropertyObserver.PROP_CHG_CURRENT, (float) amps);
            AppLogger.i(TAG, "Set VHAL charging current: " + amps + "A");
        } else {
            AppLogger.w(TAG, "VPO not ready, cannot set charging current: " + amps + "A");
        }
    }

    private void sendEcoModeIntent(int state) {
        VehiclePropertyObserver vpo = getVehiclePropertyObserver();
        if (vpo != null) {
            vpo.setVehicleProperty(
                    VehiclePropertyObserver.PROP_CHARGE_FUNC_AC, state);
            AppLogger.i(TAG, "Set VHAL AC charging: " + (state == 1 ? "START" : "STOP"));
        } else {
            AppLogger.w(TAG, "VPO not ready, cannot set AC charging state: " + state);
        }
    }

    private void broadcastChargingState() {
        Intent intent = new Intent(BleConstants.ACTION_CHARGING_STATE);
        intent.putExtra(BleConstants.EXTRA_CHARGING_AMPS, currentAmps);
        intent.putExtra(BleConstants.EXTRA_CHARGING_ENABLED, enabled);

        // Determine "Is Charging Active" state for UI indicator
        boolean isActive = false;
        if (ecoModeEnabled && isEcoScheduleActive()) {
            // In new Eco Mode logic, we send real amps (5-32) or 0 (Stop).
            // So if lastSentAmps > 0, we are charging.
            isActive = (lastSentAmps > 0);
        } else if (forceMaxMode) {
            isActive = true;
        } else if (enabled) {
            // but practically it is modulating amps.
            // Since we don't have a "stop" logic in standard mode (min 5A), it is always
            // charging if enabled.
            isActive = true;
        }

        intent.putExtra(BleConstants.EXTRA_IS_CHARGING_ACTIVE, isActive);
        intent.putExtra(KEY_ECO_MODE, ecoModeEnabled); // Broadcast eco mode state too so UI can update switch if needed

        context.sendBroadcast(intent);
    }

    // ── Getters / Setters ──────────────────────────────────────────────────

    public boolean isSafetyTimeoutEnabled() {
        return safetyTimeoutEnabled;
    }

    public void setSafetyTimeoutEnabled(boolean safetyTimeoutEnabled) {
        this.safetyTimeoutEnabled = safetyTimeoutEnabled;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_SAFETY_TIMEOUT, safetyTimeoutEnabled).apply();
        AppLogger.i(TAG, "Safety timeout enabled: " + safetyTimeoutEnabled);
    }

    public int getSafetyFallbackAmps() {
        return safetyFallbackAmps;
    }

    public void setSafetyFallbackAmps(int amps) {
        this.safetyFallbackAmps = Math.max(MIN_AMPS, Math.min(MAX_AMPS, amps));
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_SAFETY_AMPS, this.safetyFallbackAmps).apply();
        AppLogger.i(TAG, "Safety fallback amps set to " + this.safetyFallbackAmps + "A");
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        if (!enabled) {
            // Reset to minimum when disabled
            currentAmps = MIN_AMPS;
            lastSentAmps = -1;
            editor.putInt(KEY_CURRENT_AMPS, currentAmps);
        }
        editor.putBoolean(KEY_ENABLED, enabled).apply();

        broadcastChargingState();
        AppLogger.i(TAG, "Charging controller " + (enabled ? "ENABLED" : "DISABLED"));
    }

    public int getOverConsumptionToleranceW() {
        return overConsumptionToleranceW;
    }

    public void setOverConsumptionToleranceW(int watts) {
        this.overConsumptionToleranceW = watts;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_TOLERANCE, watts).apply();
        AppLogger.i(TAG, "Over-consumption tolerance set to " + watts + "W");
    }

    public boolean isEcoModeEnabled() {
        return ecoModeEnabled;
    }

    public void setEcoModeEnabled(boolean enabled) {
        this.ecoModeEnabled = enabled;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ECO_MODE, enabled).apply();

        if (!enabled) {
            // If Eco Mode is disabled, and it was currently stopped, we must force it back
            // ON
            if (!ecoModeIsActivelyCharging) {
                AppLogger.i(TAG, "Eco Mode DISABLED: Resuming normal charging state.");
                sendEcoModeIntent(1); // Force vehicle back to charging permitted state
                setEcoModeIsActivelyCharging(true);

                // Normal logic only broadcasts on change, ensure we start at least at MIN_AMPS
                if (currentAmps < MIN_AMPS) {
                    currentAmps = MIN_AMPS;
                }
                sendChargingIntent(currentAmps);
                lastSentAmps = currentAmps;
            }
        }

        // Trigger logic update immediately?
        // Maybe just broadcast state so UI updates
        broadcastChargingState();
        AppLogger.i(TAG, "Eco Mode: " + (enabled ? "ENABLED" : "DISABLED"));
    }

    public int getEcoToleranceW() {
        return ecoToleranceW;
    }

    public void setEcoToleranceW(int watts) {
        this.ecoToleranceW = watts;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_ECO_TOLERANCE, watts).apply();
        AppLogger.i(TAG, "Eco Mode tolerance set to " + watts + "W");
    }

    public int getCurrentAmps() {
        return currentAmps;
    }

    /**
     * Returns the estimated power draw of charging at current amps.
     */
    public int getEstimatedChargingWatts() {
        return currentAmps * VOLTS;
    }
}
