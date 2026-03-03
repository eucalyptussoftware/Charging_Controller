package com.yahooeu2k.dlb_charging;

import java.util.UUID;

/**
 * BLE constants extracted from the Emerald app's BluetoothHandler.smali
 * and CommonUtils$BLE_CONSTANTS.smali.
 */
public final class BleConstants {

    private BleConstants() {}

    // ── Service & Characteristic UUIDs ──────────────────────────────────────
    /** Advertised service UUID used for scanning (IHD_UUID) */
    public static final UUID IHD_SERVICE_UUID =
            UUID.fromString("0000a201-0000-1000-8000-00805f9b34fb");

    /** Read / Notify characteristic */
    public static final UUID READ_UUID =
            UUID.fromString("00002b10-0000-1000-8000-00805f9b34fb");

    /** Write characteristic */
    public static final UUID WRITE_UUID =
            UUID.fromString("00002b11-0000-1000-8000-00805f9b34fb");

    /** Client Characteristic Configuration Descriptor (for enabling notifications) */
    public static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // ── Commands (from CommonUtils$BLE_CONSTANTS) ──────────────────────────
    /** Get Pairing Code command */
    public static final byte[] CMD_GET_PAIRING_CODE = hexToBytes("0001030100");

    /** Prefix of the Pairing Code response */
    public static final String PAIRING_RESPONSE_PREFIX = "0001030206";

    /** Request live/updated power */
    public static final byte[] CMD_GET_LIVE_POWER = hexToBytes("0001020100");

    /** Prefix of the live power response */
    public static final String POWER_RESPONSE_PREFIX = "0001020204";

    // ── Intent Actions ─────────────────────────────────────────────────────
    /** Broadcast action for live power updates */
    public static final String ACTION_LIVE_POWER = "com.yahooeu2k.dlb_charging.LIVE_POWER";

    /** Intent extra key for watts value (int) */
    public static final String EXTRA_WATTS = "watts";

    /** Broadcast action for connection state changes */
    public static final String ACTION_CONNECTION_STATE = "com.yahooeu2k.dlb_charging.CONNECTION_STATE";

    /** Intent extra key for connection state (boolean) */
    public static final String EXTRA_CONNECTED = "connected";

    // ── Smart Charging Intents ─────────────────────────────────────────────
    /** Broadcast action to control charging current (sends amps as int) */
    public static final String ACTION_CHARGING_POWER = "com.yahooeu2k.dlb_charging.power";

    /** Intent extra key for charging amp value (int, 5–32) */
    public static final String EXTRA_CHARGING_VALUE = "value";

    /** Broadcast action for charging controller state updates (UI) */
    public static final String ACTION_CHARGING_STATE = "com.yahooeu2k.dlb_charging.CHARGING_STATE";

    /** Intent extra key for current charging amps (int) */
    public static final String EXTRA_CHARGING_AMPS = "amps";

    /** Intent extra key for charging enabled state (boolean) */
    public static final String EXTRA_CHARGING_ENABLED = "charging_enabled";

    /** Intent extra key for active charging status (boolean, true=Charging, false=Not Charging) */
    public static final String EXTRA_IS_CHARGING_ACTIVE = "is_charging_active";

    /** Broadcast action to switch Eco Mode on/off in the car (0=OFF, 1=ON) */
    public static final String ACTION_ECO_SWITCH = "com.yahooeu2k.dlb_charging.charge_state";

    // ── Timing ─────────────────────────────────────────────────────────────
    public static final int PAIRING_TIMEOUT_SECONDS = 30;
    public static final long POWER_POLL_INTERVAL_MS = 5000;

    // ── Utility ────────────────────────────────────────────────────────────
    public static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
