package com.yahooeu2k.dlb_charging;

public final class MqttConstants {
    private MqttConstants() {}

    /** Broadcast action for live power updates from MQTT */
    public static final String ACTION_MQTT_POWER = "com.yahooeu2k.dlb_charging.MQTT_POWER";

    /** Intent extra key for watts value (int) or String message */
    public static final String EXTRA_PAYLOAD = "payload";

    /** Broadcast action for connection state changes */
    public static final String ACTION_CONNECTION_STATE = "com.yahooeu2k.dlb_charging.MQTT_CONNECTION_STATE";

    /** Intent extra key for connection state (boolean) */
    public static final String EXTRA_CONNECTED = "connected";
    
    // Preference keys
    public static final String PREFS_NAME = "mqtt_config";
    public static final String KEY_BROKER = "brokerUrl";
    public static final String KEY_TOPIC = "topic";
    public static final String KEY_USERNAME = "username";
    public static final String KEY_PASSWORD = "password";
    public static final String KEY_CLIENT_ID = "clientId";
    public static final String KEY_ENABLED = "mqtt_enabled";
}
