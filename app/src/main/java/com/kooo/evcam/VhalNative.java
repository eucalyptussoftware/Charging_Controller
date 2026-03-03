package com.kooo.evcam;

/**
 * JNI bridge to native vehicle signal decoder library.
 * All protocol details are hidden in the native layer.
 */
public class VhalNative {

    static {
        System.loadLibrary("vhal_decoder");
    }

    // Event types returned by decode()
    public static final int EVT_TURN_SIGNAL = 1;
    public static final int EVT_DOOR_OPEN = 2;
    public static final int EVT_DOOR_CLOSE = 3;
    public static final int EVT_SPEED = 4;
    public static final int EVT_CUSTOM_KEY = 5;

    // EV Charging specific events
    public static final int EVT_CHG_TIME = 10;
    public static final int EVT_CHG_ENERGY = 11;
    public static final int EVT_CHG_SPEED = 12;
    public static final int EVT_BATTERY_LVL = 13;
    public static final int EVT_BATTERY_PCT = 14;
    public static final int EVT_CHG_CURRENT = 15;
    public static final int EVT_CHG_CURRENT_POWER = 16;
    public static final int EVT_PLUG_STATE = 17;
    public static final int EVT_CHG_WORK_CURRENT = 18;

    // Turn signal directions
    public static final int DIR_NONE = 0;
    public static final int DIR_LEFT = 1;
    public static final int DIR_RIGHT = 2;

    // Door positions
    public static final int DOOR_FL = 1;
    public static final int DOOR_FR = 2;
    public static final int DOOR_RL = 3;
    public static final int DOOR_RR = 4;

    /** Get service host address */
    public static native String getGrpcHost();

    /** Get service port number */
    public static native int getGrpcPort();

    /** Get streaming method full name */
    public static native String getStreamMethod();

    /** Get send-all method full name */
    public static native String getSendAllMethod();

    /**
     * Decode a property batch from the vehicle API stream.
     *
     * @param data raw bytes from the stream
     * @return int array: [numEvents, type1, p1, p2, type2, p1, p2, ...]
     *         Each event is 3 ints: [eventType, param1, param2]
     */
    public static native int[] decode(byte[] data);

    /**
     * Configure custom key property IDs and speed threshold.
     */
    public static native void configureCustomKey(int speedPropId, int buttonPropId,
            float speedThreshold);
}
