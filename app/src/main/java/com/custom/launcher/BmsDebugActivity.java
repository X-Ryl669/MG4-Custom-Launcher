package com.custom.launcher;

import java.util.Locale;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import com.custom.launcher.car.BmsProperties;
import com.custom.launcher.car.CarPropertyClient;

/**
 * Live dump of every BMS property, for working out what the raw numbers mean.
 *
 * <p>
 * The energy tile showed 82.3 kWh/100km while the dashboard said 15.4, and there
 * is no way to calibrate that from a distance: this head unit has no adb, and its
 * logcat buffer turns over in well under a second because the Bluetooth stack and
 * the TBox NMEA feed never stop talking. A log-based dump of these values loses
 * the race every time.
 *
 * <p>
 * So this screen just shows them, refreshed once a second, next to the property's
 * name, id, the area the car answered from, and the Java type that came back. A
 * person sitting in the car can read it straight off the display and compare with
 * the dashboard.
 */
public class BmsDebugActivity extends AppCompatActivity {
    private static final String TAG = "BmsDebugActivity";

    private static final long REFRESH_MS = 1000;

    /** Areas to probe, so the screen also answers "which area works here?". */
    private static final int[] AREAS = { 0x01000000, 0 };

    private TextView output;
    private CarPropertyClient car;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            update();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bms_debug);
        output = findViewById(R.id.bmsOutput);
        output.setText("Binding to CarService…");

        car = new CarPropertyClient(this, null);
        car.bind();
    }

    @Override
    protected void onStart() {
        super.onStart();
        handler.post(refresh);
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(refresh);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(refresh);
        if (car != null) {
            car.unbind();
        }
    }

    private void update() {
        StringBuilder sb = new StringBuilder();

        if (car == null || !car.isReady()) {
            sb.append("BMS service NOT available.\n\n")
                    .append("CarService bound: ").append(car != null && car.isBound()).append('\n')
                    .append("This means getCarService(\"bms\") returned null,\n")
                    .append("so no energy data can be read at all.\n");
            output.setText(sb.toString());
            return;
        }

        sb.append("area 0x1000000 is what SAIC's own apps use.\n\n");

        for (int i = 0; i < BmsProperties.ALL_IDS.length; i++) {
            int id = BmsProperties.ALL_IDS[i];
            String name = BmsProperties.ALL_NAMES[i];

            sb.append(name).append('\n');
            sb.append(String.format(Locale.US, "  id 0x%08x", id));

            boolean answered = false;
            for (int area : AREAS) {
                CarPropertyClient.Value v = car.get(id, area);
                if (v == null) {
                    continue;
                }
                answered = true;
                sb.append(String.format(Locale.US, "\n  area 0x%x -> %s  [%s, status %d]",
                        area,
                        v.value == null ? "null" : v.value.toString(),
                        v.value == null ? "?" : v.value.getClass().getSimpleName(),
                        v.status));
            }
            if (!answered) {
                sb.append("\n  no answer from any area");
            }
            sb.append("\n\n");
        }

        output.setText(sb.toString());
        logDumpOnce(sb.toString());
    }

    /**
     * Writes the dump to the log exactly once per visit to this screen.
     *
     * <p>
     * It used to log only the words "BMS dump refreshed", once a second — so the
     * saved log from the car contained twenty-five of those and not one actual
     * value, which is the one thing the screen exists to capture. Logging the
     * whole dump every second would be as useless in the other direction, so this
     * logs the first complete dump and then stays quiet; the screen itself is live
     * for anything after that.
     */
    private void logDumpOnce(String dump) {
        if (loggedDump) {
            return;
        }
        loggedDump = true;
        Log.i(TAG, "=== BMS property dump ===");
        for (String line : dump.split("\n")) {
            if (!line.trim().isEmpty()) {
                Log.i(TAG, line);
            }
        }
        Log.i(TAG, "=== end BMS property dump ===");
    }

    private boolean loggedDump;
}
