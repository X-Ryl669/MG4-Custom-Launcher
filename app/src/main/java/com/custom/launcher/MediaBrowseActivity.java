package com.custom.launcher;

import java.util.ArrayList;
import java.util.List;

import android.content.ComponentName;
import android.content.Intent;
import android.media.MediaDescription;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
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

import com.custom.launcher.media.MediaSources;
import com.custom.launcher.radio.RadioClient;
import com.custom.launcher.saic.SaicSourceSwitch;
import com.custom.launcher.service.MediaListenerService;

/**
 * Full-screen media browser, opened from the browse button on the player tile.
 *
 * <p>
 * One screen serves both sources, because both are plain
 * {@code MediaBrowserService}s: the Bluetooth A2DP-sink browser exposes the
 * phone's AVRCP tree (so album / artist / playlist / virtual-filesystem
 * browsing all come from {@code subscribe()}), and the radio service exposes its
 * station list the same way. See {@link MediaSources}.
 *
 * <p>
 * The top level is a hand-built menu of entry points; below that it is generic
 * tree navigation over whatever the source returns, with a back stack.
 */
public class MediaBrowseActivity extends AppCompatActivity {
    private static final String TAG = "MediaBrowseActivity";

    /** Synthetic ids for the hand-built top-level menu. */
    private static final String ITEM_QUEUE = "__queue__";
    private static final String ITEM_BLUETOOTH = "__bluetooth__";
    private static final String ITEM_PLAY_BLUETOOTH = "__play_bluetooth__";
    private static final String ITEM_CURRENT_RADIO = "__current_radio__";
    private static final String ITEM_SCAN_RADIO = "__scan_radio__";
    private static final String ITEM_CONFIGURE_RADIO = "__configure_radio__";
    /** Prefix for rows that tune a station found by a scan. */
    private static final String ITEM_STATION_PREFIX = "__station_";

    /**
     * How long to wait for a source to answer a subscribe before giving up.
     *
     * <p>
     * {@code MediaBrowser} has no timeout of its own: a service that accepts the
     * subscription and then never calls {@code sendResult} leaves the screen on
     * "Loading…" forever, which is exactly what the radio service does. A watchdog
     * turns that into a message a person can act on.
     */
    private static final long LOAD_TIMEOUT_MS = 8000;

    private static class Row {
        final String id;
        final String title;
        final String subtitle;
        final boolean browsable;
        final boolean playable;
        /** Set for queue rows, which are selected by queue id rather than media id. */
        final Long queueId;

        Row(String id, String title, String subtitle, boolean browsable, boolean playable,
                Long queueId) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.browsable = browsable;
            this.playable = playable;
            this.queueId = queueId;
        }
    }

    /** One level of navigation, so Back can restore where we were. */
    private static class Level {
        final ComponentName source;
        final String parentId;
        final String title;

        Level(ComponentName source, String parentId, String title) {
            this.source = source;
            this.parentId = parentId;
            this.title = title;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final List<Level> stack = new ArrayList<>();

    private ListView list;
    private TextView breadcrumb;
    private TextView emptyText;
    private RowAdapter adapter;

    private MediaBrowser browser;
    private ComponentName connectedSource;
    private String pendingParentId;
    private String subscribedParentId;
    private final android.os.Handler handler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable loadTimeout;

    private RadioClient radio;
    /** Stations from the last scan, indexed by their row id. */
    private final List<RadioClient.Station> scannedStations = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_media_browse);

        list = findViewById(R.id.browseList);
        breadcrumb = findViewById(R.id.browseBreadcrumb);
        emptyText = findViewById(R.id.browseEmpty);
        findViewById(R.id.browseBackButton).setOnClickListener(v -> onBackPressed());

        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> onRowClicked(rows.get(position)));

        radio = new RadioClient(this, radioListener);
        radio.bind();

        showTopLevel();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelLoadTimeout();
        disconnectBrowser();
        if (radio != null) {
            radio.unbind();
            radio = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (stack.isEmpty()) {
            super.onBackPressed();
            return;
        }
        stack.remove(stack.size() - 1);
        if (stack.isEmpty()) {
            disconnectBrowser();
            showTopLevel();
        } else {
            Level level = stack.get(stack.size() - 1);
            openLevel(level, false);
        }
    }

    // --- top level ---

    private void showTopLevel() {
        rows.clear();
        breadcrumb.setText("Browse");
        // Nothing in the tree is on screen any more, so no subscription owns it.
        subscribedParentId = null;

        MediaController controller = MediaListenerService.getActiveController();
        if (controller != null && controller.getQueue() != null && !controller.getQueue().isEmpty()) {
            rows.add(new Row(ITEM_QUEUE, "Up next",
                    controller.getQueue().size() + " tracks in queue", true, false, null));
        }

        rows.add(new Row(ITEM_BLUETOOTH, "Phone library",
                "Albums, artists and folders over Bluetooth", true, false, null));
        rows.add(new Row(ITEM_PLAY_BLUETOOTH, "Play from phone",
                "Make Bluetooth the car's source and start it", false, false, null));
        rows.add(new Row(ITEM_CURRENT_RADIO, currentRadioTitle(),
                "Make radio the source, unmute it and play", false, false, null));
        rows.add(new Row(ITEM_SCAN_RADIO, "Scan for radio stations",
                "Sweeps the band - retunes the radio while it runs", false, false, null));
        rows.add(new Row(ITEM_CONFIGURE_RADIO, "Configure radio",
                "Seek, search, presets and DAB", false, false, null));

        adapter.notifyDataSetChanged();
        updateEmptyState();
    }

    private void onRowClicked(Row row) {
        switch (row.id) {
            case ITEM_QUEUE:
                pushLevel(new Level(null, ITEM_QUEUE, "Up next"));
                return;
            case ITEM_BLUETOOTH:
                pushLevel(new Level(MediaSources.bluetoothBrowser(), null, "Phone library"));
                return;
            case ITEM_PLAY_BLUETOOTH:
                startBluetoothPlayback();
                return;
            case ITEM_CURRENT_RADIO:
                selectRadio();
                return;
            case ITEM_SCAN_RADIO:
                startScan();
                return;
            case ITEM_CONFIGURE_RADIO:
                openRadioApp();
                return;
            default:
                break;
        }

        if (row.id != null && row.id.startsWith(ITEM_STATION_PREFIX)) {
            tuneScannedStation(row.id);
            return;
        }

        if (row.queueId != null) {
            playQueueItem(row.queueId);
        } else if (row.browsable) {
            Level current = stack.isEmpty() ? null : stack.get(stack.size() - 1);
            ComponentName source = current != null ? current.source : MediaSources.bluetoothBrowser();
            pushLevel(new Level(source, row.id, row.title));
        } else if (row.playable) {
            playMediaId(row.id);
        }
    }

    /**
     * Makes Bluetooth the car's audio source and starts it playing.
     *
     * <p>
     * This used to call {@code play()} on the Bluetooth {@code MediaSession} and
     * nothing happened: on the car the session stayed in {@code STATE_PAUSED},
     * because the radio still held the source and the source is not something a
     * media session can take. The real switch is a SAIC mode command —
     * {@link SaicSourceSwitch} has the details, including the surprise that
     * Bluetooth audio is owned by {@code com.saicmotor.media} and not by
     * {@code com.android.bluetooth}.
     *
     * <p>
     * The session {@code play()} still follows, as a belt-and-braces nudge for the
     * case where the source was already Bluetooth and merely paused.
     */
    private void startBluetoothPlayback() {
        boolean switched = SaicSourceSwitch.playBluetooth(this);

        MediaController controller =
                MediaListenerService.getControllerForPackage(MediaSources.BLUETOOTH_PACKAGE);
        if (controller != null) {
            MediaListenerService.selectSource(MediaSources.BLUETOOTH_PACKAGE);
            controller.getTransportControls().play();
        } else {
            Log.w(TAG, "No Bluetooth media session yet; sessions present: "
                    + MediaListenerService.getSessionPackages());
        }

        if (!switched && controller == null) {
            Toast.makeText(this, "Could not reach the media service", Toast.LENGTH_LONG).show();
            return;
        }
        Log.i(TAG, "Requested Bluetooth playback (source command sent=" + switched + ")");
        finish();
    }

    /**
     * Makes radio the car's source, unmutes it and starts it.
     *
     * <p>
     * There is no radio {@code MediaSession} to grab until the radio is actually
     * the source — on the car the session list was just
     * {@code [com.android.bluetooth]}, which is why the previous version of this
     * always reported "Radio has no media session right now". So the source
     * command goes first, and the unmute goes through {@link RadioClient}: the car
     * boots with the radio holding the source but muted, and nothing in the media
     * session API can unmute it.
     */
    private void selectRadio() {
        SaicSourceSwitch.playRadio(this);

        if (radio != null && radio.isReady()) {
            radio.startPlaying();
        } else {
            Log.w(TAG, "RadioService not bound; sent the source command only");
        }

        MediaListenerService.selectSource(MediaSources.RADIO_PACKAGE);
        Log.i(TAG, "Switched the car to radio");
        finish();
    }

    /** "Current radio", with the tuned station's name when the radio will tell us. */
    private String currentRadioTitle() {
        if (radio == null || !radio.isReady()) {
            return "Current radio";
        }
        RadioClient.Station station = radio.getCurrentStation();
        if (station == null) {
            return "Current radio";
        }
        return "Current radio - " + station.displayName();
    }

    /**
     * Asks the radio to sweep the band.
     *
     * <p>
     * A scan is the <em>only</em> way to get a station list off this head unit.
     * {@code RadioMBService} accepts browse subscriptions and answers none — it
     * calls {@code result.detach()} and never {@code sendResult()} — and the
     * presets live in the radio app's private SQLite database with no
     * ContentProvider in front of them, which system uid cannot read either.
     * {@code IRadioManager} has no list getter, only
     * {@code onScanFinished(band, stations)} delivered to a callback. Hence a
     * deliberate, explicitly-labelled action rather than something that happens on
     * opening a screen: it retunes the radio while it runs.
     */
    private void startScan() {
        if (radio == null || !radio.isReady()) {
            Toast.makeText(this, "Radio service is not available", Toast.LENGTH_LONG).show();
            return;
        }
        scannedStations.clear();
        rows.clear();
        adapter.notifyDataSetChanged();
        breadcrumb.setText("Scanning");
        emptyText.setText("Scanning the band…\nThis retunes the radio and takes a moment.");
        emptyText.setVisibility(View.VISIBLE);
        stack.add(new Level(null, ITEM_SCAN_RADIO, "Radio stations"));
        radio.scan();
    }

    private void showScanResults(int band, List<RadioClient.Station> stations) {
        // Guarded because navigating back to this level re-renders from the field
        // itself, and clear()-then-addAll() on the same list would empty it.
        if (stations != scannedStations) {
            scannedStations.clear();
            scannedStations.addAll(stations);
        }

        rows.clear();
        breadcrumb.setText("Radio stations");
        for (int i = 0; i < scannedStations.size(); i++) {
            RadioClient.Station station = scannedStations.get(i);
            rows.add(new Row(ITEM_STATION_PREFIX + i, station.displayName(),
                    station.frequencyLabel(), false, true, null));
        }
        adapter.notifyDataSetChanged();
        if (rows.isEmpty()) {
            emptyText.setText("The scan found no stations on band " + band + ".");
            emptyText.setVisibility(View.VISIBLE);
        } else {
            emptyText.setVisibility(View.GONE);
        }
    }

    private void tuneScannedStation(String rowId) {
        int index;
        try {
            index = Integer.parseInt(rowId.substring(ITEM_STATION_PREFIX.length()));
        } catch (NumberFormatException e) {
            return;
        }
        if (radio == null || index < 0 || index >= scannedStations.size()) {
            return;
        }
        SaicSourceSwitch.playRadio(this);
        radio.tune(scannedStations.get(index));
        radio.startPlaying();
        finish();
    }

    private final RadioClient.Listener radioListener = new RadioClient.Listener() {
        @Override
        public void onRadioReady() {
            // Refresh the "Current radio" caption now that we can name the station.
            if (stack.isEmpty()) {
                showTopLevel();
            }
        }

        @Override
        public void onRadioLost() {
        }

        @Override
        public void onStationChanged(RadioClient.Station station) {
            Log.i(TAG, "Station changed to " + station);
            if (stack.isEmpty()) {
                showTopLevel();
            }
        }

        @Override
        public void onScanStarted(int band) {
            emptyText.setText("Scanning band " + band + "…");
        }

        @Override
        public void onScanFinished(int band, List<RadioClient.Station> stations) {
            showScanResults(band, stations);
        }
    };

    private void openRadioApp() {
        try {
            Intent intent = SaicPackages.buildLaunchIntent(this, SaicPackages.RADIO);
            if (intent == null) {
                Log.w(TAG, "No radio app found on this head unit");
                Toast.makeText(this, "Radio app not found", Toast.LENGTH_SHORT).show();
                return;
            }
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open radio app: " + e.getMessage());
            Toast.makeText(this, "Could not open radio", Toast.LENGTH_SHORT).show();
        }
    }

    // --- queue ---

    private void showQueue() {
        MediaController controller = MediaListenerService.getActiveController();
        if (controller == null) {
            Toast.makeText(this, "Nothing is playing", Toast.LENGTH_SHORT).show();
            return;
        }
        rows.clear();
        breadcrumb.setText("Up next");

        List<android.media.session.MediaSession.QueueItem> queue = controller.getQueue();
        if (queue != null) {
            for (android.media.session.MediaSession.QueueItem item : queue) {
                MediaDescription d = item.getDescription();
                rows.add(new Row(String.valueOf(item.getQueueId()),
                        text(d.getTitle(), "Unknown track"),
                        text(d.getSubtitle(), null),
                        false, true, item.getQueueId()));
            }
        }
        adapter.notifyDataSetChanged();
        updateEmptyState();
    }

    private void playQueueItem(long queueId) {
        MediaController controller = MediaListenerService.getActiveController();
        if (controller == null) {
            return;
        }
        controller.getTransportControls().skipToQueueItem(queueId);
        finish();
    }

    // --- tree navigation ---

    private void pushLevel(Level level) {
        stack.add(level);
        openLevel(level, true);
    }

    private void openLevel(Level level, boolean isNew) {
        breadcrumb.setText(level.title);
        rows.clear();
        adapter.notifyDataSetChanged();
        emptyText.setText("Loading…");
        emptyText.setVisibility(View.VISIBLE);

        if (level.source == null) {
            // Synthetic levels have no MediaBrowser behind them. Re-render from
            // what we already hold rather than pushing another level: showQueue()
            // used to add to the stack itself, so every Back that landed here
            // grew the stack again and Back could never escape.
            if (ITEM_SCAN_RADIO.equals(level.parentId)) {
                showScanResults(-1, scannedStations);
            } else {
                showQueue();
            }
            return;
        }

        pendingParentId = level.parentId;

        if (browser != null && browser.isConnected() && level.source.equals(connectedSource)) {
            subscribeTo(level.parentId != null ? level.parentId : browser.getRoot());
            return;
        }

        disconnectBrowser();
        connectTo(level.source);
    }

    private void connectTo(ComponentName source) {
        connectedSource = source;
        browser = new MediaBrowser(this, source, new MediaBrowser.ConnectionCallback() {
            @Override
            public void onConnected() {
                Log.i(TAG, "Connected to " + source.getClassName());
                String parent = pendingParentId != null ? pendingParentId : browser.getRoot();
                subscribeTo(parent);
            }

            @Override
            public void onConnectionFailed() {
                cancelLoadTimeout();
                Log.w(TAG, "Could not connect to " + source.flattenToShortString());
                showError("This source is not available");
            }

            @Override
            public void onConnectionSuspended() {
                Log.w(TAG, "Connection suspended to " + source.flattenToShortString());
            }
        }, null);

        try {
            browser.connect();
        } catch (Exception e) {
            Log.e(TAG, "connect() threw: " + e.getMessage());
            showError("This source is not available");
        }
    }

    private void subscribeTo(String parentId) {
        if (browser == null || parentId == null) {
            showError("Nothing to show here");
            return;
        }
        // Re-subscribing to the same id would double up callbacks.
        if (subscribedParentId != null) {
            browser.unsubscribe(subscribedParentId);
        }
        subscribedParentId = parentId;

        startLoadTimeout(parentId);

        browser.subscribe(parentId, new MediaBrowser.SubscriptionCallback() {
            @Override
            public void onChildrenLoaded(String parentId, List<MediaBrowser.MediaItem> children) {
                // A big folder arrives in batches - one on-car folder streamed 420
                // items across eleven callbacks over three seconds. If the user
                // goes Back mid-stream, the tail of the old folder must not
                // repaint over the level we just returned to.
                if (!parentId.equals(subscribedParentId)) {
                    Log.d(TAG, "Ignoring stale children of " + parentId
                            + "; showing " + subscribedParentId);
                    return;
                }
                cancelLoadTimeout();
                rows.clear();
                for (MediaBrowser.MediaItem item : children) {
                    MediaDescription d = item.getDescription();
                    rows.add(new Row(item.getMediaId(),
                            text(d.getTitle(), "Untitled"),
                            text(d.getSubtitle(), null),
                            item.isBrowsable(), item.isPlayable(), null));
                }
                adapter.notifyDataSetChanged();
                updateEmptyState();
                Log.i(TAG, "Loaded " + children.size() + " children of " + parentId);
            }

            @Override
            public void onError(String parentId) {
                cancelLoadTimeout();
                Log.w(TAG, "Failed to load children of " + parentId);
                showError("Could not load this folder");
            }
        });
    }

    private void startLoadTimeout(String parentId) {
        cancelLoadTimeout();
        loadTimeout = () -> {
            Log.w(TAG, "Timed out waiting for children of " + parentId);
            showError("This source did not respond.\n"
                    + "It accepted the request but never sent anything back.");
        };
        handler.postDelayed(loadTimeout, LOAD_TIMEOUT_MS);
    }

    private void cancelLoadTimeout() {
        if (loadTimeout != null) {
            handler.removeCallbacks(loadTimeout);
            loadTimeout = null;
        }
    }

    private void playMediaId(String mediaId) {
        if (browser == null || !browser.isConnected()) {
            return;
        }
        try {
            MediaController controller = new MediaController(this, browser.getSessionToken());
            controller.getTransportControls().playFromMediaId(mediaId, null);
            finish();
        } catch (Exception e) {
            Log.e(TAG, "Failed to play " + mediaId + ": " + e.getMessage());
            Toast.makeText(this, "Could not play that item", Toast.LENGTH_SHORT).show();
        }
    }

    private void disconnectBrowser() {
        if (browser != null) {
            try {
                if (subscribedParentId != null && browser.isConnected()) {
                    browser.unsubscribe(subscribedParentId);
                }
                browser.disconnect();
            } catch (Exception e) {
                Log.w(TAG, "Error disconnecting browser: " + e.getMessage());
            }
        }
        browser = null;
        connectedSource = null;
        subscribedParentId = null;
    }

    // --- helpers ---

    private static String text(CharSequence cs, String fallback) {
        if (cs == null || cs.length() == 0) {
            return fallback;
        }
        return cs.toString();
    }

    private void showError(String message) {
        rows.clear();
        adapter.notifyDataSetChanged();
        emptyText.setText(message);
        emptyText.setVisibility(View.VISIBLE);
    }

    private void updateEmptyState() {
        if (rows.isEmpty()) {
            emptyText.setText("Nothing here");
            emptyText.setVisibility(View.VISIBLE);
        } else {
            emptyText.setVisibility(View.GONE);
        }
    }

    private class RowAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = LayoutInflater.from(MediaBrowseActivity.this)
                        .inflate(R.layout.item_media, parent, false);
            }

            Row row = rows.get(position);
            TextView title = view.findViewById(R.id.mediaTitle);
            TextView subtitle = view.findViewById(R.id.mediaSubtitle);
            ImageView chevron = view.findViewById(R.id.mediaChevron);

            title.setText(row.title);
            if (row.subtitle == null) {
                subtitle.setVisibility(View.GONE);
            } else {
                subtitle.setVisibility(View.VISIBLE);
                subtitle.setText(row.subtitle);
            }
            chevron.setVisibility(row.browsable ? View.VISIBLE : View.INVISIBLE);

            return view;
        }
    }
}
