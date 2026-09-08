package com.custom.launcher.energy;

import java.util.ArrayList;
import java.util.List;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * A bounded time series of consumption samples, persisted so the graph survives
 * a launcher restart.
 *
 * <p>
 * The stock energy app keeps its own history in a greenDAO table
 * ({@code PowerConsumptionInfo} in {@code com.saicmotor.saicev}), which we could
 * read as system UID. Sampling ourselves instead keeps us off their schema and
 * lets us pick the resolution; their table stays available if you ever want to
 * backfill history from before this launcher was installed.
 *
 * <p>
 * Storage is a single CSV-ish string in SharedPreferences. At one sample per
 * 30s over a 4h window that is a few hundred entries, which is far too small to
 * justify a database.
 */
public class ConsumptionHistory {
    private static final String TAG = "ConsumptionHistory";

    private static final String PREFS = "consumption_history";
    private static final String KEY_SAMPLES = "samples";

    /** Roughly four hours at one sample per 30 seconds. */
    private static final int MAX_SAMPLES = 480;

    /** Samples older than this are dropped on load, so the graph stays relevant. */
    private static final long MAX_AGE_MS = 24L * 60 * 60 * 1000;

    public static class Sample {
        public final long timestamp;
        /** Consumption in kWh/100km. */
        public final float value;

        public Sample(long timestamp, float value) {
            this.timestamp = timestamp;
            this.value = value;
        }
    }

    private final SharedPreferences prefs;
    private final List<Sample> samples = new ArrayList<>();

    public ConsumptionHistory(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    public synchronized List<Sample> getSamples() {
        return new ArrayList<>(samples);
    }

    public synchronized boolean isEmpty() {
        return samples.isEmpty();
    }

    /** Adds a sample and persists. Ignores NaN and negative values. */
    public synchronized void add(long timestamp, float value) {
        if (Float.isNaN(value) || Float.isInfinite(value) || value < 0f) {
            return;
        }
        samples.add(new Sample(timestamp, value));
        while (samples.size() > MAX_SAMPLES) {
            samples.remove(0);
        }
        save();
    }

    public synchronized void clear() {
        samples.clear();
        prefs.edit().remove(KEY_SAMPLES).apply();
    }

    private void load() {
        String raw = prefs.getString(KEY_SAMPLES, "");
        if (raw == null || raw.isEmpty()) {
            return;
        }
        long cutoff = System.currentTimeMillis() - MAX_AGE_MS;
        int skipped = 0;
        for (String entry : raw.split(";")) {
            if (entry.isEmpty()) {
                continue;
            }
            int comma = entry.indexOf(',');
            if (comma <= 0) {
                skipped++;
                continue;
            }
            try {
                long ts = Long.parseLong(entry.substring(0, comma));
                float v = Float.parseFloat(entry.substring(comma + 1));
                if (ts >= cutoff) {
                    samples.add(new Sample(ts, v));
                }
            } catch (NumberFormatException e) {
                skipped++;
            }
        }
        Log.i(TAG, "Loaded " + samples.size() + " consumption samples"
                + (skipped > 0 ? " (" + skipped + " malformed entries skipped)" : ""));
    }

    private void save() {
        StringBuilder sb = new StringBuilder(samples.size() * 24);
        for (Sample s : samples) {
            sb.append(s.timestamp).append(',').append(s.value).append(';');
        }
        prefs.edit().putString(KEY_SAMPLES, sb.toString()).apply();
    }
}
