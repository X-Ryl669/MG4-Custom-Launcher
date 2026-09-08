package com.custom.launcher.saic;

import java.util.List;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.util.Log;

import com.saicmotor.common.CommandParcelable;

/**
 * Switches the head unit's active audio source the way the stock launcher does.
 *
 * <h3>Why this exists</h3>
 * The car boots with the radio holding the source, muted. Commanding
 * {@code play()} on the Bluetooth {@code MediaSession} does nothing in that state
 * — confirmed on-car, the session stays in {@code STATE_PAUSED} — because the
 * source itself has not changed hands. Until now the only way through was to
 * press the source button in the stock launcher first.
 *
 * <h3>How the stock launcher does it</h3>
 * Read out of {@code ModeManager} in {@code Launcher_eh32_ll.apk} (R33 SWI69
 * 1100). It builds a {@code CommandParcelable}, puts it in an intent under the
 * key {@code "command"}, and {@code startService}s it at the <em>target app's
 * own</em> service:
 *
 * <pre>
 *   CommandParcelable c = new CommandParcelable();
 *   c.setEvent(MODE_EVENT_SWITCH_SOURCE_ONLY_PLAY);
 *   c.setTargetType(mediaType);
 *   c.setTargetPackage("com.saicmotor.launcher");   // sender, not recipient
 *   c.sendCommand(ctx, targetPkg, LaunchUtils.getAppService(targetPkg, ctx));
 * </pre>
 *
 * <h3>The Bluetooth surprise</h3>
 * There is no Bluetooth case in that dispatch. {@code getNextValidSource()}
 * rewrites the package before it ever gets there:
 *
 * <pre>
 *   if (pkg.equals("com.android.bluetooth") || pkg.equals(PACKAGE_NAME_ONLINE)) {
 *       pkg = PackageConstants.MEDIA.PackageName;   // "com.saicmotor.media"
 *   }
 *   entity = new MediaSourceEntity(pkg, mSourceKeyList.keyAt(i));  // type stays 2
 * </pre>
 *
 * So <b>Bluetooth audio is owned by {@code com.saicmotor.media}, not by
 * {@code com.android.bluetooth}</b>; the media type (2) is what says "Bluetooth".
 * Addressing {@code com.android.bluetooth} — the obvious guess, and what this
 * launcher did before — reaches a package with no mode service at all.
 */
public final class SaicSourceSwitch {
    private static final String TAG = "SaicSourceSwitch";

    /** The stock launcher's package, which is what it stamps commands with. */
    private static final String SENDER_PACKAGE = "com.saicmotor.launcher";

    /** Owns USB, online and — the non-obvious part — Bluetooth audio. */
    public static final String MEDIA_PACKAGE = "com.saicmotor.media";
    public static final String RADIO_PACKAGE = "com.saicmotor.radio";

    // com.saicmotor.common.ModeConstants, verbatim.
    public static final int MODE_EVENT_SWITCH_SOURCE_ONLY = 32769;
    public static final int MODE_EVENT_SWITCH_SOURCE_ONLY_PLAY = 32773;
    public static final int MODE_EVENT_SWITCH_SOURCE_ONLY_PAUSE = 32774;

    public static final int MEDIA_TYPE_USB1_MUSIC = 0;
    public static final int MEDIA_TYPE_BT_MUSIC = 2;
    public static final int MEDIA_TYPE_LOCAL_MUSIC = 4;
    public static final int MEDIA_TYPE_RADIO_FM = 8;
    public static final int MEDIA_TYPE_RADIO_AM = 9;
    public static final int MEDIA_TYPE_RADIO_DAB = 11;

    private SaicSourceSwitch() {
    }

    /** Makes Bluetooth the active source and starts it playing. */
    public static boolean playBluetooth(Context context) {
        return send(context, MEDIA_PACKAGE, MODE_EVENT_SWITCH_SOURCE_ONLY_PLAY,
                MEDIA_TYPE_BT_MUSIC);
    }

    /** Makes FM radio the active source and starts it playing. */
    public static boolean playRadio(Context context) {
        return send(context, RADIO_PACKAGE, MODE_EVENT_SWITCH_SOURCE_ONLY_PLAY,
                MEDIA_TYPE_RADIO_FM);
    }

    /** Selects a source without commanding playback. */
    public static boolean selectSource(Context context, String packageName, int mediaType) {
        return send(context, packageName, MODE_EVENT_SWITCH_SOURCE_ONLY, mediaType);
    }

    private static boolean send(Context context, String targetPackage, int event, int mediaType) {
        String service = findModeService(context, targetPackage);
        if (service == null) {
            Log.w(TAG, "No service to command in " + targetPackage
                    + " - is it installed on this trim?");
            return false;
        }

        CommandParcelable command = new CommandParcelable();
        command.setEvent(event);
        command.setTargetType(mediaType);
        command.setTargetPackage(SENDER_PACKAGE);

        Intent intent = new Intent();
        intent.putExtra(CommandParcelable.COMMAND_KEY, command);
        intent.setComponent(new ComponentName(targetPackage, service));
        intent.setPackage(targetPackage);

        try {
            context.startService(intent);
            Log.i(TAG, "Sent " + command + " to " + targetPackage + "/" + service);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "startService(" + targetPackage + "/" + service + ") failed: " + e);
            return false;
        }
    }

    /**
     * Finds the service in {@code packageName} that handles mode commands.
     *
     * <p>
     * The stock {@code LaunchUtils.getAppService} queries with an action-less
     * intent — which matches every filter, since {@code IntentFilter.match} skips
     * the action test when the action is null — and blindly takes
     * {@code get(0).serviceInfo.name}. That happens to work on the stock build,
     * but {@code com.saicmotor.media} exports four services and three of them are
     * {@code MediaBrowserService}s that would drop the command on the floor. So
     * this skips known browser services and prefers a plain one, falling back to
     * the stock behaviour if that leaves nothing.
     */
    private static String findModeService(Context context, String packageName) {
        Intent probe = new Intent();
        probe.setPackage(packageName);

        List<ResolveInfo> services;
        try {
            services = context.getPackageManager().queryIntentServices(probe, 0);
        } catch (Exception e) {
            Log.w(TAG, "queryIntentServices(" + packageName + ") failed: " + e);
            return null;
        }
        if (services == null || services.isEmpty()) {
            return null;
        }

        String first = null;
        for (ResolveInfo info : services) {
            String name = info.serviceInfo.name;
            Log.d(TAG, "  candidate service in " + packageName + ": " + name);
            if (first == null) {
                first = name;
            }
            if (!name.contains("MediaBrowserService") && !name.contains("MBService")) {
                return name;
            }
        }
        Log.w(TAG, "Only browser services found in " + packageName
                + "; falling back to " + first);
        return first;
    }
}
