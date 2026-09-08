package com.custom.launcher.car;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

/**
 * Climate control on the MG4 SE/Standard, via the HVAC binder handed out by
 * {@link CarAdapter#CLIENT_HVAC}.
 *
 * <p>
 * Transaction numbers and marshalling were taken from
 * {@code com.saicmotor.carapi.hvac.ICarHvacService} as compiled into the stock
 * SE launcher, so they are firmware-specific rather than guessed. Verified
 * against {@code com.saicmotor.launcher} v105 / R33 SWI69 1100.
 *
 * <p>
 * Note that power is a <em>toggle</em> ({@code switchHvacPowerStatus}), not a
 * setter — there is no "set power to off". Read the current state first and
 * drive the UI from what the car reports back, never from what we asked for.
 */
public class HvacClient {
    private static final String TAG = "HvacClient";

    private static final String DESCRIPTOR = "com.saicmotor.carapi.hvac.ICarHvacService";

    private static final int TRANSACTION_SWITCH_HVAC_POWER_STATUS = 5;
    private static final int TRANSACTION_GET_HVAC_POWER_STATUS = 6;
    private static final int TRANSACTION_SET_FAN_SPEED = 19;
    private static final int TRANSACTION_GET_FAN_SPEED = 20;
    private static final int TRANSACTION_SET_DRIVER_TEMPERATURE = 21;
    private static final int TRANSACTION_GET_DRIVER_TEMPERATURE = 22;

    /** Returned by the getters when the value could not be read. */
    public static final int FAN_SPEED_UNKNOWN = -1;
    public static final float TEMPERATURE_UNKNOWN = Float.NaN;

    private final IBinder binder;

    public HvacClient(IBinder binder) {
        this.binder = binder;
    }

    public boolean isAvailable() {
        return binder != null && binder.pingBinder();
    }

    /** Driver-side target temperature in degrees, or NaN if unavailable. */
    public float getDriverTemperature() {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            if (binder == null) {
                return TEMPERATURE_UNKNOWN;
            }
            data.writeInterfaceToken(DESCRIPTOR);
            binder.transact(TRANSACTION_GET_DRIVER_TEMPERATURE, data, reply, 0);
            reply.readException();
            return reply.readFloat();
        } catch (RemoteException e) {
            Log.w(TAG, "getDriverTemperature failed: " + e.getMessage());
            return TEMPERATURE_UNKNOWN;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    public void setDriverTemperature(float celsius) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            if (binder == null) {
                return;
            }
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeFloat(celsius);
            binder.transact(TRANSACTION_SET_DRIVER_TEMPERATURE, data, reply, 0);
            reply.readException();
        } catch (RemoteException e) {
            Log.w(TAG, "setDriverTemperature failed: " + e.getMessage());
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /** Blower level, or {@link #FAN_SPEED_UNKNOWN} if unavailable. */
    public int getFanSpeed() {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            if (binder == null) {
                return FAN_SPEED_UNKNOWN;
            }
            data.writeInterfaceToken(DESCRIPTOR);
            binder.transact(TRANSACTION_GET_FAN_SPEED, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } catch (RemoteException e) {
            Log.w(TAG, "getFanSpeed failed: " + e.getMessage());
            return FAN_SPEED_UNKNOWN;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    public void setFanSpeed(int level) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            if (binder == null) {
                return;
            }
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(level);
            binder.transact(TRANSACTION_SET_FAN_SPEED, data, reply, 0);
            reply.readException();
        } catch (RemoteException e) {
            Log.w(TAG, "setFanSpeed failed: " + e.getMessage());
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    public boolean getHvacPowerStatus() {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            if (binder == null) {
                return false;
            }
            data.writeInterfaceToken(DESCRIPTOR);
            binder.transact(TRANSACTION_GET_HVAC_POWER_STATUS, data, reply, 0);
            reply.readException();
            return reply.readInt() != 0;
        } catch (RemoteException e) {
            Log.w(TAG, "getHvacPowerStatus failed: " + e.getMessage());
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /** Toggles climate power. Read {@link #getHvacPowerStatus()} for the result. */
    public void switchHvacPowerStatus() {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            if (binder == null) {
                return;
            }
            data.writeInterfaceToken(DESCRIPTOR);
            binder.transact(TRANSACTION_SWITCH_HVAC_POWER_STATUS, data, reply, 0);
            reply.readException();
        } catch (RemoteException e) {
            Log.w(TAG, "switchHvacPowerStatus failed: " + e.getMessage());
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
