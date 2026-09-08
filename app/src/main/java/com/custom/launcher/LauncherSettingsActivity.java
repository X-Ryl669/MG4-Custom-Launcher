package com.custom.launcher;

import java.util.ArrayList;
import java.util.List;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.custom.launcher.location.LocationRelayService;
import com.custom.launcher.location.LocationTools;
import com.custom.launcher.media.BluetoothArtCache;
import com.custom.launcher.util.LauncherPrefs;
import com.custom.launcher.util.LogTee;

/**
 * The launcher's own settings, reached from the hamburger button on the tile bar.
 *
 * <p>
 * This replaces the Climate button that used to sit in the quick-action rail,
 * which was redundant once the climate tile gained its own controls. Everything
 * here is either a launcher preference or a diagnostic that cannot be run any
 * other way on this car: there is no adb, and the sideloaded terminal runs as an
 * unprivileged app, so reading or writing {@code secure} settings has to happen
 * from inside a process with system uid — this one.
 */
public class LauncherSettingsActivity extends AppCompatActivity {
    private static final String TAG = "LauncherSettings";

    private static class Entry {
        final String title;
        final String subtitle;
        final Runnable action;

        Entry(String title, String subtitle, Runnable action) {
            this.title = title;
            this.subtitle = subtitle;
            this.action = action;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private EntryAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_media_browse);

        TextView breadcrumb = findViewById(R.id.browseBreadcrumb);
        breadcrumb.setText("Launcher settings");
        findViewById(R.id.browseBackButton).setOnClickListener(v -> finish());
        findViewById(R.id.browseEmpty).setVisibility(View.GONE);

        buildEntries();

        ListView list = findViewById(R.id.browseList);
        adapter = new EntryAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> entries.get(position).action.run());
    }

    private void buildEntries() {
        entries.clear();

        entries.add(new Entry("Location & GPS",
                "location_mode, providers, last fix",
                this::showLocation));

        entries.add(new Entry(
                LocationRelayService.isRunning()
                        ? "Stop the network location relay"
                        : "Start the network location relay",
                "Feeds GPS fixes to apps asking for \"network\" or \"fused\"",
                this::toggleLocationRelay));

        entries.add(new Entry("BMS raw values",
                "Live energy properties, for calibration",
                () -> startActivity(new Intent(this, BmsDebugActivity.class))));

        entries.add(new Entry("Debug log",
                "App log, saved to Download/" + LogTee.logFile().getName(),
                () -> startActivity(new Intent(this, LogViewerActivity.class))));

        entries.add(new Entry("Navigation app",
                navAppSubtitle(),
                this::pickNavApp));

        entries.add(new Entry(
                LauncherPrefs.isHeatingHidden(this)
                        ? "Show seat & wheel heating"
                        : "Hide seat & wheel heating",
                "Override, for a car that reports 0 instead of \"not fitted\"",
                this::toggleHeatingVisibility));

        entries.add(new Entry("Clear Bluetooth cover cache",
                "Fixes covers left over from a previous phone session",
                this::clearArtCache));

        entries.add(new Entry("Vehicle settings",
                "Opens the car's own settings app",
                this::openVehicleSettings));
    }

    // --- actions ---

    private void showLocation() {
        String report = LocationTools.describe(this);
        Log.i(TAG, "Location state:\n" + report);

        new AlertDialog.Builder(this)
                .setTitle("Location & GPS")
                .setMessage(report)
                .setPositiveButton("Close", null)
                .setNeutralButton("Change mode…", (d, which) -> showLocationModePicker())
                .show();
    }

    /**
     * Lets the user force a location mode. The point is the ABRP experiment: if
     * forcing sensors-only (which removes the "network" provider from the picture)
     * makes ABRP track in driving mode, then ABRP was watching a provider this car
     * never feeds. See {@link LocationTools}.
     */
    private void showLocationModePicker() {
        final int[] modes = {
                LocationTools.MODE_OFF,
                LocationTools.MODE_SENSORS_ONLY,
                LocationTools.MODE_BATTERY_SAVING,
                LocationTools.MODE_HIGH_ACCURACY
        };
        String[] labels = new String[modes.length];
        for (int i = 0; i < modes.length; i++) {
            labels[i] = modes[i] + "  " + LocationTools.nameForMode(modes[i]);
        }

        new AlertDialog.Builder(this)
                .setTitle("Set location_mode")
                .setItems(labels, (d, which) -> {
                    int applied = LocationTools.setLocationMode(this, modes[which]);
                    String message = applied == modes[which]
                            ? "location_mode is now " + applied
                            : "Write did not stick - still " + applied;
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Turns the mock-provider relay on or off.
     *
     * <p>
     * Deliberately manual. It publishes mock location providers, which is a
     * system-wide change that would be rude to leave running by default, and its
     * whole purpose is to be an A/B test: run ABRP's driving mode with it off,
     * then with it on, and see whether the position starts tracking.
     */
    private void toggleLocationRelay() {
        boolean wasRunning = LocationRelayService.isRunning();
        if (wasRunning) {
            LocationRelayService.stop(this);
        } else {
            LocationRelayService.start(this);
        }
        Log.i(TAG, "Location relay " + (wasRunning ? "stopped" : "started"));
        Toast.makeText(this,
                wasRunning ? "Relay stopped" : "Relay started - check Debug log for fixes",
                Toast.LENGTH_LONG).show();
        // The row's own label is its state readout.
        buildEntries();
        adapter.notifyDataSetChanged();
    }

    private String navAppSubtitle() {
        String pkg = LauncherPrefs.getNavPackage(this);
        if (pkg == null) {
            return "Not set - the GPS tile has nothing to open";
        }
        try {
            return getPackageManager()
                    .getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0))
                    .toString();
        } catch (Exception e) {
            return pkg + " (no longer installed)";
        }
    }

    /**
     * Reuses MainActivity's picker so there is one list and one code path, rather
     * than a second copy that drifts.
     */
    private void pickNavApp() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction(MainActivity.ACTION_PICK_NAV_APP);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    /**
     * The manual escape hatch for the heating controls.
     *
     * <p>
     * They hide themselves when {@code AirConditionBean} reports null for a level,
     * which is how the car says it has no such hardware. If a car answers 0
     * instead, that is indistinguishable from "fitted and switched off" and the
     * automatic path cannot help — hence a switch the owner can throw, since they
     * know what their car has.
     */
    private void toggleHeatingVisibility() {
        boolean nowHidden = !LauncherPrefs.isHeatingHidden(this);
        LauncherPrefs.setHeatingHidden(this, nowHidden);
        Log.i(TAG, "Seat/wheel heating controls forced " + (nowHidden ? "hidden" : "visible"));
        Toast.makeText(this,
                nowHidden ? "Heating controls hidden" : "Heating controls shown where fitted",
                Toast.LENGTH_LONG).show();
        buildEntries();
        adapter.notifyDataSetChanged();
    }

    private void clearArtCache() {
        int removed = BluetoothArtCache.clear(null);
        Toast.makeText(this, "Removed " + removed + " cached cover file(s)",
                Toast.LENGTH_LONG).show();
    }

    /**
     * Opens the car's own vehicle-settings app.
     *
     * <p>
     * Trophy/Luxury has a dedicated charge-management activity worth going
     * straight to; the SE {@code vehiclesetting} app has no such standalone
     * screen, so there we can only launch its main entry point.
     */
    private void openVehicleSettings() {
        Intent intent = null;
        if (SaicPackages.isInstalled(this, SaicPackages.LUX_VEHICLE_SETTINGS)) {
            intent = new Intent();
            intent.setComponent(new android.content.ComponentName(
                    SaicPackages.LUX_VEHICLE_SETTINGS,
                    SaicPackages.LUX_CHARGE_MANAGEMENT_ACTIVITY));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        if (intent == null) {
            intent = SaicPackages.buildLaunchIntent(this, SaicPackages.VEHICLE_SETTINGS);
        }
        if (intent == null) {
            Toast.makeText(this, "Vehicle settings app not found", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "Could not open vehicle settings: " + e.getMessage());
            Toast.makeText(this, "Could not open vehicle settings", Toast.LENGTH_SHORT).show();
        }
    }

    private class EntryAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public Object getItem(int position) {
            return entries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = LayoutInflater.from(LauncherSettingsActivity.this)
                        .inflate(R.layout.item_media, parent, false);
            }
            Entry entry = entries.get(position);
            ((TextView) view.findViewById(R.id.mediaTitle)).setText(entry.title);
            TextView subtitle = view.findViewById(R.id.mediaSubtitle);
            subtitle.setVisibility(View.VISIBLE);
            subtitle.setText(entry.subtitle);
            ((ImageView) view.findViewById(R.id.mediaChevron)).setVisibility(View.VISIBLE);
            return view;
        }
    }
}
