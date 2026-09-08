package com.custom.launcher.car;

/**
 * Battery-management property ids for the MG4 SE/Standard.
 *
 * <p>
 * Read out of {@code android.car.hardware.CarBMSManager} as compiled into
 * {@code EvCharge_eh32_ll.apk} (the stock energy app, {@code com.saicmotor.saicev})
 * from the R33 SWI69 1100 firmware. That app is the thing that draws the factory
 * energy screen, so these are the ids the car actually answers.
 *
 * <p>
 * The type is encoded in the id itself, per AOSP's {@code VehiclePropertyType}:
 * {@code 0x216…} float, {@code 0x214…} int32, {@code 0x212…} boolean. Note that
 * the {@code *_VALID} companions are <em>not</em> consistently typed — some are
 * boolean, some int32 — which is why {@link CarPropertyClient} decodes values
 * generically instead of assuming a type per property.
 */
public final class BmsProperties {

    private BmsProperties() {
    }

    // --- energy consumption ---

    /** Electric consumption per km. The kWh/100km figure, once scaled. FLOAT. */
    public static final int ELEC_CSUMP_PERKM = 0x2160f41d;
    /** Validity companion for {@link #ELEC_CSUMP_PERKM}. INT32. */
    public static final int ELEC_CSUMP_PERKM_VALID = 0x2140f440;
    /** Rolling average electric consumption. FLOAT. */
    public static final int CRNT_AVG_ELEC_CSUMP = 0x2160f41b;
    /** Battery energy average rate. FLOAT. */
    public static final int BAT_ELEC_ENRG_AVG_RATE = 0x2160f421;

    // --- pack, for instantaneous power ---

    /** Pack current in amps. FLOAT. */
    public static final int PACK_CURRENT = 0x2160f407;
    /** Validity companion for {@link #PACK_CURRENT}. BOOLEAN. */
    public static final int PACK_CURRENT_VALID = 0x2120f423;
    /** Pack voltage in volts. FLOAT. */
    public static final int PACK_VOLTAGE = 0x2160f406;
    /** Validity companion for {@link #PACK_VOLTAGE}. INT32. */
    public static final int PACK_VOLTAGE_VALID = 0x2140f433;

    // --- state of charge and range ---

    /** Displayed state of charge, percent. FLOAT. */
    public static final int PACK_SOC_DISPLAY = 0x2160f404;
    /** Validity companion for {@link #PACK_SOC_DISPLAY}. BOOLEAN. */
    public static final int PACK_SOC_DISPLAY_VALID = 0x2120f422;
    /** Estimated electric range. INT32. */
    public static final int ESTD_ELEC_RANGE = 0x2140f416;
    /** Validity companion for {@link #ESTD_ELEC_RANGE}. BOOLEAN. */
    public static final int ESTD_ELEC_RANGE_VALID = 0x2120f425;
    /** Vehicle electric range, the cluster's figure. INT32. */
    public static final int VEH_ELEC_RANGE = 0x2140f41c;

    // --- charging ---

    /** Charge status, see {@code CHRG_STS_*}. INT32. */
    public static final int CHARGE_STATUS = 0x2140f409;
    /** Charging time remaining, minutes. INT32. */
    public static final int CHARGING_REMAINING_TIME = 0x2140f417;
    /** Charge plug connection state. INT32. */
    public static final int CHARGE_PLUG_CONNECTED = 0x2140f408;

    // --- charge status values ---

    public static final int CHRG_STS_UNPLUGGED = 0;
    public static final int CHRG_STS_ONBOARD_CHARGING = 1;
    public static final int CHRG_STS_CHARGE_DONE = 2;
    public static final int CHRG_STS_BALANCING = 3;
    public static final int CHRG_STS_CHARGE_FAULT = 4;
    public static final int CHRG_STS_CONNECTING = 5;
    public static final int CHRG_STS_NOT_RECOGNIZED = 6;
    public static final int CHRG_STS_NOT_CHARGE = 7;
    public static final int CHRG_STS_CHARGE_CEASE = 8;
    public static final int CHRG_STS_CHARGE_RESERVED = 9;
    public static final int CHRG_STS_OFFBOARD_CHARGING = 10;
    public static final int CHRG_STS_SUPER_OFFBOARD_CHARGING = 11;
    public static final int CHRG_STS_MULTI_OFFBOARD_CHARGING = 12;

    /** True when the given charge status means energy is actually flowing in. */
    public static boolean isCharging(int chargeStatus) {
        switch (chargeStatus) {
            case CHRG_STS_ONBOARD_CHARGING:
            case CHRG_STS_OFFBOARD_CHARGING:
            case CHRG_STS_SUPER_OFFBOARD_CHARGING:
            case CHRG_STS_MULTI_OFFBOARD_CHARGING:
                return true;
            default:
                return false;
        }
    }

    /**
     * Every property above, paired with its name, so the BMS debug screen can
     * dump the lot without a second hand-maintained list. Order is display
     * order, not id order.
     *
     * <p>
     * There is no adb on this head unit and logcat rotates faster than a person
     * can open the log viewer, so an on-screen live dump is the only practical
     * way to compare these raw readings against the dashboard and work out the
     * real units.
     */
    public static final int[] ALL_IDS = {
            PACK_SOC_DISPLAY, PACK_SOC_DISPLAY_VALID,
            ESTD_ELEC_RANGE, ESTD_ELEC_RANGE_VALID, VEH_ELEC_RANGE,
            PACK_VOLTAGE, PACK_VOLTAGE_VALID,
            PACK_CURRENT, PACK_CURRENT_VALID,
            ELEC_CSUMP_PERKM, ELEC_CSUMP_PERKM_VALID,
            CRNT_AVG_ELEC_CSUMP, BAT_ELEC_ENRG_AVG_RATE,
            CHARGE_STATUS, CHARGE_PLUG_CONNECTED, CHARGING_REMAINING_TIME,
    };

    public static final String[] ALL_NAMES = {
            "PACK_SOC_DISPLAY", "PACK_SOC_DISPLAY_VALID",
            "ESTD_ELEC_RANGE", "ESTD_ELEC_RANGE_VALID", "VEH_ELEC_RANGE",
            "PACK_VOLTAGE", "PACK_VOLTAGE_VALID",
            "PACK_CURRENT", "PACK_CURRENT_VALID",
            "ELEC_CSUMP_PERKM", "ELEC_CSUMP_PERKM_VALID",
            "CRNT_AVG_ELEC_CSUMP", "BAT_ELEC_ENRG_AVG_RATE",
            "CHARGE_STATUS", "CHARGE_PLUG_CONNECTED", "CHARGING_REMAINING_TIME",
    };
}
