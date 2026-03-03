package com.yahooeu2k.dlb_charging;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AppLogger {
    private static boolean fileLoggingEnabled = false;
    private static File logFile = null;
    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    private static final long MAX_LOG_SIZE = 5 * 1024 * 1024; // 5MB
    private static final int MAX_ROTATED_LOGS = 2;
    private static final String LOG_FILE_NAME = "dlp_charging_log.txt";

    public static void enableFileLogging(Context context, boolean enable) {
        fileLoggingEnabled = enable;
        if (enable) {
            File dir = context.getFilesDir();
            logFile = new File(dir, LOG_FILE_NAME);
        } else {
            logFile = null;
        }
    }

    public static void d(String tag, String msg) {
        Log.d(tag, msg);
        logToFile("DEBUG", tag, msg, null);
    }

    public static void i(String tag, String msg) {
        Log.i(tag, msg);
        logToFile("INFO", tag, msg, null);
    }

    public static void w(String tag, String msg) {
        Log.w(tag, msg);
        logToFile("WARN", tag, msg, null);
    }

    public static void w(String tag, String msg, Throwable tr) {
        Log.w(tag, msg, tr);
        logToFile("WARN", tag, msg, tr);
    }

    public static void e(String tag, String msg) {
        Log.e(tag, msg);
        logToFile("ERROR", tag, msg, null);
    }

    public static void e(String tag, String msg, Throwable tr) {
        Log.e(tag, msg, tr);
        logToFile("ERROR", tag, msg, tr);
    }

    private static void rotateLogsIfNeeded() {
        if (logFile == null || !logFile.exists()) return;
        if (logFile.length() < MAX_LOG_SIZE) return;
        
        // Rotate old logs
        File dir = logFile.getParentFile();
        String baseName = logFile.getName();
        
        // Delete the oldest rotated file if it exists
        File oldestFile = new File(dir, baseName + "." + MAX_ROTATED_LOGS);
        if (oldestFile.exists()) {
            oldestFile.delete();
        }

        // Shift existing rotated files
        for (int i = MAX_ROTATED_LOGS - 1; i >= 1; i--) {
            File oldFile = new File(dir, baseName + "." + i);
            File nextFile = new File(dir, baseName + "." + (i + 1));
            if (oldFile.exists()) {
                oldFile.renameTo(nextFile);
            }
        }

        // Rename current log file to .1
        File firstRotated = new File(dir, baseName + ".1");
        if (logFile.exists()) {
            logFile.renameTo(firstRotated);
        }
        
        // Create new log file object (it will be created on next write)
        logFile = new File(dir, baseName);
    }

    private static synchronized void logToFile(String level, String tag, String msg, Throwable tr) {
        if (!fileLoggingEnabled || logFile == null) return;
        try {
            rotateLogsIfNeeded();
            
            String time = sdf.format(new Date());
            StringBuilder sb = new StringBuilder();
            sb.append(time).append(" ").append(level).append("/").append(tag).append(": ").append(msg);
            if (tr != null) {
                sb.append("\n").append(Log.getStackTraceString(tr));
            }
            sb.append("\n");
            
            try (FileWriter fw = new FileWriter(logFile, true)) {
                fw.write(sb.toString());
            }
        } catch (Exception e) {
            // If file logging fails, ignore to avoid recursion or crash
            Log.e("AppLogger", "Failed to write to log file", e);
        }
    }
}
