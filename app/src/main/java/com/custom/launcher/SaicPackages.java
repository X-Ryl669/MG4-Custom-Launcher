package com.custom.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.text.TextUtils;
import android.util.Log;

/**
 * SAIC system app package names, which differ between MG4 trim levels.
 *
 * <p>
 * The head unit software comes in (at least) two flavours with completely
 * different package structures for the built-in apps:
 *
 * <ul>
 * <li><b>Standard / SE</b> (EH32) uses flat {@code com.saicmotor.<app>} names.</li>
 * <li><b>Luxury / Trophy</b> uses {@code com.saicmotor.hmi.<app>} names.</li>
 * </ul>
 *
 * <p>
 * This launcher was originally written against a Trophy unit, so every target
 * was hardcoded to the {@code hmi} names and nothing resolved on an SE. Rather
 * than swap one hardcoded trim for the other, each function below lists its
 * candidate packages SE-first and resolves the first one actually installed.
 * The same APK therefore works on both trims, and the log line says which
 * flavour was detected.
 *
 * <p>
 * Mapping confirmed against the Standard launcher APK
 * ({@code com.saicmotor.launcher}, EH32 v105) and this XDA post:
 * https://xdaforums.com/t/mg4-electric-aaos-9-playing-and-possibly-other-mg-models.4697712/post-90524538
 *
 * <table>
 * <tr><th>Function</th><th>Standard / SE</th><th>Luxury / Trophy</th></tr>
 * <tr><td>Launcher</td><td>com.saicmotor.launcher</td><td>com.saicmotor.hmi.launcher</td></tr>
 * <tr><td>HVAC</td><td>com.saicmotor.hvac</td><td>com.saicmotor.hmi.hvac</td></tr>
 * <tr><td>Vehicle settings</td><td>com.saicmotor.vehiclesetting</td><td>com.saicmotor.hmi.vehiclesettings</td></tr>
 * <tr><td>Phone</td><td>com.saicmotor.btphone</td><td>com.saicmotor.hmi.btcall</td></tr>
 * <tr><td>Radio</td><td>com.saicmotor.radio</td><td>com.saicmotor.hmi.radio</td></tr>
 * <tr><td>Media</td><td>com.saicmotor.media</td><td>com.saicmotor.hmi.music</td></tr>
 * <tr><td>Settings</td><td>com.saicmotor.settings</td><td>com.saicmotor.hmi.systemsettings</td></tr>
 * <tr><td>360 / AVM</td><td>com.saicmotor.avm</td><td>com.saicmotor.hmi.aroundview</td></tr>
 * </table>
 */
public final class SaicPackages {
    private static final String TAG = "SaicPackages";

    // Standard / SE (EH32)
    public static final String SE_LAUNCHER = "com.saicmotor.launcher";
    public static final String SE_HVAC = "com.saicmotor.hvac";
    public static final String SE_VEHICLE_SETTINGS = "com.saicmotor.vehiclesetting";
    public static final String SE_PHONE = "com.saicmotor.btphone";
    public static final String SE_RADIO = "com.saicmotor.radio";
    public static final String SE_MEDIA = "com.saicmotor.media";
    public static final String SE_SETTINGS = "com.saicmotor.settings";
    public static final String SE_AVM = "com.saicmotor.avm";

    /**
     * SE battery / EV data source. Not wired up yet: {@code BaseManager} still
     * binds the Trophy {@code com.saicmotor.service.vehicle.VehicleService},
     * which is why the reported battery value is wrong on an SE. On Standard
     * the launcher instead binds {@code EvsService} below and talks to it via
     * {@code com.saicmotor.ev.IBatteryService} /
     * {@code com.saicmotor.ev.IBatteryCallbacks}.
     */
    public static final String SE_EVS = "com.saicmotor.evs";
    public static final String SE_EVS_SERVICE = "com.saicmotor.evs.service.EvsService";
    public static final String SE_BATTERY_INTERFACE = "com.saicmotor.ev.IBatteryService";

    // Luxury / Trophy
    public static final String LUX_LAUNCHER = "com.saicmotor.hmi.launcher";
    public static final String LUX_HVAC = "com.saicmotor.hmi.hvac";
    public static final String LUX_VEHICLE_SETTINGS = "com.saicmotor.hmi.vehiclesettings";
    public static final String LUX_PHONE = "com.saicmotor.hmi.btcall";
    public static final String LUX_RADIO = "com.saicmotor.hmi.radio";
    public static final String LUX_MEDIA = "com.saicmotor.hmi.music";
    public static final String LUX_SETTINGS = "com.saicmotor.hmi.systemsettings";
    public static final String LUX_AVM = "com.saicmotor.hmi.aroundview";

    /** Candidate packages per function, SE first, Luxury as fallback. */
    public static final String[] LAUNCHER = { SE_LAUNCHER, LUX_LAUNCHER };
    public static final String[] HVAC = { SE_HVAC, LUX_HVAC };
    public static final String[] VEHICLE_SETTINGS = { SE_VEHICLE_SETTINGS, LUX_VEHICLE_SETTINGS };
    public static final String[] SETTINGS = { SE_SETTINGS, LUX_SETTINGS };
    public static final String[] RADIO = { SE_RADIO, LUX_RADIO };
    public static final String[] MEDIA = { SE_MEDIA, LUX_MEDIA };

    /**
     * Explicit activity for the Trophy charge management screen. The SE
     * vehiclesetting app has no equivalent standalone activity (on Standard the
     * charge UI lives inside the launcher itself, fed by
     * {@code com.saicmotor.evs.service.EvsService}), so on SE we fall back to
     * the vehiclesetting app's own entry point.
     */
    public static final String LUX_CHARGE_MANAGEMENT_ACTIVITY =
            "com.saicmotor.hmi.vehiclesettings.chargemanagement.ui.ChargeManagementActivity";

    private SaicPackages() {
    }

    /** True if the package is installed on this head unit. */
    public static boolean isInstalled(Context context, String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Returns the first installed package from {@code candidates}, or null if
     * none are present.
     */
    public static String resolvePackage(Context context, String... candidates) {
        for (String candidate : candidates) {
            if (isInstalled(context, candidate)) {
                Log.i(TAG, "Resolved SAIC package: " + candidate);
                return candidate;
            }
        }
        Log.w(TAG, "None of these SAIC packages are installed: " + TextUtils.join(", ", candidates));
        return null;
    }

    /**
     * Builds a launch intent for the first installed package from
     * {@code candidates}, using the package's own declared launcher activity so
     * we don't have to hardcode activity class names per trim. Returns null if
     * nothing resolves.
     */
    public static Intent buildLaunchIntent(Context context, String... candidates) {
        String pkg = resolvePackage(context, candidates);
        if (pkg == null) {
            return null;
        }

        Intent intent = context.getPackageManager().getLaunchIntentForPackage(pkg);
        if (intent == null) {
            Log.w(TAG, pkg + " is installed but exposes no launcher activity");
            return null;
        }

        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }
}
