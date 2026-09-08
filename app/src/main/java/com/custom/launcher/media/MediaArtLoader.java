package com.custom.launcher.media;

import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

/**
 * Loads cover thumbnails for rows in the browse list.
 *
 * <p>
 * The browse tree hands us a {@code MediaDescription} per item, whose icon is
 * either an inline {@code Bitmap} or a {@code Uri}. Over Bluetooth it is nearly
 * always a Uri pointing into {@code /storage/emulated/0/bluetooth/}, so every row
 * that shows a cover means a file read and a JPEG decode — which is exactly the
 * work that must not happen on the main thread of a scrolling list on this
 * hardware.
 *
 * <p>
 * Hence: one background thread, an in-memory cache, and downsampling at decode
 * time. A list row is 56dp of thumbnail, and a phone's cover art is routinely
 * 500x500 or larger; decoding those at full size would burn about 40x the memory
 * per row for no visible gain.
 *
 * <h3>Stale binds</h3>
 * {@code ListView} recycles its rows, so by the time a decode finishes the
 * {@code ImageView} that asked for it may have been re-bound to a different
 * track. Each request stamps its key on the view via {@code setTag} and the
 * result is dropped unless the stamp still matches.
 */
public final class MediaArtLoader {
    private static final String TAG = "MediaArtLoader";

    /** Longest edge we ever need on screen, in pixels. Rows are 56dp at DPI 160. */
    private static final int THUMB_PX = 128;

    /**
     * Decoded thumbnails are ~64KB each at THUMB_PX, so this is a couple of MB —
     * cheap next to the win of not re-reading storage on every scroll.
     */
    private static final int CACHE_ENTRIES = 48;

    /**
     * Serial, not a pool. The bottleneck is a slow eMMC and these decodes are
     * strictly nice-to-have; several threads fighting over it would make the list
     * scroll worse, not better.
     */
    private static final Executor IO = Executors.newSingleThreadExecutor();

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(CACHE_ENTRIES);

    /** Marker for "we tried this key and there is no image", so we stop retrying. */
    private static final Bitmap MISS = Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8);

    private MediaArtLoader() {
    }

    /**
     * Art that has to be fetched rather than read from a uri, plus the key to
     * cache it under.
     *
     * <p>
     * Exists for DAB station logos, which live as blobs in the radio app's own
     * database and so have no uri to hand to a {@code ContentResolver}. Everything
     * else about loading them is identical to a cover — off the main thread, cached,
     * downsampled, dropped if the row was recycled — so they come through the same
     * machinery rather than a parallel copy of it.
     */
    public interface ByteSource {
        /** Stable identity of this image, for caching. Must not hit storage. */
        String key();

        /** The encoded image, read on a background thread. Null when there is none. */
        byte[] bytes(Context context);
    }

    /**
     * Shows the image {@code source} supplies, falling back to
     * {@code placeholderRes}.
     *
     * @param view           the row's thumbnail, whose tag this method owns
     * @param source         where to get the bytes, or null for no art at all
     * @param placeholderRes drawable to show while loading and when there is none
     */
    public static void bind(ImageView view, ByteSource source, int placeholderRes) {
        if (view == null) {
            return;
        }
        view.setClipToOutline(true);

        if (source == null) {
            view.setTag(null);
            showPlaceholder(view, placeholderRes);
            return;
        }

        String key = source.key();
        view.setTag(key);

        Bitmap cached = CACHE.get(key);
        if (cached != null) {
            if (cached == MISS) {
                showPlaceholder(view, placeholderRes);
            } else {
                showCover(view, cached);
            }
            return;
        }

        showPlaceholder(view, placeholderRes);

        Context context = view.getContext().getApplicationContext();
        IO.execute(() -> {
            Bitmap loaded = decodeBytes(source.bytes(context), key);
            CACHE.put(key, loaded != null ? loaded : MISS);
            if (loaded == null) {
                return;
            }
            MAIN.post(() -> {
                if (key.equals(view.getTag())) {
                    showCover(view, loaded);
                }
            });
        });
    }

    /**
     * Shows the cover for one row, falling back to {@code placeholderRes}.
     *
     * @param view          the row's thumbnail, whose tag this method owns
     * @param inline        an already-decoded bitmap from the media description, if any
     * @param iconUri       where to read the cover from, if any
     * @param placeholderRes drawable to show while loading and when there is no cover
     */
    public static void bind(ImageView view, Bitmap inline, Uri iconUri, int placeholderRes) {
        if (view == null) {
            return;
        }

        // Clips a square cover to the rounded tile behind it. Set here rather than
        // in the layout because there is no public XML attribute for it.
        view.setClipToOutline(true);

        if (inline != null) {
            view.setTag(null);
            showCover(view, inline);
            return;
        }

        if (iconUri == null) {
            view.setTag(null);
            showPlaceholder(view, placeholderRes);
            return;
        }

        String key = iconUri.toString();
        view.setTag(key);

        Bitmap cached = CACHE.get(key);
        if (cached != null) {
            if (cached == MISS) {
                showPlaceholder(view, placeholderRes);
            } else {
                showCover(view, cached);
            }
            return;
        }

        // Nothing yet: show the placeholder rather than whatever the recycled row
        // was showing a moment ago.
        showPlaceholder(view, placeholderRes);

        Context context = view.getContext().getApplicationContext();
        IO.execute(() -> {
            Bitmap loaded = decode(context, iconUri);
            CACHE.put(key, loaded != null ? loaded : MISS);
            if (loaded == null) {
                return;
            }
            MAIN.post(() -> {
                if (key.equals(view.getTag())) {
                    showCover(view, loaded);
                }
            });
        });
    }

    /**
     * A cover fills the tile; a glyph sits centred inside it at its own size. The
     * two need different scaling, and a recycled row can arrive set up for either.
     */
    private static void showCover(ImageView view, Bitmap bitmap) {
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setPadding(0, 0, 0, 0);
        view.setImageBitmap(bitmap);
    }

    private static void showPlaceholder(ImageView view, int drawableRes) {
        int inset = Math.round(14 * view.getResources().getDisplayMetrics().density);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setPadding(inset, inset, inset, inset);
        view.setImageResource(drawableRes);
    }

    /** Drops the cache, so a reconnect cannot serve last session's covers. */
    public static void clearCache() {
        CACHE.evictAll();
    }

    // --- decoding ---

    private static Bitmap decode(Context context, Uri uri) {
        String path = normalisePath(uri.toString());

        // A content:// uri has to go through the resolver; a plain path is faster
        // read directly, and on this car the Bluetooth stack hands us plain paths.
        if (path.startsWith("/")) {
            return decodeFile(path);
        }
        Bitmap viaResolver = decodeStream(context, uri);
        if (viaResolver != null) {
            return viaResolver;
        }
        // Some uris are file:// wrappers around a path we can just open.
        String stripped = uri.getPath();
        return stripped != null ? decodeFile(normalisePath(stripped)) : null;
    }

    private static Bitmap decodeFile(String path) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight);
            return BitmapFactory.decodeFile(path, opts);
        } catch (Exception e) {
            Log.d(TAG, "Could not decode " + path + ": " + e);
            return null;
        } catch (OutOfMemoryError e) {
            Log.w(TAG, "Out of memory decoding " + path);
            return null;
        }
    }

    private static Bitmap decodeStream(Context context, Uri uri) {
        ContentResolver resolver = context.getContentResolver();
        try (InputStream in = resolver.openInputStream(uri)) {
            // No bounds pass here: the stream is not rewindable, and one decode of
            // a cover-sized image is affordable when it is the uncommon path.
            return in != null ? BitmapFactory.decodeStream(in) : null;
        } catch (Exception e) {
            Log.d(TAG, "Resolver could not open " + uri + ": " + e);
            return null;
        } catch (OutOfMemoryError e) {
            Log.w(TAG, "Out of memory decoding " + uri);
            return null;
        }
    }

    /**
     * Decodes an in-memory image, downsampled the same way a file is.
     *
     * <p>
     * The full length of the array is decoded rather than any length field that
     * came with it, which is what the stock radio app does with these blobs too.
     */
    public static Bitmap decodeBytes(byte[] encoded, String what) {
        if (encoded == null || encoded.length == 0) {
            return null;
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(encoded, 0, encoded.length, bounds);

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight);
            return BitmapFactory.decodeByteArray(encoded, 0, encoded.length, opts);
        } catch (Exception e) {
            Log.d(TAG, "Could not decode " + what + ": " + e);
            return null;
        } catch (OutOfMemoryError e) {
            Log.w(TAG, "Out of memory decoding " + what);
            return null;
        }
    }

    private static int sampleSizeFor(int width, int height) {
        int longest = Math.max(width, height);
        int sample = 1;
        while (longest / (sample * 2) >= THUMB_PX) {
            sample *= 2;
        }
        return sample;
    }

    /**
     * Undoes the two ways this head unit mangles cover paths: percent-encoding, and
     * the stray spaces the Bluetooth stack sprinkles through MAC addresses
     * ({@code XX:XX:XX: XX: XX: XX}). Both were found the hard way in
     * {@code MediaListenerService}, which does the same repairs for the now-playing
     * art.
     */
    static String normalisePath(String raw) {
        String path = raw;
        try {
            path = URLDecoder.decode(path, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            // Not encoded, or encoded wrongly; either way the original is our best bet.
        }
        if (path.contains("bluetooth/") && path.contains(": ")) {
            path = path.replace(": ", ":");
        }
        if (path.startsWith("file://")) {
            path = path.substring("file://".length());
        }
        return path;
    }
}
