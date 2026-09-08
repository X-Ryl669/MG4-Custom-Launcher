package com.custom.launcher;

import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.CompoundButton;

import com.custom.launcher.car.CarAdapter;
import com.custom.launcher.car.HvacClient;
import com.custom.launcher.widget.ArcSliderView;

/**
 * Drives the climate tile: driver set temperature, fan speed, and a power
 * switch, backed by {@link HvacClient}.
 *
 * <h3>Controls</h3>
 * Temperature and fan are {@link ArcSliderView} dials, one on each side of the
 * tile. They were +/- buttons (32 taps to cross the range), then side-by-side
 * horizontal SeekBars, which on the car turned out to sit close enough together
 * to be grabbed by mistake — a thin horizontal thumb is a poor target on a screen
 * you reach across for. Two dials in two different places are hard to confuse.
 *
 * <p>
 * The card is <em>not</em> clickable. Tapping anywhere used to open the full
 * climate app, which fired on stray taps and on any part of the tile the controls
 * did not cover; now only {@code hvacOpenButton} in the header does that.
 *
 * <p>
 * State is refreshed by polling once a second while the tile is visible, and
 * again shortly after every write. The AIDL does expose an
 * {@code ICarHvacCallback} for push updates, which would be the better long-term
 * answer; implementing it means hosting a callback binder whose transaction
 * layout we cannot exercise without the car, so this starts with polling and
 * leaves that swap as a contained follow-up. A one-second binder read is cheap
 * and the tile only polls while the launcher is in the foreground.
 */
public class HvacTileController implements CarAdapter.Listener {
    private static final String TAG = "HvacTileController";

    private static final long POLL_INTERVAL_MS = 1000L;
    /** Time for the car to apply a write before we trust a read again. */
    private static final long WRITE_SETTLE_MS = 250L;

    // MG4 climate range. The car clamps out-of-range writes itself; these keep
    // the UI from sending obvious nonsense.
    private static final float TEMP_MIN = 16.0f;
    private static final float TEMP_MAX = 32.0f;
    private static final float TEMP_STEP = 0.5f;
    private static final int FAN_MIN = 0;
    private static final int FAN_MAX = 7;

    /** Dial steps for the temperature range, at {@link #TEMP_STEP} each. */
    private static final int TEMP_STEPS = Math.round((TEMP_MAX - TEMP_MIN) / TEMP_STEP);

    /**
     * How long after the user touches a dial we stop writing poll results back
     * into it. Without this, the 1 Hz poll fights the finger: the car needs a
     * moment to report the new setpoint, and until it does the poll is returning
     * the old one.
     */
    private static final long USER_INPUT_GRACE_MS = 1500L;

    private final MainActivity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /**
     * Typed as CompoundButton, not Switch: MainActivity is an AppCompatActivity,
     * and AppCompat's view inflater silently rewrites {@code <Switch>} in the
     * layout to {@code SwitchCompat}, which extends CompoundButton but not
     * android.widget.Switch. Holding it as a Switch throws ClassCastException at
     * inflation time. CompoundButton has everything this needs.
     */
    private final CompoundButton powerSwitch;
    private final ArcSliderView tempDial;
    private final ArcSliderView fanDial;

    private CarAdapter carAdapter;
    private HvacClient hvac;

    private boolean polling;
    private boolean powerOn;
    /** Wall-clock time until which the dials belong to the user, not the poll. */
    private long userInputUntil;
    private float temperature = HvacClient.TEMPERATURE_UNKNOWN;
    private int fanSpeed = HvacClient.FAN_SPEED_UNKNOWN;

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            refresh();
            if (polling) {
                handler.postDelayed(this, POLL_INTERVAL_MS);
            }
        }
    };

    public HvacTileController(MainActivity activity) {
        this.activity = activity;
        this.powerSwitch = activity.findViewById(R.id.hvacPowerSwitch);
        this.tempDial = activity.findViewById(R.id.hvacTempDial);
        this.fanDial = activity.findViewById(R.id.hvacFanDial);

        wireControls();
        showUnavailable();
    }

    private void wireControls() {
        // setOnClickListener rather than setOnCheckedChangeListener: the poll
        // calls setChecked(), and a checked-change listener cannot tell that
        // apart from a user tap, so it would command a toggle on every refresh.
        powerSwitch.setOnClickListener(v -> {
            if (hvac == null) {
                return;
            }
            Log.i(TAG, "Climate power switched (was " + (powerOn ? "on" : "off") + ")");
            // The car exposes a toggle, not a setter, so the switch's new visual
            // state is a request; the next read confirms or corrects it.
            hvac.switchHvacPowerStatus();
            refreshAfterWrite();
        });

        tempDial.setRange(0, TEMP_STEPS);
        tempDial.setLabel("TEMP");
        tempDial.setOnValueChangeListener((view, value, fromUser) -> {
            if (!fromUser) {
                return;
            }
            userInputUntil = System.currentTimeMillis() + USER_INPUT_GRACE_MS;
            temperature = TEMP_MIN + value * TEMP_STEP;
            renderTemperature();
        });
        tempDial.setOnValueCommitListener((view, value) -> commitTemperature());

        fanDial.setRange(FAN_MIN, FAN_MAX);
        fanDial.setLabel("FAN");
        fanDial.setOnValueChangeListener((view, value, fromUser) -> {
            if (!fromUser) {
                return;
            }
            userInputUntil = System.currentTimeMillis() + USER_INPUT_GRACE_MS;
            fanSpeed = value;
            renderFan();
        });
        fanDial.setOnValueCommitListener((view, value) -> commitFan());

        // The one and only way into the full climate app from this tile.
        View openButton = activity.findViewById(R.id.hvacOpenButton);
        if (openButton != null) {
            openButton.setOnClickListener(v -> activity.openHVAC());
        }
    }

    /**
     * Sends the dial's temperature once the user lets go. Writing on every value
     * change would spam the car with a write per degree of rotation.
     */
    private void commitTemperature() {
        if (hvac == null || Float.isNaN(temperature)) {
            return;
        }
        float target = Math.max(TEMP_MIN, Math.min(TEMP_MAX, temperature));
        Log.i(TAG, "Setting driver temperature to " + target);
        hvac.setDriverTemperature(target);
        refreshAfterWrite();
    }

    private void commitFan() {
        if (hvac == null || fanSpeed == HvacClient.FAN_SPEED_UNKNOWN) {
            return;
        }
        int target = Math.max(FAN_MIN, Math.min(FAN_MAX, fanSpeed));
        Log.i(TAG, "Setting fan speed to " + target);
        hvac.setFanSpeed(target);
        refreshAfterWrite();
    }

    private void refreshAfterWrite() {
        handler.postDelayed(this::refresh, WRITE_SETTLE_MS);
    }

    // --- lifecycle, called from MainActivity ---

    public void onStart() {
        if (carAdapter == null) {
            carAdapter = new CarAdapter(activity, this);
        }
        carAdapter.bind();
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
        if (carAdapter != null) {
            carAdapter.unbind();
            carAdapter = null;
        }
        hvac = null;
    }

    // --- CarAdapter.Listener ---

    @Override
    public void onCarAdapterReady(CarAdapter adapter) {
        IBinder binder = adapter.queryClient(CarAdapter.CLIENT_HVAC);
        if (binder == null) {
            Log.w(TAG, "Car adapter connected but returned no HVAC client");
            showUnavailable();
            return;
        }
        hvac = new HvacClient(binder);
        Log.i(TAG, "HVAC client acquired");
        refresh();
    }

    @Override
    public void onCarAdapterLost() {
        hvac = null;
        showUnavailable();
    }

    // --- state ---

    private void refresh() {
        if (hvac == null || !hvac.isAvailable()) {
            showUnavailable();
            return;
        }

        powerOn = hvac.getHvacPowerStatus();
        renderPower();

        // While the user is working a dial, their value wins; the car has not
        // necessarily caught up yet and overwriting would fight the drag.
        if (System.currentTimeMillis() < userInputUntil
                || tempDial.isDragging() || fanDial.isDragging()) {
            return;
        }

        temperature = hvac.getDriverTemperature();
        fanSpeed = hvac.getFanSpeed();

        renderTemperature();
        renderFan();
    }

    private void renderPower() {
        powerSwitch.setEnabled(hvac != null);
        powerSwitch.setChecked(powerOn);
        powerSwitch.setContentDescription(powerOn ? "Turn climate off" : "Turn climate on");

        // Climate off means the dials are not meaningful, and dragging them would
        // set a value nothing acts on.
        tempDial.setEnabled(powerOn);
        fanDial.setEnabled(powerOn);
    }

    private void renderTemperature() {
        if (Float.isNaN(temperature)) {
            tempDial.setValueText("--");
            return;
        }
        // Half-degree steps, so drop the decimal only when it's a whole number.
        tempDial.setValueText(temperature == Math.rint(temperature)
                ? String.format("%.0f°", temperature)
                : String.format("%.1f°", temperature));
        tempDial.setValue(Math.round((temperature - TEMP_MIN) / TEMP_STEP));
    }

    private void renderFan() {
        if (fanSpeed == HvacClient.FAN_SPEED_UNKNOWN) {
            fanDial.setValueText("--");
            return;
        }
        fanDial.setValueText(fanSpeed == 0 ? "off" : String.valueOf(fanSpeed));
        fanDial.setValue(fanSpeed);
    }

    private void showUnavailable() {
        temperature = HvacClient.TEMPERATURE_UNKNOWN;
        fanSpeed = HvacClient.FAN_SPEED_UNKNOWN;
        powerOn = false;
        tempDial.setValueText("--");
        fanDial.setValueText("--");
        tempDial.setEnabled(false);
        fanDial.setEnabled(false);
        powerSwitch.setChecked(false);
        powerSwitch.setEnabled(false);
    }
}
