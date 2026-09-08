package com.custom.launcher.media;

import android.content.ComponentName;

/**
 * The media backends on this head unit, and how to tell them apart.
 *
 * <p>
 * Both sources we care about are ordinary {@code MediaBrowserService}s with
 * ordinary {@code MediaSession}s, which is what makes one UI able to drive both:
 *
 * <ul>
 * <li><b>Bluetooth</b> — {@code com.android.bluetooth/.a2dpsink.mbs.A2dpMediaBrowserService}
 * is AOSP's A2DP-sink browser. It exposes the phone's AVRCP browse tree, so
 * album/artist/playlist/virtual-filesystem browsing needs no AVRCP work of our
 * own: {@code subscribe(parentId)} walks whatever hierarchy the phone offers.</li>
 * <li><b>Radio</b> — {@code com.saicmotor.radio/.service.RadioMBService}, whose
 * session implements {@code onSkipToNext}/{@code onSkipToPrevious} as station
 * changes. That is why the tile's previous/next buttons become previous/next
 * station for free when radio is the active session — no remapping needed on our
 * side.</li>
 * </ul>
 *
 * <p>
 * <b>Radio cannot be browsed.</b> {@code RadioMBService} does declare a browse
 * root ({@code "_ROOT_"}), but its {@code onLoadChildren} is:
 *
 * <pre>
 *   onLoadChildren(parentId, result) {
 *       LOG.i("onLoadChildren_parentId:" + parentId);
 *       result.detach();          // ... and never sendResult()
 *   }
 * </pre>
 *
 * so it accepts every subscription and answers none. Any station list built on
 * {@code MediaBrowser} hangs on "Loading" forever — this was tried, and that is
 * exactly what happened on the car. A real station list would have to go through
 * {@code com.android.car.radio.service.IRadioManager} instead, which delivers via
 * {@code IRadioCallback}/{@code IDabCallback} rather than a getter, so it needs
 * callback binders hosted on our side. Not attempted yet; the browse screen offers
 * "Current radio" and "Configure radio" instead.
 *
 * Verified against the R33 SWI69 1100 firmware.
 */
public final class MediaSources {

    public static final String BLUETOOTH_PACKAGE = "com.android.bluetooth";
    private static final String BLUETOOTH_BROWSER_CLASS =
            "com.android.bluetooth.a2dpsink.mbs.A2dpMediaBrowserService";

    public static final String RADIO_PACKAGE = "com.saicmotor.radio";

    private MediaSources() {
    }

    public static ComponentName bluetoothBrowser() {
        return new ComponentName(BLUETOOTH_PACKAGE, BLUETOOTH_BROWSER_CLASS);
    }

    /** Short label for whichever app owns the currently active session. */
    public static String labelForPackage(String packageName) {
        if (packageName == null) {
            return "NOW PLAYING";
        }
        if (packageName.startsWith(BLUETOOTH_PACKAGE)) {
            return "BLUETOOTH";
        }
        if (packageName.startsWith(RADIO_PACKAGE)) {
            return "RADIO";
        }
        if (packageName.startsWith("com.allgo")) {
            return "CARPLAY";
        }
        if (packageName.startsWith("com.saicmotor.media")) {
            return "MEDIA";
        }
        return "NOW PLAYING";
    }

    public static boolean isRadio(String packageName) {
        return packageName != null && packageName.startsWith(RADIO_PACKAGE);
    }

    public static boolean isBluetooth(String packageName) {
        return packageName != null && packageName.startsWith(BLUETOOTH_PACKAGE);
    }
}
