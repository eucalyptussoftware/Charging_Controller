package com.yahooeu2k.dlb_charging;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Build;
import android.util.Log;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.yahooeu2k.dlb_charging.HistoryManager;
import com.yahooeu2k.dlb_charging.MainActivity;
import com.yahooeu2k.dlb_charging.MqttConstants;
import com.yahooeu2k.dlb_charging.ScheduleManager;
// import com.yahooeu2k.dlb_charging.R; // Not needed since we are in the same package

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.UUID;

/**
 * Background service that wraps the HiveMQ client.
 * Managed by the ScheduleManager logic (via MainActivity) or manual toggle.
 */
public class MqttService extends Service {

    private static final String TAG = "DlbCharging.MqttService";
    private static final String CHANNEL_ID = "dlb_mqtt_channel";
    private static final int NOTIFICATION_ID = 2; // Different ID from BleService

    // Diagnostic: Track instances
    private static final java.util.concurrent.atomic.AtomicInteger instanceCount = new java.util.concurrent.atomic.AtomicInteger(
            0);
    private final String instanceId = UUID.randomUUID().toString().substring(0, 4);

    private Mqtt3AsyncClient client;
    private Handler handler;
    private ScheduleManager scheduleManager;
    private volatile boolean isConnected = false;
    private volatile boolean isConnecting = false; // Guard against overlapping connection attempts
    private volatile boolean isSubscribed = false; // Guard against duplicate subscriptions

    private String brokerUrl;
    private String topic;
    private String clientId;
    private String username;
    private String password;

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    private static final long SCHEDULE_CHECK_INTERVAL_MS = 60000; // 1 minute

    @Override
    public void onCreate() {
        super.onCreate();
        int count = instanceCount.incrementAndGet();
        AppLogger.i(TAG, "MqttService CREATED [" + instanceId + "]. Active instances: " + count);

        handler = new Handler(Looper.getMainLooper());
        scheduleManager = new ScheduleManager(this);
        createNotificationChannel();

        // Initialize ConnectivityManager
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager != null) {
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    SharedPreferences prefs = getSharedPreferences(MqttConstants.PREFS_NAME, MODE_PRIVATE);
                    boolean mqttEnabled = prefs.getBoolean(MqttConstants.KEY_ENABLED, true);

                    if (mqttEnabled) {
                        AppLogger.i(TAG, "[" + instanceId + "] Network available: " + network);
                    }
                    // Delayed check to ensure connection stability + unique client ID generation
                    handler.postDelayed(() -> {
                        if (mqttEnabled) {
                            AppLogger.i(TAG, "[" + instanceId + "] Network stabilized, performing health check...");
                        }
                        performHealthCheck();
                    }, 3000);
                }

                @Override
                public void onLost(Network network) {
                    AppLogger.w(TAG, "[" + instanceId + "] Network lost: " + network);
                    disconnect();
                    updateNotification("Waiting for network...");
                }
            };

            try {
                NetworkRequest request = new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build();
                connectivityManager.registerNetworkCallback(request, networkCallback);
            } catch (Exception e) {
                AppLogger.e(TAG, "[" + instanceId + "] Failed to register network callback", e);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification("Starting..."));
        AppLogger.i(TAG, "[" + instanceId + "] onStartCommand");

        if (intent != null && intent.getBooleanExtra("FORCE_RESTART", false)) {
            AppLogger.i(TAG, "[" + instanceId + "] Force restart requested — disconnecting");
            disconnect();
        }

        checkScheduleAndAct();
        return START_STICKY;
    }

    private void checkScheduleAndAct() {
        performHealthCheck();
        handler.postDelayed(this::checkScheduleAndAct, SCHEDULE_CHECK_INTERVAL_MS);
    }

    private void performHealthCheck() {
        if (isConnecting) {
            AppLogger.d(TAG, "[" + instanceId + "] Connection in progress, skipping health check");
            return;
        }

        SharedPreferences prefs = getSharedPreferences(MqttConstants.PREFS_NAME, MODE_PRIVATE);
        boolean mqttEnabled = prefs.getBoolean(MqttConstants.KEY_ENABLED, true);

        // Logic: Connect if MQTT is Enabled AND (Schedule is Disabled OR Time is Within
        // Schedule)
        // AND Force Max is NOT active
        boolean scheduleEnforced = scheduleManager.isScheduleEnforced();
        boolean timeWindowActive = isTimeWindowActive();
        boolean forceMaxActive = false;

        ChargingController cc = ChargingController.getInstance(this);
        forceMaxActive = cc.isForceMaxActive();

        boolean shouldConnect = mqttEnabled && (!scheduleEnforced || timeWindowActive) && !forceMaxActive;

        /*
         * AppLogger.d(TAG, "[" + instanceId + "] Schedule check: enabled=" +
         * mqttEnabled
         * + ", scheduleEnforced=" + scheduleEnforced
         * + ", timeActive=" + timeWindowActive
         * + ", forceMax=" + forceMaxActive
         * + " -> shouldConnect=" + shouldConnect);
         */

        if (shouldConnect) {
            if (!isConnected && client == null) {
                loadConfigAndConnect();
            } else if (!isConnected && client != null) {
                // Poll actual state to see if we missed a callback
                boolean actualState = false;
                try {
                    // Attempt to check actual client state
                    if (client.getState() != null && client.getState().isConnected()) {
                        actualState = true;
                    }
                } catch (Exception e) {
                    AppLogger.w(TAG, "[" + instanceId + "] Could not check client state", e);
                }

                if (actualState) {
                    AppLogger.i(TAG, "[" + instanceId
                            + "] Client is actually connected but service flag was false — correcting");
                    isConnected = true;
                    broadcastConnectionState(true);
                    updateNotification("Connected: " + topic);
                    // Do NOT call subscribe() here blindly.
                    if (!isSubscribed) {
                        AppLogger.w(TAG, "[" + instanceId + "] Connected but not subscribed? Subscribing now.");
                        subscribe();
                    }
                } else {
                    // AppLogger.d(TAG, "[" + instanceId + "] Client exists but is disconnected,
                    // trying to connect...");
                    loadConfigAndConnect();
                }
            }
        } else {
            if (isConnected) {
                disconnect();
            }
            updateNotification("MQTT Service suspended (Schedule/Disabled)");
        }
    }

    private boolean isTimeWindowActive() {
        // Only checks if CURRENT time is within the set schedule range.
        // Whether schedule is ENFORCED is checked in checkScheduleAndAct.

        // Reuse ScheduleManager for time calculation
        java.util.Calendar now = java.util.Calendar.getInstance();
        int today = now.get(java.util.Calendar.DAY_OF_WEEK);
        if (!scheduleManager.isDayEnabled(today))
            return false;

        int nowMinutes = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE);
        int startMinutes = scheduleManager.getStartHour() * 60 + scheduleManager.getStartMinute();
        int endMinutes = scheduleManager.getEndHour() * 60 + scheduleManager.getEndMinute();

        return nowMinutes >= startMinutes && nowMinutes < endMinutes;
    }

    private void loadConfigAndConnect() {
        if (isConnecting)
            return;
        isConnecting = true;

        SharedPreferences prefs = getSharedPreferences(MqttConstants.PREFS_NAME, MODE_PRIVATE);

        // Load defaults
        String defBroker = "", defTopic = "", defUser = "", defPass = "", defClientId = "";
        try {
            Properties properties = new Properties();
            InputStream inputStream = getAssets().open("mqtt.properties");
            properties.load(inputStream);
            defBroker = properties.getProperty("brokerUrl", "");
            defTopic = properties.getProperty("topic", "");
            defUser = properties.getProperty("username", "");
            defPass = properties.getProperty("password", "");
            defClientId = properties.getProperty("clientId", "EmeraldClient-" + UUID.randomUUID());
        } catch (Exception e) {
            AppLogger.e(TAG, "Error loading asset config", e);
        }

        brokerUrl = prefs.getString(MqttConstants.KEY_BROKER, defBroker);
        topic = prefs.getString(MqttConstants.KEY_TOPIC, defTopic);
        username = prefs.getString(MqttConstants.KEY_USERNAME, defUser);
        password = prefs.getString(MqttConstants.KEY_PASSWORD, defPass);
        clientId = prefs.getString(MqttConstants.KEY_CLIENT_ID, defClientId);

        // Append suffixes to ensure unique Client ID per session
        if (Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk") || Build.PRODUCT.contains("sdk")
                || Build.MODEL.contains("Emulator")) {
            clientId += "-emu";
        }
        clientId += "-" + UUID.randomUUID().toString().substring(0, 5);

        if (brokerUrl != null && !brokerUrl.isEmpty()) {
            connect();
        } else {
            updateNotification("Config Missing");
            isConnecting = false;
        }
    }

    private void connect() {
        if (isConnected) {
            isConnecting = false;
            return;
        }

        // Cleanup any existing client to prevent leaks
        if (client != null) {
            AppLogger.w(TAG, "[" + instanceId + "] Found existing client instance, forcing cleanup before connect");
            // Don't call disconnect() here as it might trigger recursive state changes or
            // notifications, just clean the object
            try {
                client.disconnect();
            } catch (Exception ignored) {
            }
            client = null;
        }

        updateNotification("Connecting...");
        AppLogger.i(TAG, "[" + instanceId + "] Connecting to " + brokerUrl + " as " + clientId);

        try {
            java.net.URI uri = new java.net.URI(brokerUrl);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();
            if (port == -1)
                port = "ssl".equalsIgnoreCase(scheme) ? 8883 : 1883;

            // We are NOT enabling automaticReconnect here anymore.
            // We handle network changes via ConnectivityManager, so we want precise
            // control.
            // Automatic reconnects often cause zombie clients if the network environment
            // changes (e.g. WiFi -> 4G).

            if ("ssl".equalsIgnoreCase(scheme) || "tls".equalsIgnoreCase(scheme)) {
                client = MqttClient.builder()
                        .useMqttVersion3()
                        .identifier(clientId)
                        .serverHost(host)
                        .serverPort(port)
                        .sslWithDefaultConfig()
                        .addDisconnectedListener(context -> {
                            Log.w(TAG, "[" + instanceId + "] Disconnected: " + context.getCause().getMessage());
                            isConnected = false;
                            isSubscribed = false; // Reset subscription flag
                            broadcastConnectionState(false);
                            updateNotification("Disconnected");
                            // Do NOT auto-reconnect here; let healthCheck or network callback handle it
                        })
                        .addConnectedListener(context -> {
                            Log.i(TAG, "[" + instanceId + "] Connected/Reconnected");
                            isConnected = true;
                            broadcastConnectionState(true);
                            updateNotification("Connected: " + topic);
                        })
                        .buildAsync();
            } else {
                client = MqttClient.builder()
                        .useMqttVersion3()
                        .identifier(clientId)
                        .serverHost(host)
                        .serverPort(port)
                        .addDisconnectedListener(context -> {
                            AppLogger.w(TAG, "[" + instanceId + "] Disconnected: " + context.getCause().getMessage());
                            isConnected = false;
                            isSubscribed = false; // Reset subscription flag
                            broadcastConnectionState(false);
                            updateNotification("Disconnected");
                        })
                        .addConnectedListener(context -> {
                            AppLogger.i(TAG, "[" + instanceId + "] Connected/Reconnected");
                            isConnected = true;
                            broadcastConnectionState(true);
                            updateNotification("Connected: " + topic);
                        })
                        .buildAsync();
            }

            client.connectWith()
                    .simpleAuth()
                    .username(username)
                    .password(password != null ? password.getBytes(StandardCharsets.UTF_8) : null)
                    .applySimpleAuth()
                    .send()
                    .whenComplete((connAck, throwable) -> {
                        isConnecting = false; // Reset flag regardless of outcome
                        if (throwable != null) {
                            AppLogger.e(TAG, "[" + instanceId + "] Initial connection failed", throwable);
                            updateNotification("Connection Failed");
                        } else {
                            AppLogger.i(TAG, "[" + instanceId + "] Initial connection successful");
                            subscribe();
                        }
                    });

        } catch (Exception e) {
            isConnecting = false;
            AppLogger.e(TAG, "[" + instanceId + "] Init failed", e);
            updateNotification("Init Error");
        }
    }

    private void subscribe() {
        if (client == null || topic == null)
            return;

        if (isSubscribed) {
            AppLogger.w(TAG, "[" + instanceId + "] Already subscribed, skipping duplicate request.");
            return;
        }

        AppLogger.d(TAG, "[" + instanceId + "] Subscribing to topic: " + topic);

        client.subscribeWith()
                .topicFilter(topic)
                .callback(publish -> {
                    String payload = new String(publish.getPayloadAsBytes(), StandardCharsets.UTF_8);

                    // Self-healing: If we are getting data, we ARE connected.
                    if (!isConnected) {
                        AppLogger.w(TAG, "[" + instanceId
                                + "] Data received while reports disconnected — self-healing connection state");
                        isConnected = true;
                        broadcastConnectionState(true);
                        updateNotification("Connected: " + topic);
                    }

                    AppLogger.d(TAG, "[" + instanceId + "] Received: " + payload);
                    broadcastPower(payload);
                    updateNotification("⚡ " + payload);
                    try {
                        int watts = (int) Double.parseDouble(payload);
                        HistoryManager.getInstance(MqttService.this).addPoint(watts, true);
                    } catch (Exception ignored) {
                    }
                })
                .send()
                .whenComplete((subAck, throwable) -> {
                    if (throwable != null) {
                        AppLogger.e(TAG, "[" + instanceId + "] Subscribe failed", throwable);
                        isSubscribed = false;
                    } else {
                        AppLogger.i(TAG, "[" + instanceId + "] Subscribe SUCCESS");
                        isSubscribed = true;
                    }
                });
    }

    private void disconnect() {
        if (client != null) {
            try {
                // Remove listeners to avoid "Disconnected" callbacks during intentional
                // shutdown
                // (Though HiveMQ client doesn't make this easy, we just rely on the flag)
                client.disconnect();
            } catch (Exception e) {
                AppLogger.w(TAG, "[" + instanceId + "] Error disconnecting", e);
            }
            client = null; // Prevent leaks
        }
        isConnected = false;
        isSubscribed = false;
        isConnecting = false;
        broadcastConnectionState(false);
    }

    private void broadcastPower(String payload) {
        Intent intent = new Intent(MqttConstants.ACTION_MQTT_POWER);
        intent.putExtra(MqttConstants.EXTRA_PAYLOAD, payload);
        sendBroadcast(intent);
    }

    private void broadcastConnectionState(boolean connected) {
        Intent intent = new Intent(MqttConstants.ACTION_CONNECTION_STATE);
        intent.putExtra(MqttConstants.EXTRA_CONNECTED, connected);
        sendBroadcast(intent);
    }

    @Override
    public void onDestroy() {
        int count = instanceCount.decrementAndGet();
        AppLogger.i(TAG, "MqttService DESTROYED [" + instanceId + "]. Active instances: " + count);

        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception e) {
                AppLogger.e(TAG, "[" + instanceId + "] Error unregistering network callback", e);
            }
        }
        disconnect();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "MQTT Power Service",
                NotificationManager.IMPORTANCE_LOW);
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
                .setContentTitle("MQTT Power")
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
