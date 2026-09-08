package com.custom.launcher.car;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

/**
 * Gateway to the MG4 SE/Standard car API.
 *
 * <p>
 * On this trim every car subsystem is reached through a single binder pool
 * hosted by {@code com.saicmotor.caradapter/.CarAdapterService}. You bind that
 * service once, then ask it for a per-subsystem binder with
 * {@code queryClient(id)}. This is the SE equivalent of the Trophy
 * {@code com.saicmotor.service.vehicle.VehicleService} the rest of this app was
 * originally written against.
 *
 * <p>
 * Rather than vendor several thousand lines of generated AIDL stubs for the six
 * calls we actually make, the transactions are issued directly against the
 * binder. The descriptors, transaction numbers and client ids below were read
 * out of the stock SE launcher ({@code com.saicmotor.launcher} v105) and the
 * R33 SWI69 1100 firmware — see {@link HvacClient} for the HVAC ones.
 */
public class CarAdapter {
    private static final String TAG = "CarAdapter";

    private static final String SERVICE_PACKAGE = "com.saicmotor.caradapter";
    private static final String SERVICE_ACTION = "com.saicmotor.caradapter.CarAdapterService";
    private static final String DESCRIPTOR = "com.saicmotor.carapi.ICarAdapterService";

    private static final int TRANSACTION_QUERY_CLIENT = 1;
    private static final int TRANSACTION_IS_SERVICE_INIT = 2;

    /** Subsystem ids accepted by {@code queryClient}. */
    public static final int CLIENT_HVAC = 7;

    public interface Listener {
        /** Called on the main thread once the adapter is usable. */
        void onCarAdapterReady(CarAdapter adapter);

        void onCarAdapterLost();
    }

    private final Context context;
    private final Listener listener;
    private IBinder adapterBinder;
    private boolean bindRequested;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            adapterBinder = service;
            Log.i(TAG, "CarAdapterService connected");
            if (listener != null) {
                listener.onCarAdapterReady(CarAdapter.this);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            adapterBinder = null;
            Log.w(TAG, "CarAdapterService disconnected");
            if (listener != null) {
                listener.onCarAdapterLost();
            }
        }
    };

    public CarAdapter(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    /** True once the adapter service is bound. */
    public boolean isConnected() {
        return adapterBinder != null;
    }

    public void bind() {
        if (bindRequested) {
            return;
        }
        try {
            Intent intent = new Intent(SERVICE_ACTION);
            intent.setPackage(SERVICE_PACKAGE);
            bindRequested = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            Log.i(TAG, "bindService(CarAdapterService) returned " + bindRequested);
            if (!bindRequested) {
                // Expected on the emulator and on a Trophy unit, where this
                // package does not exist.
                Log.w(TAG, SERVICE_PACKAGE + " is not available on this head unit");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to bind CarAdapterService: " + e.getMessage());
        }
    }

    public void unbind() {
        if (!bindRequested) {
            return;
        }
        try {
            context.unbindService(connection);
        } catch (Exception e) {
            Log.w(TAG, "Failed to unbind CarAdapterService: " + e.getMessage());
        }
        bindRequested = false;
        adapterBinder = null;
    }

    /**
     * The adapter reports when its own vehicle-signal plumbing has finished
     * starting up. Values read before this returns true may be stale defaults.
     */
    public boolean isServiceInit() {
        IBinder binder = adapterBinder;
        if (binder == null) {
            return false;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            binder.transact(TRANSACTION_IS_SERVICE_INIT, data, reply, 0);
            reply.readException();
            return reply.readInt() != 0;
        } catch (RemoteException e) {
            Log.w(TAG, "isServiceInit failed: " + e.getMessage());
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * Returns the binder for one subsystem, or null if the adapter isn't
     * connected or doesn't provide that client.
     */
    public IBinder queryClient(int clientId) {
        IBinder binder = adapterBinder;
        if (binder == null) {
            return null;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(clientId);
            binder.transact(TRANSACTION_QUERY_CLIENT, data, reply, 0);
            reply.readException();
            return reply.readStrongBinder();
        } catch (RemoteException e) {
            Log.w(TAG, "queryClient(" + clientId + ") failed: " + e.getMessage());
            return null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
