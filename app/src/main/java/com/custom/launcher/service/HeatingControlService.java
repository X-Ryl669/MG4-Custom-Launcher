package com.custom.launcher.service;

import android.content.Context;
import android.util.Log;
import com.saicmotor.sdk.vehiclesettings.VehicleServiceContract;
import com.saicmotor.sdk.vehiclesettings.bean.AirConditionBean;
import com.saicmotor.sdk.vehiclesettings.manager.BaseManager;
import com.saicmotor.sdk.vehiclesettings.manager.AirConditionManager;

/**
 * Service to control and monitor vehicle heating (heated seats and steering
 * wheel)
 * Uses direct SDK imports (requires platform signing with android.uid.system)
 */
public class HeatingControlService {
    private static final String TAG = "HeatingControlService";

    private final Context context;
    private AirConditionManager managerInstance;
    private boolean isConnected = false;
    private HeatingStatusListener statusListener;

    public boolean isConnected() {
        return isConnected;
    }

    public interface HeatingStatusListener {
        /**
         * A level of {@code null} means the car has no such hardware — that is how
         * {@code AirConditionBean} reports an absent feature, and it is the only
         * signal there is. This used to be three plain ints with null coerced to
         * 0, which is why an SE with no heated seats still showed working-looking
         * seat buttons that did nothing.
         */
        void onHeatingStatusChanged(Integer drvSeatLevel, Integer psgSeatLevel,
                Integer wheelLevel);

        void onConnectionStatusChanged(boolean connected);
    }

    public HeatingControlService(Context context) {
        this.context = context;
    }

    public void setStatusListener(HeatingStatusListener listener) {
        this.statusListener = listener;
    }

    /**
     * Initialize and bind to the SAIC SDK AirConditionManager
     */
    public void bind() {
        try {
            Log.i(TAG, "[HEATING] Initializing HeatingControlService...");

            // Create service listener
            VehicleServiceContract.IVehicleServiceListener serviceListener = new VehicleServiceContract.IVehicleServiceListener() {
                @Override
                public void onServiceConnected(BaseManager manager) {
                    Log.i(TAG, "[HEATING] ✓✓✓ Service connected! Manager: " + manager.getClass().getSimpleName());
                    managerInstance = (AirConditionManager) manager;
                    isConnected = true;

                    if (statusListener != null) {
                        statusListener.onConnectionStatusChanged(true);
                    }

                    // Register callback for status updates
                    registerCallback();

                    // Read initial status
                    readHeatingStatus();
                }

                @Override
                public void onServiceDisconnected() {
                    Log.w(TAG, "[HEATING] ✗ Service disconnected");
                    isConnected = false;

                    if (statusListener != null) {
                        statusListener.onConnectionStatusChanged(false);
                    }
                }
            };

            // Initialize the SDK
            Log.i(TAG, "[HEATING] Calling AirConditionManager.init()...");
            AirConditionManager.init(context, serviceListener, 1500L);
            Log.i(TAG, "[HEATING] ✓ Init called, waiting for service connection callback...");

        } catch (Exception e) {
            Log.e(TAG, "[HEATING] Failed to initialize: " + e.getMessage(), e);
            isConnected = false;
        }
    }

    /**
     * Register callback to receive heating status updates from vehicle
     */
    private void registerCallback() {
        try {
            if (managerInstance == null) {
                Log.w(TAG, "Cannot register callback - manager not initialized");
                return;
            }

            // Create callback to receive heating status updates
            VehicleServiceContract.IAirConditionCallback callback = new VehicleServiceContract.IAirConditionCallback() {
                @Override
                public void onAirConditionChangeEvent(AirConditionBean bean) {
                    Log.d(TAG, "[HEATING] Air condition status changed event received");
                    readHeatingStatusFromBean(bean);
                }

                @Override
                public void onAirConditionErrorEvent(String error, int code) {
                    Log.e(TAG, "[HEATING] Error: " + error + " (code: " + code + ")");
                }
            };

            // Register the callback
            managerInstance.registerAirConditionCallback(callback);
            Log.i(TAG, "[HEATING] ✓ Registered AirConditionCallback");

        } catch (Exception e) {
            Log.e(TAG, "[HEATING] Failed to register callback: " + e.getMessage(), e);
        }
    }

    /**
     * Read current heating status from the vehicle
     * Public so MainActivity can request immediate status after sending commands
     */
    public void readHeatingStatus() {
        try {
            if (managerInstance == null) {
                Log.w(TAG, "Cannot read status - manager not initialized");
                return;
            }

            // Get the AirConditionBean
            AirConditionBean bean = managerInstance.getAirConditionStatus();
            if (bean != null) {
                readHeatingStatusFromBean(bean);
            }

        } catch (Exception e) {
            Log.e(TAG, "[HEATING] Failed to read status: " + e.getMessage(), e);
        }
    }

    /**
     * Extract heating levels from AirConditionBean
     */
    private void readHeatingStatusFromBean(AirConditionBean bean) {
        try {
            // Get driver seat heating level (0-3)
            Integer drvSeatLevel = bean.getDrvSeatHeatLevel();

            // Get passenger seat heating level (0-3)
            Integer psgSeatLevel = bean.getPsgSeatHeatLevel();

            // Get steering wheel heating level (0 or 1)
            Integer wheelLevel = bean.getSteeringWheelHeatLevel();

            // Logged at INFO, and with nulls shown as null rather than 0, because
            // which of these the car answers is exactly the open question: null
            // means "not fitted", 0 means "fitted and off", and the two look
            // identical once they have been coerced.
            Log.i(TAG, "[HEATING] Status from vehicle: DrvSeat=" + drvSeatLevel
                    + ", PsgSeat=" + psgSeatLevel + ", Wheel=" + wheelLevel);

            if (statusListener != null) {
                statusListener.onHeatingStatusChanged(drvSeatLevel, psgSeatLevel, wheelLevel);
            }

        } catch (Exception e) {
            Log.e(TAG, "[HEATING] Failed to parse status from bean: " + e.getMessage(), e);
        }
    }

    /**
     * Set driver seat heating level
     * 
     * @param level 0=off, 1=low, 2=medium, 3=high
     */
    public void setDriverSeatHeating(int level) {
        try {
            if (managerInstance == null) {
                Log.e(TAG, "[HEATING] ✗✗✗ Cannot set driver seat - manager not initialized");
                return;
            }

            Log.i(TAG, "[HEATING] >>> Sending driver seat heating command: level " + level);
            managerInstance.setDrvSeatHeatLevel(level);
            Log.i(TAG, "[HEATING] ✓ Driver seat heating command sent successfully");

        } catch (Exception e) {
            Log.e(TAG, "[HEATING] ✗✗✗ Failed to set driver seat heating: " + e.getMessage(), e);
        }
    }

    /**
     * Set passenger seat heating level
     * 
     * @param level 0=off, 1=low, 2=medium, 3=high
     */
    public void setPassengerSeatHeating(int level) {
        try {
            if (managerInstance == null) {
                Log.e(TAG, "[HEATING] ✗✗✗ Cannot set passenger seat - manager not initialized");
                return;
            }

            Log.i(TAG, "[HEATING] >>> Sending passenger seat heating command: level " + level);
            managerInstance.setPsgSeatHeatLevel(level);
            Log.i(TAG, "[HEATING] ✓ Passenger seat heating command sent successfully");

        } catch (Exception e) {
            Log.e(TAG, "[HEATING] ✗✗✗ Failed to set passenger seat heating: " + e.getMessage(), e);
        }
    }

    /**
     * Set steering wheel heating
     * 
     * @param level 0=off, 1=on
     */
    public void setSteeringWheelHeating(int level) {
        try {
            if (managerInstance == null) {
                Log.e(TAG, "[HEATING] ✗✗✗ Cannot set steering wheel - manager not initialized");
                return;
            }

            Log.i(TAG, "[HEATING] >>> Sending steering wheel heating command: level " + level);
            managerInstance.setSteeringWheelHeat(level);
            Log.i(TAG, "[HEATING] ✓ Steering wheel heating command sent successfully");

        } catch (Exception e) {
            Log.e(TAG, "[HEATING] ✗✗✗ Failed to set steering wheel heating: " + e.getMessage(), e);
        }
    }

    /**
     * Release resources and unregister callbacks
     */
    public void release() {
        try {
            if (managerInstance != null) {
                // Note: The unregister method should be called if available
                // For now, just cleanup the reference
                Log.i(TAG, "[HEATING] ✓ Releasing heating service");
            }

            managerInstance = null;
            isConnected = false;

        } catch (Exception e) {
            Log.e(TAG, "[HEATING] Failed to release: " + e.getMessage(), e);
        }
    }
}
