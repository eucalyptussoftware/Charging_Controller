package com.yahooeu2k.dlb_charging;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;

/**
 * Manages the BLE service schedule using SharedPreferences.
 * Stores which days of the week and what time range the service should operate.
 */
public class ScheduleManager {

    private static final String PREFS_NAME = "dlb_charging_schedule";

    // Keys
    private static final String KEY_MON = "day_mon";
    private static final String KEY_TUE = "day_tue";
    private static final String KEY_WED = "day_wed";
    private static final String KEY_THU = "day_thu";
    private static final String KEY_FRI = "day_fri";
    private static final String KEY_SAT = "day_sat";
    private static final String KEY_SUN = "day_sun";
    private static final String KEY_START_HOUR = "start_hour";
    private static final String KEY_START_MINUTE = "start_minute";
    private static final String KEY_END_HOUR = "end_hour";
    private static final String KEY_END_MINUTE = "end_minute";
    private static final String KEY_ENABLED = "schedule_enabled"; // This enables the BLE service
    private static final String KEY_SCHEDULE_ENFORCED = "schedule_enforced"; // This decides if time window is checked

    private final SharedPreferences prefs;

    public ScheduleManager(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ── Defaults ───────────────────────────────────────────────────────────

    public void initDefaults() {
        if (!prefs.contains(KEY_ENABLED)) {
            SharedPreferences.Editor editor = prefs.edit();
            editor.putBoolean(KEY_ENABLED, true);
            editor.putBoolean(KEY_SCHEDULE_ENFORCED, false);
            // Enable all days by default
            editor.putBoolean(KEY_MON, true);
            editor.putBoolean(KEY_TUE, true);
            editor.putBoolean(KEY_WED, true);
            editor.putBoolean(KEY_THU, true);
            editor.putBoolean(KEY_FRI, true);
            editor.putBoolean(KEY_SAT, true);
            editor.putBoolean(KEY_SUN, true);
            // Default time range: 10:00 - 15:00
            editor.putInt(KEY_START_HOUR, 10);
            editor.putInt(KEY_START_MINUTE, 0);
            editor.putInt(KEY_END_HOUR, 15);
            editor.putInt(KEY_END_MINUTE, 0);
            editor.apply();
        }
    }

    // ── Getters ────────────────────────────────────────────────────────────

    public boolean isEnabled()   { return prefs.getBoolean(KEY_ENABLED, true); }
    public boolean isScheduleEnforced() { return prefs.getBoolean(KEY_SCHEDULE_ENFORCED, false); }
    public boolean isMonday()    { return prefs.getBoolean(KEY_MON, true); }
    public boolean isTuesday()   { return prefs.getBoolean(KEY_TUE, true); }
    public boolean isWednesday() { return prefs.getBoolean(KEY_WED, true); }
    public boolean isThursday()  { return prefs.getBoolean(KEY_THU, true); }
    public boolean isFriday()    { return prefs.getBoolean(KEY_FRI, true); }
    public boolean isSaturday()  { return prefs.getBoolean(KEY_SAT, true); }
    public boolean isSunday()    { return prefs.getBoolean(KEY_SUN, true); }

    public int getStartHour()    { return prefs.getInt(KEY_START_HOUR, 10); }
    public int getStartMinute()  { return prefs.getInt(KEY_START_MINUTE, 0); }
    public int getEndHour()      { return prefs.getInt(KEY_END_HOUR, 15); }
    public int getEndMinute()    { return prefs.getInt(KEY_END_MINUTE, 0); }

    public boolean isDayEnabled(int calendarDay) {
        switch (calendarDay) {
            case Calendar.MONDAY:    return isMonday();
            case Calendar.TUESDAY:   return isTuesday();
            case Calendar.WEDNESDAY: return isWednesday();
            case Calendar.THURSDAY:  return isThursday();
            case Calendar.FRIDAY:    return isFriday();
            case Calendar.SATURDAY:  return isSaturday();
            case Calendar.SUNDAY:    return isSunday();
            default: return false;
        }
    }

    // ── Save ───────────────────────────────────────────────────────────────

    public void save(boolean enabled, boolean scheduleEnforced,
                     boolean mon, boolean tue, boolean wed, boolean thu,
                     boolean fri, boolean sat, boolean sun,
                     int startHour, int startMinute,
                     int endHour, int endMinute) {
        prefs.edit()
                .putBoolean(KEY_ENABLED, enabled)
                .putBoolean(KEY_SCHEDULE_ENFORCED, scheduleEnforced)
                .putBoolean(KEY_MON, mon)
                .putBoolean(KEY_TUE, tue)
                .putBoolean(KEY_WED, wed)
                .putBoolean(KEY_THU, thu)
                .putBoolean(KEY_FRI, fri)
                .putBoolean(KEY_SAT, sat)
                .putBoolean(KEY_SUN, sun)
                .putInt(KEY_START_HOUR, startHour)
                .putInt(KEY_START_MINUTE, startMinute)
                .putInt(KEY_END_HOUR, endHour)
                .putInt(KEY_END_MINUTE, endMinute)
                .apply();
    }

    // ── Schedule Check ─────────────────────────────────────────────────────

    /**
     * Returns true if the service should be active RIGHT NOW.
     * Takes into account master enable switch AND schedule logic.
     */
    public boolean isWithinSchedule() {
        if (!isEnabled()) return false; // Master switch off = Force Off
        if (!isScheduleEnforced()) return true; // Schedule ignored = Always On (if enabled)
        
        return isWithinScheduleIgnoringEnabled();
    }

    public boolean isWithinScheduleIgnoringEnabled() {
        Calendar now = Calendar.getInstance();

        // Check day of week
        int today = now.get(Calendar.DAY_OF_WEEK);
        if (!isDayEnabled(today)) return false;

        // Check time range
        int nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int nowTotal = nowMinutes; // typo fix? no, just clarity
        
        int startTotal = getStartHour() * 60 + getStartMinute();
        int endTotal = getEndHour() * 60 + getEndMinute();

        // Handle overnight schedule if start > end? 
        // Current logic assumes same day (start < end). 
        // If start > end, it usually means overnight.
        // But for simplicity let's keep existing logic structure:
        // return nowMinutes >= startMinutes && nowMinutes < endMinutes;
        // If users want overnight, they might need 2 distinct schedules or logic upgrade.
        // Sticking to existing logic for now to avoid regression.

        return nowTotal >= startTotal && nowTotal < endTotal;
    }
}
