package com.yahooeu2k.dlb_charging;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Properties;
import java.util.UUID;

public class MqttActivity extends AppCompatActivity {

    private static final String TAG = "DlbCharging.Mqtt";

    private TextView tvStatus;
    private TextView tvPower;
    private TextView btnConnect;
    private TextView btnBack;
    
    // Inputs
    private EditText etBroker;
    private EditText etTopic;
    private EditText etUsername;
    private EditText etPassword;

    // Config values
    private String brokerUrl;
    private String topic;
    private String clientId;
    private String username;
    private String password;

    private final BroadcastReceiver mqttReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (MqttConstants.ACTION_CONNECTION_STATE.equals(intent.getAction())) {
                boolean connected = intent.getBooleanExtra(MqttConstants.EXTRA_CONNECTED, false);
                updateConnectionStatus(connected);
            } else if (MqttConstants.ACTION_MQTT_POWER.equals(intent.getAction())) {
                String payload = intent.getStringExtra(MqttConstants.EXTRA_PAYLOAD);
                tvPower.setText(payload + " W");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mqtt);

        tvStatus = findViewById(R.id.tv_mqtt_status);
        tvPower = findViewById(R.id.tv_mqtt_power);
        btnConnect = findViewById(R.id.btn_mqtt_connect);
        btnBack = findViewById(R.id.btn_back);
        
        etBroker = findViewById(R.id.et_broker);
        etTopic = findViewById(R.id.et_topic);
        etUsername = findViewById(R.id.et_username);
        etPassword = findViewById(R.id.et_password);

        loadConfig();

        btnConnect.setText("SAVE & RESTART");
        btnConnect.setOnClickListener(v -> saveAndRestartService());
        btnBack.setOnClickListener(v -> finish());
    }

    private void loadConfig() {
        SharedPreferences prefs = getSharedPreferences(MqttConstants.PREFS_NAME, MODE_PRIVATE);
        
        // 1. Load from Assets (defaults)
        String defBroker = "", defTopic = "", defUser = "", defPass = "", defClientId = "";
        try {
            Properties properties = new Properties();
            java.io.InputStream inputStream = getAssets().open("mqtt.properties");
            properties.load(inputStream);
            defBroker = properties.getProperty("brokerUrl", "");
            defTopic = properties.getProperty("topic", "");
            defUser = properties.getProperty("username", "");
            defPass = properties.getProperty("password", "");
            defClientId = properties.getProperty("clientId", "EmeraldClient-" + UUID.randomUUID());
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 2. Load from Prefs (overrides), fallback to defaults
        brokerUrl = prefs.getString(MqttConstants.KEY_BROKER, defBroker);
        topic = prefs.getString(MqttConstants.KEY_TOPIC, defTopic);
        username = prefs.getString(MqttConstants.KEY_USERNAME, defUser);
        password = prefs.getString(MqttConstants.KEY_PASSWORD, defPass);
        clientId = prefs.getString(MqttConstants.KEY_CLIENT_ID, defClientId);
        
        // 3. Populate UI
        etBroker.setText(brokerUrl);
        etTopic.setText(topic);
        etUsername.setText(username);
        etPassword.setText(password);
    }
    
    private void saveAndRestartService() {
        brokerUrl = etBroker.getText().toString().trim();
        topic = etTopic.getText().toString().trim();
        username = etUsername.getText().toString().trim();
        password = etPassword.getText().toString().trim();
        
        getSharedPreferences(MqttConstants.PREFS_NAME, MODE_PRIVATE).edit()
                .putString(MqttConstants.KEY_BROKER, brokerUrl)
                .putString(MqttConstants.KEY_TOPIC, topic)
                .putString(MqttConstants.KEY_USERNAME, username)
                .putString(MqttConstants.KEY_PASSWORD, password)
                .putString(MqttConstants.KEY_CLIENT_ID, clientId)
                .putBoolean(MqttConstants.KEY_ENABLED, true) // Force enable if saving
                .apply();

        Toast.makeText(this, "Settings Saved. Restarting Service...", Toast.LENGTH_SHORT).show();

        Intent intent = new Intent(this, MqttService.class);
        intent.putExtra("FORCE_RESTART", true);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void updateConnectionStatus(boolean connected) {
        if (connected) {
            tvStatus.setText("Connected");
            tvStatus.setTextColor(0xFF55FF55);
        } else {
            tvStatus.setText("Disconnected");
            tvStatus.setTextColor(0xFFFF5555);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(MqttConstants.ACTION_CONNECTION_STATE);
        filter.addAction(MqttConstants.ACTION_MQTT_POWER);
        registerReceiver(mqttReceiver, filter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(mqttReceiver);
    }
}
