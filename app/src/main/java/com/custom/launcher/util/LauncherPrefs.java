package com.custom.launcher.util;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The launcher's own preferences.
 *
 * <p>
 * Small enough to be one class rather than a settings framework. Everything here
 * exists because this trim differs from the Trophy the launcher was written for,
 * so the answers cannot be baked in.
 */
public final class LauncherPrefs {
    private static final String PREFS = "launcher_prefs";

    private static final String KEY_NAV_PACKAGE = "nav_package";
    private static final String KEY_HEATING_HIDDEN = "heating_hidden";
    private static final String KEY_BROWSE_SORTED = "browse_sorted";
    private static final String KEY_LOGGING = "logging_enabled";

    private LauncherPrefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // --- navigation app ---

    /**
     * Package the GPS tile opens when tapped, or null if the user has not picked
     * one. There is no sensible default: this car ships no maps app at all, so
     * whatever is here got sideloaded and only the owner knows which one they
     * want.
     */
    public static String getNavPackage(Context context) {
        return prefs(context).getString(KEY_NAV_PACKAGE, null);
    }

    public static void setNavPackage(Context context, String packageName) {
        prefs(context).edit().putString(KEY_NAV_PACKAGE, packageName).apply();
    }

    // --- media browsing ---

    /**
     * Whether the browse list is sorted A-Z rather than shown in the order the
     * source sent it.
     *
     * <p>
     * Off by default, and deliberately a choice rather than a default: a phone's
     * "All tracks" folder arrives in no order at all and is unusable without
     * sorting, while an album arrives in track order that sorting would destroy.
     * Only the person looking at the list knows which of those they have.
     */
    public static boolean isBrowseSorted(Context context) {
        return prefs(context).getBoolean(KEY_BROWSE_SORTED, false);
    }

    public static void setBrowseSorted(Context context, boolean sorted) {
        prefs(context).edit().putBoolean(KEY_BROWSE_SORTED, sorted).apply();
    }

    // --- diagnostics ---

    /**
     * Whether to capture this app's log to a file on the car's flash.
     *
     * <p>
     * Off by default, and that default is the whole point. The capture is the only
     * diagnostic channel there is - the head unit has no adb, and the in-app viewer
     * can only show the tail of a logcat buffer that the car's own services flood
     * hundreds of lines a second - so it has to exist. But it is a file on the
     * car's internal storage that grows whenever the launcher is running, and
     * nobody should be paying that cost around the clock to answer a question they
     * are not currently asking.
     *
     * <p>
     * So: turn it on when about to reproduce something, drive, read it, turn it
     * off. See {@link LogTee}.
     */
    public static boolean isLoggingEnabled(Context context) {
        return prefs(context).getBoolean(KEY_LOGGING, false);
    }

    public static void setLoggingEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_LOGGING, enabled).apply();
    }

    // --- seat and wheel heating ---

    /**
     * Whether to hide the seat and wheel heating controls outright.
     *
     * <p>
     * Normally unnecessary: the controls hide themselves when
     * {@code AirConditionBean} reports null for a level, which is how the car says
     * it has no such hardware. This is the manual override for the case where the
     * car answers 0 instead of null — indistinguishable from "fitted but off" —
     * which would leave dead controls on screen with no other way to be rid of
     * them.
     */
    public static boolean isHeatingHidden(Context context) {
        return prefs(context).getBoolean(KEY_HEATING_HIDDEN, false);
    }

    public static void setHeatingHidden(Context context, boolean hidden) {
        prefs(context).edit().putBoolean(KEY_HEATING_HIDDEN, hidden).apply();
    }
}
