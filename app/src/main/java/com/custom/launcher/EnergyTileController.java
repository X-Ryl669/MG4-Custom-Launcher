package com.custom.launcher;

import static android.os.Debug.isDebuggerConnected;

import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.custom.launcher.car.BmsProperties;
import com.custom.launcher.car.CarPropertyClient;
import com.custom.launcher.energy.ConsumptionGraphView;
import com.custom.launcher.energy.ConsumptionHistory;

/**
 * Drives the battery tile: state of charge, range, instantaneous power,
 * consumption, and a time graph of consumption.
 *
 * <p>
 * Data comes from AAOS CarService's vendor BMS property service via
 * {@link CarPropertyClient}, which is a different path from the Trophy-era
 * {@code VehicleDataService} this replaces. That older service bound the Luxury
 * SAIC SDK and, when it failed on an SE, silently fell back to hardcoded mock
 * values (78% / 285km) — which is why the tile has been reporting nonsense.
 *
 * <h3>Unit calibration</h3>
 * The raw scaling of the consumption and pack properties is <em>not</em> known
 * from the firmware — the vendor constants give ranges but not units, and this
 * has never been run against a car. So every raw reading is logged (visible in
 * the in-app log viewer, since there is no adb on this head unit) and converted
 * through the clearly-marked constants below. Compare a logged raw value against
 * what the dash shows, then fix the scale factor once.
 */
public class EnergyTileController implements CarPropertyClient.Listener {
    private static final String TAG = "EnergyTileController";

    /** Poll cadence while the launcher is in the foreground. */
    private static final long POLL_INTERVAL_MS = 2000L;
    /** How often a consumption sample is appended to the graph history. */
    private static final long SAMPLE_INTERVAL_MS = 30_000L;

    /**
     * Confirmed on-car: the property is already kWh/100km. While driving the tile
     * agreed with the dashboard trip computer, so no conversion is needed.
     */
    private static final float CONSUMPTION_SCALE = 1.0f;

    /**
     * The car's "no figure available" value for all three consumption properties.
     *
     * <p>
     * Parked, the car answers 82.3 on ELEC_CSUMP_PERKM, CRNT_AVG_ELEC_CSUMP
     * <em>and</em> BAT_ELEC_ENRG_AVG_RATE simultaneously — three independent
     * signals landing on the same value is a sentinel, not a measurement. It is
     * also the same shape as CHARGING_REMAINING_TIME answering 1023 when nothing
     * is charging. Confirmed against the dash: while driving these read true, and
     * only at rest do they sit at 82.3.
     *
     * <p>
     * Compared with a tolerance because the value arrives as a float.
     */
    private static final float CONSUMPTION_SENTINEL = 82.3f;
    private static final float SENTINEL_EPSILON = 0.05f;

    /** CHARGING_REMAINING_TIME's equivalent of "not applicable". */
    private static final int CHARGING_TIME_SENTINEL = 1023;

    /**
     * Multiplier turning raw pack current × voltage into kW. Confirmed on-car:
     * CarService logged {@code PACK_VOLTAGE = 343.25} and
     * {@code PACK_CURRENT = 0.65} with the car parked, i.e. 223 W of standby draw,
     * so the properties really are volts and amps and this is just W → kW.
     */
    private static final float POWER_SCALE = 1.0f / 1000.0f;

    /** Above this the reading is treated as implausible and not displayed. */
    private static final float MAX_PLAUSIBLE_KWH_PER_100KM = 200f;
    private static final float MAX_PLAUSIBLE_KW = 500f;

    private final MainActivity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /**
     * One line holding power, average consumption, charge and range. It used to
     * be a 64sp state-of-charge figure plus a range line plus a two-column
     * power/consumption row, which between them left the graph 60dp of the tile.
     */
    private final TextView statsText;
    private final TextView batteryPercent;
    private final TextView batteryRange;
    private final TextView graphCaption;
    private final ConsumptionGraphView graph;
    private final View batteryFill;

    private CarPropertyClient car;
    private ConsumptionHistory history;

    private boolean polling;
    private long lastSampleAt;
    /** Logs raw values once per connection rather than every poll. */
    private boolean loggedRawThisConnection;

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            refresh();
            if (polling) {
                handler.postDelayed(this, POLL_INTERVAL_MS);
            }
        }
    };

    public EnergyTileController(MainActivity activity) {
        this.activity = activity;
        this.statsText = activity.findViewById(R.id.energyStatsText);
        this.graphCaption = activity.findViewById(R.id.graphCaption);
        this.graph = activity.findViewById(R.id.consumptionGraph);
        this.batteryFill = activity.findViewById(R.id.batteryFill);
        this.batteryPercent = activity.findViewById(R.id.batteryPercent);
        this.batteryRange = activity.findViewById(R.id.batteryRange);

        this.history = new ConsumptionHistory(activity);
        if (graph != null) {
            graph.setSamples(history.getSamples());
        }
        updateGraphCaption();
    }

    // --- lifecycle ---

    public void onStart() {
        if (car == null) {
            car = new CarPropertyClient(activity, this);
        }
        car.bind();
        polling = true;
        handler.removeCallbacks(pollTask);
        handler.post(pollTask);
    }

    public void onStop() {
        polling = false;
        handler.removeCallbacks(pollTask);
    }

    public void onDestroy() {
        onStop();
        handler.removeCallbacksAndMessages(null);
        if (car != null) {
            car.unbind();
            car = null;
        }
    }

    // --- CarPropertyClient.Listener ---

    @Override
    public void onCarPropertiesReady() {
        loggedRawThisConnection = false;
        refresh();
    }

    @Override
    public void onCarPropertiesLost() {
        showUnavailable();
    }

    // --- reading ---

    private void refresh() {
        if (car == null || !car.isReady()) {
            if ( isDebuggerConnected()  ) {
                // DEBUG HERE:
                float soc = (float) (Math.random() * 100f);
                float kw = (float) (Math.random() * 30f - 10f);
                float consumption = (float) (Math.random() * 25f - 4f);


                renderStats(soc, (int)Math.floor(soc * 3.5), kw, consumption, -1);
                updateBatteryFill(soc);
                maybeSample(consumption);
                return;
            }

            showUnavailable();
            return;
        }

        float soc = readSoc();
        int range = readRange();
        float kw = readPowerKw();
        float consumption = readConsumptionKwhPer100km();

        if (!loggedRawThisConnection) {
            logRawReadings();
            loggedRawThisConnection = true;
        }

        renderStats(soc, range, kw, consumption, readChargingMinutes());
        updateBatteryFill(soc);

        maybeSample(consumption);
    }

    /**
     * <h4>Why the {@code *_VALID} companions are not consulted</h4>
     * They used to gate every reading here, and that is what made the tile show
     * "--" for state of charge, range and power on the car even though the values
     * themselves were arriving fine: the log showed PACK_SOC_DISPLAY = 51.1 with
     * status 0 at the same moment the tile rendered "--". The companions
     * (PACK_SOC_DISPLAY_VALID, ESTD_ELEC_RANGE_VALID, PACK_CURRENT_VALID,
     * PACK_VOLTAGE_VALID) answer zero on a parked SE, whatever they actually mean
     * — they are clearly not "this reading is usable". Only
     * ELEC_CSUMP_PERKM_VALID behaved, which is why consumption was the one figure
     * that did display.
     *
     * <p>
     * {@code CarPropertyValue.mStatus} already carries availability and it is the
     * car's own answer, so that plus a plausibility range is what gates readings
     * now. The companions are still dumped by the BMS debug screen.
     */
    private float readSoc() {
        float soc = car.getFloat(BmsProperties.PACK_SOC_DISPLAY, Float.NaN);
        if (Float.isNaN(soc) || soc < 0f || soc > 100f) {
            return Float.NaN;
        }
        return soc;
    }

    /**
     * Range in km.
     *
     * <p>
     * VEH_ELEC_RANGE is preferred over ESTD_ELEC_RANGE because it is the one that
     * tracks reality: parked at 51% the car answered ESTD_ELEC_RANGE = 300 and
     * VEH_ELEC_RANGE = 162, and 162 is what an SE at half charge actually has.
     * ESTD looks like a static best-case figure.
     */
    private int readRange() {
        int range = car.getInt(BmsProperties.VEH_ELEC_RANGE, -1);
        if (range <= 0) {
            range = car.getInt(BmsProperties.ESTD_ELEC_RANGE, -1);
        }
        return range > 0 ? range : -1;
    }

    /**
     * Instantaneous power. Positive means drawing from the pack, negative means
     * regenerating or charging — though the car's sign convention has not been
     * confirmed, so the charge status is used to settle the direction.
     */
    private float readPowerKw() {
        float amps = car.getFloat(BmsProperties.PACK_CURRENT, Float.NaN);
        float volts = car.getFloat(BmsProperties.PACK_VOLTAGE, Float.NaN);
        if (Float.isNaN(amps) || Float.isNaN(volts)) {
            return Float.NaN;
        }

        float kw = (amps * volts * POWER_SCALE);
        if (kw > MAX_PLAUSIBLE_KW) {
            Log.w(TAG, "Implausible power " + kw + " kW from " + amps + "A x " + volts
                    + "V - POWER_SCALE likely needs calibration");
            return Float.NaN;
        }

        // The current is signed (while regenerating), so this value is signed too
        return kw;
//        int chargeStatus = car.getInt(BmsProperties.CHARGE_STATUS, -1);
//        return BmsProperties.isCharging(chargeStatus) ? -kw : kw;
    }

    /**
     * Average consumption in kWh/100km, or NaN when the car has no figure.
     *
     * <p>
     * All three candidate properties are tried in turn, and each is checked
     * against {@link #CONSUMPTION_SENTINEL} — the "no figure" value the car parks
     * them all at when stationary. Showing 82.3 was worse than showing nothing:
     * it looked like a real reading and it is roughly five times a plausible one.
     */
    private float readConsumptionKwhPer100km() {
        float raw = firstRealConsumption(
                BmsProperties.ELEC_CSUMP_PERKM,
                BmsProperties.CRNT_AVG_ELEC_CSUMP,
                BmsProperties.BAT_ELEC_ENRG_AVG_RATE);
        if (Float.isNaN(raw)) {
            return Float.NaN;
        }

        float scaled = raw * CONSUMPTION_SCALE;
        if (scaled > MAX_PLAUSIBLE_KWH_PER_100KM) {
            Log.w(TAG, "Implausible consumption " + scaled + " kWh/100km from raw " + raw);
            return Float.NaN;
        }
        return scaled;
    }

    private float firstRealConsumption(int... propertyIds) {
        for (int propertyId : propertyIds) {
            float value = car.getFloat(propertyId, Float.NaN);
            if (!Float.isNaN(value) && !isSentinel(value)) {
                return value;
            }
        }
        return Float.NaN;
    }

    private static boolean isSentinel(float value) {
        return Math.abs(value - CONSUMPTION_SENTINEL) < SENTINEL_EPSILON;
    }

    /** Minutes of charging left, or -1 when not charging or not known. */
    private int readChargingMinutes() {
        int minutes = car.getInt(BmsProperties.CHARGING_REMAINING_TIME, -1);
        return (minutes < 0 || minutes >= CHARGING_TIME_SENTINEL) ? -1 : minutes;
    }

    /**
     * Dumps every raw BMS reading once per connection. This is the only way to
     * calibrate the scale factors, since there is no adb on this head unit — read
     * it in the in-app log viewer and compare against the dash.
     */
    private void logRawReadings() {
        Log.i(TAG, "=== raw BMS readings (for unit calibration) ===");
        logRaw("PACK_SOC_DISPLAY", BmsProperties.PACK_SOC_DISPLAY);
        logRaw("ESTD_ELEC_RANGE", BmsProperties.ESTD_ELEC_RANGE);
        logRaw("VEH_ELEC_RANGE", BmsProperties.VEH_ELEC_RANGE);
        logRaw("PACK_CURRENT", BmsProperties.PACK_CURRENT);
        logRaw("PACK_VOLTAGE", BmsProperties.PACK_VOLTAGE);
        logRaw("ELEC_CSUMP_PERKM", BmsProperties.ELEC_CSUMP_PERKM);
        logRaw("CRNT_AVG_ELEC_CSUMP", BmsProperties.CRNT_AVG_ELEC_CSUMP);
        logRaw("BAT_ELEC_ENRG_AVG_RATE", BmsProperties.BAT_ELEC_ENRG_AVG_RATE);
        logRaw("CHARGE_STATUS", BmsProperties.CHARGE_STATUS);
        logRaw("CHARGING_REMAINING_TIME", BmsProperties.CHARGING_REMAINING_TIME);
        Log.i(TAG, "=== end raw BMS readings ===");
    }

    private void logRaw(String name, int propertyId) {
        CarPropertyClient.Value v = car.get(propertyId);
        if (v == null) {
            Log.i(TAG, name + " (0x" + Integer.toHexString(propertyId) + ") = <no value>");
        } else {
            Log.i(TAG, name + " (0x" + Integer.toHexString(propertyId) + ") = " + v.value
                    + " [status " + v.status + "]");
        }
    }

    private void maybeSample(float consumption) {
        if (Float.isNaN(consumption)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (lastSampleAt != 0 && now - lastSampleAt < SAMPLE_INTERVAL_MS) {
            return;
        }
        lastSampleAt = now;
        history.add(now, consumption);
        if (graph != null) {
            graph.setSamples(history.getSamples());
        }
        updateGraphCaption();
    }

    // --- rendering ---

    /**
     * Renders every figure onto one line, with an em-dash separator and "--" for
     * anything the car has no answer for. Charging time is appended only while
     * something is actually charging, so the line does not carry dead text.
     */
    private void renderStats(float soc, int rangeKm, float kw, float consumption,
            int chargingMinutes) {
        if (statsText == null) {
            return;
        }

        StringBuilder line = new StringBuilder();
        line.append(Float.isNaN(kw)
                ? "-- kW"
                : String.format(java.util.Locale.US, "%.1f kW", kw));
        line.append("  ·  ");
        line.append(Float.isNaN(consumption)
                ? "-- kWh/100km"
                : String.format(java.util.Locale.US, "%.1f kWh/100km", consumption));
        if (chargingMinutes >= 0) {
            line.append("  ·  ").append(chargingMinutes).append(" min to full");
        }

        statsText.setText(line);
        batteryPercent.setText(Float.isNaN(soc)
                ? "--%"
                : String.format(java.util.Locale.US, "%.0f%%", soc));
        batteryRange.setText(rangeKm < 0 ? "-- km" : rangeKm + " km");


    }

    /**
     * Drives the gauge graphic from the real state of charge. It was static
     * before: nothing called this unless the state-of-charge text rendered, and
     * that was gated out by the {@code *_VALID} companions.
     */
    private void updateBatteryFill(float soc) {
        if (batteryFill == null || Float.isNaN(soc)) {
            return;
        }
        activity.updateBatteryFill(Math.round(soc));
    }

    private void updateGraphCaption() {
        if (graphCaption == null || graph == null) {
            return;
        }
        float avg = graph.getAverage();
        if (Float.isNaN(avg)) {
            graphCaption.setText("Collecting consumption data…");
        } else {
            graphCaption.setText(String.format("avg %.1f · %.1f–%.1f kWh/100km",
                    avg, graph.getMin(), graph.getMax()));
        }
    }

    private void showUnavailable() {
        if (statsText != null) {
            statsText.setText("-- kW  ·  -- kWh/100km");
        }
        if (batteryPercent != null) {
            batteryPercent.setText("-- %");
        }
        if (batteryRange != null) {
            batteryRange.setText("-- km");
        }
    }
}
