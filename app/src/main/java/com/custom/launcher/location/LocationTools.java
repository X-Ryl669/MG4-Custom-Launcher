package com.custom.launcher.location;

import java.util.ArrayList;
import java.util.List;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.provider.Settings;
import android.util.Log;

/**
 * Reads and writes the system location settings from inside the launcher.
 *
 * <p>
 * This exists because the head unit has no adb and its sideloaded terminal runs
 * as an ordinary app, so {@code settings get secure location_mode} comes back
 * "permission denied". The launcher declares {@code android.uid.system} and holds
 * {@code WRITE_SECURE_SETTINGS}, so it can both read the value and change it —
 * which is what makes the ABRP experiment possible at all.
 *
 * <p>
 * The experiment: ABRP's location layer picks <em>one</em> provider by
 * {@code providerOrder} and never falls back, so on BALANCED accuracy it can end
 * up watching {@code network} — a provider this car has no backend for — and
 * therefore never receives an update, while GPSLogger (which asks for
 * {@code gps} explicitly) keeps working. Forcing the system to sensors-only
 * ({@link #MODE_SENSORS_ONLY}) removes {@code network} from the picture. If ABRP
 * then tracks in driving mode, that diagnosis is confirmed.
 */
public final class LocationTools {
    private static final String TAG = "LocationTools";

    /** {@code Settings.Secure.LOCATION_MODE} values. */
    public static final int MODE_OFF = 0;
    public static final int MODE_SENSORS_ONLY = 1;
    public static final int MODE_BATTERY_SAVING = 2;
    public static final int MODE_HIGH_ACCURACY = 3;

    /** Marks a provider the framework does not report at all. */
    private static final String MISSING_SUFFIX = "  NOT PRESENT";

    private static final String GMS_PACKAGE = "com.google.android.gms";
    private static final String PLAY_STORE_PACKAGE = "com.android.vending";

    private LocationTools() {
    }

    public static String nameForMode(int mode) {
        switch (mode) {
            case MODE_OFF:
                return "OFF";
            case MODE_SENSORS_ONLY:
                return "SENSORS_ONLY (gps only)";
            case MODE_BATTERY_SAVING:
                return "BATTERY_SAVING (network only)";
            case MODE_HIGH_ACCURACY:
                return "HIGH_ACCURACY (gps + network)";
            default:
                return "unknown (" + mode + ")";
        }
    }

    /** Current {@code location_mode}, or -1 if it cannot be read. */
    public static int getLocationMode(Context context) {
        try {
            return Settings.Secure.getInt(context.getContentResolver(),
                    Settings.Secure.LOCATION_MODE);
        } catch (Exception e) {
            Log.w(TAG, "Cannot read location_mode: " + e);
            return -1;
        }
    }

    /**
     * Writes {@code location_mode} and returns the value that is actually in
     * place afterwards, so the caller can show whether the write took. Android 9
     * routes this through LocationManagerService's settings observer rather than
     * applying it directly, and it can silently refuse, so the read-back matters.
     */
    public static int setLocationMode(Context context, int mode) {
        ContentResolver cr = context.getContentResolver();
        try {
            Settings.Secure.putInt(cr, Settings.Secure.LOCATION_MODE, mode);
            Log.i(TAG, "Wrote location_mode=" + mode + " (" + nameForMode(mode) + ")");
        } catch (Exception e) {
            Log.w(TAG, "Failed to write location_mode: " + e);
        }
        int readBack = getLocationMode(context);
        if (readBack != mode) {
            Log.w(TAG, "location_mode did not stick: asked " + mode + ", got " + readBack);
        }
        return readBack;
    }

    /**
     * Human-readable dump of everything relevant to the GPS question, for the
     * launcher settings screen. Kept as one string because the only way to get
     * this off the car is a person reading it off the display.
     */
    public static String describe(Context context) {
        StringBuilder sb = new StringBuilder();

        int mode = getLocationMode(context);
        sb.append("location_mode = ")
                .append(mode < 0 ? "unreadable" : mode + "  " + nameForMode(mode))
                .append('\n');

        String allowed = null;
        try {
            allowed = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.LOCATION_PROVIDERS_ALLOWED);
        } catch (Exception e) {
            Log.w(TAG, "Cannot read location_providers_allowed: " + e);
        }
        sb.append("providers_allowed = ")
                .append(allowed == null || allowed.isEmpty() ? "(empty)" : allowed)
                .append("\n\n");

        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            sb.append("No LocationManager!\n");
            return sb.toString();
        }

        sb.append("Providers:\n");
        for (String provider : allProviders(lm)) {
            if (provider.endsWith(MISSING_SUFFIX)) {
                sb.append("  ").append(provider).append('\n');
                continue;
            }
            boolean enabled;
            try {
                enabled = lm.isProviderEnabled(provider);
            } catch (Exception e) {
                enabled = false;
            }
            sb.append("  ").append(provider)
                    .append(enabled ? "  ENABLED" : "  disabled");

            // The whole ABRP question is whether a provider that looks enabled
            // ever actually delivers, so show its last fix age too.
            try {
                Location last = lm.getLastKnownLocation(provider);
                if (last == null) {
                    sb.append("   no fix");
                } else {
                    long ageMs = System.currentTimeMillis() - last.getTime();
                    sb.append("   fix ").append(ageMs / 1000).append("s ago");
                }
            } catch (SecurityException e) {
                sb.append("   (no permission)");
            } catch (Exception e) {
                sb.append("   (error)");
            }
            sb.append('\n');
        }

        sb.append('\n');
        sb.append("Google Play services: ")
                .append(isInstalled(context, GMS_PACKAGE) ? "present" : "ABSENT").append('\n');
        sb.append("Play Store: ")
                .append(isInstalled(context, PLAY_STORE_PACKAGE) ? "present" : "ABSENT").append('\n');

        return sb.toString();
    }

    private static List<String> allProviders(LocationManager lm) {
        List<String> providers = new ArrayList<>();
        try {
            List<String> reported = lm.getAllProviders();
            if (reported != null) {
                providers.addAll(reported);
            }
        } catch (Exception e) {
            Log.w(TAG, "getAllProviders() failed: " + e);
        }
        // Make absence visible rather than invisible: if "network" is not in the
        // list at all, that is itself the answer to the ABRP question.
        for (String expected : new String[] {
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER }) {
            if (!providers.contains(expected)) {
                providers.add(expected + MISSING_SUFFIX);
            }
        }
        return providers;
    }

    private static boolean isInstalled(Context context, String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
