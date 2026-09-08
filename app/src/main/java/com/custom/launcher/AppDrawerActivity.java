package com.custom.launcher;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Full-screen list of everything installed, like a normal Android launcher.
 *
 * <p>
 * The stock SAIC launcher is pinned to the front of the list rather than left to
 * sort alphabetically, since getting back to it is the main reason to open this
 * screen. Everything else is sorted by label.
 *
 * <p>
 * No package-visibility declaration is needed: this head unit is API 28 and the
 * filtering introduced in API 30 does not apply. On a newer platform this would
 * additionally need an {@code <intent>} entry under {@code <queries>}.
 */
public class AppDrawerActivity extends AppCompatActivity {
    private static final String TAG = "AppDrawerActivity";

    private static class Entry {
        final String label;
        final Drawable icon;
        final Intent launchIntent;
        final boolean pinned;

        Entry(String label, Drawable icon, Intent launchIntent, boolean pinned) {
            this.label = label;
            this.icon = icon;
            this.launchIntent = launchIntent;
            this.pinned = pinned;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_drawer);

        findViewById(R.id.drawerBackButton).setOnClickListener(v -> finish());

        loadApps();

        GridView grid = findViewById(R.id.appGrid);
        grid.setAdapter(new AppAdapter());
        grid.setOnItemClickListener((parent, view, position, id) -> launch(entries.get(position)));
    }

    private void launch(Entry entry) {
        try {
            startActivity(entry.launchIntent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch " + entry.label + ": " + e.getMessage());
            Toast.makeText(this, "Could not open " + entry.label, Toast.LENGTH_SHORT).show();
        }
    }

    private void loadApps() {
        entries.clear();
        PackageManager pm = getPackageManager();

        Intent probe = new Intent(Intent.ACTION_MAIN);
        probe.addCategory(Intent.CATEGORY_LAUNCHER);

        String saicLauncher = SaicPackages.resolvePackage(this, SaicPackages.LAUNCHER);
        String self = getPackageName();

        List<Entry> others = new ArrayList<>();

        for (ResolveInfo info : pm.queryIntentActivities(probe, 0)) {
            if (info.activityInfo == null) {
                continue;
            }
            String pkg = info.activityInfo.packageName;

            // Listing ourselves is just a way to get stuck in a loop.
            if (self.equals(pkg)) {
                continue;
            }

            Intent launch = new Intent(Intent.ACTION_MAIN);
            launch.addCategory(Intent.CATEGORY_LAUNCHER);
            launch.setClassName(pkg, info.activityInfo.name);
            launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

            String label = resolveLabel(pm, info);
            boolean pinned = pkg.equals(saicLauncher);
            Entry entry = new Entry(label, info.loadIcon(pm), launch, pinned);

            if (pinned) {
                entries.add(entry);
            } else {
                others.add(entry);
            }
        }

        Collator collator = Collator.getInstance(Locale.getDefault());
        Collections.sort(others, (a, b) -> collator.compare(a.label, b.label));
        entries.addAll(others);

        Log.i(TAG, "Found " + entries.size() + " launchable apps"
                + (saicLauncher != null ? " (SAIC launcher pinned first)" : ""));
    }

    private String resolveLabel(PackageManager pm, ResolveInfo info) {
        CharSequence label = info.loadLabel(pm);
        if (label != null && label.length() > 0) {
            return label.toString();
        }
        ApplicationInfo app = info.activityInfo.applicationInfo;
        if (app != null) {
            CharSequence appLabel = pm.getApplicationLabel(app);
            if (appLabel != null && appLabel.length() > 0) {
                return appLabel.toString();
            }
        }
        return info.activityInfo.packageName;
    }

    private class AppAdapter extends BaseAdapter {
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
                view = LayoutInflater.from(AppDrawerActivity.this)
                        .inflate(R.layout.item_app, parent, false);
            }

            Entry entry = entries.get(position);
            ((ImageView) view.findViewById(R.id.appIcon)).setImageDrawable(entry.icon);

            TextView label = view.findViewById(R.id.appLabel);
            label.setText(entry.label);
            // The stock launcher is the one entry people come here looking for.
            label.setTypeface(null, entry.pinned ? android.graphics.Typeface.BOLD
                    : android.graphics.Typeface.NORMAL);

            return view;
        }
    }
}
