package com.custom.launcher.util;

import android.util.Log;

/**
 * Utility class for simplified logging with filtered stack traces
 */
public class LogUtils {

    /**
     * Every log tag this app writes under.
     *
     * <p>
     * This is the single source of truth: {@link LogTee} filters logcat with it,
     * and {@code LogViewerActivity} uses it for its "app only" view. It used to be
     * duplicated in both places, and a tag missing from one of the copies meant
     * silently losing the only diagnostic channel there is on this head unit —
     * there is no adb, so a log line that isn't captured is a line that never
     * existed. Add new tags here and nowhere else.
     */
    public static final String[] APP_LOG_TAGS = {
            "CustomLauncher",
            "EnergyTileController",
            "CarPropertyClient",
            "BmsDebugActivity",
            "MediaListenerService",
            "MediaBrowseActivity",
            "BluetoothArtCache",
            "HvacTileController",
            "HvacClient",
            "CarAdapter",
            "SaicPackages",
            "LauncherSettings",
            "LocationTools",
            "LogTee",
            "LogUtils",
            "SaicSourceSwitch",
            "RadioClient",
            "LocationRelay"
    };

    /** {@code logcat} filterspec that keeps only our tags: "-s T1:V T2:V ... *:S". */
    public static String[] logcatFilterSpec() {
        String[] spec = new String[APP_LOG_TAGS.length + 1];
        for (int i = 0; i < APP_LOG_TAGS.length; i++) {
            spec[i] = APP_LOG_TAGS[i] + ":V";
        }
        spec[APP_LOG_TAGS.length] = "*:S";
        return spec;
    }

    /**
     * Initialize logging by setting log level to VERBOSE for all app tags.
     * This ensures all log messages are visible even if the system default is
     * ERROR.
     * Call this early in application startup (e.g., MainActivity.onCreate).
     */
    public static void initializeLogging() {
        for (String tag : APP_LOG_TAGS) {
            try {
                // Use reflection to call Log.isLoggable which forces tag registration
                // This ensures our tags log at VERBOSE level regardless of system settings
                Log.isLoggable(tag, Log.VERBOSE);

                // Force a log entry to ensure tag is registered
                Log.i(tag, "Logging initialized - all levels enabled");
            } catch (Exception e) {
                // Fallback if reflection fails
                Log.e("LogUtils", "Failed to initialize logging for tag: " + tag);
            }
        }

        Log.i("LogUtils", "=== Custom Launcher Logging Initialized ===");
        Log.i("LogUtils", "All log levels (VERBOSE, DEBUG, INFO, WARN, ERROR) are now enabled");
        Log.i("LogUtils", "Tags: " + String.join(", ", APP_LOG_TAGS));
    }

    /**
     * Log an error with a filtered stack trace showing only app-relevant frames
     * 
     * @param tag       Log tag
     * @param message   Error message
     * @param throwable Exception to log
     */
    public static void logError(String tag, String message, Throwable throwable) {
        // Log the message and exception type
        Log.e(tag, message + ": " + throwable.getClass().getSimpleName() +
                (throwable.getMessage() != null ? " - " + throwable.getMessage() : ""));

        // Log filtered stack trace
        logFilteredStackTrace(tag, throwable);
    }

    /**
     * Log a warning with a filtered stack trace showing only app-relevant frames
     * 
     * @param tag       Log tag
     * @param message   Warning message
     * @param throwable Exception to log
     */
    public static void logWarning(String tag, String message, Throwable throwable) {
        // Log the message and exception type
        Log.w(tag, message + ": " + throwable.getClass().getSimpleName() +
                (throwable.getMessage() != null ? " - " + throwable.getMessage() : ""));

        // Log filtered stack trace
        logFilteredStackTrace(tag, throwable);
    }

    /**
     * Log only the stack trace elements that are from our app (com.custom.launcher)
     * or are the direct cause of the error
     */
    private static void logFilteredStackTrace(String tag, Throwable throwable) {
        StackTraceElement[] elements = throwable.getStackTrace();
        boolean foundAppFrame = false;
        int appFrameCount = 0;

        StringBuilder sb = new StringBuilder();
        sb.append("  Stack trace (app frames only):\n");

        for (StackTraceElement element : elements) {
            String className = element.getClassName();

            // Include frames from our app package
            if (className.startsWith("com.custom.launcher")) {
                sb.append("    at ").append(element.toString()).append("\n");
                foundAppFrame = true;
                appFrameCount++;
            }
            // Also include the first non-app frame after app frames (immediate caller)
            else if (foundAppFrame && appFrameCount == 1) {
                sb.append("    at ").append(element.toString()).append(" (immediate caller)\n");
                break; // Stop after showing the immediate external caller
            }
        }

        if (appFrameCount == 0) {
            // No app frames found, show first frame only
            if (elements.length > 0) {
                sb.append("    at ").append(elements[0].toString()).append("\n");
            } else {
                sb.append("    (no stack trace available)\n");
            }
        }

        Log.e(tag, sb.toString());

        // If there's a cause, log it too (recursively, filtered)
        if (throwable.getCause() != null) {
            Log.e(tag, "  Caused by: " + throwable.getCause().getClass().getSimpleName() +
                    (throwable.getCause().getMessage() != null ? " - " + throwable.getCause().getMessage() : ""));
            logFilteredStackTrace(tag, throwable.getCause());
        }
    }
}
