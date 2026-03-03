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
import java.util.List;

public class AmpsHistoryManager {

    private static final String TAG = "DlbCharging.AmpsHist";
    private static final String FILENAME = "amps_history.csv";
    private static final long MAX_AGE_MS = 24 * 60 * 60 * 1000L; // 24 hours

    private static AmpsHistoryManager instance;
    private final Context context;
    private final List<AmpsDataPoint> history = new ArrayList<>();

    public static class AmpsDataPoint implements Comparable<AmpsDataPoint> {
        public long timestamp;
        public int amps;

        public AmpsDataPoint(long timestamp, int amps) {
            this.timestamp = timestamp;
            this.amps = amps;
        }

        @Override
        public int compareTo(AmpsDataPoint o) {
            return Long.compare(this.timestamp, o.timestamp);
        }
    }

    private AmpsHistoryManager(Context context) {
        this.context = context.getApplicationContext();
        load();
    }

    public static synchronized AmpsHistoryManager getInstance(Context context) {
        if (instance == null) {
            instance = new AmpsHistoryManager(context);
        }
        return instance;
    }

    public synchronized void addPoint(int amps) {
        addPoint(System.currentTimeMillis(), amps);
    }

    public synchronized void addPoint(long timestamp, int amps) {
        history.add(new AmpsDataPoint(timestamp, amps));
        pruneOldData();
        saveAsync();
    }

    public synchronized List<AmpsDataPoint> getData() {
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
            for (AmpsDataPoint p : history) {
                writer.write(p.timestamp + "," + p.amps);
                writer.newLine();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error saving history", e);
        }
    }

    private synchronized void load() {
        File file = new File(context.getFilesDir(), FILENAME);
        if (!file.exists())
            return;

        history.clear();
        long cutoff = System.currentTimeMillis() - MAX_AGE_MS;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length == 2) {
                    long ts = Long.parseLong(parts[0]);
                    if (ts >= cutoff) {
                        int amps = Integer.parseInt(parts[1]);
                        history.add(new AmpsDataPoint(ts, amps));
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading history", e);
        }
    }
}
