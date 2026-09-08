package com.custom.launcher.media;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

/**
 * Wipes the stale AVRCP cover cache whenever a phone connects.
 *
 * <p>
 * Registered at runtime rather than in the manifest: it only needs to be live
 * while the launcher is, and a runtime receiver avoids being woken for devices
 * connecting when nothing is on screen.
 *
 * <p>
 * Both A2DP sink connection state and the plain ACL-connected broadcast are
 * watched, because whichever arrives first is the one that matters — the goal is
 * to clear the directory <em>before</em> the stack starts writing new covers.
 * Clearing twice is harmless.
 */
public class BluetoothConnectionReceiver extends BroadcastReceiver {
    private static final String TAG = "BluetoothArtCache";

    /**
     * Sink-side A2DP state action. Not a public constant, so it is spelled out;
     * on a head unit the car is the sink, and this is the broadcast that fires.
     */
    private static final String ACTION_A2DP_SINK_CONNECTION_STATE_CHANGED =
            "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED";

    private boolean registered;

    public void register(Context context) {
        if (registered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(ACTION_A2DP_SINK_CONNECTION_STATE_CHANGED);
        try {
            context.registerReceiver(this, filter);
            registered = true;
            Log.i(TAG, "Watching for Bluetooth connections to clear stale cover art");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register Bluetooth receiver: " + e.getMessage());
        }
    }

    public void unregister(Context context) {
        if (!registered) {
            return;
        }
        try {
            context.unregisterReceiver(this);
        } catch (Exception e) {
            Log.w(TAG, "Failed to unregister Bluetooth receiver: " + e.getMessage());
        }
        registered = false;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) {
            return;
        }

        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        String mac = device != null ? device.getAddress() : null;

        if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
            Log.i(TAG, "Bluetooth device connected (ACL)" + (mac != null ? ": " + mac : ""));
            clear(mac);
            return;
        }

        int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
        if (state == BluetoothProfile.STATE_CONNECTED) {
            Log.i(TAG, "A2DP connected" + (mac != null ? ": " + mac : ""));
            clear(mac);
        }
    }

    private void clear(String mac) {
        // The directory is named by MAC, but the stack's naming has been seen with
        // stray spaces, so fall back to clearing every device directory when the
        // per-device path finds nothing.
        int removed = 0;
        if (mac != null) {
            removed = BluetoothArtCache.clear(mac);
        }
        if (removed == 0) {
            BluetoothArtCache.clear(null);
        }
    }
}
