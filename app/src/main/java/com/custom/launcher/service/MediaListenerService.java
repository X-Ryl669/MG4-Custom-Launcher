package com.custom.launcher.service;

import java.util.List;
import java.net.URLDecoder;
import java.io.UnsupportedEncodingException;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.util.Log;
import com.custom.launcher.media.MediaArtLoader;
import com.custom.launcher.radio.RadioClient;
import com.custom.launcher.radio.RadioStationStore;
import com.custom.launcher.util.LogUtils;

/**
 * Service to listen for media playback information
 * This monitors active media sessions to display now playing information
 */
public class MediaListenerService extends NotificationListenerService {
    // Track last failed album art URI to suppress repeated log spam
    private String lastFailedArtUri = null;
    private String lastTrackId = null;
    private static final String TAG = "MediaListenerService";

    private static MediaListenerService instance;
    private static MediaInfoListener listener;

    private MediaSessionManager mediaSessionManager;
    private MediaController activeController;

    /** The station package whose sessions carry no art of their own. */
    private static final String RADIO_PACKAGE = "com.saicmotor.radio";

    /** Last decoded station logo, so a re-reported track is not a database read. */
    private String cachedLogoKey;
    private Bitmap cachedLogo;
    private long lastLogoLookup;

    /** Floor between station lookups when the track has not changed. */
    private static final long LOGO_RECHECK_MS = 5000;
    private List<MediaController> sessions = java.util.Collections.emptyList();

    /**
     * Source the user picked in the browse screen, or null to follow whatever the
     * car is doing.
     *
     * <p>
     * This car boots with the radio session active but muted, so "first active
     * session" is the radio and the tile's play button was commanding a muted
     * radio rather than the phone. Letting the user name the source they mean
     * fixes that without needing a vendor source-switch API, which this firmware
     * does not appear to have: {@code ICarAudioService} only covers volume and EQ.
     */
    private static String preferredPackage;

    public interface MediaInfoListener {
        void onMediaChanged(String title, String artist, boolean isPlaying, Bitmap albumArt);
    }

    public static void setListener(MediaInfoListener l) {
        listener = l;
        // If service is already running, trigger immediate update
        if (instance != null) {
            instance.updateMediaInfo();
        }
    }

    public static MediaController getActiveController() {
        if (instance != null) {
            return instance.activeController;
        }
        return null;
    }

    /**
     * Controller for a specific app's session, whether or not it is the one the
     * tile is currently showing. Used to start Bluetooth playback while the radio
     * still holds the "active" slot.
     */
    public static MediaController getControllerForPackage(String packageName) {
        if (instance == null || packageName == null) {
            return null;
        }
        for (MediaController controller : instance.sessions) {
            if (controller.getPackageName() != null
                    && controller.getPackageName().startsWith(packageName)) {
                return controller;
            }
        }
        return null;
    }

    /** Packages that currently have a media session, for the browse screen. */
    public static List<String> getSessionPackages() {
        List<String> packages = new java.util.ArrayList<>();
        if (instance != null) {
            for (MediaController controller : instance.sessions) {
                if (controller.getPackageName() != null) {
                    packages.add(controller.getPackageName());
                }
            }
        }
        return packages;
    }

    /**
     * Pins the tile to one source. Pass null to go back to following the car.
     * Returns the controller now in use, if any.
     */
    public static MediaController selectSource(String packageName) {
        preferredPackage = packageName;
        Log.i(TAG, "Preferred media source set to " + packageName);
        if (instance != null) {
            instance.onActiveSessionsChanged(instance.sessions);
        }
        return getActiveController();
    }

    public static String getPreferredSource() {
        return preferredPackage;
    }

    /**
     * Package that owns the currently active session, or null if nothing is
     * playing. Lets the UI label the source and tell radio apart from Bluetooth.
     */
    public static String getActiveSourcePackage() {
        if (instance != null && instance.activeController != null) {
            return instance.activeController.getPackageName();
        }
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        mediaSessionManager = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);

        // Listen for active media session changes
        mediaSessionManager.addOnActiveSessionsChangedListener(
                this::onActiveSessionsChanged,
                new ComponentName(this, MediaListenerService.class));

        // Get initial active sessions
        onActiveSessionsChanged(mediaSessionManager.getActiveSessions(
                new ComponentName(this, MediaListenerService.class)));
    }

    private void onActiveSessionsChanged(List<MediaController> controllers) {
        sessions = controllers != null ? controllers : java.util.Collections.emptyList();

        MediaController chosen = pickController(sessions);
        if (chosen == null) {
            if (activeController != null) {
                activeController.unregisterCallback(mediaCallback);
            }
            activeController = null;
            if (listener != null) {
                listener.onMediaChanged("No media playing", "", false, null);
            }
            return;
        }

        if (activeController != null) {
            activeController.unregisterCallback(mediaCallback);
        }
        activeController = chosen;
        activeController.registerCallback(mediaCallback);
        Log.i(TAG, "Active media session is now " + activeController.getPackageName()
                + " (of " + sessions.size() + " session(s))");

        updateMediaInfo();
    }

    /**
     * Chooses which session the tile follows.
     *
     * <p>
     * Order matters here. The user's explicit choice wins, because the whole point
     * of the source picker is to override the car. Failing that, a session that is
     * actually playing beats one that merely exists — otherwise the muted radio
     * session the car creates at boot shadows the phone. Only then does it fall
     * back to the first session, which is what this used to do unconditionally.
     */
    private MediaController pickController(List<MediaController> controllers) {
        if (controllers.isEmpty()) {
            return null;
        }

        if (preferredPackage != null) {
            for (MediaController controller : controllers) {
                if (controller.getPackageName() != null
                        && controller.getPackageName().startsWith(preferredPackage)) {
                    return controller;
                }
            }
            Log.i(TAG, "Preferred source " + preferredPackage + " has no session right now");
        }

        for (MediaController controller : controllers) {
            PlaybackState state = controller.getPlaybackState();
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                return controller;
            }
        }

        return controllers.get(0);
    }

    private MediaController.Callback mediaCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            updateMediaInfo();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            updateMediaInfo();
        }
    };

    private void updateMediaInfo() {
        if (activeController == null || listener == null) {
            return;
        }

        try {
            MediaMetadata metadata = activeController.getMetadata();
            PlaybackState state = activeController.getPlaybackState();

            if (metadata != null) {
                String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
                String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);

                // Generate a unique track ID to detect track changes
                String currentTrackId = title + "|" + artist;
                boolean trackChanged = !currentTrackId.equals(lastTrackId);
                if (trackChanged) {
                    lastTrackId = currentTrackId;
                    lastFailedArtUri = null; // Reset failed URI on track change
                }

                Bitmap albumArt = null;

                // Try to get ART_URI or ALBUM_ART_URI first (R67 stock launcher method)
                String artUriString = metadata.getString(MediaMetadata.METADATA_KEY_ART_URI);
                if (artUriString == null) {
                    artUriString = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
                }

                if (artUriString != null && !artUriString.equals(lastFailedArtUri)) {
                    try {
                        // Only log when track changes to avoid spam
                        if (trackChanged) {
                            Log.i(TAG, "=== ALBUM ART DEBUG ===");
                            Log.i(TAG, "Original URI string: [" + artUriString + "]");
                            Log.i(TAG, "URI length: " + artUriString.length());
                        }

                        Uri artUri;
                        String cleanPath = artUriString;

                        // URL decode to handle encoded characters like %20 -> space
                        try {
                            String decoded = URLDecoder.decode(artUriString, "UTF-8");
                            if (!decoded.equals(artUriString)) {
                                if (trackChanged) {
                                    Log.i(TAG, "After URL decode: [" + decoded + "]");
                                }
                                cleanPath = decoded;
                            }
                        } catch (UnsupportedEncodingException e) {
                            if (trackChanged) {
                                Log.w(TAG, "Failed to URL decode: " + e.getMessage());
                            }
                        }

                        // Fix malformed Bluetooth paths with extra spaces
                        // Pattern: /storage/emulated/0/bluetooth/XX:XX:XX: XX: XX: XX/...
                        // Should be: /storage/emulated/0/bluetooth/XX:XX:XX:XX:XX:XX/...
                        if (cleanPath.contains("bluetooth/") && cleanPath.contains(": ")) {
                            String fixed = cleanPath.replaceAll(": ", ":");
                            if (!fixed.equals(cleanPath)) {
                                if (trackChanged) {
                                    Log.i(TAG, "Fixed Bluetooth path spaces: [" + fixed + "]");
                                }
                                cleanPath = fixed;
                            }
                        }

                        if (trackChanged) {
                            Log.i(TAG, "Final path to use: [" + cleanPath + "]");
                        }

                        // Check if it's a file path that needs conversion
                        if (cleanPath.startsWith("/")) {
                            // Convert absolute file path to file:// URI
                            artUri = Uri.parse("file://" + cleanPath);
                            if (trackChanged) {
                                Log.i(TAG, "Created file:// URI: " + artUri);
                            }
                        } else {
                            artUri = Uri.parse(cleanPath);
                            if (trackChanged) {
                                Log.i(TAG, "Parsed as URI: " + artUri);
                            }
                        }

                        // Try multiple methods to load the bitmap
                        try {
                            // Method 1: ContentResolver (works for content:// URIs)
                            if (trackChanged) {
                                Log.i(TAG, "Attempt 1: ContentResolver.openInputStream()");
                            }
                            ContentResolver resolver = getContentResolver();
                            albumArt = BitmapFactory.decodeStream(resolver.openInputStream(artUri));
                            if (trackChanged) {
                                Log.i(TAG, "✓✓ SUCCESS via ContentResolver");
                            }
                        } catch (Exception e1) {
                            if (trackChanged) {
                                Log.i(TAG, "ContentResolver failed: " + e1.getMessage());
                            }

                            try {
                                // Method 2: Direct file path (for file:// URIs)
                                Log.i(TAG, "Attempt 2: Direct file access");
                                if (cleanPath.startsWith("/")) {
                                    Log.i(TAG, "Checking file: " + cleanPath);
                                    java.io.File file = new java.io.File(cleanPath);
                                    Log.i(TAG, "File exists: " + file.exists() + ", canRead: " + file.canRead());
                                    if (file.exists() && file.canRead()) {
                                        Log.i(TAG, "File size: " + file.length() + " bytes");
                                        albumArt = BitmapFactory.decodeFile(cleanPath);
                                        if (albumArt != null) {
                                            Log.i(TAG, "✓✓ SUCCESS via direct file access");
                                        } else {
                                            Log.w(TAG, "File exists but BitmapFactory.decodeFile returned null");
                                        }
                                    } else {
                                        Log.w(TAG, "✗✗ File doesn't exist or can't be read: " + cleanPath);

                                        // Try to list parent directory to see what files are there
                                        java.io.File parentDir = file.getParentFile();
                                        if (parentDir != null && parentDir.exists()) {
                                            Log.i(TAG, "Parent directory exists: " + parentDir.getAbsolutePath());
                                            String[] files = parentDir.list();
                                            if (files != null && files.length > 0) {
                                                Log.i(TAG, "Files in directory (" + files.length + "):");
                                                for (int i = 0; i < Math.min(files.length, 10); i++) {
                                                    Log.i(TAG, "  - " + files[i]);
                                                }
                                            } else {
                                                Log.i(TAG, "Parent directory is empty or unreadable");
                                            }
                                        } else {
                                            Log.i(TAG, "Parent directory doesn't exist");
                                        }
                                    }
                                }
                            } catch (Exception e2) {
                                Log.w(TAG, "Direct file access exception: " + e2.getMessage());
                                e2.printStackTrace();
                            }
                        }

                        if (albumArt == null) {
                            // Only log failure once per URI to avoid spam
                            if (trackChanged) {
                                Log.w(TAG, "✗ Failed to load album art from URI after all attempts: " + artUriString);
                            }
                            lastFailedArtUri = artUriString;
                        } else if (trackChanged) {
                            Log.i(TAG, "Album art loaded: " + albumArt.getWidth() + "x" + albumArt.getHeight());
                        }
                        if (trackChanged) {
                            Log.i(TAG, "======================");
                        }
                    } catch (Exception e) {
                        if (trackChanged) {
                            LogUtils.logWarning(TAG, "✗ Exception loading album art from URI: " + artUriString, e);
                        }
                        lastFailedArtUri = artUriString;
                        albumArt = null;
                    }
                }

                // Fallback to embedded bitmaps if URI loading fails
                if (albumArt == null) {
                    albumArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                    if (albumArt != null && trackChanged) {
                        Log.i(TAG, "✓ Using embedded METADATA_KEY_ALBUM_ART");
                    }
                }
                if (albumArt == null) {
                    albumArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
                    if (albumArt != null && trackChanged) {
                        Log.i(TAG, "✓ Using embedded METADATA_KEY_ART");
                    }
                }
                if (albumArt == null) {
                    albumArt = dabStationLogo(trackChanged);
                }

                boolean isPlaying = state != null &&
                        state.getState() == PlaybackState.STATE_PLAYING;

                listener.onMediaChanged(
                        title != null ? title : "Unknown",
                        artist != null ? artist : "Unknown Artist",
                        isPlaying,
                        albumArt);
            }
        } catch (Exception e) {
            LogUtils.logError(TAG, "Failed to get media info", e);
        }
    }

    /**
     * The tuned DAB station's logo, for when the radio session offers no art.
     *
     * <h3>Why the radio needs its own path</h3>
     * The radio's media session publishes a station name and RDS text but no
     * image, so the player tile sat blank on every broadcast station. The art does
     * exist though — the tuner receives a logo per DAB service and the radio app
     * stores it in its own database, which is also where it gets the logo it draws
     * on its own screen. See {@code RadioStationStore}.
     *
     * <p>
     * Only consulted after the metadata has been exhausted, and only for the radio
     * package, so nothing else pays for it. The cached decode means a station that
     * keeps re-reporting the same track does not re-read the database.
     */
    private Bitmap dabStationLogo(boolean trackChanged) {
        if (activeController == null
                || !RADIO_PACKAGE.equals(activeController.getPackageName())) {
            return null;
        }
        // The radio republishes its metadata many times a second while RDS text
        // scrolls - the log shows forty updates inside four seconds - and each one
        // reaches here. Without this the station lookup would be forty database
        // opens a second for an answer that changes when the station does.
        long now = SystemClock.uptimeMillis();
        if (!trackChanged && now - lastLogoLookup < LOGO_RECHECK_MS) {
            return cachedLogo;
        }
        lastLogoLookup = now;

        RadioClient.DabStation station = RadioStationStore.currentDabStation(this);
        if (station == null) {
            return null;
        }
        String key = RadioStationStore.logoKey(station);
        if (key.equals(cachedLogoKey)) {
            return cachedLogo;
        }
        byte[] encoded = RadioStationStore.dabLogo(this, station.serviceId, station.ensembleId);
        cachedLogoKey = key;
        cachedLogo = MediaArtLoader.decodeBytes(encoded, key);
        if (trackChanged) {
            Log.i(TAG, (cachedLogo != null ? "✓ Using the DAB station logo for "
                    : "No stored logo for ") + station.displayName());
        }
        return cachedLogo;
    }

    @Override
    public void onNotificationPosted(android.service.notification.StatusBarNotification sbn) {
        // Not needed for media control - we use MediaSessionManager
    }

    @Override
    public void onNotificationRemoved(android.service.notification.StatusBarNotification sbn) {
        // Not needed for media control - we use MediaSessionManager
    }
}
