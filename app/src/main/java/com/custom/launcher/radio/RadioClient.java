package com.custom.launcher.radio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.util.Log;

/**
 * Talks to the head unit's radio over its real control interface.
 *
 * <h3>Why not MediaBrowser</h3>
 * {@code com.saicmotor.radio}'s {@code RadioMBService} declares a browse root and
 * then answers nothing — its {@code onLoadChildren} logs the parent id, calls
 * {@code result.detach()}, and never calls {@code sendResult()}. Any station list
 * built on {@code MediaBrowser} therefore hangs on "Loading" forever, which is
 * exactly what happened on the car. The station list is simply not published that
 * way.
 *
 * <h3>Where the station list actually lives</h3>
 * Two places, neither of them a public API:
 *
 * <ul>
 * <li><b>On disk</b>, in the radio app's private SQLite database
 * {@code /data/data/com.saicmotor.radio/databases/RadioDatabase} — tables
 * {@code favorite_table} (presets: channel_number, sub_channel, band,
 * program_service), {@code pre_scanned_table} (auto-scan results) and the
 * {@code dab_*_table} family. There is <em>no</em> ContentProvider anywhere in
 * the APK, and another app's data directory is mode 0700, so running as system
 * uid does not help: this is unreadable from here.</li>
 * <li><b>Over binder</b>, from {@code com.android.car.radio.service.IRadioManager},
 * which {@code RadioService} returns from {@code onBind}. It has no list getter
 * either — the list arrives asynchronously through an {@code IRadioCallback} we
 * have to host ourselves, as {@code onScanFinished(band, List&lt;RadioStation&gt;)}
 * after a {@code scan()}.</li>
 * </ul>
 *
 * So a real station list costs a band scan, which retunes the radio and takes
 * time. That is why {@link #scan()} is only ever driven by an explicit user
 * action, never on entering a screen.
 *
 * <h3>Wire format</h3>
 * Plain AIDL, so the transactions are hand-issued rather than generated:
 * {@code writeInterfaceToken}, args, then {@code readException()} and the result.
 * Descriptor, transaction numbers and the {@code RadioStation}/{@code RadioRds}
 * parcel layouts were read out of {@code Radio_eh32_ll.apk} in the R33 SWI69 1100
 * firmware. {@code android.car}'s radio classes are not on our classpath, hence
 * the manual decode.
 */
public class RadioClient {
    private static final String TAG = "RadioClient";

    private static final String RADIO_PACKAGE = "com.saicmotor.radio";
    private static final String RADIO_SERVICE_ACTION = "com.saicmotor.IRadioService";

    private static final String IRADIO_MANAGER = "com.android.car.radio.service.IRadioManager";
    private static final String IRADIO_CALLBACK = "com.android.car.radio.service.IRadioCallback";

    // IRadioManager transactions.
    private static final int TXN_TUNE = 1;
    private static final int TXN_SCAN = 8;
    private static final int TXN_MUTE = 9;
    private static final int TXN_UN_MUTE = 11;
    private static final int TXN_IS_MUTED = 12;
    private static final int TXN_OPEN_RADIO_BAND = 13;
    private static final int TXN_ADD_RADIO_TUNER_CALLBACK = 14;
    private static final int TXN_REMOVE_RADIO_TUNER_CALLBACK = 15;
    private static final int TXN_GET_CURRENT_RADIO_STATION = 16;
    private static final int TXN_IS_INITIALIZED = 17;
    private static final int TXN_HAS_FOCUS = 18;
    private static final int TXN_IS_FAVORITE_LIST_EMPTY = 26;
    private static final int TXN_ON_MEDIA_SESSION_PLAY = 30;
    private static final int TXN_ON_MEDIA_SESSION_SKIP_TO_NEXT = 32;
    private static final int TXN_ON_MEDIA_SESSION_SKIP_TO_PREVIOUS = 33;
    private static final int TXN_RESUME_RADIO_PLAYING = 47;

    // IRadioCallback transactions we care about; the rest are acknowledged and dropped.
    private static final int CB_ON_RADIO_STATION_CHANGED = 1;
    private static final int CB_ON_RADIO_METADATA_CHANGED = 2;
    private static final int CB_ON_RADIO_BAND_CHANGED = 3;
    private static final int CB_ON_SCAN_STARTED = 4;
    private static final int CB_ON_SCAN_FINISHED = 5;
    private static final int CB_ON_RADIO_MUTE_CHANGED = 8;

    public static final int BAND_AM = 0;
    public static final int BAND_FM = 1;

    /** One tuned station, flattened out of {@code RadioStation} + {@code RadioRds}. */
    public static class Station {
        public final int channelNumber;
        public final int subChannel;
        public final int band;
        public final String programService;
        public final String artist;
        public final String title;

        Station(int channelNumber, int subChannel, int band,
                String programService, String artist, String title) {
            this.channelNumber = channelNumber;
            this.subChannel = subChannel;
            this.band = band;
            this.programService = programService;
            this.artist = artist;
            this.title = title;
        }

        /**
         * The channel number is in kHz for FM on this platform (e.g. 98700), so a
         * plain division gives the dial frequency the dash shows.
         */
        public String frequencyLabel() {
            if (band == BAND_FM) {
                return String.format(java.util.Locale.US, "%.1f FM", channelNumber / 1000f);
            }
            if (band == BAND_AM) {
                return channelNumber + " AM";
            }
            return "band " + band + " ch " + channelNumber;
        }

        public String displayName() {
            if (programService != null && !programService.trim().isEmpty()) {
                return programService.trim();
            }
            return frequencyLabel();
        }

        @Override
        public String toString() {
            return "Station{" + frequencyLabel() + ", ps='" + programService + "'}";
        }
    }

    public interface Listener {
        void onRadioReady();

        void onRadioLost();

        void onStationChanged(Station station);

        /** Delivered after {@link #scan()} completes. */
        void onScanFinished(int band, List<Station> stations);

        void onScanStarted(int band);
    }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private IBinder radioManager;
    private boolean bindRequested;
    private boolean callbackRegistered;

    /**
     * The callback binder handed to the radio. Subclassing {@link Binder} and
     * decoding {@code onTransact} by hand is the only option: the generated
     * {@code IRadioCallback.Stub} lives in the radio's own APK, not in any
     * framework jar we compile against.
     */
    private final Binder callback = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            switch (code) {
                case CB_ON_RADIO_STATION_CHANGED: {
                    data.enforceInterface(IRADIO_CALLBACK);
                    Station station = readNullableStation(data);
                    if (reply != null) {
                        reply.writeNoException();
                    }
                    post(() -> listener.onStationChanged(station));
                    return true;
                }
                case CB_ON_RADIO_METADATA_CHANGED: {
                    data.enforceInterface(IRADIO_CALLBACK);
                    // RDS only; the station callback carries the same text.
                    if (reply != null) {
                        reply.writeNoException();
                    }
                    return true;
                }
                case CB_ON_SCAN_STARTED: {
                    data.enforceInterface(IRADIO_CALLBACK);
                    int band = data.readInt();
                    if (reply != null) {
                        reply.writeNoException();
                    }
                    Log.i(TAG, "Scan started on band " + band);
                    post(() -> listener.onScanStarted(band));
                    return true;
                }
                case CB_ON_SCAN_FINISHED: {
                    data.enforceInterface(IRADIO_CALLBACK);
                    int band = data.readInt();
                    List<Station> stations = readStationList(data);
                    if (reply != null) {
                        reply.writeNoException();
                    }
                    Log.i(TAG, "Scan finished on band " + band + " with "
                            + stations.size() + " station(s)");
                    post(() -> listener.onScanFinished(band, stations));
                    return true;
                }
                case CB_ON_RADIO_BAND_CHANGED:
                case CB_ON_RADIO_MUTE_CHANGED: {
                    data.enforceInterface(IRADIO_CALLBACK);
                    int value = data.readInt();
                    if (reply != null) {
                        reply.writeNoException();
                    }
                    Log.i(TAG, (code == CB_ON_RADIO_BAND_CHANGED ? "Band" : "Mute")
                            + " changed to " + value);
                    return true;
                }
                default:
                    // Every other callback is a two-way AIDL call, so it has to be
                    // acknowledged even though we ignore the payload - otherwise the
                    // radio blocks waiting for a reply it never gets.
                    if (reply != null) {
                        reply.writeNoException();
                    }
                    return true;
            }
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            radioManager = service;
            Log.i(TAG, "RadioService connected: " + name.flattenToShortString()
                    + ", initialised=" + isInitialised() + ", muted=" + isMuted());
            registerCallback();
            listener.onRadioReady();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "RadioService disconnected");
            radioManager = null;
            callbackRegistered = false;
            listener.onRadioLost();
        }
    };

    public RadioClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        callback.attachInterface(null, IRADIO_CALLBACK);
    }

    public boolean isReady() {
        return radioManager != null && radioManager.pingBinder();
    }

    public void bind() {
        if (bindRequested) {
            return;
        }
        Intent intent = new Intent(RADIO_SERVICE_ACTION);
        intent.setPackage(RADIO_PACKAGE);
        try {
            bindRequested = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            Log.i(TAG, "bindService(" + RADIO_SERVICE_ACTION + ") returned " + bindRequested);
        } catch (Exception e) {
            Log.e(TAG, "Failed to bind RadioService: " + e);
        }
    }

    public void unbind() {
        if (!bindRequested) {
            return;
        }
        if (callbackRegistered) {
            callVoid(TXN_REMOVE_RADIO_TUNER_CALLBACK, callback);
            callbackRegistered = false;
        }
        try {
            context.unbindService(connection);
        } catch (Exception e) {
            Log.w(TAG, "Failed to unbind RadioService: " + e);
        }
        bindRequested = false;
        radioManager = null;
    }

    private void registerCallback() {
        if (callbackRegistered || radioManager == null) {
            return;
        }
        callbackRegistered = callVoid(TXN_ADD_RADIO_TUNER_CALLBACK, callback);
        Log.i(TAG, "addRadioTunerCallback succeeded=" + callbackRegistered);
    }

    // --- commands ---

    /**
     * Everything needed to get audible radio from a cold, muted boot. The car
     * comes up with the radio holding the source but muted, so unmuting has to be
     * explicit — {@code play()} on the media session alone leaves it silent.
     */
    public void startPlaying() {
        callVoid(TXN_RESUME_RADIO_PLAYING);
        callVoid(TXN_ON_MEDIA_SESSION_PLAY);
        boolean unmuted = callBoolean(TXN_UN_MUTE);
        Log.i(TAG, "startPlaying: unMute returned " + unmuted
                + ", muted is now " + isMuted());
    }

    public void nextStation() {
        callVoid(TXN_ON_MEDIA_SESSION_SKIP_TO_NEXT);
    }

    public void previousStation() {
        callVoid(TXN_ON_MEDIA_SESSION_SKIP_TO_PREVIOUS);
    }

    public void setMuted(boolean muted) {
        if (muted) {
            callBooleanArg(TXN_MUTE, true);
        } else {
            callBoolean(TXN_UN_MUTE);
        }
    }

    public boolean isMuted() {
        return callBoolean(TXN_IS_MUTED);
    }

    public boolean isInitialised() {
        return callBoolean(TXN_IS_INITIALIZED);
    }

    public boolean hasFocus() {
        return callBoolean(TXN_HAS_FOCUS);
    }

    public boolean isFavouriteListEmpty() {
        return callBoolean(TXN_IS_FAVORITE_LIST_EMPTY);
    }

    /** Selects AM or FM. Returns the band the radio reports it opened. */
    public int openBand(int band) {
        IBinder binder = radioManager;
        if (binder == null) {
            return -1;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IRADIO_MANAGER);
            data.writeInt(band);
            binder.transact(TXN_OPEN_RADIO_BAND, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } catch (Exception e) {
            Log.w(TAG, "openRadioBand(" + band + ") failed: " + e);
            return -1;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * Starts a band scan. The result arrives later via
     * {@link Listener#onScanFinished}; this is the only route to a station list.
     * It retunes the radio while it runs, so only call it from a deliberate user
     * action.
     */
    public void scan() {
        callVoid(TXN_SCAN);
    }

    /** Tunes a station previously returned by a scan. */
    public void tune(Station station) {
        IBinder binder = radioManager;
        if (binder == null || station == null) {
            return;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IRADIO_MANAGER);
            // Nullable Parcelable: presence flag, then RadioStation's own layout.
            data.writeInt(1);
            data.writeInt(station.channelNumber);
            data.writeInt(station.subChannel);
            data.writeInt(station.band);
            writeNullableRds(data, station);
            binder.transact(TXN_TUNE, data, reply, 0);
            reply.readException();
            Log.i(TAG, "Tuned " + station);
        } catch (Exception e) {
            Log.w(TAG, "tune(" + station + ") failed: " + e);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    public Station getCurrentStation() {
        IBinder binder = radioManager;
        if (binder == null) {
            return null;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IRADIO_MANAGER);
            binder.transact(TXN_GET_CURRENT_RADIO_STATION, data, reply, 0);
            reply.readException();
            return readNullableStation(reply);
        } catch (Exception e) {
            Log.w(TAG, "getCurrentRadioStation() failed: " + e);
            return null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    // --- transaction helpers ---

    private boolean callVoid(int transaction) {
        return callVoid(transaction, null);
    }

    private boolean callVoid(int transaction, IBinder binderArg) {
        IBinder binder = radioManager;
        if (binder == null) {
            return false;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IRADIO_MANAGER);
            if (binderArg != null) {
                data.writeStrongBinder(binderArg);
            }
            binder.transact(transaction, data, reply, 0);
            reply.readException();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "transaction " + transaction + " failed: " + e);
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private boolean callBoolean(int transaction) {
        return callBooleanInternal(transaction, false, false);
    }

    private boolean callBooleanArg(int transaction, boolean arg) {
        return callBooleanInternal(transaction, true, arg);
    }

    private boolean callBooleanInternal(int transaction, boolean hasArg, boolean arg) {
        IBinder binder = radioManager;
        if (binder == null) {
            return false;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IRADIO_MANAGER);
            if (hasArg) {
                data.writeInt(arg ? 1 : 0);
            }
            binder.transact(transaction, data, reply, 0);
            reply.readException();
            return reply.readInt() != 0;
        } catch (Exception e) {
            Log.w(TAG, "transaction " + transaction + " failed: " + e);
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    // --- parcel decoding ---

    /** Reads AIDL's nullable-Parcelable shape: an int flag, then the object. */
    private static Station readNullableStation(Parcel parcel) {
        if (parcel.readInt() == 0) {
            return null;
        }
        return readStation(parcel);
    }

    private static Station readStation(Parcel parcel) {
        int channel = parcel.readInt();
        int subChannel = parcel.readInt();
        int band = parcel.readInt();

        // RadioStation writes its RadioRds with writeParcelable, which prefixes the
        // class name; a null Rds is written as a null name.
        String programService = null;
        String artist = null;
        String title = null;
        String className = parcel.readString();
        if (className != null) {
            programService = parcel.readString();
            artist = parcel.readString();
            title = parcel.readString();
        }
        return new Station(channel, subChannel, band, programService, artist, title);
    }

    private static void writeNullableRds(Parcel parcel, Station station) {
        if (station.programService == null && station.artist == null && station.title == null) {
            parcel.writeString(null);
            return;
        }
        parcel.writeString("com.android.car.radio.service.RadioRds");
        parcel.writeString(station.programService);
        parcel.writeString(station.artist);
        parcel.writeString(station.title);
    }

    /**
     * Reads {@code createTypedArrayList}'s shape: a count, then per entry a
     * presence flag followed by the item.
     */
    private static List<Station> readStationList(Parcel parcel) {
        int count = parcel.readInt();
        if (count <= 0) {
            return Collections.emptyList();
        }
        List<Station> stations = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (parcel.readInt() != 0) {
                stations.add(readStation(parcel));
            }
        }
        return stations;
    }

    private void post(Runnable r) {
        main.post(r);
    }
}
