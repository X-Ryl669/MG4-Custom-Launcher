package com.custom.launcher.location;

import java.lang.reflect.Method;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

/**
 * Republishes this car's real GPS fixes under provider names the head unit does
 * not have, so navigation apps asking for coarse or fused location get something.
 *
 * <h3>What the car actually looks like</h3>
 * Read off the car itself, not assumed:
 *
 * <pre>
 *   location_mode      = 1  SENSORS_ONLY (gps only)
 *   providers_allowed  = gps
 *   passive  ENABLED   no fix
 *   gps      ENABLED   no fix
 *   network  NOT PRESENT
 *   Google Play services: ABSENT
 * </pre>
 *
 * Setting {@code location_mode} to 3 added {@code network} to
 * {@code providers_allowed} and the write stuck — and changed nothing, because
 * the setting only says a provider is <em>permitted</em>. There is no network
 * provider implementation on the image to permit.
 *
 * <h3>The two hypotheses, and which one is dead</h3>
 * <ul>
 * <li><b>"The GPS engine needs a fast client to stay awake."</b> Dead. GPSLogger
 * polling at 1 s did not make ABRP's driving mode update. So a second listener of
 * our own is not the fix either — it is here only as an instrument, to log whether
 * fixes really do arrive, because the launcher's own
 * {@code getLastKnownLocation} returned nothing while GPSLogger was happily
 * recording.</li>
 * <li><b>"ABRP asks for a provider that does not exist."</b> Still standing, and
 * this service is the test. Its one-shot path works and its driving-mode path does
 * not, which is the signature of an app that uses a plain {@code LocationManager}
 * read for the first and a coarse, fused or criteria-selected subscription for the
 * second. If that subscription resolves to {@code network}, today it resolves to
 * nothing at all.</li>
 * </ul>
 *
 * <h3>The honest caveat</h3>
 * If ABRP's driving mode goes through {@code FusedLocationProviderClient}, this
 * cannot help: that is Play services code, Play services is absent, and no test
 * provider substitutes for a missing library. The relay covers the
 * {@code LocationManager} case only. Which case it is takes one on-car test to
 * find out, and that is the point of building it.
 *
 * <h3>Mock provider permissions</h3>
 * {@code addTestProvider} is gated on the {@code OP_MOCK_LOCATION} app op, which
 * normally requires selecting the app under developer options — awkward here,
 * since there is no adb and the stock settings app is not the AOSP one. Being
 * platform-signed we can hold {@code MANAGE_APP_OPS_MODES}, so the service asks
 * for the op for itself through {@code AppOpsManager.setMode}, which is
 * {@code @hide} and so reached by reflection. If that is refused it is logged and
 * the relay still runs its listener, so the diagnostic half keeps working.
 */
public class LocationRelayService extends Service {
    private static final String TAG = "LocationRelay";

    /**
     * Providers to publish. {@code network} is the one apps actually ask for by
     * name; {@code fused} is included because criteria-based selection can land
     * there and it costs nothing to feed both.
     */
    private static final String[] RELAY_PROVIDERS = {
            LocationManager.NETWORK_PROVIDER, "fused",
    };

    private static final long MIN_INTERVAL_MS = 1000L;
    private static final float MIN_DISTANCE_M = 0f;

    /** {@code AppOpsManager.OP_MOCK_LOCATION} on API 28. */
    private static final int OP_MOCK_LOCATION = 58;
    private static final int MODE_ALLOWED = 0;

    private static volatile boolean running;

    private LocationManager locationManager;
    private final boolean[] providerReady = new boolean[RELAY_PROVIDERS.length];
    private int fixCount;

    public static boolean isRunning() {
        return running;
    }

    public static void start(Context context) {
        context.startService(new Intent(context, LocationRelayService.class));
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, LocationRelayService.class));
    }

    private final LocationListener gpsListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            fixCount++;
            // Every fix, not a sample: the open question is whether fixes arrive
            // at all, and at 1 Hz into a tag-filtered log file this is affordable.
            Log.i(TAG, "GPS fix #" + fixCount + ": " + location.getLatitude() + ", "
                    + location.getLongitude() + " acc=" + location.getAccuracy()
                    + "m speed=" + location.getSpeed() + "m/s");
            republish(location);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
            Log.i(TAG, "GPS status changed to " + status);
        }

        @Override
        public void onProviderEnabled(String provider) {
            Log.i(TAG, "Provider enabled: " + provider);
        }

        @Override
        public void onProviderDisabled(String provider) {
            Log.w(TAG, "Provider disabled: " + provider);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            Log.e(TAG, "No LocationManager - nothing to relay");
            stopSelf();
            return;
        }

        requestMockLocationOp();
        installProviders();
        subscribeToGps();

        running = true;
        Log.i(TAG, "Relay started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Sticky so the relay survives the launcher being backgrounded while the
        // user is actually inside the app being tested.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(gpsListener);
            } catch (Exception e) {
                Log.w(TAG, "removeUpdates failed: " + e);
            }
            removeProviders();
        }
        Log.i(TAG, "Relay stopped after " + fixCount + " fix(es)");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // --- setup ---

    private void subscribeToGps() {
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                    MIN_INTERVAL_MS, MIN_DISTANCE_M, gpsListener);
            Log.i(TAG, "Subscribed to GPS at " + MIN_INTERVAL_MS + "ms");
        } catch (SecurityException e) {
            Log.e(TAG, "No permission to read GPS: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "requestLocationUpdates(gps) failed: " + e);
        }

        Location last = null;
        try {
            last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        } catch (Exception e) {
            Log.w(TAG, "getLastKnownLocation(gps) failed: " + e);
        }
        Log.i(TAG, "Last known GPS fix at start: " + (last == null ? "none" : last.toString()));
        if (last != null) {
            republish(last);
        }
    }

    private void installProviders() {
        for (int i = 0; i < RELAY_PROVIDERS.length; i++) {
            String provider = RELAY_PROVIDERS[i];
            try {
                // Clear first: a provider left behind by a previous run would make
                // addTestProvider throw.
                try {
                    locationManager.removeTestProvider(provider);
                } catch (Exception ignored) {
                    // Not previously installed, which is the normal case.
                }
                locationManager.addTestProvider(provider,
                        false,  // requiresNetwork
                        false,  // requiresSatellite
                        false,  // requiresCell
                        false,  // hasMonetaryCost
                        true,   // supportsAltitude
                        true,   // supportsSpeed
                        true,   // supportsBearing
                        Criteria.POWER_LOW,
                        Criteria.ACCURACY_FINE);
                locationManager.setTestProviderEnabled(provider, true);
                providerReady[i] = true;
                Log.i(TAG, "Published provider \"" + provider + "\"");
            } catch (SecurityException e) {
                Log.e(TAG, "Not allowed to publish \"" + provider
                        + "\" - OP_MOCK_LOCATION was not granted: " + e.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "addTestProvider(\"" + provider + "\") failed: " + e);
            }
        }
    }

    private void removeProviders() {
        for (int i = 0; i < RELAY_PROVIDERS.length; i++) {
            if (!providerReady[i]) {
                continue;
            }
            try {
                locationManager.setTestProviderEnabled(RELAY_PROVIDERS[i], false);
                locationManager.removeTestProvider(RELAY_PROVIDERS[i]);
                Log.i(TAG, "Withdrew provider \"" + RELAY_PROVIDERS[i] + "\"");
            } catch (Exception e) {
                Log.w(TAG, "removeTestProvider(\"" + RELAY_PROVIDERS[i] + "\") failed: " + e);
            }
            providerReady[i] = false;
        }
    }

    private void republish(Location gpsFix) {
        for (int i = 0; i < RELAY_PROVIDERS.length; i++) {
            if (!providerReady[i]) {
                continue;
            }
            String provider = RELAY_PROVIDERS[i];
            Location copy = new Location(provider);
            copy.setLatitude(gpsFix.getLatitude());
            copy.setLongitude(gpsFix.getLongitude());
            copy.setAccuracy(gpsFix.hasAccuracy() ? gpsFix.getAccuracy() : 10f);
            if (gpsFix.hasAltitude()) {
                copy.setAltitude(gpsFix.getAltitude());
            }
            if (gpsFix.hasSpeed()) {
                copy.setSpeed(gpsFix.getSpeed());
            }
            if (gpsFix.hasBearing()) {
                copy.setBearing(gpsFix.getBearing());
            }
            copy.setTime(System.currentTimeMillis());
            // Required: LocationManagerService silently discards a fix with no
            // elapsed-realtime stamp.
            copy.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());

            try {
                locationManager.setTestProviderLocation(provider, copy);
            } catch (Exception e) {
                Log.w(TAG, "setTestProviderLocation(\"" + provider + "\") failed: " + e);
                providerReady[i] = false;
            }
        }
    }

    /**
     * Asks for {@code OP_MOCK_LOCATION} for this app.
     *
     * <p>
     * {@code AppOpsManager.setMode} is {@code @hide}, so it is reached by
     * reflection rather than by vendoring a stub. It needs
     * {@code MANAGE_APP_OPS_MODES}, which is signature|privileged and therefore
     * available on a platform-signed build. On a build that is not platform
     * signed this simply fails and is logged.
     */
    private void requestMockLocationOp() {
        Object appOps = getSystemService(Context.APP_OPS_SERVICE);
        if (appOps == null) {
            Log.w(TAG, "No AppOpsManager; published providers will probably be refused");
            return;
        }
        try {
            Method setMode = appOps.getClass().getMethod("setMode",
                    int.class, int.class, String.class, int.class);
            setMode.invoke(appOps, OP_MOCK_LOCATION,
                    android.os.Process.myUid(), getPackageName(), MODE_ALLOWED);
            Log.i(TAG, "OP_MOCK_LOCATION allowed for " + getPackageName()
                    + " (uid " + android.os.Process.myUid() + ")");
        } catch (Exception e) {
            Log.w(TAG, "Could not set OP_MOCK_LOCATION (" + e
                    + "); falling back to whatever the system already allows");
        }
    }
}
