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
