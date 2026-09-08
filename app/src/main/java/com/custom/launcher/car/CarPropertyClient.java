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
 * Reads vehicle properties from AAOS CarService on the MG4 SE/Standard.
 *
 * <p>
 * This is a different path from {@link CarAdapter}: energy data does not come
 * through SAIC's {@code caradapter} binder pool at all. The stock energy app
 * reaches it via SAIC's <em>extended</em> {@code android.car}, whose
 * {@code CarBMSManager} is a vendor addition on top of AOSP's property API. The
 * chain is:
 *
 * <pre>
 *   bindService(action "android.car.ICar", package "com.android.car")
 *     → ICar.getCarService("bms")            // txn 2
 *       → ICarProperty.getProperty(id, area) // txn 4  → CarPropertyValue
 * </pre>
 *
 * <p>
 * {@code android.car} is <em>statically linked</em> into each app on this
 * platform — {@code EvCharge.apk} bundles its own copy and declares no
 * {@code <uses-library android:name="android.car"/>} — so those classes are not
 * on our classpath at runtime and cannot simply be imported. Rather than vendor a
 * whole car-lib, this issues the three transactions directly. Descriptors,
 * transaction numbers and the {@code CarPropertyValue} wire format were all read
 * out of the firmware's own copy.
 */
public class CarPropertyClient {
    private static final String TAG = "CarPropertyClient";

    private static final String CAR_SERVICE_PACKAGE = "com.android.car";
    private static final String ICAR_DESCRIPTOR = "android.car.ICar";
    private static final int TRANSACTION_GET_CAR_SERVICE = 2;

    private static final String ICAR_PROPERTY_DESCRIPTOR = "android.car.hardware.property.ICarProperty";
    private static final int TRANSACTION_GET_PROPERTY = 4;

    /** Name of the vendor battery-management service in CarService's registry. */
    private static final String SERVICE_BMS = "bms";

    /**
     * Area id to pass to {@code getProperty}.
     *
     * <p>
     * AOSP uses 0 for global properties, but SAIC's own client code does not:
     * CarService's {@code VehicleHal.get()} logs every read the stock apps make
     * as {@code areaId: 0x1000000}, i.e. they pass the {@code VehicleArea.GLOBAL}
     * <em>type</em> constant as the area id. Captured on-car:
     *
     * <pre>
     *   I/CAR.HAL: get, property: 0x2140f409, areaId: 0x1000000
     * </pre>
     *
     * where {@code 0x2140f409} is {@link BmsProperties#CHARGE_STATUS}. Reading
     * with area 0 is what made most of the energy tile show "--", so we ask with
     * the vendor's area first and keep 0 as a fallback.
     */
    private static final int AREA_VENDOR_GLOBAL = 0x01000000;
    private static final int AREA_AOSP_GLOBAL = 0;

    /** Areas to try, in order, until one answers. */
    private static final int[] AREA_CANDIDATES = { AREA_VENDOR_GLOBAL, AREA_AOSP_GLOBAL };

    // CarPropertyValue.mStatus
    private static final int STATUS_AVAILABLE = 0;

    public interface Listener {
        void onCarPropertiesReady();

        void onCarPropertiesLost();
    }

    /** One property reading, with the car's own status flag. */
    public static class Value {
        public final int status;
        public final Object value;
        /** Area the car actually answered from, for the debug screen. */
        public final int areaId;

        Value(int status, Object value, int areaId) {
            this.status = status;
            this.value = value;
            this.areaId = areaId;
        }

        public boolean isAvailable() {
            return status == STATUS_AVAILABLE && value != null;
        }
    }

    private final Context context;
    private final Listener listener;
    private IBinder carService;
    private IBinder bmsBinder;
    private boolean bindRequested;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            carService = service;
            bmsBinder = getCarService(SERVICE_BMS);
            if (bmsBinder == null) {
                Log.w(TAG, "CarService connected but has no \"" + SERVICE_BMS + "\" service");
            } else {
                Log.i(TAG, "BMS property service acquired");
            }
            if (listener != null) {
                listener.onCarPropertiesReady();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "CarService disconnected");
            carService = null;
            bmsBinder = null;
            if (listener != null) {
                listener.onCarPropertiesLost();
            }
        }
    };

    public CarPropertyClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public boolean isReady() {
        return bmsBinder != null && bmsBinder.pingBinder();
    }

    /**
     * Whether CarService itself is bound, regardless of whether it handed us a
     * "bms" service. Lets the debug screen tell "no CarService" apart from
     * "CarService without the vendor BMS service".
     */
    public boolean isBound() {
        return carService != null;
    }

    public void bind() {
        if (bindRequested) {
            return;
        }
        try {
            Intent intent = new Intent(ICAR_DESCRIPTOR);
            intent.setPackage(CAR_SERVICE_PACKAGE);
            bindRequested = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            Log.i(TAG, "bindService(CarService) returned " + bindRequested);
            if (!bindRequested) {
                Log.w(TAG, CAR_SERVICE_PACKAGE + " is not available on this head unit");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to bind CarService: " + e.getMessage());
        }
    }

    public void unbind() {
        if (!bindRequested) {
            return;
        }
        try {
            context.unbindService(connection);
        } catch (Exception e) {
            Log.w(TAG, "Failed to unbind CarService: " + e.getMessage());
        }
        bindRequested = false;
        carService = null;
        bmsBinder = null;
    }

    private IBinder getCarService(String serviceName) {
        IBinder binder = carService;
        if (binder == null) {
            return null;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ICAR_DESCRIPTOR);
            data.writeString(serviceName);
            binder.transact(TRANSACTION_GET_CAR_SERVICE, data, reply, 0);
            reply.readException();
            return reply.readStrongBinder();
        } catch (RemoteException e) {
            Log.w(TAG, "getCarService(" + serviceName + ") failed: " + e.getMessage());
            return null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * Reads one BMS property. Returns null if the service isn't available or the
     * car declined to answer.
     */
    public Value get(int propertyId) {
        for (int area : AREA_CANDIDATES) {
            Value v = get(propertyId, area);
            if (v != null && v.isAvailable()) {
                return v;
            }
        }
        return null;
    }

    /** Reads one property from one specific area. Exposed for the BMS debug screen. */
    public Value get(int propertyId, int areaId) {
        IBinder binder = bmsBinder;
        if (binder == null) {
            return null;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ICAR_PROPERTY_DESCRIPTOR);
            data.writeInt(propertyId);
            data.writeInt(areaId);
            binder.transact(TRANSACTION_GET_PROPERTY, data, reply, 0);
            reply.readException();
            if (reply.readInt() == 0) {
                // Null CarPropertyValue: the car has nothing for this id/area.
                return null;
            }
            return readCarPropertyValue(reply, propertyId);
        } catch (Exception e) {
            // Includes RemoteException and any decode failure. A property the car
            // doesn't implement shows up here, so this stays at debug level: with
            // two candidate areas, one miss per read is the normal case.
            Log.d(TAG, "getProperty(0x" + Integer.toHexString(propertyId)
                    + ", area 0x" + Integer.toHexString(areaId) + ") failed: " + e);
            return null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * Decodes {@code android.car.hardware.CarPropertyValue}'s parcel layout:
     * propertyId, areaId, status, timestamp, value class name, then the value.
     */
    private Value readCarPropertyValue(Parcel parcel, int requestedId) {
        int propertyId = parcel.readInt();
        int areaId = parcel.readInt();
        int status = parcel.readInt();
        parcel.readLong(); // timestamp

        if (propertyId != requestedId) {
            Log.w(TAG, "Asked for 0x" + Integer.toHexString(requestedId)
                    + " but got 0x" + Integer.toHexString(propertyId));
        }

        String className = parcel.readString();
        if (className == null) {
            return new Value(status, null, areaId);
        }

        // Only the numeric types matter for the BMS properties we read. String and
        // byte[] would need Parcel.readBlob(), which is not public API, so they are
        // rejected rather than guessed at.
        if ("java.lang.String".equals(className) || "[B".equals(className)) {
            Log.w(TAG, "Unsupported property value type " + className
                    + " for 0x" + Integer.toHexString(propertyId));
            return new Value(status, null, areaId);
        }

        Object value = parcel.readValue(getClass().getClassLoader());
        return new Value(status, value, areaId);
    }

    // --- typed convenience readers ---

    /**
     * Reads a property as a float. Returns {@code fallback} when unavailable.
     * Values arrive as Float or Integer depending on the property's encoded type,
     * so both are accepted.
     */
    public float getFloat(int propertyId, float fallback) {
        Value v = get(propertyId);
        if (v == null || !v.isAvailable() || !(v.value instanceof Number)) {
            return fallback;
        }
        return ((Number) v.value).floatValue();
    }

    public int getInt(int propertyId, int fallback) {
        Value v = get(propertyId);
        if (v == null || !v.isAvailable()) {
            return fallback;
        }
        if (v.value instanceof Number) {
            return ((Number) v.value).intValue();
        }
        if (v.value instanceof Boolean) {
            return ((Boolean) v.value) ? 1 : 0;
        }
        return fallback;
    }

    /**
     * Reads a validity companion property. These are inconsistently typed in the
     * firmware — some boolean, some int32 — so anything non-zero counts as valid,
     * and an absent companion is treated as valid rather than blocking the
     * reading it guards.
     */
    public boolean isValid(int validityPropertyId) {
        Value v = get(validityPropertyId);
        if (v == null || !v.isAvailable()) {
            return true;
        }
        if (v.value instanceof Boolean) {
            return (Boolean) v.value;
        }
        if (v.value instanceof Number) {
            return ((Number) v.value).intValue() != 0;
        }
        return true;
    }
}
