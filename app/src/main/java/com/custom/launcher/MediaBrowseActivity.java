package com.custom.launcher;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaDescription;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.custom.launcher.media.MediaArtLoader;
import com.custom.launcher.media.MediaSources;
import com.custom.launcher.radio.RadioClient;
import com.custom.launcher.radio.RadioStationStore;
import com.custom.launcher.saic.SaicSourceSwitch;
import com.custom.launcher.service.MediaListenerService;
import com.custom.launcher.util.LauncherPrefs;

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
    private static final String ITEM_STATION_LIST = "__station_list__";
    /** Prefix for rows that tune a station found by a scan. */
    private static final String ITEM_STATION_PREFIX = "__station_";
    /** Prefix for rows that tune a DAB service from the radio's own list. */
    private static final String ITEM_DAB_PREFIX = "__dab_";

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

    /**
     * Keys of the jump index, in the order they are laid out.
     *
     * <p>
     * 27 of them, filled down each column in turn, so the three columns read
     * A-I, J-R, S-# top to bottom rather than the alphabet zig-zagging across.
     */
    private static final String INDEX_LABELS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ#";
    private static final int INDEX_ROWS = 9;
    private static final int INDEX_COLUMNS = 3;

    /**
     * Below this many rows the index is more clutter than help - the top-level
     * menu is six items and every key in a 27-key grid would be dead.
     */
    private static final int INDEX_MIN_ROWS = 15;

    /**
     * How long to wait for a band scan before saying so.
     *
     * <p>
     * A full FM sweep is slow, hence a minute rather than the eight seconds the
     * browse tree gets. The first on-car attempt simply sat on "Scanning the
     * band…" forever, which tells the user nothing about which step failed.
     */
    private static final long SCAN_TIMEOUT_MS = 60000;

    /**
     * How long to keep asking the tuner for its DAB list before giving up.
     *
     * <p>
     * Long enough for a few goes at {@link #STORE_POLL_MS}, because the first
     * request can legitimately answer with nothing: the list is only populated
     * once the tuner has decoded an ensemble.
     */
    private static final long DAB_LIST_TIMEOUT_MS = 20000;

    /** How often to re-read the radio's station table while waiting for it to fill. */
    private static final long STORE_POLL_MS = 2000;

    /** Time for a source switch to land before the tuner will take orders. */
    private static final long SOURCE_SETTLE_MS = 1200;

    /** Fastest a streaming folder redraws, so it stays usable while it fills. */
    private static final long PAINT_THROTTLE_MS = 1200;

    /** Quiet period after the last batch that means the folder is complete. */
    private static final long PAINT_SETTLE_MS = 400;

    /**
     * The platform's own per-page deadline when listing a folder over Bluetooth.
     *
     * <p>
     * Twenty items are requested at a time with five seconds allowed for each
     * page; on a miss the partial list is kept and the fetch stops silently. A gap
     * this long between batches is that happening.
     */
    private static final long AVRCP_PAGE_TIMEOUT_MS = 5000;

    /**
     * The media id the Bluetooth stack gives the synthetic now-playing folder.
     *
     * <h3>Why it needs de-duplicating</h3>
     * This row is not the phone's. The platform invents it, in the same method
     * that hands each fetched page of a player's folder to us:
     *
     * <pre>
     * private void sendFolderBroadcastAndUpdateNode() {
     *     ...
     *     if (bn.isPlayer()) {
     *         // Add the now playing folder.
     *         mFolderList.add(new MediaItem(mdb.build(), MediaItem.FLAG_BROWSABLE));
     *     }
     *     mBrowseTree.refreshChildren(bn, mFolderList);
     *     broadcastFolderList(mID, mFolderList);
     * }
     * </pre>
     *
     * {@code mFolderList} accumulates across pages and that method runs once per
     * page, so the row is appended again on every page. A player folder fetched in
     * one page arrives with one; fetched in eight pages it arrives with eight
     * copies scattered through it, and the counts give it away exactly - a
     * twenty-item page came back as twenty-one rows.
     *
     * <p>
     * That is the "NOW PLAYING items in a loop that kill any other possible
     * choice" seen on the car. De-duplicating by media id removes it, and cannot
     * remove anything legitimate, because a media id is an identity.
     */
    private static final String NOW_PLAYING_ID = "NOW_PLAYING";

    private static class Row {
        final String id;
        final String title;
        final String subtitle;
        final boolean browsable;
        final boolean playable;
        /** Set for queue rows, which are selected by queue id rather than media id. */
        final Long queueId;
        /** Cover art, either as a uri to read or already decoded by the source. */
        final Uri iconUri;
        final Bitmap iconBitmap;
        /**
         * Art that has to be fetched by hand, for rows whose image is neither of
         * the above — currently DAB station logos, which are blobs in the radio
         * app's database rather than anything with a uri.
         */
        final MediaArtLoader.ByteSource art;

        Row(String id, String title, String subtitle, boolean browsable, boolean playable,
                Long queueId) {
            this(id, title, subtitle, browsable, playable, queueId, null, null, null);
        }

        Row(String id, String title, String subtitle, boolean browsable, boolean playable,
                Long queueId, MediaArtLoader.ByteSource art) {
            this(id, title, subtitle, browsable, playable, queueId, null, null, art);
        }

        Row(String id, String title, String subtitle, boolean browsable, boolean playable,
                Long queueId, Uri iconUri, Bitmap iconBitmap) {
            this(id, title, subtitle, browsable, playable, queueId, iconUri, iconBitmap, null);
        }

        Row(String id, String title, String subtitle, boolean browsable, boolean playable,
                Long queueId, Uri iconUri, Bitmap iconBitmap, MediaArtLoader.ByteSource art) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.browsable = browsable;
            this.playable = playable;
            this.queueId = queueId;
            this.iconUri = iconUri;
            this.iconBitmap = iconBitmap;
            this.art = art;
        }

        /**
         * The index key this row falls under: its first letter, {@code '#'} for a
         * leading digit, or 0 when it has no indexable character at all.
         *
         * <p>
         * Leading punctuation is skipped, because a phone's library is full of
         * titles like {@code "(Intro)"} and nobody looks for those under
         * {@code "("}.
         */
        char indexKey() {
            if (title == null) {
                return 0;
            }
            for (int i = 0; i < title.length(); i++) {
                char c = title.charAt(i);
                if (Character.isDigit(c)) {
                    return '#';
                }
                if (Character.isLetter(c)) {
                    return fold(c);
                }
            }
            return 0;
        }

        /**
         * Strips the accent off a letter so "Étienne" is reachable under E.
         * Anything that does not decompose to an ASCII letter — Cyrillic, CJK — is
         * returned as-is and simply has no key in the index.
         */
        private static char fold(char c) {
            char upper = Character.toUpperCase(c);
            if (upper >= 'A' && upper <= 'Z') {
                return upper;
            }
            String decomposed = Normalizer.normalize(String.valueOf(upper), Normalizer.Form.NFD);
            for (int i = 0; i < decomposed.length(); i++) {
                char d = Character.toUpperCase(decomposed.charAt(i));
                if (d >= 'A' && d <= 'Z') {
                    return d;
                }
            }
            return upper;
        }
    }

    /** One level of navigation, so Back can restore where we were. */
    private static class Level {
        final ComponentName source;
        final String parentId;
        final String title;

        /**
         * The children exactly as the source sent them, in source order.
         *
         * <p>
         * Back repaints from this instead of re-subscribing. AVRCP browsing is
         * stateful on the phone's side — folder ids are only meaningful relative
         * to where the remote's cursor currently is — so asking for a parent we
         * have already left is not reliably answerable. Keeping what we were shown
         * makes Back exact, instant, and independent of the phone's mood. Sorting
         * is applied when painting, never here, so the toggle cannot lose the
         * original order.
         */
        final List<Row> cached = new ArrayList<>();
        /** Where the list was scrolled to, so Back returns to the same place. */
        int firstVisibleRow;
        /** Guard so a single-child root is skipped through exactly once. */
        boolean autoDescended;

        Level(ComponentName source, String parentId, String title) {
            this.source = source;
            this.parentId = parentId;
            this.title = title;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final List<Level> stack = new ArrayList<>();

    /**
     * The tree the user was last browsing, kept across activity instances.
     *
     * <h3>Why this has to outlive the activity</h3>
     * Playing a track finishes this screen, and re-opening it used to start over
     * at the source root — except that the root is not a fixed thing. The
     * Bluetooth browser's single player node reports <em>the phone's current
     * browse cursor</em>, not the phone's library: on the car it held eight
     * categories on the way in, and two on the way back after a track had been
     * played from an album. So "start over at the root" landed the user inside the
     * album they had just played from, with nothing above it to go back to.
     *
     * <p>
     * Re-asking the phone cannot fix that, because the answer is the thing that
     * changed. Keeping what we were shown can, and does: the tree comes back
     * exactly as it was browsed, Back walks all the way out of it, and the phone's
     * cursor is never consulted again. Dropped when the phone disconnects, since
     * the library then belongs to somebody else.
     */
    private static final List<Level> savedStack = new ArrayList<>();

    private ListView list;
    private TextView breadcrumb;
    private TextView emptyText;
    private RowAdapter adapter;

    private LinearLayout indexPanel;
    /** One view per key of {@link #INDEX_LABELS}, in that same order. */
    private final List<TextView> indexKeyViews = new ArrayList<>();
    /** Row each key jumps to, or -1 when no row falls under it. */
    private final int[] indexTargets = new int[INDEX_LABELS.length()];

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
    /** Set when the radio acknowledges a scan, so a timeout can say which step failed. */
    private boolean scanStarted;
    private Runnable scanTimeout;
    /** Re-reads the radio's station table; see {@link #startListPoll()}. */
    private Runnable listPoll;
    /** Row count from the previous poll, so a settling table can be spotted. */
    private int lastStoreCount = -1;
    /** DAB services from the radio's own list, indexed by their row id. */
    private final List<RadioClient.DabStation> dabStations = new ArrayList<>();

    private TextView sortButton;

    /** Newest complete snapshot of the folder being streamed in. */
    private List<Row> pendingRows;
    private Runnable throttledPaint;
    private Runnable settlePaint;
    private long lastPaintUptime;
    /** When the last batch of a streaming folder arrived; see {@link #noteBatchArrived()}. */
    private long lastBatchUptime;
    /** Set when a gap long enough to be the platform's page timeout is seen. */
    private boolean folderStalled;

    private TextView reloadButton;

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

        indexPanel = findViewById(R.id.browseIndex);
        buildIndexPanel();

        sortButton = findViewById(R.id.browseSortButton);
        sortButton.setOnClickListener(v -> toggleSort());
        renderSortButton();

        reloadButton = findViewById(R.id.browseReloadButton);
        reloadButton.setOnClickListener(v -> reloadCurrentLevel());

        radio = new RadioClient(this, radioListener);
        radio.bind();

        showTopLevel();
        restoreSavedTree();
    }

    /** Re-enters the tree from {@link #savedStack}, if there is one. */
    private void restoreSavedTree() {
        if (savedStack.isEmpty()) {
            return;
        }
        stack.addAll(savedStack);
        Log.i(TAG, "Resuming the saved tree at " + pathLabel());
        openLevel(currentLevel(), false);
    }

    /**
     * Keeps the current tree for the next time this screen opens.
     *
     * <p>
     * All or nothing: a level with no cached children cannot be repainted, and a
     * synthetic level (the queue, a station list) re-renders from fields that die
     * with the activity. Keeping a partial stack would restore a path whose middle
     * is blank, so anything less than a fully repaintable tree is dropped.
     */
    private void saveTree() {
        savedStack.clear();
        for (Level level : stack) {
            if (level.source == null || level.cached.isEmpty()) {
                savedStack.clear();
                return;
            }
            savedStack.add(level);
        }
    }

    /** Forgets the browsed tree; called when the phone goes away. */
    public static void forgetTree() {
        savedStack.clear();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Saved here rather than in onDestroy because playing a track finishes the
        // activity, and that is precisely the case that has to survive.
        rememberScroll();
        saveTree();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelLoadTimeout();
        cancelScanTimeout();
        cancelListPoll();
        cancelPendingPaints();
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
        rememberScroll();
        stack.remove(stack.size() - 1);
        cancelLoadTimeout();
        cancelScanTimeout();
        cancelListPoll();
        // A repaint still queued belongs to the folder we are leaving.
        cancelPendingPaints();
        pendingRows = null;
        if (stack.isEmpty()) {
            disconnectBrowser();
            showTopLevel();
        } else {
            openLevel(stack.get(stack.size() - 1), false);
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
        rows.add(new Row(ITEM_STATION_LIST, "Radio stations",
                "The list the tuner already holds - no sweep, no retune",
                true, false, null));
        rows.add(new Row(ITEM_SCAN_RADIO, "Scan for radio stations",
                "Sweeps the band - retunes the radio while it runs", false, false, null));
        rows.add(new Row(ITEM_CONFIGURE_RADIO, "Configure radio",
                "Seek, search, presets and DAB", false, false, null));

        rowsChanged();
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
            case ITEM_STATION_LIST:
                openStationList();
                return;
            case ITEM_SCAN_RADIO:
                rescanStations();
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
        if (row.id != null && row.id.startsWith(ITEM_DAB_PREFIX)) {
            tuneDabStation(row.id);
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
     * Shows the radio's station list, by whichever route this band actually has.
     *
     * <p>
     * The car turned out to be on DAB — {@code band 4}, which the stock app's
     * {@code CarRadioDabPresenter} confirms — and on DAB the FM sweep this used to
     * run is inert: the radio accepted {@code scan()} without complaint and did
     * nothing, which is exactly what was observed. DAB has its own half of the
     * interface, including {@code queryDabStationList()}, which returns the list
     * the radio is already holding with no sweep and no retune.
     *
     * <p>
     * So this is the cheap path where one exists, and falls back to a sweep only
     * on FM/AM where nothing else is on offer.
     */
    private void openStationList() {
        if (radio == null || !radio.isReady()) {
            Toast.makeText(this, "Radio service is not available", Toast.LENGTH_LONG).show();
            return;
        }

        dabStations.clear();
        scannedStations.clear();
        int band = radio.currentBand();

        if (band != RadioClient.BAND_DAB) {
            // FM/AM keep their sweep results in pre_scanned_table; there is no
            // other list on those bands.
            stack.add(new Level(null, ITEM_SCAN_RADIO, "Radio stations"));
            breadcrumb.setText(pathLabel());
            List<RadioClient.Station> stored = RadioStationStore.scannedStations(this, band);
            if (!stored.isEmpty()) {
                showScanResults(band, stored);
                return;
            }
            rows.clear();
            rowsChanged();
            emptyText.setText("The radio has no stored stations for this band.\n"
                    + "\"Scan for radio stations\" will sweep for them.");
            emptyText.setVisibility(View.VISIBLE);
            return;
        }

        stack.add(new Level(null, ITEM_STATION_LIST, "Radio stations"));
        breadcrumb.setText(pathLabel());

        // Whatever the radio already holds, on screen immediately.
        List<RadioClient.DabStation> stored = RadioStationStore.dabStations(this);
        if (!stored.isEmpty()) {
            showDabStations(stored);
        } else {
            rows.clear();
            rowsChanged();
            emptyText.setText("Reading the radio's station list\u2026");
            emptyText.setVisibility(View.VISIBLE);
        }

        // Then ask the tuner to write a fresh one, and watch the table for it. The
        // request is still worth making even though its callback is dead on this
        // ROM: handling it is what makes the radio re-store the list.
        radio.queryDabStationList();
        startListPoll();
        startListTimeout(DAB_LIST_TIMEOUT_MS, ITEM_STATION_LIST,
                "The radio holds no DAB services.\n"
                        + "\"Scan for radio stations\" will sweep the band for them.");
    }

    /**
     * Watches the radio's own station table until it settles.
     *
     * <h3>Why a table and not a callback</h3>
     * {@code IDabCallback.notifyDabStationListInfoChanged} has no call sites in
     * this ROM's {@code RadioService} — the station-list handler stores the list
     * and returns without broadcasting it, unlike every other DAB callback in the
     * same class. No client can receive a list over the binder, which is why sixty
     * seconds of asking produced nothing while mute and now-playing events arrived
     * throughout. The stock UI reads the database too. See
     * {@link RadioStationStore}.
     */
    private void startListPoll() {
        cancelListPoll();
        lastStoreCount = -1;
        listPoll = new Runnable() {
            @Override
            public void run() {
                Level current = currentLevel();
                String id = current == null ? null : current.parentId;
                boolean dabList = ITEM_STATION_LIST.equals(id);
                if (!dabList && !ITEM_SCAN_RADIO.equals(id)) {
                    listPoll = null;
                    return;
                }
                pollStationStore(dabList);
                if (listPoll != null) {
                    handler.postDelayed(this, STORE_POLL_MS);
                }
            }
        };
        handler.postDelayed(listPoll, STORE_POLL_MS);
    }

    /**
     * Re-reads the station table and stops once two reads agree.
     *
     * <p>
     * Two have to agree because the radio stores a new list as
     * {@code clearDabStationList()} followed by an <em>asynchronous</em> insert, so
     * a single read can catch the table empty or half filled. Stopping on the
     * first non-empty answer would show a truncated list and call it finished.
     */
    private void pollStationStore(boolean dabList) {
        if (dabList) {
            if (radio != null) {
                radio.queryDabStationList();
            }
            List<RadioClient.DabStation> stored = RadioStationStore.dabStations(this);
            if (stored.isEmpty()) {
                return;
            }
            boolean settled = stored.size() == lastStoreCount;
            lastStoreCount = stored.size();
            showDabStations(stored);
            if (settled) {
                cancelListPoll();
                cancelScanTimeout();
            }
            return;
        }

        int band = radio != null ? radio.currentBand() : RadioClient.BAND_FM;
        List<RadioClient.Station> stored = RadioStationStore.scannedStations(this, band);
        if (stored.isEmpty()) {
            return;
        }
        boolean settled = stored.size() == lastStoreCount;
        lastStoreCount = stored.size();
        showScanResults(band, stored);
        if (settled) {
            cancelListPoll();
            cancelScanTimeout();
        }
    }

    private void cancelListPoll() {
        if (listPoll != null) {
            handler.removeCallbacks(listPoll);
            listPoll = null;
        }
    }

    /** Deliberate re-sweep, on whichever band we are on. */
    private void rescanStations() {
        if (radio == null || !radio.isReady()) {
            Toast.makeText(this, "Radio service is not available", Toast.LENGTH_LONG).show();
            return;
        }
        if (radio.currentBand() == RadioClient.BAND_DAB) {
            dabStations.clear();
            stack.add(new Level(null, ITEM_STATION_LIST, "Radio stations"));
            breadcrumb.setText(pathLabel());
            rows.clear();
            rowsChanged();
            emptyText.setText("Sweeping the DAB band\u2026\nThis takes a while.");
            emptyText.setVisibility(View.VISIBLE);
            boolean sent = radio.dabScan(0);
            Log.i(TAG, "dabScan sent=" + sent);
            // The sweep itself works - on the car the audio cut out and came back
            // as it ran - but it reports nothing when it finishes. The list has to
            // be asked for, so ask repeatedly until it turns up.
            startListPoll();
            startListTimeout(SCAN_TIMEOUT_MS, ITEM_STATION_LIST,
                    "The DAB sweep ran but the tuner never produced a service list.");
            return;
        }
        startScan();
    }

    private void showDabStations(List<RadioClient.DabStation> stations) {
        if (stations != dabStations) {
            dabStations.clear();
            dabStations.addAll(stations);
        }

        List<Row> built = new ArrayList<>();
        int headings = 0;
        for (int i = 0; i < dabStations.size(); i++) {
            RadioClient.DabStation station = dabStations.get(i);
            // The stock list interleaves ensemble headings with services; the
            // ensemble is already on every row as its subtitle, so a heading row
            // here would just be an unclickable duplicate.
            if (station.isHeading()) {
                headings++;
                continue;
            }
            built.add(new Row(ITEM_DAB_PREFIX + i, station.displayName(),
                    station.subtitle(), false, true, null, logoSource(station)));
        }

        breadcrumb.setText(pathLabel());
        paintRows(built);
        Log.i(TAG, "DAB list: " + built.size() + " service(s), " + headings + " heading(s) skipped");
        if (rows.isEmpty()) {
            emptyText.setText("The radio holds no DAB services.\n"
                    + "\"Rescan stations\" will sweep the band for them.\n\n"
                    + (radio != null ? radio.diagnostics() : ""));
            emptyText.setVisibility(View.VISIBLE);
        } else {
            emptyText.setVisibility(View.GONE);
        }
    }

    /**
     * The logo the radio holds for one DAB service, as a lazily read thumbnail.
     *
     * <p>
     * Broadcast station logos are the one kind of art on this head unit that
     * actually exists: AVRCP browse items carry none at all, but the tuner
     * receives a logo per DAB service and the radio app files them in its own
     * database. Keyed on both ids because a service id is only unique within its
     * ensemble.
     */
    private static MediaArtLoader.ByteSource logoSource(RadioClient.DabStation station) {
        final String key = RadioStationStore.logoKey(station);
        final long serviceId = station.serviceId;
        final int ensembleId = station.ensembleId;
        return new MediaArtLoader.ByteSource() {
            @Override
            public String key() {
                return key;
            }

            @Override
            public byte[] bytes(Context context) {
                return RadioStationStore.dabLogo(context, serviceId, ensembleId);
            }
        };
    }

    private void tuneDabStation(String rowId) {
        int index;
        try {
            index = Integer.parseInt(rowId.substring(ITEM_DAB_PREFIX.length()));
        } catch (NumberFormatException e) {
            return;
        }
        if (radio == null || index < 0 || index >= dabStations.size()) {
            return;
        }
        SaicSourceSwitch.playRadio(this);
        radio.dabTune(dabStations.get(index));
        radio.startPlaying();
        finish();
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

        // A scan is a tuner operation, and on this platform the tuner answers to
        // whoever holds the audio source. The first on-car attempt was made with
        // Bluetooth holding the source and came back with nothing at all, so take
        // the source first and give it a moment to land.
        boolean hadFocus = radio.hasFocus();
        if (!hadFocus) {
            SaicSourceSwitch.playRadio(this);
            radio.startPlaying();
        }
        // No callback, no result: onScanFinished is the only delivery route.
        boolean callbackLive = radio.ensureCallback();
        Log.i(TAG, "Starting scan; focus=" + hadFocus + " callback=" + callbackLive
                + "; " + radio.diagnostics());

        scannedStations.clear();
        scanStarted = false;
        stack.add(new Level(null, ITEM_SCAN_RADIO, "Radio stations"));

        rows.clear();
        breadcrumb.setText(pathLabel());
        emptyText.setText("Scanning the band…\n"
                + "This retunes the radio and takes up to a minute."
                + (callbackLive ? "" : "\n\nWarning: the radio refused our result callback."));
        emptyText.setVisibility(View.VISIBLE);
        rowsChanged();

        handler.postDelayed(() -> {
            if (radio == null || !radio.isReady()) {
                return;
            }
            // Only force a band when nothing is tuned, which is the one case where
            // no band is open to sweep. Otherwise leave the user's band alone.
            // "Nothing tuned" is not the same as a null station: the car answered
            // with a real Station carrying channel -1, so the channel has to be
            // checked and not just the object.
            RadioClient.Station tuned = radio.getCurrentStation();
            if (tuned == null || tuned.channelNumber <= 0) {
                Log.i(TAG, "Nothing tuned (" + tuned + "); opening FM before the sweep");
                radio.openBand(RadioClient.BAND_FM);
            }
            radio.scan();
            Log.i(TAG, "scan() issued; " + radio.diagnostics());
        }, hadFocus ? 0 : SOURCE_SETTLE_MS);

        // onScanFinished may or may not arrive; pre_scanned_table is written
        // either way, so watch that rather than relying on the callback.
        startListPoll();
        startListTimeout(SCAN_TIMEOUT_MS, ITEM_SCAN_RADIO,
                "The sweep produced no stations in the radio's own table.");
    }

    /**
     * Turns a request that never answers into something readable.
     *
     * <p>
     * "Doesn't return a list" has several causes here — the callback was refused,
     * the radio ignored the request, the sweep found nothing, or the result parcel
     * did not decode — and they are indistinguishable from a screen that just says
     * "Scanning…". This names the step that was reached and prints the radio's
     * state, which is the only diagnostic channel on a head unit with no adb.
     */
    private void startListTimeout(long delayMs, String levelId, String reachedMessage) {
        cancelScanTimeout();
        scanTimeout = () -> {
            scanTimeout = null;
            // The user may have navigated away; do not paint over another screen.
            if (stack.isEmpty() || !levelId.equals(stack.get(stack.size() - 1).parentId)) {
                return;
            }
            cancelListPoll();
            // A list already on screen is not a failure. The station table is read
            // straight away and only then refreshed, so the refresh timing out
            // must not paint an error over stations the user can already see.
            if (!rows.isEmpty()) {
                Log.i(TAG, "Refresh timed out but " + rows.size()
                        + " station(s) are already showing; leaving them alone");
                return;
            }
            String reached = scanStarted
                    ? "The radio started the sweep but never reported a result."
                    : reachedMessage;
            Log.w(TAG, "Station list timed out. " + reached);
            showError("Nothing after " + (delayMs / 1000) + " seconds.\n"
                    + reached + "\n\n"
                    + (radio != null ? radio.diagnostics() : "radio service gone")
                    + "\n" + RadioStationStore.diagnostics(MediaBrowseActivity.this));
        };
        handler.postDelayed(scanTimeout, delayMs);
    }

    private void cancelScanTimeout() {
        if (scanTimeout != null) {
            handler.removeCallbacks(scanTimeout);
            scanTimeout = null;
        }
    }

    private void showScanResults(int band, List<RadioClient.Station> stations) {
        // Guarded because navigating back to this level re-renders from the field
        // itself, and clear()-then-addAll() on the same list would empty it.
        if (stations != scannedStations) {
            scannedStations.clear();
            scannedStations.addAll(stations);
        }

        breadcrumb.setText(pathLabel());
        List<Row> built = new ArrayList<>();
        for (int i = 0; i < scannedStations.size(); i++) {
            RadioClient.Station station = scannedStations.get(i);
            built.add(new Row(ITEM_STATION_PREFIX + i, station.displayName(),
                    station.frequencyLabel(), false, true, null));
        }
        paintRows(built);
        if (rows.isEmpty()) {
            emptyText.setText("The scan found no stations on band " + band + ".\n\n"
                    + (radio != null ? radio.diagnostics() : ""));
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
            scanStarted = true;
            emptyText.setText("Scanning band " + band + "…");
        }

        @Override
        public void onScanFinished(int band, List<RadioClient.Station> stations) {
            cancelScanTimeout();
            showScanResults(band, stations);
        }

        @Override
        public void onDabStationList(List<RadioClient.DabStation> stations) {
            cancelScanTimeout();
            // Only take over the screen if that is what the user is waiting for:
            // the radio also pushes this list unprompted when it changes.
            Level current = currentLevel();
            if (current != null && ITEM_STATION_LIST.equals(current.parentId)) {
                showDabStations(stations);
            } else {
                dabStations.clear();
                dabStations.addAll(stations);
                Log.i(TAG, "Cached " + stations.size() + " DAB entries for later");
            }
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
        breadcrumb.setText(pathLabel());

        List<Row> built = new ArrayList<>();
        List<android.media.session.MediaSession.QueueItem> queue = controller.getQueue();
        if (queue != null) {
            for (android.media.session.MediaSession.QueueItem item : queue) {
                MediaDescription d = item.getDescription();
                built.add(new Row(String.valueOf(item.getQueueId()),
                        text(d.getTitle(), "Unknown track"),
                        text(d.getSubtitle(), null),
                        false, true, item.getQueueId(),
                        d.getIconUri(), d.getIconBitmap()));
            }
        }
        paintRows(built);
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
        rememberScroll();
        // Anything still queued belongs to the level we are leaving.
        cancelPendingPaints();
        pendingRows = null;
        stack.add(level);
        openLevel(level, true);
    }

    private void openLevel(Level level, boolean isNew) {
        breadcrumb.setText(pathLabel());

        // Going back is a repaint, never a refetch. AVRCP folder ids are only
        // meaningful relative to where the phone's browse cursor currently sits,
        // so re-subscribing to a folder we have already left is not reliably
        // answerable - which is why Back used to land on the source root instead
        // of one level up. What we were shown is what we show again.
        if (!isNew && !level.cached.isEmpty()) {
            paintRows(level.cached);
            emptyText.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
            list.setSelectionFromTop(level.firstVisibleRow, 0);
            Log.i(TAG, "Restored " + level.cached.size() + " cached rows for " + level.title);
            return;
        }

        rows.clear();
        rowsChanged();
        lastBatchUptime = 0;
        folderStalled = false;
        emptyText.setText("Loading…");
        emptyText.setVisibility(View.VISIBLE);

        if (level.source == null) {
            // Synthetic levels have no MediaBrowser behind them. Re-render from
            // what we already hold rather than pushing another level: showQueue()
            // used to add to the stack itself, so every Back that landed here
            // grew the stack again and Back could never escape.
            if (ITEM_SCAN_RADIO.equals(level.parentId)) {
                showScanResults(-1, scannedStations);
            } else if (ITEM_STATION_LIST.equals(level.parentId)) {
                showDabStations(dabStations);
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

    /**
     * Skips a source root that has exactly one folder in it.
     *
     * <p>
     * The Bluetooth browser's root is a list of connected players, and with one
     * phone paired that is a single row reading "Bluetooth Player" — a screen with
     * one choice on it, which then had to be tapped before any real browsing
     * began, and which Back landed back on. Descending through it automatically
     * makes that step disappear in both directions: Back from the first real
     * folder returns to the launcher's own menu, where it looks like it should.
     *
     * <p>
     * Only ever done for a source root, and only once per level, so it cannot
     * chain or loop.
     */
    private boolean descendSingleChild(Level level, List<Row> loaded) {
        if (level.parentId != null || level.autoDescended || loaded.size() != 1) {
            return false;
        }
        Row only = loaded.get(0);
        if (!only.browsable || only.id == null) {
            return false;
        }
        level.autoDescended = true;
        Log.i(TAG, "Root holds only \"" + only.title + "\"; descending through it");
        // Replace rather than push: the skipped level must not come back on Back.
        stack.set(stack.size() - 1, new Level(level.source, only.id, level.title));
        subscribeTo(only.id);
        return true;
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

                List<Row> loaded = new ArrayList<>(children.size());
                Set<String> seen = new HashSet<>();
                int withArt = 0;
                int duplicates = 0;
                for (MediaBrowser.MediaItem item : children) {
                    MediaDescription d = item.getDescription();
                    if (d.getIconUri() != null || d.getIconBitmap() != null) {
                        withArt++;
                    }
                    String mediaId = item.getMediaId();
                    // A media id is an identity, so the same one twice is one row.
                    // Not defensive tidying - the platform really does repeat one;
                    // see {@link #NOW_PLAYING_ID}.
                    if (mediaId != null && !seen.add(mediaId)) {
                        duplicates++;
                        continue;
                    }
                    loaded.add(new Row(mediaId,
                            prettyTitle(text(d.getTitle(), "Untitled")),
                            text(d.getSubtitle(), null),
                            item.isBrowsable(), item.isPlayable(), null,
                            d.getIconUri(), d.getIconBitmap()));
                }

                // Counted rather than assumed: no thumbnail appeared on the car,
                // and "the source sends no art" and "we failed to decode the art"
                // look identical from the outside. This says which - and the
                // answer, on every folder of two on-car runs, was zero. AVRCP
                // browse items on this phone carry no art at all; only the
                // now-playing track does, over BIP.
                Log.i(TAG, "Loaded " + children.size() + " children of " + parentId
                        + " -> " + loaded.size() + " rows (" + duplicates + " duplicate ids)"
                        + "; " + withArt + " carried cover art"
                        + "; first rows: " + firstTitles(loaded));
                noteBatchArrived();

                Level current = currentLevel();
                if (current != null && descendSingleChild(current, loaded)) {
                    return;
                }
                if (current != null) {
                    current.cached.clear();
                    current.cached.addAll(loaded);
                }
                streamRows(loaded);
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
        rowsChanged();
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

    // --- painting a folder that arrives in pieces ---

    /**
     * Puts a still-growing folder on screen without repainting on every batch.
     *
     * <h3>Why this is not just {@code paintRows}</h3>
     * A2DP browse results stream in. One on-car folder arrived as forty-five
     * separate callbacks over twenty seconds — 7 rows, then 19, then 34, all the
     * way to 593 — and each one re-sorted the list and called
     * {@code notifyDataSetChanged}. That is what "the list is sorting itself for
     * eons, continuously refreshing" was: not slow sorting, but forty-five full
     * repaints. It also made the jump index look broken, because a jump landed
     * correctly and was then scrolled out from under the finger a third of a
     * second later.
     *
     * <p>
     * So: paint the first batch at once so something is on screen, then at most
     * once every {@link #PAINT_THROTTLE_MS} while the stream continues, plus one
     * final time {@link #PAINT_SETTLE_MS} after the last batch so the finished
     * list is exact. Every repaint keeps whatever row was at the top of the
     * viewport there, so scrolling and jumping survive a folder still loading
     * underneath.
     */
    private void streamRows(List<Row> source) {
        pendingRows = source;
        if (rows.isEmpty()) {
            flushRows(false);
            return;
        }
        long now = SystemClock.uptimeMillis();
        long earliest = lastPaintUptime + PAINT_THROTTLE_MS;
        if (now >= earliest) {
            flushRows(false);
            return;
        }
        if (throttledPaint == null) {
            throttledPaint = () -> flushRows(false);
            handler.postAtTime(throttledPaint, earliest);
        }
        // Independent of the throttle: the batch that turns out to be the last one
        // must be painted even though the throttle has just fired.
        if (settlePaint != null) {
            handler.removeCallbacks(settlePaint);
        }
        settlePaint = () -> flushRows(true);
        handler.postDelayed(settlePaint, PAINT_SETTLE_MS);
    }

    private void flushRows(boolean complete) {
        cancelPendingPaints();
        if (pendingRows == null) {
            return;
        }
        String anchorId = topRowId();
        int anchorOffset = topRowOffset();
        paintRows(pendingRows);
        restoreAnchor(anchorId, anchorOffset);
        updateEmptyState();
        lastPaintUptime = SystemClock.uptimeMillis();
        if (complete && folderStalled) {
            // Said out loud, because a folder that stops short looks identical to
            // a folder that is genuinely that size.
            folderStalled = false;
            Toast.makeText(this,
                    "The phone stalled while listing this folder, so it may be "
                            + "incomplete. \"Reload\" asks again.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void cancelPendingPaints() {
        if (throttledPaint != null) {
            handler.removeCallbacks(throttledPaint);
            throttledPaint = null;
        }
        if (settlePaint != null) {
            handler.removeCallbacks(settlePaint);
            settlePaint = null;
        }
    }

    /** The id of the row at the top of the viewport, or null if there is none. */
    private String topRowId() {
        if (list == null) {
            return null;
        }
        int first = list.getFirstVisiblePosition();
        return first >= 0 && first < rows.size() ? rows.get(first).id : null;
    }

    private int topRowOffset() {
        View child = list == null ? null : list.getChildAt(0);
        return child == null ? 0 : child.getTop();
    }

    /**
     * Puts {@code anchorId} back at the top of the viewport after a repaint.
     *
     * <p>
     * By row id rather than by index, because both insertion and the sort toggle
     * move a row's index. Anchoring on the identity of what the user was looking
     * at is the only thing that holds still while five hundred rows arrive
     * underneath it.
     */
    private void restoreAnchor(String anchorId, int offset) {
        if (anchorId == null || list == null) {
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            if (anchorId.equals(rows.get(i).id)) {
                list.setSelectionFromTop(i, offset);
                return;
            }
        }
    }

    /**
     * Turns the platform's internal folder names into something readable.
     *
     * <p>
     * The only one so far is {@code NOW_PLAYING}, which the Bluetooth stack
     * synthesises rather than getting from the phone, and so hands over as a bare
     * constant name.
     */
    private static String prettyTitle(String title) {
        if (title == null) {
            return null;
        }
        return title.startsWith(NOW_PLAYING_ID) ? "Now playing" : title;
    }

    /**
     * Records the gap since the previous batch, to catch a truncated listing.
     *
     * <h3>What the gap means</h3>
     * The platform fetches a folder twenty items at a time and allows the phone
     * five seconds per page. Miss that and it keeps whatever it has, hands it over
     * as if complete, and stops - a folder silently short with nothing in the API
     * to say so. A batch arriving more than five seconds after the one before it
     * is that timeout happening, so it is worth telling the user their list may be
     * incomplete instead of letting them wonder where their tracks went.
     */
    private void noteBatchArrived() {
        long now = SystemClock.uptimeMillis();
        if (lastBatchUptime > 0 && now - lastBatchUptime >= AVRCP_PAGE_TIMEOUT_MS) {
            folderStalled = true;
            Log.w(TAG, "The phone stalled " + (now - lastBatchUptime)
                    + "ms mid-folder; the listing may be truncated");
        }
        lastBatchUptime = now;
    }

    /**
     * Re-asks the phone for the folder on screen.
     *
     * <p>
     * Worth having because a short listing is retryable: the platform only serves
     * a folder from its cache while that folder is the one the phone's browse
     * cursor sits in, and marks the previous folder stale on every move. Leaving
     * and re-entering therefore forces a real re-fetch, and a second attempt often
     * completes where the first timed out.
     */
    private void reloadCurrentLevel() {
        Level current = currentLevel();
        if (current == null) {
            showTopLevel();
            return;
        }
        if (current.source == null) {
            // A synthetic level: the radio list is the only one worth re-reading.
            openLevel(current, false);
            return;
        }
        Log.i(TAG, "Reloading " + current.title + " (" + current.parentId + ")");
        current.cached.clear();
        current.autoDescended = false;
        cancelPendingPaints();
        pendingRows = null;
        // Dropping the subscription is what makes the next one a fetch rather than
        // a replay of the same short answer.
        if (browser != null && subscribedParentId != null) {
            browser.unsubscribe(subscribedParentId);
            subscribedParentId = null;
        }
        openLevel(current, true);
    }

    /** The first few titles of a batch, for the log; the tree shape is not visible otherwise. */
    private static String firstTitles(List<Row> loaded) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < loaded.size() && i < 4; i++) {
            if (i > 0) {
                sb.append(" | ");
            }
            Row row = loaded.get(i);
            sb.append(row.title).append(row.browsable ? "/" : "");
        }
        return sb.toString();
    }

    // --- sorting and painting ---

    /**
     * Puts {@code source} on screen, sorted or not according to the toggle.
     *
     * <p>
     * Every path that fills the list goes through here, so the sort setting, the
     * jump index and the empty state can never disagree with each other.
     */
    private void paintRows(List<Row> source) {
        // Whatever is being painted now supersedes anything queued. Centralised
        // here because the synthetic screens (queue, station list) paint directly
        // rather than through streamRows, and a repaint left over from a folder
        // must never land on top of one of them.
        cancelPendingPaints();
        pendingRows = null;
        rows.clear();
        rows.addAll(source);
        if (LauncherPrefs.isBrowseSorted(this)) {
            sortRows();
        }
        rowsChanged();
    }

    /**
     * Folders first, then by title, ignoring case and accents.
     *
     * <p>
     * Folders first because a mixed folder with its subfolders scattered among
     * three hundred tracks is worse than either pure case. The comparison reuses
     * the index's own folding, so a row always sorts under the key its index
     * button uses - otherwise "Étienne" would sort past Z while its index key said
     * E.
     */
    private void sortRows() {
        Collections.sort(rows, (a, b) -> {
            if (a.browsable != b.browsable) {
                return a.browsable ? -1 : 1;
            }
            char ka = a.indexKey();
            char kb = b.indexKey();
            if (ka != kb) {
                // Unkeyed rows (CJK, symbols) go last rather than interleaving.
                if (ka == 0) {
                    return 1;
                }
                if (kb == 0) {
                    return -1;
                }
                return Character.compare(ka, kb);
            }
            String ta = a.title == null ? "" : a.title;
            String tb = b.title == null ? "" : b.title;
            return ta.compareToIgnoreCase(tb);
        });
    }

    private void toggleSort() {
        boolean sorted = !LauncherPrefs.isBrowseSorted(this);
        LauncherPrefs.setBrowseSorted(this, sorted);
        renderSortButton();
        repaintCurrentLevel();
    }

    private void renderSortButton() {
        if (sortButton == null) {
            return;
        }
        boolean sorted = LauncherPrefs.isBrowseSorted(this);
        sortButton.setText(sorted ? "Sorted A-Z" : "Sort A-Z");
        sortButton.setAlpha(sorted ? 1f : 0.55f);
    }

    /** Re-renders whatever is on screen from the level's own cached children. */
    private void repaintCurrentLevel() {
        Level current = currentLevel();
        if (current == null) {
            showTopLevel();
            return;
        }
        if (!current.cached.isEmpty()) {
            paintRows(current.cached);
            return;
        }
        // A synthetic level holds its data elsewhere.
        openLevel(current, false);
    }

    private Level currentLevel() {
        return stack.isEmpty() ? null : stack.get(stack.size() - 1);
    }

    /** Remembers the scroll offset so Back returns to the same row. */
    private void rememberScroll() {
        Level current = currentLevel();
        if (current != null && list != null) {
            current.firstVisibleRow = list.getFirstVisiblePosition();
        }
    }

    /** The full path, so a deep folder says where it is rather than just its name. */
    private String pathLabel() {
        if (stack.isEmpty()) {
            return "Browse";
        }
        StringBuilder sb = new StringBuilder();
        for (Level level : stack) {
            if (sb.length() > 0) {
                sb.append(" \u203a ");
            }
            sb.append(level.title);
        }
        return sb.toString();
    }

    // --- jump index ---

    /**
     * Builds the 3x9 grid of jump keys once, at startup.
     *
     * <p>
     * The keys are sized by weight rather than in dp because this head unit's
     * usable height is not something we can hard-code with any confidence - the
     * panel simply divides whatever it is given into nine, which keeps every key a
     * comfortable target on a screen this shape.
     */
    private void buildIndexPanel() {
        if (indexPanel == null) {
            return;
        }
        indexPanel.removeAllViews();
        indexKeyViews.clear();

        int keyIndex = 0;
        for (int column = 0; column < INDEX_COLUMNS; column++) {
            LinearLayout columnView = new LinearLayout(this);
            columnView.setOrientation(LinearLayout.VERTICAL);
            columnView.setLayoutParams(new LinearLayout.LayoutParams(
                    dp(56), LinearLayout.LayoutParams.MATCH_PARENT));

            for (int row = 0; row < INDEX_ROWS; row++) {
                if (keyIndex >= INDEX_LABELS.length()) {
                    break;
                }
                char label = INDEX_LABELS.charAt(keyIndex);
                TextView key = new TextView(this);
                key.setText(String.valueOf(label));
                key.setGravity(Gravity.CENTER);
                key.setTextSize(18f);
                key.setTextColor(getResources().getColor(R.color.text_primary, null));
                key.setBackgroundResource(R.drawable.index_button_background);
                key.setOnClickListener(v -> jumpTo(label));

                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
                params.setMargins(dp(3), dp(3), dp(3), dp(3));
                key.setLayoutParams(params);

                columnView.addView(key);
                indexKeyViews.add(key);
                keyIndex++;
            }
            indexPanel.addView(columnView);
        }
    }

    /**
     * Repaints the list and re-keys the jump index.
     *
     * <p>
     * These two always go together: an index built against the previous folder
     * would scroll to positions that no longer exist.
     */
    private void rowsChanged() {
        adapter.notifyDataSetChanged();
        refreshIndex();
        if (sortButton != null) {
            // Nothing to sort on the hand-built menu, and sorting it would shuffle
            // entries that are in a deliberate order.
            sortButton.setVisibility(stack.isEmpty() ? View.GONE : View.VISIBLE);
        }
        if (reloadButton != null) {
            reloadButton.setVisibility(stack.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    /**
     * Points each key at the first row that falls under it, and greys out the keys
     * with nothing to jump to.
     *
     * <p>
     * Greying out matters here: browse results arrive in whatever order the phone
     * chose, and a key that silently does nothing when tapped reads as a broken
     * screen. A key that is visibly dim reads as "no tracks under R", which is the
     * truth.
     */
    private void refreshIndex() {
        if (indexPanel == null || indexKeyViews.isEmpty()) {
            return;
        }

        for (int i = 0; i < indexTargets.length; i++) {
            indexTargets[i] = -1;
        }
        for (int position = 0; position < rows.size(); position++) {
            int slot = INDEX_LABELS.indexOf(rows.get(position).indexKey());
            // First row wins: the index answers "where does R start", and a later
            // stray R further down the list is not that.
            if (slot >= 0 && indexTargets[slot] < 0) {
                indexTargets[slot] = position;
            }
        }

        boolean worthShowing = rows.size() >= INDEX_MIN_ROWS;
        indexPanel.setVisibility(worthShowing ? View.VISIBLE : View.GONE);
        if (!worthShowing) {
            return;
        }
        for (int i = 0; i < indexKeyViews.size(); i++) {
            boolean live = indexTargets[i] >= 0;
            TextView key = indexKeyViews.get(i);
            key.setEnabled(live);
            key.setAlpha(live ? 1f : 0.3f);
        }
    }

    private void jumpTo(char label) {
        int slot = INDEX_LABELS.indexOf(label);
        if (slot < 0 || indexTargets[slot] < 0) {
            return;
        }
        // Not a smooth scroll: over hundreds of rows on this hardware, animating
        // the whole way there is slower than the flick-scroll the index replaces.
        list.setSelectionFromTop(indexTargets[slot], 0);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
            ImageView thumb = view.findViewById(R.id.mediaThumb);

            // A folder glyph where there is no cover keeps the left gutter aligned
            // down the whole list, so titles do not shift about as covers arrive.
            int placeholder = row.browsable
                    ? R.drawable.ic_folder
                    : R.drawable.ic_music_placeholder;
            if (row.art != null) {
                MediaArtLoader.bind(thumb, row.art, placeholder);
            } else {
                MediaArtLoader.bind(thumb, row.iconBitmap, row.iconUri, placeholder);
            }

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
