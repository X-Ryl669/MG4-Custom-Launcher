package com.custom.launcher;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.content.ComponentName;
import android.content.Intent;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import com.custom.launcher.service.CarPlayService;
import com.custom.launcher.service.HeatingControlService;
import com.custom.launcher.media.BluetoothConnectionReceiver;
import com.custom.launcher.media.MediaSources;
import com.custom.launcher.saic.SaicSourceSwitch;
import com.custom.launcher.service.MediaListenerService;
import com.custom.launcher.util.LauncherPrefs;
import com.custom.launcher.window.FloatingAppController;
import com.custom.launcher.util.LogTee;
import com.custom.launcher.util.LogUtils;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "CustomLauncher";

    /** Raised by the launcher settings screen to reuse this activity's picker. */
    static final String ACTION_PICK_NAV_APP = "com.custom.launcher.PICK_NAV_APP";

    private HvacTileController hvacTile;
    private EnergyTileController energyTile;
    private GpsTileController gpsTile;

    // Floating map window ("PiP") state.
    private View musicCard;
    private View gpsFace;
    private View miniPlayerFace;
    private ImageView miniAlbumArt;
    private TextView miniTitle;
    private TextView miniArtist;
    private ImageButton miniPlayPauseButton;
    private boolean floatingMapActive;
    private String floatingMapPackage;
    /** Long enough for the launched activity to have a task to operate on. */
    private static final long FLOAT_REPAIR_DELAY_MS = 1200L;
    private TextView mediaSourceLabel;
    private final BluetoothConnectionReceiver btArtCacheReceiver = new BluetoothConnectionReceiver();
    private TextView batteryLabel;
    private TextView songTitle;
    private TextView artistName;
    private TextView currentTime;
    private TextView totalTime;
    private ImageButton playPauseButton;
    private ImageView albumArt;
    private ImageView albumArtBlurred;
    private CardView batteryCard;
    private View batteryFill;
    private android.widget.SeekBar progressBar;

    // Heating control views
    private ImageView leftSeatIcon;
    private ImageView rightSeatIcon;
    private ImageView wheelIcon;

    // CarPlay icon views
    private ImageView carPlayIcon;
    private TextView carPlayText;

    // Heating control states (0 = off, 1-3 = heat levels)
    private int leftSeatLevel = 0;
    private int rightSeatLevel = 0;
    private boolean wheelHeating = false;

    private HeatingControlService heatingControlService;
    /**
     * Whether the car has each heating feature. Start false so nothing shows
     * until the car has said it exists — the previous default was "visible", and
     * on a car without the hardware nothing ever came along to correct it.
     */
    private boolean hasLeftSeatHeating;
    private boolean hasRightSeatHeating;
    private boolean hasWheelHeating;
    private CarPlayService carPlayService;
    private Handler progressHandler;
    private Runnable progressRunnable;
    private MediaController activeMediaController;
    private Handler retryHandler = new Handler(Looper.getMainLooper());
    private final int RETRY_INTERVAL_MS = 10000;
    private final Runnable retryRunnable = new Runnable() {
        @Override
        public void run() {
            // Retry heating control service
            if (heatingControlService != null && !heatingControlService.isConnected()) {
                Log.i(TAG, "[RETRY] Retrying heating service connection (attempt at " +
                        new SimpleDateFormat("HH:mm:ss", Locale.UK).format(new Date()) + ")");
                try {
                    heatingControlService.bind();
                } catch (Exception e) {
                    LogUtils.logError(TAG, "[RETRY] ✗ Exception during heating retry", e);
                }
            } else if (heatingControlService != null && heatingControlService.isConnected()) {
                Log.i(TAG, "[RETRY] Heating service is now connected");
            }

            // The energy tile manages its own CarService binding, so only the
            // heating service still needs this retry loop.
            boolean shouldRetry = heatingControlService != null && !heatingControlService.isConnected();
            if (shouldRetry) {
                retryHandler.removeCallbacks(this);
                retryHandler.postDelayed(this, RETRY_INTERVAL_MS);
                Log.i(TAG, "[RETRY] Next retry scheduled in " + (RETRY_INTERVAL_MS / 1000) + " seconds");
            } else {
                Log.i(TAG, "[RETRY] All services connected, stopping retry loop");
            }
        }
    };

    private boolean isMediaPlaying = false;
    private boolean lastCommandWasPlay = false;
    private int currentBatteryLevel = 39;

    // Debug dialog fields
    private int clockTapCount = 0;
    private long lastClockTapTime = 0;
    private static final long TAP_TIMEOUT = 500; // ms between taps
    // Debug dialog removed - now using LogViewerActivity full-screen
    private TextView adbStatusView;
    private android.widget.Button enableAdbButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize logging FIRST to ensure all log levels are enabled
        LogUtils.initializeLogging();

        // Mirror our own log lines to Download/, because logcat's buffer on this
        // car turns over in well under a second: a log captured from the car
        // contained 201 lines, none of them ours. Without this there is no way to
        // get a diagnostic off the vehicle - but it is also a file on the car's
        // flash that grows the whole time the launcher runs, so it is off until
        // asked for. Menu > Debug logging.
        if (LauncherPrefs.isLoggingEnabled(this)) {
            LogTee.start();
        }

        setContentView(R.layout.activity_main);

        // Log display metrics immediately on startup
        logDisplayMetrics();

        initializeViews();
        requestStoragePermissions();
        checkNotificationListenerPermission();
        setupVehicleService();
        setupMediaService();
        startProgressUpdates();
        hvacTile = new HvacTileController(this);
        energyTile = new EnergyTileController(this);
        gpsTile = new GpsTileController(this);
        // Nothing has told us what this car has yet, so hide the lot until it does.
        applyHeatingAvailability();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handlePickNavAppRequest(intent);
    }

    /**
     * The settings screen cannot show this picker itself and then have the tile
     * relabel, so it bounces the request back here where both the dialog and the
     * tile live.
     */
    private void handlePickNavAppRequest(Intent intent) {
        if (intent != null && ACTION_PICK_NAV_APP.equals(intent.getAction())) {
            // Consumed, so a configuration change does not raise it again.
            intent.setAction(null);
            pickNavigationApp();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (hvacTile != null) {
            hvacTile.onStart();
        }
        if (energyTile != null) {
            energyTile.onStart();
        }
        if (gpsTile != null) {
            gpsTile.onStart();
        }
        // The car may have answered while we were away, and the override may have
        // been flipped in the launcher menu.
        applyHeatingAvailability();
        handlePickNavAppRequest(getIntent());
        btArtCacheReceiver.register(this);

        // Returning to the launcher while a map is meant to be floating: re-apply
        // it. This is what makes the home button an escape hatch if the window
        // ever comes up fullscreen and hides the compact player.
        if (floatingMapActive) {
            retryHandler.postDelayed(this::refloatMap, FLOAT_REPAIR_DELAY_MS);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Stop polling the car for climate state while we're not on screen.
        if (hvacTile != null) {
            hvacTile.onStop();
        }
        if (energyTile != null) {
            energyTile.onStop();
        }
        if (gpsTile != null) {
            gpsTile.onStop();
        }
        btArtCacheReceiver.unregister(this);
    }

    /**
     * Request storage permissions for accessing Bluetooth album art
     */
    private void requestStoragePermissions() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            String[] permissions = {
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            };

            boolean needsPermission = false;
            for (String permission : permissions) {
                if (checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    needsPermission = true;
                    Log.i(TAG, "Missing permission: " + permission);
                }
            }

            if (needsPermission) {
                Log.i(TAG, "Requesting storage permissions for Bluetooth album art access...");
                requestPermissions(permissions, 1001);
            } else {
                Log.i(TAG, "✓ Storage permissions already granted");
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1001) {
            boolean allGranted = true;
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Log.i(TAG, "✓ Permission granted: " + permissions[i]);
                } else {
                    Log.w(TAG, "✗ Permission denied: " + permissions[i]);
                    allGranted = false;
                }
            }
            if (allGranted) {
                Log.i(TAG, "✓ All storage permissions granted - album art should now work");
            } else {
                Log.w(TAG, "⚠ Some permissions denied - album art may not work for Bluetooth sources");
            }
        }
    }

    /**
     * Log display metrics for debugging
     */
    private void logDisplayMetrics() {
        try {
            android.util.DisplayMetrics displayMetrics = new android.util.DisplayMetrics();
            getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
            int dpi = displayMetrics.densityDpi;
            int width = displayMetrics.widthPixels;
            int height = displayMetrics.heightPixels;
            float density = displayMetrics.density;
            int widthDp = (int) (width / density);
            int heightDp = (int) (height / density);

            Log.i(TAG, "=================================================");
            Log.i(TAG, "DISPLAY METRICS:");
            Log.i(TAG, "  Resolution: " + width + "x" + height + " pixels");
            Log.i(TAG, "  DPI: " + dpi);
            Log.i(TAG, "  Density: " + density);
            Log.i(TAG, "  Size in DP: " + widthDp + "x" + heightDp + " dp");
            Log.i(TAG, "=================================================");
        } catch (Exception e) {
            Log.e(TAG, "Failed to log display metrics: " + e.getMessage());
        }
    }

    private void initializeViews() {
        batteryLabel = findViewById(R.id.batteryLabel);
        songTitle = findViewById(R.id.songTitle);
        artistName = findViewById(R.id.artistName);
        currentTime = findViewById(R.id.currentTime);
        totalTime = findViewById(R.id.totalTime);
        playPauseButton = findViewById(R.id.playPauseButton);
        albumArt = findViewById(R.id.albumArt);
        albumArtBlurred = findViewById(R.id.albumArtBlurred);
        batteryCard = findViewById(R.id.batteryCard);
        batteryFill = findViewById(R.id.batteryFill);

        // Initialize heating control views (with null safety)
        leftSeatIcon = findViewById(R.id.leftSeatIcon);
        rightSeatIcon = findViewById(R.id.rightSeatIcon);
        wheelIcon = findViewById(R.id.wheelIcon);

        // CarPlay icon
        carPlayIcon = findViewById(R.id.carPlayIcon);
        carPlayText = findViewById(R.id.carPlayText);

        // Ensure CarPlay starts in disabled state
        if (carPlayIcon != null) {
            carPlayIcon.setEnabled(false);
            Log.d(TAG, "CarPlay icon initialized as DISABLED");
        }

        // Setup heating control click listeners (only if views exist)
        View leftSeatButton = findViewById(R.id.leftSeatButton);
        if (leftSeatButton != null) {
            leftSeatButton.setOnClickListener(v -> toggleLeftSeat());
        } else {
            Log.w(TAG, "leftSeatButton not found in layout");
        }

        View rightSeatButton = findViewById(R.id.rightSeatButton);
        if (rightSeatButton != null) {
            rightSeatButton.setOnClickListener(v -> toggleRightSeat());
        } else {
            Log.w(TAG, "rightSeatButton not found in layout");
        }

        View wheelButton = findViewById(R.id.wheelButton);
        if (wheelButton != null) {
            wheelButton.setOnClickListener(v -> toggleWheel());
        } else {
            Log.w(TAG, "wheelButton not found in layout");
        }

        // The debug handles were a triple-tap and long-press on the battery card's
        // 10sp label, which turned out to be near-impossible to hit in a parked car
        // and impossible in a moving one. Everything is now reachable from the
        // hamburger menu; the long-press context menu stays, moved onto that
        // button, for the extra items the settings screen does not list.
        View menuButton = findViewById(R.id.menuButton);
        if (menuButton != null) {
            registerForContextMenu(menuButton);
            menuButton.setOnLongClickListener(v -> {
                v.showContextMenu();
                return true;
            });
        }

        progressBar = findViewById(R.id.progressBar);

        // Disable SeekBar interaction (read-only progress indicator)
        progressBar.setEnabled(false);

        // Setup media control buttons
        findViewById(R.id.prevButton).setOnClickListener(v -> {
            sendMediaButtonCommand(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        });

        playPauseButton.setOnClickListener(v -> {
            sendMediaButtonCommand(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        });

        findViewById(R.id.nextButton).setOnClickListener(v -> {
            sendMediaButtonCommand(KeyEvent.KEYCODE_MEDIA_NEXT);
        });

        mediaSourceLabel = findViewById(R.id.mediaSourceLabel);

        musicCard = findViewById(R.id.musicCard);
        gpsFace = findViewById(R.id.gpsFace);
        miniPlayerFace = findViewById(R.id.miniPlayerFace);
        miniAlbumArt = findViewById(R.id.miniAlbumArt);
        miniTitle = findViewById(R.id.miniTitle);
        miniArtist = findViewById(R.id.miniArtist);
        miniPlayPauseButton = findViewById(R.id.miniPlayPauseButton);

        findViewById(R.id.pipToggleButton).setOnClickListener(v -> startFloatingMap());
        findViewById(R.id.miniPrevButton).setOnClickListener(
                v -> sendMediaButtonCommand(KeyEvent.KEYCODE_MEDIA_PREVIOUS));
        miniPlayPauseButton.setOnClickListener(
                v -> sendMediaButtonCommand(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        findViewById(R.id.miniNextButton).setOnClickListener(
                v -> sendMediaButtonCommand(KeyEvent.KEYCODE_MEDIA_NEXT));

        View browseButton = findViewById(R.id.browseButton);
        if (browseButton != null) {
            browseButton.setOnClickListener(v -> openMediaBrowser());
        }

        // The battery card used to open the car's charge-management screen on tap.
        // Removed: a tap on an energy gauge landing in the vehicle-settings app was
        // the wrong destination, and it fired by accident constantly. The tile is
        // display-only for now.

        // Setup quick action buttons
        findViewById(R.id.carPlayButton).setOnClickListener(v -> {
            Log.i(TAG, "CarPlay button clicked!");
            openCarPlay();
        });

        findViewById(R.id.menuButton).setOnClickListener(v -> {
            Log.i(TAG, "Menu button clicked!");
            openLauncherSettings();
        });

        findViewById(R.id.settingsButton).setOnClickListener(v -> {
            Log.i(TAG, "Settings button clicked!");
            openSettings();
        });

        findViewById(R.id.appsButton).setOnClickListener(v -> {
            Log.i(TAG, "Apps button clicked!");
            openAppDrawer();
        });
    }

    private void openCarPlay() {
        if (carPlayService != null && carPlayService.isCarPlayConnected()) {
            Log.i(TAG, "Launching CarPlay via service...");
            carPlayService.launchCarPlay();
        } else {
            Log.w(TAG, "CarPlay not connected");
            Toast.makeText(this, "CarPlay not connected. Please connect your iPhone.", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Update CarPlay icon state based on connection
     */
    private void updateCarPlayIcon(boolean isConnected) {
        Log.i(TAG, "Updating CarPlay icon: " + (isConnected ? "CONNECTED" : "DISCONNECTED"));

        if (carPlayIcon != null) {
            carPlayIcon.setEnabled(isConnected);
            carPlayIcon.setAlpha(isConnected ? 1.0f : 0.5f);
        }

        if (carPlayText != null) {
            carPlayText.setAlpha(isConnected ? 1.0f : 0.5f);
        }
    }

    private void openMediaBrowser() {
        try {
            Log.i(TAG, "Opening media browser...");
            startActivity(new Intent(this, MediaBrowseActivity.class));
        } catch (Exception e) {
            Log.e(TAG, "✗ Failed to open media browser: " + e.getMessage());
            Toast.makeText(this, "Error opening browser", Toast.LENGTH_SHORT).show();
        }
    }

    /** Labels the tile with whichever source owns the active session. */
    private void updateMediaSourceLabel() {
        if (mediaSourceLabel == null) {
            return;
        }
        mediaSourceLabel.setText(
                MediaSources.labelForPackage(MediaListenerService.getActiveSourcePackage()));
    }

    private void openAppDrawer() {
        try {
            Log.i(TAG, "Opening app drawer...");
            startActivity(new Intent(this, AppDrawerActivity.class));
        } catch (Exception e) {
            Log.e(TAG, "✗ Failed to open app drawer: " + e.getMessage());
            Toast.makeText(this, "Error opening app list", Toast.LENGTH_SHORT).show();
        }
    }

    /** Package-visible so the climate tile can hand off to the full HVAC app. */
    void openHVAC() {
        try {
            Log.i(TAG, "Attempting to open dedicated HVAC app...");
            Intent intent = SaicPackages.buildLaunchIntent(this, SaicPackages.HVAC);
            if (intent == null) {
                Log.w(TAG, "✗ No HVAC app found on this head unit");
                return;
            }
            startActivity(intent);
            Log.i(TAG, "✓ Successfully launched HVAC app");
        } catch (Exception e) {
            Log.e(TAG, "✗ Failed to open HVAC app: " + e.getMessage());
            Log.i(TAG, "This will work on the actual MG4 car where the HVAC app is installed");
        }
    }

    private void openSettings() {
        try {
            Log.i(TAG, "Opening Android Settings...");
            Intent intent = new Intent(Settings.ACTION_SETTINGS);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            Log.i(TAG, "✓ Successfully launched Settings");
        } catch (Exception e) {
            Log.e(TAG, "✗ Failed to open Settings: " + e.getMessage());
        }
    }

    private void openUsbDebugScreen() {
        try {
            Log.i(TAG, "Opening USB Debug Screen...");
            Intent intent = new Intent(this, UsbDebugActivity.class);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open USB Debug Screen: " + e.getMessage());
            Toast.makeText(this, "Error opening debug screen", Toast.LENGTH_SHORT).show();
        }
    }

    private void openShellActivity() {
        try {
            Log.i(TAG, "Opening Shell Command Interface...");
            Intent intent = new Intent(this, ShellActivity.class);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open Shell Activity: " + e.getMessage());
            Toast.makeText(this, "Error opening shell", Toast.LENGTH_SHORT).show();
        }
    }

    /** Opens the launcher's own settings, which also hosts the diagnostics. */
    private void openLauncherSettings() {
        try {
            startActivity(new Intent(this, LauncherSettingsActivity.class));
        } catch (Exception e) {
            Log.e(TAG, "Failed to open launcher settings: " + e.getMessage());
            Toast.makeText(this, "Error opening settings", Toast.LENGTH_SHORT).show();
        }
    }

    private void openLogViewer() {
        try {
            Log.i(TAG, "Opening Log Viewer...");
            Intent intent = new Intent(this, LogViewerActivity.class);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open Log Viewer: " + e.getMessage());
            Toast.makeText(this, "Error opening log viewer", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Heating control methods
     * Seats have 3 levels (off -> 3 -> 2 -> 1 -> off)
     * Wheel is binary (off -> on -> off)
     */
    private void toggleLeftSeat() {
        // Calculate next level: Off -> High (3) -> Medium (2) -> Low (1) -> Off
        int nextLevel;
        if (leftSeatLevel == 0) {
            nextLevel = 3; // Off -> High
        } else if (leftSeatLevel == 3) {
            nextLevel = 2; // High -> Medium
        } else if (leftSeatLevel == 2) {
            nextLevel = 1; // Medium -> Low
        } else {
            nextLevel = 0; // Low -> Off
        }

        // Update UI immediately (optimistic)
        leftSeatLevel = nextLevel;
        updateSeatDisplay(leftSeatIcon, leftSeatLevel);

        // SAIC API is inverted: API 1=High, 2=Med, 3=Low
        int vehicleLevel = (nextLevel == 0) ? 0 : (4 - nextLevel);
        Log.i(TAG, String.format("Left seat: UI level %d -> Vehicle API level %d", nextLevel, vehicleLevel));

        // Send command to vehicle
        if (heatingControlService != null && heatingControlService.isConnected()) {
            heatingControlService.setDriverSeatHeating(vehicleLevel);
        } else {
            Log.w(TAG, "Heating service not connected, command not sent");
        }
    }

    private void toggleRightSeat() {
        // Calculate next level: Off -> High (3) -> Medium (2) -> Low (1) -> Off
        int nextLevel;
        if (rightSeatLevel == 0) {
            nextLevel = 3; // Off -> High
        } else if (rightSeatLevel == 3) {
            nextLevel = 2; // High -> Medium
        } else if (rightSeatLevel == 2) {
            nextLevel = 1; // Medium -> Low
        } else {
            nextLevel = 0; // Low -> Off
        }

        // Update UI immediately (optimistic)
        rightSeatLevel = nextLevel;
        updateSeatDisplay(rightSeatIcon, rightSeatLevel);

        // SAIC API is inverted: API 1=High, 2=Med, 3=Low
        int vehicleLevel = (nextLevel == 0) ? 0 : (4 - nextLevel);
        Log.i(TAG, String.format("Right seat: UI level %d -> Vehicle API level %d", nextLevel, vehicleLevel));

        // Send command to vehicle
        if (heatingControlService != null && heatingControlService.isConnected()) {
            heatingControlService.setPassengerSeatHeating(vehicleLevel);
        } else {
            Log.w(TAG, "Heating service not connected, command not sent");
        }
    }

    private void toggleWheel() {
        // Toggle state: OFF (false) <-> ON (true)
        boolean nextState = !wheelHeating;
        int nextLevel = nextState ? 1 : 0;

        // Update UI immediately (optimistic)
        wheelHeating = nextState;
        updateWheelDisplay();
        Log.i(TAG, String.format("Steering wheel heating: %s -> %s (sending level %d)",
                !nextState ? "OFF" : "ON",
                nextState ? "ON" : "OFF",
                nextLevel));

        // Send command to vehicle
        if (heatingControlService != null && heatingControlService.isConnected()) {
            heatingControlService.setSteeringWheelHeating(nextLevel);
        } else {
            Log.w(TAG, "Heating service not connected, command not sent");
        }
    }

    /** Inverts the car's level to the UI's, treating an absent feature as off. */
    private static int toUiLevel(Integer apiLevel) {
        if (apiLevel == null || apiLevel == 0) {
            return 0;
        }
        return 4 - apiLevel;
    }

    /**
     * Shows only the heating controls this car actually has, and hides the whole
     * pill when it has none.
     *
     * <p>
     * On an SE that is all three: the buttons were visible, changed icon when
     * pressed, and did nothing, because the level came back null and was being
     * read as 0. {@link LauncherPrefs#isHeatingHidden} is the manual override for
     * a car that answers 0 instead of null, where there is no way to tell "not
     * fitted" from "fitted and off".
     */
    private void applyHeatingAvailability() {
        boolean forceHidden = LauncherPrefs.isHeatingHidden(this);

        boolean left = hasLeftSeatHeating && !forceHidden;
        boolean wheel = hasWheelHeating && !forceHidden;
        boolean right = hasRightSeatHeating && !forceHidden;

        setVisible(R.id.leftSeatButton, left);
        setVisible(R.id.wheelButton, wheel);
        setVisible(R.id.rightSeatButton, right);
        // Dividers only earn their place between two visible segments.
        setVisible(R.id.heatingDividerLeft, left && (wheel || right));
        setVisible(R.id.heatingDividerRight, wheel && right);
        setVisible(R.id.heatingPill, left || wheel || right);
    }

    private void setVisible(int viewId, boolean visible) {
        View view = findViewById(viewId);
        if (view != null) {
            view.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void updateSeatDisplay(ImageView icon, int level) {
        switch (level) {
            case 0:
                icon.setImageResource(R.drawable.ic_heated_seat_off);
                break;
            case 1:
                icon.setImageResource(R.drawable.ic_heated_seat_level1);
                break;
            case 2:
                icon.setImageResource(R.drawable.ic_heated_seat_level2);
                break;
            case 3:
                icon.setImageResource(R.drawable.ic_heated_seat_level3);
                break;
        }
    }

    private void updateWheelDisplay() {
        if (wheelHeating) {
            wheelIcon.setImageResource(R.drawable.ic_heated_wheel_on);
        } else {
            wheelIcon.setImageResource(R.drawable.ic_heated_wheel_off);
        }
    }

    private void checkNotificationListenerPermission() {
        // Check if notification listener permission is granted
        if (!isNotificationServiceEnabled()) {
            // Show dialog to guide user to settings
            new AlertDialog.Builder(this)
                    .setTitle("Media Controls Setup")
                    .setMessage(
                            "To enable media playback controls, please allow notification access in Settings.\n\nSettings → Apps → Custom Launcher → Notifications → Notification access")
                    .setPositiveButton("Open Settings", (dialog, which) -> {
                        Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                        startActivity(intent);
                    })
                    .setNegativeButton("Skip", null)
                    .show();
        }
    }

    private boolean isNotificationServiceEnabled() {
        String pkgName = getPackageName();
        final String flat = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        if (!TextUtils.isEmpty(flat)) {
            final String[] names = flat.split(":");
            for (String name : names) {
                final ComponentName cn = ComponentName.unflattenFromString(name);
                if (cn != null) {
                    if (TextUtils.equals(pkgName, cn.getPackageName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void setupVehicleService() {
        // Battery, range, power and consumption are owned by EnergyTileController,
        // which reads AAOS CarService's BMS properties directly.

        // Initialize heating control service
        heatingControlService = new HeatingControlService(this);
        heatingControlService.setStatusListener(new HeatingControlService.HeatingStatusListener() {
            @Override
            public void onHeatingStatusChanged(Integer drvSeatLevel, Integer psgSeatLevel,
                    Integer wheelLevel) {
                // SAIC API is inverted: API 1=High, 2=Med, 3=Low, so invert for UI.
                // A null level means the car has no such hardware, and that
                // segment of the pill disappears rather than pretending to work.
                runOnUiThread(() -> {
                    hasLeftSeatHeating = drvSeatLevel != null;
                    hasRightSeatHeating = psgSeatLevel != null;
                    hasWheelHeating = wheelLevel != null;

                    leftSeatLevel = toUiLevel(drvSeatLevel);
                    rightSeatLevel = toUiLevel(psgSeatLevel);
                    wheelHeating = wheelLevel != null && wheelLevel > 0;

                    updateSeatDisplay(leftSeatIcon, leftSeatLevel);
                    updateSeatDisplay(rightSeatIcon, rightSeatLevel);
                    updateWheelDisplay();
                    applyHeatingAvailability();
                });
            }

            @Override
            public void onConnectionStatusChanged(boolean connected) {
                if (connected) {
                    Log.i(TAG, "[RETRY] Heating service connected successfully");
                    stopRetryLoop();
                } else {
                    Log.w(TAG, "[RETRY] Heating service connection failed, will retry");
                    startRetryLoop();
                }
            }
        });

        heatingControlService.bind();

        // Initialize CarPlay service
        carPlayService = new CarPlayService(this);
        carPlayService.setConnectionListener(new CarPlayService.ConnectionListener() {
            @Override
            public void onCarPlayConnectionChanged(boolean isConnected) {
                runOnUiThread(() -> updateCarPlayIcon(isConnected));
            }

            @Override
            public void onAndroidAutoConnectionChanged(boolean isConnected) {
                Log.i(TAG, "Android Auto connection: " + isConnected);
            }
        });
        carPlayService.bind();
    }

    private void startRetryLoop() {
        Log.i(TAG, "[RETRY] Starting retry loop");
        retryHandler.removeCallbacks(retryRunnable);
        retryHandler.postDelayed(retryRunnable, RETRY_INTERVAL_MS);
    }

    private void stopRetryLoop() {
        Log.i(TAG, "[RETRY] Stopping retry loop");
        retryHandler.removeCallbacks(retryRunnable);
    }

    // scheduleVehicleServiceRetry() is now replaced by
    // startRetryLoop()/stopRetryLoop()

    /**
     * Creates a desaturation color filter
     * 
     * @param saturation 0.0 = grayscale, 1.0 = original colors
     */
    private android.graphics.ColorMatrixColorFilter createDesaturateFilter(float saturation) {
        android.graphics.ColorMatrix colorMatrix = new android.graphics.ColorMatrix();
        colorMatrix.setSaturation(saturation);
        return new android.graphics.ColorMatrixColorFilter(colorMatrix);
    }

    /**
     * Creates a blurred bitmap from the source bitmap
     */
    private android.graphics.Bitmap createBlurredBitmap(android.graphics.Bitmap source) {
        try {
            // Create a smaller bitmap for better performance
            int width = Math.round(source.getWidth() * 0.25f);
            int height = Math.round(source.getHeight() * 0.25f);
            android.graphics.Bitmap scaledBitmap = android.graphics.Bitmap.createScaledBitmap(
                    source, width, height, true);

            // Apply RenderScript blur
            android.renderscript.RenderScript rs = android.renderscript.RenderScript.create(this);
            android.renderscript.Allocation input = android.renderscript.Allocation.createFromBitmap(
                    rs, scaledBitmap);
            android.renderscript.Allocation output = android.renderscript.Allocation.createTyped(
                    rs, input.getType());
            android.renderscript.ScriptIntrinsicBlur script = android.renderscript.ScriptIntrinsicBlur.create(
                    rs, android.renderscript.Element.U8_4(rs));
            script.setRadius(25f); // Max blur radius
            script.setInput(input);
            script.forEach(output);
            output.copyTo(scaledBitmap);

            rs.destroy();
            return scaledBitmap;
        } catch (Exception e) {
            Log.e(TAG, "Failed to create blurred bitmap: " + e.getMessage());
            return source; // Return original on error
        }
    }


    // --- floating map window ---

    /**
     * Puts the chosen navigation app in a floating window over the media player,
     * and turns the GPS tile into the compact player.
     *
     * <p>
     * The window is placed on the media card's own screen rectangle rather than a
     * fixed size, so it lines up with the tile it replaces whatever the layout
     * does. See {@link FloatingAppController} for why this is freeform windowing
     * and not picture-in-picture - short version: this ROM ships no PiP feature
     * and OsmAnd never asks for PiP anyway.
     */
    private void startFloatingMap() {
        String pkg = LauncherPrefs.getNavPackage(this);
        if (pkg == null) {
            Toast.makeText(this, "Pick a navigation app first", Toast.LENGTH_SHORT).show();
            pickNavigationApp();
            return;
        }
        if (musicCard == null) {
            return;
        }

        Rect bounds = screenRectOf(musicCard);
        if (bounds.isEmpty()) {
            // Called before layout; nothing sensible to place a window on yet.
            Log.w(TAG, "Media card has no bounds yet; not floating the map");
            return;
        }

        String failure = FloatingAppController.show(this, pkg, bounds);
        if (failure != null) {
            Log.w(TAG, "Floating map refused: " + failure);
            Toast.makeText(this, failure, Toast.LENGTH_LONG).show();
            return;
        }

        floatingMapPackage = pkg;
        setFloatingMapActive(true);

        // The launch options are the clean route; this is the repair for a
        // platform that ignores them and brings the app up fullscreen. It checks
        // the task's real windowing mode first and leaves a correct window alone.
        retryHandler.postDelayed(this::refloatMap, FLOAT_REPAIR_DELAY_MS);
    }

    /** Re-applies the window mode and bounds; harmless when already correct. */
    private void refloatMap() {
        if (!floatingMapActive || floatingMapPackage == null || musicCard == null) {
            return;
        }
        Rect bounds = screenRectOf(musicCard);
        if (!bounds.isEmpty()) {
            FloatingAppController.forceFloat(this, floatingMapPackage, bounds);
        }
    }

    /** Puts the map away and gives the full player and the GPS readout back. */
    private void stopFloatingMap() {
        if (floatingMapPackage != null) {
            FloatingAppController.hide(this, floatingMapPackage);
        }
        floatingMapPackage = null;
        setFloatingMapActive(false);
    }

    private void setFloatingMapActive(boolean active) {
        floatingMapActive = active;

        // The card underneath is deliberately left visible. Hiding it meant that
        // any failure to draw the window - and on the first on-car run the window
        // did vanish a second after appearing - left a black hole where the player
        // had been. Leaving it up costs nothing when the window does cover it, and
        // degrades to "the player is still there" when it does not.
        if (gpsFace != null) {
            gpsFace.setVisibility(active ? View.GONE : View.VISIBLE);
        }
        if (miniPlayerFace != null) {
            miniPlayerFace.setVisibility(active ? View.VISIBLE : View.GONE);
        }

        View card = findViewById(R.id.gpsCard);
        if (card != null) {
            if (active) {
                // Tapping anywhere that is not a transport button dismisses the
                // map; the buttons keep their own clicks and still work.
                card.setOnClickListener(v -> stopFloatingMap());
                card.setOnLongClickListener(null);
            } else if (gpsTile != null) {
                gpsTile.installClickHandlers();
            }
        }

        if (active) {
            updateMiniPlayer();
        }
        Log.i(TAG, "Floating map " + (active ? "shown" : "hidden"));
    }

    private static Rect screenRectOf(View view) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return new Rect(location[0], location[1],
                location[0] + view.getWidth(), location[1] + view.getHeight());
    }

    /** Mirrors the big player's current track onto the compact one. */
    private void updateMiniPlayer() {
        if (miniTitle == null) {
            return;
        }
        miniTitle.setText(songTitle != null && songTitle.getText().length() > 0
                ? songTitle.getText() : "Nothing playing");
        miniArtist.setText(artistName != null ? artistName.getText() : "");
        miniArtist.setVisibility(miniArtist.getText().length() == 0 ? View.GONE : View.VISIBLE);
        miniPlayPauseButton.setImageResource(
                isMediaPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    private void setupMediaService() {
        MediaListenerService.setListener((title, artist, isPlaying, albumArtBitmap) -> {
            runOnUiThread(() -> {
                songTitle.setText(title);
                artistName.setText(artist);
                isMediaPlaying = isPlaying;

                // Sync our toggle state with the actual state when it changes externally
                lastCommandWasPlay = isPlaying;

                updatePlayPauseButton();
                updateMediaProgress();
                updateMediaSourceLabel();

                // Update album art with blur background and desaturated foreground
                if (albumArtBitmap != null) {
                    // Set blurred background (stretched to fill)
                    android.graphics.Bitmap blurredBitmap = createBlurredBitmap(albumArtBitmap);
                    albumArtBlurred.setImageBitmap(blurredBitmap);
                    albumArtBlurred.setColorFilter(createDesaturateFilter(0.5f)); // More desaturated

                    // Set foreground album art (fit properly) with more vibrant colors
                    albumArt.setImageBitmap(albumArtBitmap);
                    albumArt.setColorFilter(createDesaturateFilter(0.95f)); // More saturated/vibrant
                } else {
                    albumArtBlurred.setImageDrawable(null);
                    albumArtBlurred.setColorFilter(null);
                    albumArt.setImageDrawable(null);
                    albumArt.setColorFilter(null);
                }

                if (miniAlbumArt != null) {
                    // Same bitmap, no blur or desaturation: at 64dp those effects
                    // only muddy it.
                    miniAlbumArt.setImageBitmap(albumArtBitmap);
                }
                if (floatingMapActive) {
                    updateMiniPlayer();
                }
            });
        });
    }

    private void updateActiveMediaController() {
        // Get controller from MediaListenerService which already has access
        MediaController newController = MediaListenerService.getActiveController();

        // Keep the old controller if new one is null (session might have become
        // inactive but is still valid)
        if (newController != null) {
            activeMediaController = newController;
        }

        // Log.i(TAG, "updateActiveMediaController: " + activeMediaController);
    }

    /**
     * Asks the car to make the tile's current source active, so a following
     * {@code play()} is not silently ignored.
     *
     * <p>
     * Best-effort and fire-and-forget: if the source is already active the stock
     * services treat it as a no-op, and if the package is not on this trim
     * {@link SaicSourceSwitch} logs and returns false.
     */
    private void requestSourceForActiveController() {
        String pkg = activeMediaController != null
                ? activeMediaController.getPackageName() : null;
        if (pkg == null) {
            return;
        }
        if (MediaSources.isBluetooth(pkg)) {
            SaicSourceSwitch.playBluetooth(this);
        } else if (MediaSources.isRadio(pkg)) {
            SaicSourceSwitch.playRadio(this);
        }
    }

    private void sendMediaButtonCommand(int keyCode) {
        // Get active controller from MediaListenerService
        updateActiveMediaController();

        Log.i(TAG, "sendMediaButtonCommand: keyCode=" + keyCode + ", controller=" + activeMediaController);

        if (activeMediaController != null) {
            // Use MediaController transport controls
            MediaController.TransportControls controls = activeMediaController.getTransportControls();
            PlaybackState state = activeMediaController.getPlaybackState();

            Log.i(TAG, "Current playback state: " + (state != null ? state.getState() : "null"));

            switch (keyCode) {
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    Log.i(TAG, "Sending skipToPrevious");
                    controls.skipToPrevious();
                    break;
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    // Check actual state - if stopped/paused, resume; if playing, pause
                    boolean shouldPause = state != null &&
                            (state.getState() == PlaybackState.STATE_PLAYING ||
                                    state.getState() == PlaybackState.STATE_BUFFERING);

                    if (shouldPause) {
                        Log.i(TAG, "Currently playing, sending pause");
                        controls.pause();
                        lastCommandWasPlay = false;
                        isMediaPlaying = false;
                    } else {
                        Log.i(TAG, "Currently stopped/paused, requesting play");
                        // A session that is not the car's active source ignores
                        // play() outright - on the car this left the Bluetooth
                        // session sitting in STATE_PAUSED however often it was
                        // pressed, until the source was switched in the stock
                        // launcher. So claim the source first, then play. See
                        // SaicSourceSwitch for why Bluetooth is addressed as
                        // com.saicmotor.media.
                        requestSourceForActiveController();
                        controls.play();
                        lastCommandWasPlay = true;
                        isMediaPlaying = true;
                    }
                    updatePlayPauseButton();
                    break;
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    Log.i(TAG, "Sending skipToNext");
                    controls.skipToNext();
                    break;
            }
        } else {
            Log.w(TAG, "No active MediaController available");
        }
    }

    private void startProgressUpdates() {
        progressHandler = new Handler(Looper.getMainLooper());
        progressRunnable = new Runnable() {
            @Override
            public void run() {
                if (isMediaPlaying) {
                    updateMediaProgress();
                }
                progressHandler.postDelayed(this, 500); // Update every 500ms for smooth progress
            }
        };
        progressHandler.post(progressRunnable);
    }

    /**
     * Lets the user choose which app the GPS tile opens.
     *
     * <p>
     * A picker rather than a default, because this trim ships no maps app at all
     * — anything installed got sideloaded, and only the owner knows whether they
     * want ABRP, a route planner, or something else. Package-visible so both the
     * tile and the launcher menu can raise it.
     */
    void pickNavigationApp() {
        PackageManager pm = getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN);
        probe.addCategory(Intent.CATEGORY_LAUNCHER);

        java.util.List<android.content.pm.ResolveInfo> candidates =
                pm.queryIntentActivities(probe, 0);
        java.util.List<String> packages = new java.util.ArrayList<>();
        java.util.List<String> labels = new java.util.ArrayList<>();
        for (android.content.pm.ResolveInfo info : candidates) {
            String pkg = info.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || packages.contains(pkg)) {
                continue;
            }
            packages.add(pkg);
            labels.add(info.loadLabel(pm).toString());
        }
        if (packages.isEmpty()) {
            Toast.makeText(this, "No launchable apps found", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Navigation app for the GPS tile")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    LauncherPrefs.setNavPackage(this, packages.get(which));
                    Log.i(TAG, "Navigation app set to " + packages.get(which));
                    if (gpsTile != null) {
                        gpsTile.onNavAppChanged();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Package-visible so EnergyTileController can drive the fill bar. */
    void updateBatteryFill(int level) {

        // Update visual battery fill width (accounting for container padding) for
        // horizontal layout
        batteryFill.post(() -> {
            android.view.ViewGroup.LayoutParams params = batteryFill.getLayoutParams();
            // Container is 110dp wide minus 8dp padding (4dp each side) = 102dp usable
            // width
            android.view.View container = (android.view.View) batteryFill.getParent();
            int containerWidth = container.getWidth();
            int paddingHorizontal = container.getPaddingStart() + container.getPaddingEnd();
            int usableWidth = containerWidth - paddingHorizontal;
            params.width = (int) (usableWidth * level / 100f);
            batteryFill.setLayoutParams(params);

            // Update battery fill color with smooth gradient transition
            // Green (#30d158) at 50%+, transitioning through orange to red (#ff453a) at 0%
            int color;
            if (level > 50) {
                // Stay green above 50%
                color = 0xFF30D158;
            } else {
                // Much faster transition: green -> orange -> red between 50% and 0%
                // Use stronger power curve to shift colors much faster toward red
                float linearFactor = (50 - level) / 50f; // 0.0 at 50%, 1.0 at 0%
                float factor = (float) Math.pow(linearFactor, 0.4); // Power curve 0.4 for much faster transition

                // Green color: #30d158 (R:48, G:209, B:88)
                // Red color: #ff453a (R:255, G:69, B:58)
                int startR = 48, startG = 209, startB = 88;
                int endR = 255, endG = 69, endB = 58;

                // Interpolate each color channel with the power curve
                int r = (int) (startR + (endR - startR) * factor);
                int g = (int) (startG + (endG - startG) * factor);
                int b = (int) (startB + (endB - startB) * factor);

                color = 0xFF000000 | (r << 16) | (g << 8) | b;
            }

            // Create a GradientDrawable to maintain rounded corners while changing color
            android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
            drawable.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            drawable.setColor(color);
            drawable.setCornerRadius(8 * getResources().getDisplayMetrics().density); // 8dp radius to match outline
            batteryFill.setBackground(drawable);
        });
    }

    private void updatePlayPauseButton() {
        // Update play/pause icon based on state
        playPauseButton.setImageResource(isMediaPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
        if (miniPlayPauseButton != null) {
            miniPlayPauseButton.setImageResource(
                    isMediaPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
        }
    }

    private void updateMediaProgress() {
        updateActiveMediaController();

        if (activeMediaController != null) {
            PlaybackState state = activeMediaController.getPlaybackState();
            android.media.MediaMetadata metadata = activeMediaController.getMetadata();

            if (state != null) {
                long position = state.getPosition();
                long duration = -1;

                // Get duration from metadata (most reliable source)
                if (metadata != null) {
                    duration = metadata.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION);
                }

                // Update UI
                currentTime.setText(formatTime(position));

                if (duration > 0 && position >= 0) {
                    // We have a duration - show actual progress and remaining time
                    progressBar.setVisibility(View.VISIBLE);
                    currentTime.setVisibility(View.VISIBLE);
                    totalTime.setVisibility(View.VISIBLE);
                    int progress = (int) ((position * 100) / duration);
                    progressBar.setProgress(Math.min(100, Math.max(0, progress)));

                    // Show remaining time as countdown (negative)
                    long remaining = Math.max(0, duration - position);
                    totalTime.setText("-" + formatTime(remaining));
                } else if (duration <= 0) {
                    // No duration (live stream/radio) - hide seekbar and time labels
                    progressBar.setProgress(position > 0 ? 50 : 0);
                    totalTime.setText("Live");
                    progressBar.setVisibility(View.GONE);
                    currentTime.setVisibility(View.GONE);
                    totalTime.setVisibility(View.GONE);
                } else {
                    // Position not available yet, show placeholder
                    progressBar.setVisibility(View.VISIBLE);
                    currentTime.setVisibility(View.VISIBLE);
                    totalTime.setVisibility(View.VISIBLE);
                    progressBar.setProgress(0);
                    totalTime.setText("-0:00");
                }
            } else {
                // Reset to defaults
                progressBar.setVisibility(View.VISIBLE);
                currentTime.setVisibility(View.VISIBLE);
                totalTime.setVisibility(View.VISIBLE);
                progressBar.setProgress(0);
                currentTime.setText("0:00");
                totalTime.setText("-0:00");
            }
        } else {
            progressBar.setVisibility(View.VISIBLE);
            currentTime.setVisibility(View.VISIBLE);
            totalTime.setVisibility(View.VISIBLE);
            progressBar.setProgress(0);
            currentTime.setText("0:00");
            totalTime.setText("-0:00");
        }
    }

    private String formatTime(long milliseconds) {
        int totalSeconds = (int) (milliseconds / 1000);
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return String.format(java.util.Locale.US, "%d:%02d", minutes, seconds);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (hvacTile != null) {
            hvacTile.onDestroy();
        }

        if (energyTile != null) {
            energyTile.onDestroy();
        }

        if (gpsTile != null) {
            gpsTile.onDestroy();
        }

        if (heatingControlService != null) {
            heatingControlService.release();
        }

        if (carPlayService != null) {
            carPlayService.unbind();
            carPlayService = null;
        }

        if (progressHandler != null && progressRunnable != null) {
            progressHandler.removeCallbacks(progressRunnable);
        }

        if (retryHandler != null && retryRunnable != null) {
            retryHandler.removeCallbacks(retryRunnable);
        }
        // Debug dialog removed - LogViewerActivity handles its own lifecycle
    }

    @Override
    public void onBackPressed() {
        // Don't allow back button to exit launcher
        // Call super to satisfy lint, but it won't actually exit since this is a
        // launcher
        super.onBackPressed();
    }

    /**
     * Handle clock tap for debug dialog (triple-tap to open)
     */
    private void handleClockTap() {
        long currentTime = System.currentTimeMillis();

        if (currentTime - lastClockTapTime > TAP_TIMEOUT) {
            // Reset if too much time passed
            clockTapCount = 1;
        } else {
            clockTapCount++;
        }

        lastClockTapTime = currentTime;

        if (clockTapCount >= 3) {
            clockTapCount = 0;
            showDebugDialog();
        }
    }

    /**
     * Show debug log viewer in full-screen activity (triple-tap clock to activate)
     */
    private void showDebugDialog() {
        Intent intent = new Intent(MainActivity.this, LogViewerActivity.class);
        startActivity(intent);
    }

    /**
     * Update ADB diagnostics display
     */
    private void updateAdbDiagnostics() {
        new Thread(() -> {
            try {
                StringBuilder diag = new StringBuilder();
                diag.append("=== ADB DIAGNOSTICS ===").append("\n\n");

                // Check ADB status via Settings
                String adbEnabled = "unknown";
                try {
                    int adb = Settings.Global.getInt(getContentResolver(), Settings.Global.ADB_ENABLED);
                    adbEnabled = adb == 1 ? "ENABLED" : "DISABLED";
                } catch (Exception e) {
                    adbEnabled = "error: " + e.getMessage();
                }
                diag.append("ADB Settings: ").append(adbEnabled).append("\n");

                // Check USB configuration
                String usbConfig = getSystemProperty("sys.usb.config");
                diag.append("USB Config: ").append(usbConfig).append("\n");
                diag.append("Has ADB: ").append(usbConfig != null && usbConfig.contains("adb") ? "YES" : "NO")
                        .append("\n");

                // Check persist config
                String persistConfig = getSystemProperty("persist.sys.usb.config");
                diag.append("Persist Config: ").append(persistConfig).append("\n");

                // Check USB state
                String usbState = getSystemProperty("sys.usb.state");
                diag.append("USB State: ").append(usbState).append("\n");

                // Check adbd service
                String adbdService = getSystemProperty("init.svc.adbd");
                diag.append("adbd Service: ").append(adbdService).append("\n\n");

                // Check network interfaces
                diag.append("=== NETWORK INTERFACES ===").append("\n");
                Process ifconfig = Runtime.getRuntime().exec("ip addr show");
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(ifconfig.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("usb") || line.contains("inet ")) {
                        diag.append(line.trim()).append("\n");
                    }
                }
                reader.close();
                ifconfig.destroy();
                diag.append("\n");

                // Check iptables rules (may require root)
                diag.append("=== IPTABLES RULES ===").append("\n");
                try {
                    Process iptables = Runtime.getRuntime().exec(new String[] { "iptables", "-L", "INPUT", "-n" });
                    reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(iptables.getInputStream()));
                    boolean foundAdb = false;
                    while ((line = reader.readLine()) != null) {
                        if (line.contains("5555") || line.contains("adb")) {
                            diag.append(line.trim()).append("\n");
                            foundAdb = true;
                        }
                    }
                    if (!foundAdb) {
                        diag.append("No ADB-related rules found\n");
                    }
                    reader.close();
                    iptables.destroy();
                } catch (Exception e) {
                    diag.append("Cannot read iptables: ").append(e.getMessage()).append("\n");
                }
                diag.append("\n");

                // Check SELinux status
                diag.append("=== SELINUX STATUS ===").append("\n");
                String selinux = getSystemProperty("ro.build.selinux");
                diag.append("SELinux Build: ").append(selinux).append("\n");
                try {
                    Process getenforce = Runtime.getRuntime().exec("getenforce");
                    reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(getenforce.getInputStream()));
                    String mode = reader.readLine();
                    diag.append("Current Mode: ").append(mode != null ? mode : "unknown").append("\n");
                    reader.close();
                    getenforce.destroy();
                } catch (Exception e) {
                    diag.append("Cannot check enforce: ").append(e.getMessage()).append("\n");
                }
                diag.append("\n");

                // Check system security
                diag.append("=== SYSTEM SECURITY ===").append("\n");
                diag.append("ro.secure: ").append(getSystemProperty("ro.secure")).append("\n");
                diag.append("ro.adb.secure: ").append(getSystemProperty("ro.adb.secure")).append("\n");
                diag.append("ro.debuggable: ").append(getSystemProperty("ro.debuggable")).append("\n");

                String finalDiag = diag.toString();
                final String finalAdbEnabled = adbEnabled;
                final String finalUsbConfig = usbConfig;
                runOnUiThread(() -> {
                    if (adbStatusView != null) {
                        adbStatusView.setText(finalDiag);
                        // Update button text based on ADB status
                        if (enableAdbButton != null) {
                            if (finalAdbEnabled.equals("ENABLED") && finalUsbConfig != null
                                    && finalUsbConfig.contains("adb")) {
                                enableAdbButton.setText("ADB Active");
                                enableAdbButton.setEnabled(false);
                            } else {
                                enableAdbButton.setText("Enable ADB");
                                enableAdbButton.setEnabled(true);
                            }
                        }
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Error updating ADB diagnostics", e);
                runOnUiThread(() -> {
                    if (adbStatusView != null) {
                        adbStatusView.setText("Error loading diagnostics: " + e.getMessage());
                    }
                });
            }
        }).start();
    }

    /**
     * Attempt to enable ADB using multiple approaches
     */
    private void attemptEnableAdb() {
        if (enableAdbButton != null) {
            enableAdbButton.setEnabled(false);
            enableAdbButton.setText("Enabling...");
        }

        new Thread(() -> {
            StringBuilder result = new StringBuilder();
            result.append("=== ATTEMPTING TO ENABLE ADB ===").append("\n\n");

            int successCount = 0;
            int totalAttempts = 0;

            // Approach 1: Enable ADB via Settings.Global
            totalAttempts++;
            result.append("[1] Settings.Global.ADB_ENABLED...\n");
            try {
                Settings.Global.putInt(getContentResolver(), Settings.Global.ADB_ENABLED, 1);
                int check = Settings.Global.getInt(getContentResolver(), Settings.Global.ADB_ENABLED);
                if (check == 1) {
                    result.append("✓ SUCCESS: ADB enabled in settings\n\n");
                    successCount++;
                } else {
                    result.append("✗ FAILED: Setting did not persist\n\n");
                }
            } catch (Exception e) {
                result.append("✗ FAILED: ").append(e.getMessage()).append("\n\n");
            }

            // Approach 2: Set USB configuration to include ADB
            totalAttempts++;
            result.append("[2] SystemProperties: persist.sys.usb.config...\n");
            try {
                String currentConfig = getSystemProperty("persist.sys.usb.config");
                String newConfig = currentConfig != null && !currentConfig.isEmpty()
                        ? (currentConfig.contains("adb") ? currentConfig : currentConfig + ",adb")
                        : "mtp,adb";
                setSystemProperty("persist.sys.usb.config", newConfig);
                Thread.sleep(500);
                String check = getSystemProperty("persist.sys.usb.config");
                if (check != null && check.contains("adb")) {
                    result.append("✓ SUCCESS: USB config set to ").append(check).append("\n\n");
                    successCount++;
                } else {
                    result.append("✗ FAILED: Property did not change\n\n");
                }
            } catch (Exception e) {
                result.append("✗ FAILED: ").append(e.getMessage()).append("\n\n");
            }

            // Approach 3: Trigger USB configuration change
            totalAttempts++;
            result.append("[3] SystemProperties: sys.usb.config...\n");
            try {
                setSystemProperty("sys.usb.config", "mtp,adb");
                Thread.sleep(500);
                String check = getSystemProperty("sys.usb.config");
                if (check != null && check.contains("adb")) {
                    result.append("✓ SUCCESS: Active USB config includes ADB\n\n");
                    successCount++;
                } else {
                    result.append("✗ FAILED: Config = ").append(check).append("\n\n");
                }
            } catch (Exception e) {
                result.append("✗ FAILED: ").append(e.getMessage()).append("\n\n");
            }

            // Approach 4: Remove iptables firewall rule (if exists)
            totalAttempts++;
            result.append("[4] iptables: Remove ADB block...\n");
            try {
                Process iptables = Runtime.getRuntime().exec(new String[] {
                        "iptables", "-D", "INPUT", "-p", "tcp", "--dport", "5555", "-j", "DROP"
                });
                int exitCode = iptables.waitFor();
                if (exitCode == 0) {
                    result.append("✓ SUCCESS: Firewall rule removed\n\n");
                    successCount++;
                } else {
                    result.append("✗ FAILED: iptables returned code ").append(exitCode).append("\n\n");
                }
            } catch (Exception e) {
                result.append("✗ FAILED: ").append(e.getMessage()).append("\n\n");
            }

            // Approach 5: Start adbd service
            totalAttempts++;
            result.append("[5] Start adbd service...\n");
            try {
                setSystemProperty("ctl.start", "adbd");
                Thread.sleep(1000);
                String check = getSystemProperty("init.svc.adbd");
                if ("running".equals(check)) {
                    result.append("✓ SUCCESS: adbd service started\n\n");
                    successCount++;
                } else {
                    result.append("✗ FAILED: Service state = ").append(check).append("\n\n");
                }
            } catch (Exception e) {
                result.append("✗ FAILED: ").append(e.getMessage()).append("\n\n");
            }

            // Approach 6: Use alternative ADB port (bypass firewall on 5555)
            totalAttempts++;
            result.append("[6] Alternative ADB ports (bypass firewall)...\n");
            int altPort = 0;
            try {
                // Try alternative ports: 5556, 5557, 5558
                for (int port : new int[] { 5556, 5557, 5558 }) {
                    setSystemProperty("service.adb.tcp.port", String.valueOf(port));
                    Thread.sleep(500);
                    String check = getSystemProperty("service.adb.tcp.port");
                    if (String.valueOf(port).equals(check)) {
                        altPort = port;
                        break;
                    }
                }

                if (altPort > 0) {
                    // Restart adbd to apply new port
                    setSystemProperty("ctl.restart", "adbd");
                    Thread.sleep(1000);
                    String adbdState = getSystemProperty("init.svc.adbd");
                    if ("running".equals(adbdState)) {
                        result.append("✓ SUCCESS: ADB listening on port ").append(altPort).append("\n");
                        result.append("  (bypasses firewall on default port 5555)\n\n");
                        successCount++;
                    } else {
                        result.append("✗ FAILED: Port set but adbd not running\n\n");
                    }
                } else {
                    result.append("✗ FAILED: Could not set alternative port\n\n");
                }
            } catch (Exception e) {
                result.append("✗ FAILED: ").append(e.getMessage()).append("\n\n");
            }

            // Summary
            result.append("===========================\n");
            result.append("SUMMARY: ").append(successCount).append("/").append(totalAttempts)
                    .append(" approaches succeeded\n\n");

            if (successCount > 0) {
                result.append("✓ Some methods succeeded!\n");
                result.append("Try connecting with:\n");
                if (altPort > 0) {
                    result.append("  adb connect <car-ip>:").append(altPort).append("\n");
                    result.append("  (using alternative port to bypass firewall)\n\n");
                } else {
                    result.append("  adb connect <car-ip>:5555\n\n");
                }
            } else {
                result.append("✗ All methods failed.\n");
                result.append("Possible causes:\n");
                result.append("• SELinux blocking modifications\n");
                result.append("• Vendor-specific security policies\n");
                result.append("• Need root access\n");
                result.append("• USB hardware locked to host mode\n\n");
            }

            String finalResult = result.toString();
            final int finalSuccessCount = successCount;
            runOnUiThread(() -> {
                if (adbStatusView != null) {
                    adbStatusView.setText(finalResult);
                }
                if (enableAdbButton != null) {
                    enableAdbButton.setEnabled(true);
                    enableAdbButton.setText(finalSuccessCount > 0 ? "Try Again" : "Enable ADB");
                }
                Toast.makeText(MainActivity.this,
                        finalSuccessCount > 0 ? "Some methods succeeded!" : "All methods failed",
                        Toast.LENGTH_LONG).show();

                // Refresh diagnostics after 2 seconds
                new Handler(Looper.getMainLooper()).postDelayed(() -> updateAdbDiagnostics(), 2000);
            });

        }).start();
    }

    /**
     * Get system property via reflection
     */
    private String getSystemProperty(String key) {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = systemProperties.getMethod("get", String.class);
            String value = (String) get.invoke(null, key);
            return value != null && !value.isEmpty() ? value : "(not set)";
        } catch (Exception e) {
            return "(error)";
        }
    }

    /**
     * Set system property via reflection
     */
    private void setSystemProperty(String key, String value) throws Exception {
        Class<?> systemProperties = Class.forName("android.os.SystemProperties");
        java.lang.reflect.Method set = systemProperties.getMethod("set", String.class, String.class);
        set.invoke(null, key, value);
        Log.i(TAG, "Set system property: " + key + " = " + value);
    }

    /**
     * Create context menu for clock long press
     */
    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        getMenuInflater().inflate(R.menu.launcher_menu, menu);
    }

    /**
     * Handle context menu item selection
     */
    @Override
    public boolean onContextItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_shell) {
            openShellActivity();
            return true;
        } else if (id == R.id.menu_usb_debug) {
            openUsbDebugScreen();
            return true;
        } else if (id == R.id.menu_log_viewer) {
            openLogViewer();
            return true;
        }
        return super.onContextItemSelected(item);
    }
}
