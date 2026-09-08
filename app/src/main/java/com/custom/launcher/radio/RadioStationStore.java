package com.custom.launcher.radio;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads station lists straight out of the stock radio app's own database.
 *
 * <h3>Why this exists</h3>
 * The binder route for a station list is broken in this ROM, and not on our side.
 * {@code IRadioManager.queryDabStationList()} (transaction 46) reaches the tuner
 * correctly — it is forwarded through {@code RadioService} to
 * {@code DabRadioStationManager} to {@code DabTuner}, and the tuner answers on
 * {@code DabTuner.Callback.onDabStationListInfoChanged}. But look at what
 * {@code RadioService}'s implementation of that callback does, decompiled from
 * {@code Radio_eh32_ll.apk}:
 *
 * <pre>
 * public void onDabStationListInfoChanged(RadioDabInfo.StationListInfo info) {
 *     mRadioDabStationList.clear();
 *     if (info.getListNum() &gt; 0) {
 *         mRadioDabStationList.addAll(RadioDabStationListUtil.getRadioDabStationList(info));
 *         if (mRadioStorage != null) {
 *             mRadioStorage.storeDabStationList(mRadioDabStationList);
 *         }
 *     }
 * }
 * </pre>
 *
 * It stores the list and returns. Every <em>other</em> DAB callback in that class
 * — mute, main info, DLS, slideshow — walks {@code mDabTunerCallbacks} and
 * broadcasts to every registered {@code IDabCallback}. This one does not, and
 * {@code notifyDabStationListInfoChanged} has <em>zero</em> call sites anywhere in
 * the service. Transaction 2 of {@code IDabCallback} is dead code on this head
 * unit: no client can ever receive a station list over the binder, however politely
 * or often it asks. That is why sixty seconds of polling produced nothing while
 * mute and now-playing events arrived the whole time.
 *
 * <p>
 * The stock UI does not use it either. {@code CarRadioDabPresenter} fills its list
 * from {@code RadioStorage.getDabStationList()} — the local database. So the
 * database is not a back door around the intended API; it <em>is</em> the intended
 * API on this ROM, and this class reads it the same way the stock app does.
 *
 * <h3>Why we are allowed to</h3>
 * {@code com.saicmotor.radio} declares {@code sharedUserId="android.uid.system"},
 * and so do we. Its database is a file owned by our own uid, so a plain SQLite
 * open works with no provider, no permission and no root.
 *
 * <h3>What is in there</h3>
 * From {@code RadioDatabase} (schema version 10):
 * <ul>
 *   <li>{@code dab_station_list_table} — the DAB service list, one row per service,
 *       with exactly the eight fields {@code RadioDabStation} marshals, so a row
 *       reconstructs a tunable station completely.</li>
 *   <li>{@code pre_scanned_table} — FM/AM sweep results, keyed by band.</li>
 *   <li>{@code dab_logo_info_list_table} — station logos as encoded-image blobs,
 *       several sizes per service.</li>
 *   <li>{@code dab_current_station_table} — the service currently tuned.</li>
 * </ul>
 * The tuner still has to be asked to refresh the table
 * ({@code queryDabStationList} or a sweep), because it only writes on a change —
 * and that write is {@code clearDabStationList()} followed by an
 * <em>asynchronous</em> insert, so a read can land on an empty or partly filled
 * table. Callers must poll until the row count stops moving rather than trusting
 * the first answer.
 */
public final class RadioStationStore {

    private static final String TAG = "RadioStore";

    private static final String RADIO_PACKAGE = "com.saicmotor.radio";
    private static final String DATABASE_NAME = "RadioDatabase";
    private static final String DAB_TABLE = "dab_station_list_table";
    private static final String SCAN_TABLE = "pre_scanned_table";
    private static final String LOGO_TABLE = "dab_logo_info_list_table";
    private static final String CURRENT_TABLE = "dab_current_station_table";

    /** Used when the radio package cannot be resolved; the standard data path. */
    private static final String FALLBACK_PATH =
            "/data/data/" + RADIO_PACKAGE + "/databases/" + DATABASE_NAME;

    private RadioStationStore() {
    }

    /**
     * The DAB service list the radio currently holds, in stored order.
     *
     * <p>
     * Empty when the table is empty, missing, or unreadable — the three are not
     * distinguished here because the caller's response to all of them is the same:
     * ask the tuner to refresh and look again.
     */
    public static List<RadioClient.DabStation> dabStations(Context context) {
        SQLiteDatabase db = open(context);
        if (db == null) {
            return Collections.emptyList();
        }
        List<RadioClient.DabStation> stations = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT pty, service_name, ensemble_name, ensemble_id,"
                + " frequency_index, frequency_channel, type, service_id FROM "
                + DAB_TABLE, null)) {
            while (c.moveToNext()) {
                stations.add(new RadioClient.DabStation(
                        c.getInt(0),      // pty
                        c.getString(1),   // service_name
                        c.getLong(7),     // service_id
                        c.getString(2),   // ensemble_name
                        c.getInt(3),      // ensemble_id
                        c.getInt(4),      // frequency_index
                        c.getInt(6),      // type
                        c.getInt(5)));    // frequency_channel
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not read " + DAB_TABLE + ": " + e);
        } finally {
            db.close();
        }
        Log.i(TAG, DAB_TABLE + " holds " + stations.size() + " row(s)");
        return stations;
    }

    /**
     * FM/AM stations from the radio's own sweep results, for one band.
     *
     * <p>
     * This is where {@code scan()} actually puts its findings. The FM half of the
     * interface reports a sweep on {@code IRadioCallback.onScanFinished}, but the
     * table is written regardless, so reading it works whether or not the callback
     * arrives.
     */
    public static List<RadioClient.Station> scannedStations(Context context, int band) {
        SQLiteDatabase db = open(context);
        if (db == null) {
            return Collections.emptyList();
        }
        List<RadioClient.Station> stations = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT channel_number, sub_channel, band, program_service"
                + " FROM " + SCAN_TABLE + " WHERE band = ?"
                + " ORDER BY channel_number, sub_channel",
                new String[] {String.valueOf(band)})) {
            while (c.moveToNext()) {
                stations.add(new RadioClient.Station(
                        c.getInt(0), c.getInt(1), c.getInt(2), c.getString(3), null, null));
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not read " + SCAN_TABLE + ": " + e);
        } finally {
            db.close();
        }
        Log.i(TAG, SCAN_TABLE + " holds " + stations.size() + " row(s) for band " + band);
        return stations;
    }

    /**
     * The station logo the radio holds for one DAB service, as encoded image bytes.
     *
     * <h3>Selection rule, copied from the stock app</h3>
     * {@code RadioStorage.getCurrentLogoByte()} matches on service id <em>and</em>
     * ensemble id — a service id alone is not unique across ensembles — and among
     * the matches keeps the one with the largest {@code logo_width}, because a
     * service is broadcast with several sizes. Hence the {@code ORDER BY
     * logo_width DESC LIMIT 1}.
     *
     * <p>
     * The blob is an ordinary encoded image; the stock app decodes it with
     * {@code BitmapFactory.decodeByteArray(buffer, 0, buffer.length)} and ignores
     * the {@code logo_len} column, so we do the same. The radio only stores logos
     * whose width and height are within ten pixels of each other, so what comes
     * back is always roughly square and suits a thumbnail.
     *
     * <p>
     * One row at a time rather than the whole table: these are the only blobs in
     * the database, and pulling all of them through a cursor at once risks the
     * 2 MB {@code CursorWindow} for art most of which is never shown.
     */
    public static byte[] dabLogo(Context context, long serviceId, int ensembleId) {
        SQLiteDatabase db = open(context);
        if (db == null) {
            return null;
        }
        try (Cursor c = db.rawQuery("SELECT logo_buffer FROM " + LOGO_TABLE
                        + " WHERE logo_service_id = ? AND logo_ensemble_id = ?"
                        + " ORDER BY logo_width DESC LIMIT 1",
                new String[] {String.valueOf(serviceId), String.valueOf(ensembleId)})) {
            if (c.moveToFirst()) {
                byte[] blob = c.getBlob(0);
                if (blob != null && blob.length > 0) {
                    return blob;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not read a logo for service " + serviceId + ": " + e);
        } finally {
            db.close();
        }
        return null;
    }

    /**
     * A stable cache key for one service's logo.
     *
     * <p>
     * Both ids, because a service id is only unique within its ensemble - the same
     * pairing {@link #dabLogo} selects on. Lives here so the browse list and the
     * player tile cannot drift into keying the same image differently.
     */
    public static String logoKey(RadioClient.DabStation station) {
        return "dab-logo:" + station.serviceId + "/" + station.ensembleId;
    }

    /**
     * The DAB service the radio is tuned to, or null.
     *
     * <p>
     * Read from {@code dab_current_station_table}, which carries the same eight
     * columns as the service list, so the answer is a complete station and can be
     * used to look its logo up.
     */
    public static RadioClient.DabStation currentDabStation(Context context) {
        SQLiteDatabase db = open(context);
        if (db == null) {
            return null;
        }
        try (Cursor c = db.rawQuery("SELECT pty, service_name, ensemble_name, ensemble_id,"
                + " frequency_index, frequency_channel, type, service_id FROM "
                + CURRENT_TABLE + " LIMIT 1", null)) {
            if (c.moveToFirst()) {
                return new RadioClient.DabStation(
                        c.getInt(0), c.getString(1), c.getLong(7), c.getString(2),
                        c.getInt(3), c.getInt(4), c.getInt(6), c.getInt(5));
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not read " + CURRENT_TABLE + ": " + e);
        } finally {
            db.close();
        }
        return null;
    }

    /** One line about whether the database is reachable, for the diagnostics screen. */
    public static String diagnostics(Context context) {
        File file = databaseFile(context);
        if (file == null) {
            return "radio database: path unknown";
        }
        if (!file.exists()) {
            return "radio database: missing (" + file + ")";
        }
        return "radio database: " + (file.canRead() ? "readable" : "NOT readable")
                + ", " + file.length() + " bytes";
    }

    /**
     * Opens the radio's database, or null.
     *
     * <p>
     * Read-only first, because we have no business writing to another app's
     * store. The read-write retry is not sloppiness: a database left in WAL mode
     * cannot be opened read-only when the {@code -wal} sidecar needs creating, and
     * failing on that would mean no station list at all for the sake of a flag we
     * never use.
     */
    private static SQLiteDatabase open(Context context) {
        File file = databaseFile(context);
        if (file == null || !file.exists()) {
            Log.w(TAG, "Radio database not found at " + file);
            return null;
        }
        try {
            return SQLiteDatabase.openDatabase(
                    file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        } catch (Exception readOnly) {
            Log.i(TAG, "Read-only open refused (" + readOnly + "); retrying read-write");
        }
        try {
            return SQLiteDatabase.openDatabase(
                    file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
        } catch (Exception e) {
            Log.w(TAG, "Could not open the radio database: " + e);
            return null;
        }
    }

    /**
     * Where the radio keeps its database.
     *
     * <p>
     * Asked of the platform through the radio's own context rather than assembled
     * by hand, so a multi-user or relocated data directory still resolves.
     * {@code createPackageContext} is allowed here because the two packages share
     * a uid.
     */
    private static File databaseFile(Context context) {
        try {
            Context radio = context.createPackageContext(RADIO_PACKAGE, 0);
            return radio.getDatabasePath(DATABASE_NAME);
        } catch (Exception e) {
            Log.i(TAG, "createPackageContext(" + RADIO_PACKAGE + ") failed (" + e
                    + "); using " + FALLBACK_PATH);
            return new File(FALLBACK_PATH);
        }
    }
}
