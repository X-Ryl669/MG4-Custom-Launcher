package com.custom.launcher.media;

import java.io.File;

import android.util.Log;

/**
 * Clears the Bluetooth stack's cached cover art.
 *
 * <p>
 * On this head unit the stack writes them <em>flat</em> into
 * {@code /storage/emulated/0/bluetooth/}, one file per image, named
 * {@code <MAC>_AVRCP_IMG_<uid>.jpg} — confirmed on-car with the sideloaded
 * terminal. This code originally looked for per-device subdirectories, which is
 * the layout other AOSP builds use, and consequently deleted nothing at all.
 *
 * <p>
 * The uid in the name is the AVRCP <em>item UID</em>, which the phone assigns per
 * browsing session. After a reconnect the same uid usually refers to a different
 * track, so the stale JPG from last time is served against the new song, and the
 * first few tracks after every reconnect show the wrong cover.
 *
 * <p>
 * The fix is to empty that directory when a device connects, before playback
 * starts, so the stack has to re-fetch. We can do this because the launcher runs
 * as system UID with storage permissions.
 */
public final class BluetoothArtCache {
    private static final String TAG = "BluetoothArtCache";

    private static final String ART_ROOT = "/storage/emulated/0/bluetooth";

    /** Only ever delete image files, never anything else that lives here. */
    private static final String[] IMAGE_SUFFIXES = { ".jpg", ".jpeg", ".png", ".bmp" };

    /** Marker in the flat file names the stack writes, e.g. "AA:BB_AVRCP_IMG_7654321.jpg". */
    private static final String AVRCP_MARKER = "AVRCP_IMG";

    private BluetoothArtCache() {
    }

    /**
     * Deletes cached cover images for one device, or for every device when
     * {@code macAddress} is null (the address is not always known at connect
     * time). Returns the number of files removed.
     *
     * <p>
     * Both on-disk layouts are handled: the flat {@code <MAC>_AVRCP_IMG_<uid>.jpg}
     * files this car actually writes, and per-MAC subdirectories in case another
     * build of the stack does it that way.
     */
    public static int clear(String macAddress) {
        File root = new File(ART_ROOT);
        if (!root.isDirectory()) {
            Log.i(TAG, "No Bluetooth art cache at " + ART_ROOT + " - nothing to clear");
            return 0;
        }

        File[] entries = root.listFiles();
        if (entries == null) {
            Log.w(TAG, "Could not list " + ART_ROOT + " - missing storage permission?");
            return 0;
        }

        int removed = 0;
        for (File entry : entries) {
            if (entry.isDirectory()) {
                // Legacy per-device layout: only descend into the matching one.
                if (macAddress == null || matchesMac(entry.getName(), macAddress)) {
                    removed += clearDir(entry);
                }
            } else if (isCoverFile(entry.getName(), macAddress) && delete(entry)) {
                removed++;
            }
        }

        Log.i(TAG, "Cleared " + removed + " cached cover file(s)"
                + (macAddress != null ? " for " + macAddress : " across all devices")
                + " in " + ART_ROOT);
        return removed;
    }

    private static int clearDir(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            Log.w(TAG, "Could not list " + dir + " - missing storage permission?");
            return 0;
        }
        int removed = 0;
        for (File f : files) {
            if (f.isFile() && isImage(f.getName()) && delete(f)) {
                removed++;
            }
        }
        return removed;
    }

    private static boolean delete(File f) {
        if (f.delete()) {
            return true;
        }
        Log.w(TAG, "Failed to delete stale cover " + f.getName());
        return false;
    }

    /**
     * True for a cover file we are willing to delete. When a MAC is known we
     * restrict to that device's files; the stack has been seen writing addresses
     * with stray spaces, so the comparison is loose.
     */
    private static boolean isCoverFile(String name, String macAddress) {
        if (!isImage(name)) {
            return false;
        }
        if (!name.contains(AVRCP_MARKER)) {
            // Something else put an image here; leave it alone.
            return false;
        }
        return macAddress == null || matchesMac(name, macAddress);
    }

    private static boolean matchesMac(String name, String macAddress) {
        return normalise(name).contains(normalise(macAddress));
    }

    /** Strips separators and case so "38:E5:63" matches "38e563" and "38 : e5". */
    private static String normalise(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    private static boolean isImage(String name) {
        String lower = name.toLowerCase(java.util.Locale.US);
        for (String suffix : IMAGE_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }
}
