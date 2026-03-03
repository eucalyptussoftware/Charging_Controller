package com.yahooeu2k.dlb_charging;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class HistoryManager {

    private static final String TAG = "DlbCharging.History";
    private static final String FILENAME = "history.csv";
    private static final long MAX_AGE_MS = 24 * 60 * 60 * 1000L; // 24 hours

    private static HistoryManager instance;
    private final Context context;
    private final List<DataPoint> history = new ArrayList<>();

    public static class DataPoint implements Comparable<DataPoint> {
        public long timestamp;
        public int watts;
        public boolean isMqtt;

        public DataPoint(long timestamp, int watts, boolean isMqtt) {
            this.timestamp = timestamp;
            this.watts = watts;
            this.isMqtt = isMqtt;
        }

        @Override
        public int compareTo(DataPoint o) {
            return Long.compare(this.timestamp, o.timestamp);
        }
    }

    private HistoryManager(Context context) {
        this.context = context.getApplicationContext();
        load();
    }

    public static synchronized HistoryManager getInstance(Context context) {
        if (instance == null) {
            instance = new HistoryManager(context);
        }
        return instance;
    }

    public synchronized void addPoint(int watts, boolean isMqtt) {
        addPoint(System.currentTimeMillis(), watts, isMqtt);
    }

    public synchronized void addPoint(long timestamp, int watts, boolean isMqtt) {
        history.add(new DataPoint(timestamp, watts, isMqtt));
        pruneOldData();
        saveAsync();
    }

    public synchronized List<DataPoint> getData() {
        return new ArrayList<>(history);
    }

    private void pruneOldData() {
        long cutoff = System.currentTimeMillis() - MAX_AGE_MS;
        while (!history.isEmpty() && history.get(0).timestamp < cutoff) {
            history.remove(0);
        }
    }

    public synchronized void clear() {
        history.clear();
        saveAsync();
    }

    private void saveAsync() {
        new Thread(this::save).start();
    }

    private synchronized void save() {
        File file = new File(context.getFilesDir(), FILENAME);
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            for (DataPoint p : history) {
                writer.write(p.timestamp + "," + p.watts + "," + (p.isMqtt ? "1" : "0"));
                writer.newLine();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error saving history", e);
        }
    }

    private synchronized void load() {
        File file = new File(context.getFilesDir(), FILENAME);
        if (!file.exists()) return;

        history.clear();
        long cutoff = System.currentTimeMillis() - MAX_AGE_MS;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length == 3) {
                    long ts = Long.parseLong(parts[0]);
                    if (ts >= cutoff) {
                        int watts = Integer.parseInt(parts[1]);
                        boolean isMqtt = "1".equals(parts[2]);
                        history.add(new DataPoint(ts, watts, isMqtt));
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading history", e);
        }
    }
}
