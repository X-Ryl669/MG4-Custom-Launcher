package com.custom.launcher;

import java.util.Locale;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.custom.launcher.util.LauncherPrefs;

/**
 * Drives the GPS tile: latitude, longitude, fix age, and a tap that opens the
 * user's chosen navigation app.
 *
 * <h3>Why the fix age is on screen</h3>
 * The car's GPS behaves oddly and the symptoms have been hard to pin down: ABRP
 * updates on its planning screen but freezes in driving mode, GPSLogger keeps
 * recording throughout, and the launcher's own {@code getLastKnownLocation}
 * returned nothing at all while GPSLogger was working. "The position is frozen"
 * and "no fix ever arrives" look identical if all you show is a coordinate, so
 * this shows how old the coordinate is. A counter that keeps resetting means fixes
 * are flowing; one that climbs means they stopped.
 *
 * <h3>Which providers</h3>
 * It subscribes to {@code gps} for real fixes and to {@code passive} to see fixes
 * other apps provoke without provoking any itself. If
 * {@link com.custom.launcher.location.LocationRelayService} is running, its mock
 * {@code network} provider will also show up through {@code passive} — useful for
 * confirming the relay is actually publishing.
 *
 * <p>
 * Subscriptions only exist while the launcher is in the foreground: this tile is
 * not a logger, and holding the GPS engine awake behind the user's back is not
 * something a home screen should do.
 */
public class GpsTileController implements LocationListener {
    private static final String TAG = "GpsTile";

    /** Fast enough to see whether updates flow, slow enough to be free. */
    private static final long MIN_INTERVAL_MS = 1000L;
    private static final float MIN_DISTANCE_M = 0f;
    /** Cadence for re-rendering the fix age when no new fix arrives. */
    private static final long TICK_MS = 1000L;

    private final MainActivity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final TextView positionText;
    private final TextView detailText;
    private final TextView navLabel;

    private LocationManager locationManager;
    private Location lastFix;
    private int fixCount;
    private boolean subscribed;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, TICK_MS);
        }
    };

    public GpsTileController(MainActivity activity) {
        this.activity = activity;
        this.positionText = activity.findViewById(R.id.gpsPosition);
        this.detailText = activity.findViewById(R.id.gpsDetail);
        this.navLabel = activity.findViewById(R.id.gpsNavLabel);

        View card = activity.findViewById(R.id.gpsCard);
        if (card != null) {
            card.setOnClickListener(v -> openNavigation());
            // Long-press is a shortcut to the picker, so changing the app does not
            // mean a trip through the menu.
            card.setOnLongClickListener(v -> {
                activity.pickNavigationApp();
                return true;
            });
        }
    }

    // --- lifecycle ---

    public void onStart() {
        if (locationManager == null) {
            locationManager = (LocationManager)
                    activity.getSystemService(MainActivity.LOCATION_SERVICE);
        }
        subscribe();
        seedFromLastKnown();
        handler.removeCallbacks(tick);
        handler.post(tick);
        renderNavLabel();
    }

    public void onStop() {
        handler.removeCallbacks(tick);
        unsubscribe();
    }

    public void onDestroy() {
        onStop();
        handler.removeCallbacksAndMessages(null);
    }

    /** Called after the user picks a navigation app, so the tile relabels itself. */
    public void onNavAppChanged() {
        renderNavLabel();
    }

    // --- location ---

    private void subscribe() {
        if (subscribed || locationManager == null) {
            return;
        }
        for (String provider : new String[] {
                LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER }) {
            try {
                locationManager.requestLocationUpdates(provider, MIN_INTERVAL_MS,
                        MIN_DISTANCE_M, this);
                Log.i(TAG, "Subscribed to " + provider);
            } catch (SecurityException e) {
                Log.e(TAG, "No permission for " + provider + ": " + e.getMessage());
            } catch (Exception e) {
                // An absent provider throws here, which is itself worth knowing.
                Log.w(TAG, "Could not subscribe to " + provider + ": " + e);
            }
        }
        subscribed = true;
    }

    private void unsubscribe() {
        if (!subscribed || locationManager == null) {
            return;
        }
        try {
            locationManager.removeUpdates(this);
        } catch (Exception e) {
            Log.w(TAG, "removeUpdates failed: " + e);
        }
        subscribed = false;
    }

    private void seedFromLastKnown() {
        if (locationManager == null || lastFix != null) {
            return;
        }
        for (String provider : new String[] {
                LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER }) {
            try {
                Location location = locationManager.getLastKnownLocation(provider);
                if (location != null) {
                    lastFix = location;
                    Log.i(TAG, "Seeded from last known " + provider + " fix");
                    return;
                }
            } catch (Exception e) {
                Log.w(TAG, "getLastKnownLocation(" + provider + ") failed: " + e);
            }
        }
        Log.i(TAG, "No last known fix from any provider");
    }

    @Override
    public void onLocationChanged(Location location) {
        fixCount++;
        lastFix = location;
        render();
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
        Log.i(TAG, "Provider enabled: " + provider);
    }

    @Override
    public void onProviderDisabled(String provider) {
        Log.w(TAG, "Provider disabled: " + provider);
    }

    // --- rendering ---

    private void render() {
        if (positionText == null || detailText == null) {
            return;
        }
        if (lastFix == null) {
            positionText.setText("No fix");
            detailText.setText(subscribed ? "Listening on gps and passive" : "GPS unavailable");
            return;
        }

        positionText.setText(String.format(Locale.US, "%.5f, %.5f",
                lastFix.getLatitude(), lastFix.getLongitude()));

        StringBuilder detail = new StringBuilder();
        detail.append(lastFix.getProvider() == null ? "?" : lastFix.getProvider());
        if (lastFix.hasAccuracy()) {
            detail.append(String.format(Locale.US, "  ·  ±%.0f m", lastFix.getAccuracy()));
        }
        detail.append("  ·  ").append(ageLabel(lastFix.getTime()));
        // The count is the answer to "are updates arriving at all".
        detail.append("  ·  ").append(fixCount).append(fixCount == 1 ? " update" : " updates");
        detailText.setText(detail);
    }

    private static String ageLabel(long fixTimeMs) {
        long ageMs = System.currentTimeMillis() - fixTimeMs;
        if (ageMs < 0) {
            return "just now";
        }
        long seconds = ageMs / 1000L;
        if (seconds < 60) {
            return seconds + "s ago";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "m ago";
        }
        return (seconds / 3600) + "h ago";
    }

    private void renderNavLabel() {
        if (navLabel == null) {
            return;
        }
        String pkg = LauncherPrefs.getNavPackage(activity);
        if (pkg == null) {
            navLabel.setText("Set a nav app");
            return;
        }
        navLabel.setText(labelFor(pkg));
    }

    private String labelFor(String packageName) {
        PackageManager pm = activity.getPackageManager();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString();
        } catch (Exception e) {
            // Uninstalled since it was picked.
            return packageName;
        }
    }

    // --- action ---

    private void openNavigation() {
        String pkg = LauncherPrefs.getNavPackage(activity);
        if (pkg == null) {
            // Nothing configured, so the tap means "let me configure it" rather
            // than being a dead end.
            activity.pickNavigationApp();
            return;
        }
        Intent intent = activity.getPackageManager().getLaunchIntentForPackage(pkg);
        if (intent == null) {
            Log.w(TAG, "No launch intent for " + pkg + "; it may have been uninstalled");
            Toast.makeText(activity, labelFor(pkg) + " is not installed any more",
                    Toast.LENGTH_LONG).show();
            activity.pickNavigationApp();
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open " + pkg + ": " + e);
            Toast.makeText(activity, "Could not open " + labelFor(pkg),
                    Toast.LENGTH_SHORT).show();
        }
    }
}
