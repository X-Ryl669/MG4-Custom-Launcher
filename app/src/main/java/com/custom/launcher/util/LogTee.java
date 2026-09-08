package com.custom.launcher.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import android.util.Log;

/**
 * Streams this app's own logcat output to a file that survives log rotation.
 *
 * <p>
 * There is no adb on this head unit, so the in-app log viewer is the only way to
 * see anything. But the viewer reads {@code logcat -d -t 200}, and the car's own
 * services (the Bluetooth stack, the TBox NMEA feed, {@code CarBMSManager}) emit
 * hundreds of lines a second — by the time a person parks, opens the launcher's
 * hidden debug menu and scrolls, every line this app wrote is long gone. A
 * captured log from the car contained 201 lines and not one of them was ours.
 *
 * <p>
 * So a background thread runs {@code logcat} <em>filtered to our tags</em> and
 * appends to {@code /storage/emulated/0/Download/mg4-launcher.log}, which the
 * user can then open in the Files app or copy off at leisure. Filtering happens
 * in logcat itself rather than here, so the tee costs almost nothing even while
 * the rest of the system is screaming.
 */
public final class LogTee {
    private static final String TAG = "LogTee";

    /**
     * Download rather than a USB stick or app-private storage: the stock Files
     * app can reach it without root, the user does not have to have a stick
     * plugged in when something goes wrong, and it survives reinstalling us.
     */
    private static final File LOG_DIR = new File("/storage/emulated/0/Download");
    private static final String LOG_NAME = "mg4-launcher.log";
    private static final String PREV_NAME = "mg4-launcher.log.1";

    /** Rotate at 2 MB, keeping one previous generation. */
    private static final long MAX_BYTES = 2L * 1024 * 1024;

    /** Flush this often so a hard power-off loses at most a second of log. */
    private static final long FLUSH_INTERVAL_MS = 1000;

    private static Thread thread;
    private static Process process;
    private static volatile boolean running;

    private LogTee() {
    }

    public static File logFile() {
        return new File(LOG_DIR, LOG_NAME);
    }

    public static synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(LogTee::pump, "LogTee");
        thread.setDaemon(true);
        thread.start();
        Log.i(TAG, "Teeing app logs to " + logFile());
    }

    public static synchronized void stop() {
        running = false;
        if (process != null) {
            process.destroy();
            process = null;
        }
        thread = null;
    }

    private static void pump() {
        FileWriter writer = null;
        try {
            if (!LOG_DIR.isDirectory() && !LOG_DIR.mkdirs()) {
                Log.w(TAG, "Cannot create " + LOG_DIR + " - not teeing logs");
                return;
            }
            rotateIfNeeded();

            File out = logFile();
            writer = new FileWriter(out, true);
            writer.write("\n=== log tee started ===\n");
            writer.flush();

            // "-v time" to match what the viewer shows; no "-d", so this follows.
            List<String> command = new ArrayList<>();
            command.add("logcat");
            command.add("-v");
            command.add("time");
            command.add("-s");
            for (String spec : LogUtils.logcatFilterSpec()) {
                command.add(spec);
            }

            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));

            long written = out.length();
            long lastFlush = System.currentTimeMillis();
            String line;
            while (running && (line = reader.readLine()) != null) {
                writer.write(line);
                writer.write('\n');
                written += line.length() + 1;

                long now = System.currentTimeMillis();
                if (now - lastFlush >= FLUSH_INTERVAL_MS) {
                    writer.flush();
                    lastFlush = now;
                }

                if (written >= MAX_BYTES) {
                    writer.flush();
                    writer.close();
                    rotateIfNeeded();
                    writer = new FileWriter(logFile(), true);
                    written = logFile().length();
                }
            }
        } catch (Exception e) {
            // Losing the tee must never take the launcher down with it.
            Log.w(TAG, "Log tee stopped: " + e);
        } finally {
            if (writer != null) {
                try {
                    writer.flush();
                    writer.close();
                } catch (Exception ignored) {
                    // Nothing useful left to do about a failing close.
                }
            }
        }
    }

    private static void rotateIfNeeded() {
        File current = logFile();
        if (!current.isFile() || current.length() < MAX_BYTES) {
            return;
        }
        File previous = new File(LOG_DIR, PREV_NAME);
        if (previous.isFile() && !previous.delete()) {
            Log.w(TAG, "Could not delete " + previous);
        }
        if (!current.renameTo(previous)) {
            Log.w(TAG, "Could not rotate " + current + " - truncating instead");
            if (!current.delete()) {
                Log.w(TAG, "Could not delete " + current + " either");
            }
        }
    }
}
