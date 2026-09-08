package com.custom.launcher.window;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.provider.Settings;
import android.util.Log;

/**
 * Floats another app's window over the launcher, in a rectangle we choose.
 *
 * <h3>Why this is not picture-in-picture</h3>
 * PiP was the obvious answer and it cannot work on this head unit, for two
 * independent reasons that were each checked rather than assumed:
 *
 * <ul>
 * <li><b>The ROM does not have PiP.</b> {@code /system/etc/permissions} on the R33
 * SWI69 1100 image declares exactly two software features —
 * {@code android.software.webview} and
 * {@code android.software.activities_on_secondary_displays}. Without
 * {@code android.software.picture_in_picture} the platform's PiP path is off
 * entirely, and a feature cannot be added at runtime: it is read out of those XML
 * files when the package manager starts.</li>
 * <li><b>The app does not ask for PiP.</b> OsmAnd's {@code MapActivity} declares no
 * {@code android:supportsPictureInPicture} and calls no
 * {@code enterPictureInPictureMode} anywhere in its source. PiP is always
 * initiated by the app being shrunk, so no permission or system privilege on our
 * side can start it for them.</li>
 * </ul>
 *
 * <h3>What works instead</h3>
 * Freeform windowing, which is the same thing the reference launcher's "PiP" beta
 * actually does — its own README calls it "a floating freeform window layered over
 * the launcher" and says it "relies on AOSP platform-level signing". Freeform
 * needs no cooperation from the app at all: the window is placed by whoever starts
 * the activity.
 *
 * <p>
 * It does need the platform to admit freeform is allowed. That is
 * {@code mSupportsFreeformWindowManagement}, which AOSP computes once, at boot, as
 * the feature flag <em>or</em> the {@code enable_freeform_support} global setting.
 * The feature flag is absent here, so the setting is the route — and because it is
 * read at boot, <b>turning it on requires a restart of the head unit</b>. There is
 * no way around that from inside an app; it is not a limitation of this code.
 *
 * <p>
 * Everything here is reflection because these are {@code @hide} APIs. Constants are
 * read from the platform's own classes rather than hard-coded, so a ROM that
 * numbers them differently still gets the right value.
 */
public final class FloatingAppController {
    private static final String TAG = "FloatingApp";

    /**
     * The global setting AOSP checks at boot when the freeform feature flag is
     * absent. Named in {@code Settings.Global} as
     * {@code DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT}, which is {@code @hide},
     * so the string is spelled out. Confirmed present in this ROM's framework.
     */
    private static final String SETTING_FREEFORM = "enable_freeform_support";

    private static final String FEATURE_FREEFORM = "android.software.freeform_window_management";
    private static final String FEATURE_PIP = "android.software.picture_in_picture";

    /** AOSP values, used only if the platform will not tell us its own. */
    private static final int FALLBACK_WINDOWING_MODE_FULLSCREEN = 1;
    private static final int FALLBACK_WINDOWING_MODE_FREEFORM = 5;
    private static final int FALLBACK_RESIZE_MODE_SYSTEM = 0;

    private FloatingAppController() {
    }

    // --- capability ---

    /** Whether {@link #show} has any chance of working right now. */
    public static boolean isAvailable(Context context) {
        return isFreeformEnabled(context) && activityManagerService() != null;
    }

    public static boolean isFreeformEnabled(Context context) {
        if (context.getPackageManager().hasSystemFeature(FEATURE_FREEFORM)) {
            return true;
        }
        try {
            return Settings.Global.getInt(context.getContentResolver(), SETTING_FREEFORM, 0) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Turns freeform support on. Takes effect at the next boot, not now, because
     * the platform samples this setting once during startup.
     */
    public static boolean enableFreeform(Context context) {
        try {
            boolean ok = Settings.Global.putInt(
                    context.getContentResolver(), SETTING_FREEFORM, 1);
            Log.i(TAG, "enable_freeform_support=1 written: " + ok + " (needs a reboot)");
            return ok;
        } catch (Exception e) {
            // WRITE_SECURE_SETTINGS is held via the platform signature; if this
            // fails the APK is probably not signed with the platform key.
            Log.e(TAG, "Could not write " + SETTING_FREEFORM + ": " + e);
            return false;
        }
    }

    /** Everything a person needs to see to know why this does or does not work. */
    public static String diagnostics(Context context) {
        StringBuilder sb = new StringBuilder();
        sb.append("picture_in_picture feature: ")
                .append(context.getPackageManager().hasSystemFeature(FEATURE_PIP)).append('\n');
        sb.append("freeform feature: ")
                .append(context.getPackageManager().hasSystemFeature(FEATURE_FREEFORM)).append('\n');
        int setting;
        try {
            setting = Settings.Global.getInt(context.getContentResolver(), SETTING_FREEFORM, -1);
        } catch (Exception e) {
            setting = -1;
        }
        sb.append(SETTING_FREEFORM).append(": ")
                .append(setting < 0 ? "unset" : String.valueOf(setting)).append('\n');
        sb.append("ActivityManager service: ")
                .append(activityManagerService() != null ? "reachable" : "UNREACHABLE").append('\n');
        sb.append("setTaskWindowingMode: ")
                .append(findMethod("setTaskWindowingMode") != null ? "present" : "MISSING")
                .append('\n');
        sb.append("freeform windowing mode: ").append(freeformMode()).append('\n');
        sb.append("effective: ").append(isAvailable(context) ? "usable" : "not usable yet");
        return sb.toString();
    }

    // --- show and hide ---

    /**
     * Puts {@code packageName} in a freeform window occupying {@code bounds}.
     *
     * <h3>The launcher deliberately stays behind</h3>
     * The floating app is left on top and focused. Pulling the launcher to the
     * front afterwards seems like the obvious way to make the window "float over
     * us", and it is what the first version did — but it is what stopped the
     * window drawing at all. A freeform stack is not fullscreen-opaque, so the
     * launcher below it stays visible on its own; move a fullscreen task above it
     * and the freeform stack is computed as not visible and goes. On the car that
     * read as the map appearing at the right place and vanishing about a second
     * later, leaving an empty rectangle - one second being exactly the delay
     * before the repair pass ran and moved the launcher up.
     *
     * <p>
     * Touches outside the window still reach the launcher underneath, which is how
     * the compact player stays usable while the map is up.
     *
     * <h3>An already-running app is moved, never relaunched</h3>
     * This is the case that matters most, because it is the normal way to get
     * here: open the map fullscreen, enter a destination, start navigating, press
     * home, then float it. Starting the launcher intent again at that point would
     * re-enter the app through its front door and can throw away exactly the state
     * the user just set up. So when a task already exists it is moved into a
     * freeform window as it stands, and nothing is launched at all.
     *
     * <p>
     * Only a cold start goes through {@code startActivity}, and then with
     * {@code NEW_TASK} alone — never {@code MULTIPLE_TASK}, which asks for a
     * <em>second</em> task of the same app and would leave a stray fullscreen copy
     * behind the floating one.
     *
     * @return null on success, or a sentence explaining what stopped it
     */
    public static String show(Activity activity, String packageName, Rect bounds) {
        if (packageName == null) {
            return "No app chosen.";
        }
        if (!isFreeformEnabled(activity)) {
            return "Freeform windows are switched off in this ROM.\n"
                    + "Menu \u203a Floating map window turns them on, then the head "
                    + "unit has to be restarted before it takes effect.";
        }

        Integer running = findTaskId(activity, packageName);
        if (running != null) {
            Log.i(TAG, "Moving existing task " + running + " of " + packageName
                    + " into a freeform window at " + bounds);
            boolean moded = setTaskWindowingMode(running, freeformMode(), true);
            boolean sized = resize(running, bounds);
            Log.i(TAG, "after move: " + describeTask(activity, packageName));
            if (!moded && !sized) {
                return "The system would not put " + packageName + " in a window.";
            }
            return null;
        }

        Intent intent = activity.getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent == null) {
            return packageName + " is not installed.";
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            ActivityOptions options = ActivityOptions.makeBasic();
            // setLaunchBounds is public API; the windowing mode is not, and both
            // are needed - bounds alone leave it to the platform to decide, which
            // on a ROM with no freeform feature flag means fullscreen.
            options.setLaunchBounds(bounds);
            if (!setLaunchWindowingMode(options, freeformMode())) {
                Log.w(TAG, "setLaunchWindowingMode unavailable; relying on bounds alone");
            }
            activity.startActivity(intent, options.toBundle());
            Log.i(TAG, "Cold-started " + packageName + " freeform at " + bounds);
        } catch (Exception e) {
            Log.e(TAG, "Could not launch " + packageName + " freeform: " + e);
            return "Could not open " + packageName + " in a floating window.";
        }

        return null;
    }

    /**
     * Puts the floating window away without killing the app, so a route being
     * navigated survives — and so a later plain tap on the tile brings that same
     * navigation back fullscreen with its state intact.
     *
     * <p>
     * The task goes back to fullscreen but explicitly not to the top, and then the
     * launcher is moved to the front. Fullscreen is what makes it stop floating;
     * anything left in freeform draws above the launcher no matter which task is
     * focused.
     */
    public static void hide(Activity activity, String packageName) {
        Integer taskId = findTaskId(activity, packageName);
        if (taskId == null) {
            Log.i(TAG, "No task for " + packageName + " to put away");
        } else {
            normaliseToFullscreen(taskId);
        }
        // The launcher does have to be pulled to the front here: the app was on
        // top while it floated, and nothing else would bring us back.
        moveToFront(activity, activity.getTaskId());
    }

    /**
     * Returns a task to plain fullscreen, bounds and all.
     *
     * <h3>Order matters, and the first attempt had it backwards</h3>
     * {@code resizeTask(taskId, null, RESIZE_MODE_SYSTEM)} is the platform's own
     * way to un-freeform a task - AOSP's comment on it is explicit that "a null
     * bounds on a freeform task moves that task to fullscreen", and it reparents
     * the task and drops the override bounds together. But the very first thing
     * that method does is
     * {@code if (!task.getWindowConfiguration().canResizeTask()) throw new
     * IllegalArgumentException(...)}, and {@code canResizeTask()} is only true
     * while the task is <em>still freeform</em>.
     *
     * <p>
     * Switching the windowing mode first therefore guaranteed the resize would
     * throw, which is exactly what the car reported:
     * {@code resizeTask(7007, null) failed: InvocationTargetException}, every
     * single time. So the resize goes first and the mode switch is the fallback
     * for a task that was not freeform to begin with.
     */
    private static void normaliseToFullscreen(int taskId) {
        if (resize(taskId, null)) {
            return;
        }
        if (!setTaskWindowingMode(taskId, fullscreenMode(), false)) {
            Log.w(TAG, "Could not return task " + taskId + " to fullscreen");
        }
    }

    /**
     * Un-floats {@code packageName} without touching the launcher's own task, for
     * the plain fullscreen tap on the tile.
     *
     * <p>
     * Call this immediately before starting the app normally. It is a no-op when
     * the app was never floated, and the point of it is the case where it was: the
     * task is still carrying the tile-sized bounds from its last stint in a
     * window, and starting it without clearing them puts a small map in the corner
     * of a black screen instead of a fullscreen one.
     */
    public static void restoreFullscreen(Context context, String packageName) {
        if (packageName == null) {
            return;
        }
        Integer taskId = findTaskId(context, packageName);
        if (taskId == null) {
            return;
        }
        // Checked first, because the common case is a tile tap on an app that was
        // never floated, and reparenting an already-fullscreen task for nothing is
        // exactly the kind of gratuitous churn that made the window flicker.
        Integer mode = windowingModeOf(context, packageName);
        if (mode != null && mode == fullscreenMode()) {
            return;
        }
        Log.i(TAG, "Un-floating " + packageName + " for a fullscreen start (mode was "
                + (mode == null ? "unreadable" : String.valueOf(mode)) + ")");
        normaliseToFullscreen(taskId);
    }

    /**
     * Repairs a window that the platform did not put where {@link #show} asked.
     *
     * <p>
     * The launch options are the clean route, but if the platform ignores them the
     * app comes up fullscreen. Re-applying the windowing mode a moment later
     * covers that case.
     *
     * <h3>It checks before it acts</h3>
     * Re-applying the mode to a task that is <em>already</em> freeform is not
     * free: it reparents the task, and on the car that showed up as the window
     * flashing and then going. So this reads the task's actual windowing mode
     * first and does nothing when it is already right — which is the normal case,
     * because the launch options do work here.
     *
     * @return false when the task could not be found or the platform refused
     */
    public static boolean forceFloat(Activity activity, String packageName, Rect bounds) {
        Integer taskId = findTaskId(activity, packageName);
        if (taskId == null) {
            return false;
        }
        Integer mode = windowingModeOf(activity, packageName);
        if (mode != null && mode == freeformMode()) {
            Log.i(TAG, packageName + " is already freeform; leaving it alone ("
                    + describeTask(activity, packageName) + ")");
            return true;
        }
        Log.i(TAG, "Repairing " + packageName + ": mode is "
                + (mode == null ? "unreadable" : String.valueOf(mode))
                + ", wanted " + freeformMode());
        boolean moded = setTaskWindowingMode(taskId, freeformMode(), true);
        boolean sized = resize(taskId, bounds);
        Log.i(TAG, "after repair: " + describeTask(activity, packageName));
        return moded || sized;
    }

    /**
     * What the platform actually did with {@code packageName}'s stack: windowing
     * mode, bounds and visibility, as one line for the log.
     *
     * <p>
     * There is no adb on this head unit, so a guess about whether a window ended
     * up freeform and where cannot be checked any other way. This is read back
     * from {@code getAllStackInfos} after every attempt.
     */
    public static String describeTask(Context context, String packageName) {
        Object stack = stackInfoFor(context, packageName);
        if (stack == null) {
            return packageName + ": no stack";
        }
        StringBuilder sb = new StringBuilder(packageName).append(": ");
        sb.append("mode=").append(field(stack, "windowingMode", modeOf(stack)));
        sb.append(" bounds=").append(field(stack, "bounds", null));
        sb.append(" visible=").append(field(stack, "visible", null));
        return sb.toString();
    }

    /** The windowing mode of {@code packageName}'s stack, or null if unreadable. */
    private static Integer windowingModeOf(Context context, String packageName) {
        return modeOf(stackInfoFor(context, packageName));
    }

    /**
     * The windowing mode of a {@code StackInfo}, or null.
     *
     * <p>
     * Three shapes are tried because the first two came back empty on this ROM:
     * there is no {@code getWindowingMode()} and no {@code windowingMode} field on
     * its {@code StackInfo}, which is why the car logged {@code mode=null} and the
     * repair pass could never tell a correct window from a broken one. What it
     * does carry is a {@code Configuration}, and the mode lives on that
     * configuration's {@code windowConfiguration}.
     */
    private static Integer modeOf(Object stackInfo) {
        if (stackInfo == null) {
            return null;
        }
        try {
            Method m = stackInfo.getClass().getMethod("getWindowingMode");
            return (Integer) m.invoke(stackInfo);
        } catch (Exception ignored) {
            // Not on this platform; try the field.
        }
        try {
            return (Integer) stackInfo.getClass().getField("windowingMode").get(stackInfo);
        } catch (Exception ignored) {
            // Try the configuration it definitely does carry.
        }
        try {
            Object configuration = field(stackInfo, "configuration", null);
            if (configuration == null) {
                return null;
            }
            Object windowConfiguration = field(configuration, "windowConfiguration", null);
            if (windowConfiguration == null) {
                return null;
            }
            Method m = windowConfiguration.getClass().getMethod("getWindowingMode");
            return (Integer) m.invoke(windowConfiguration);
        } catch (Exception e) {
            Log.w(TAG, "Could not read a windowing mode: " + causeOf(e));
            return null;
        }
    }

    /** The real exception behind a reflection wrapper, which otherwise says nothing. */
    private static String causeOf(Exception e) {
        Throwable cause = e instanceof InvocationTargetException ? e.getCause() : null;
        return String.valueOf(cause != null ? cause : e);
    }

    /** The {@code ActivityManager.StackInfo} holding {@code packageName}, or null. */
    private static Object stackInfoFor(Context context, String packageName) {
        Method all = findMethod("getAllStackInfos");
        Object service = activityManagerService();
        if (all == null || service == null) {
            return null;
        }
        try {
            Object result = all.invoke(service);
            if (!(result instanceof List)) {
                return null;
            }
            for (Object stack : (List<?>) result) {
                Object top = field(stack, "topActivity", null);
                if (top instanceof ComponentName
                        && packageName.equals(((ComponentName) top).getPackageName())) {
                    return stack;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "getAllStackInfos failed: " + e);
        }
        return null;
    }

    private static Object field(Object target, String name, Object fallback) {
        try {
            return target.getClass().getField(name).get(target);
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * Moves an already-floating window, for when the tile it sits over changes
     * size. Returns false if the task could not be found or resized.
     */
    public static boolean reposition(Activity activity, String packageName, Rect bounds) {
        Integer taskId = findTaskId(activity, packageName);
        return taskId != null && resize(taskId, bounds);
    }

    private static boolean resize(int taskId, Rect bounds) {
        Method resize = findMethod("resizeTask", int.class, Rect.class, int.class);
        Object service = activityManagerService();
        if (resize == null || service == null) {
            return false;
        }
        try {
            resize.invoke(service, taskId, bounds, resizeModeSystem());
            return true;
        } catch (Exception e) {
            Log.w(TAG, "resizeTask(" + taskId + ", " + bounds + ") failed: " + causeOf(e));
            return false;
        }
    }

    // --- reflection plumbing ---

    private static Object activityManagerService() {
        try {
            Class<?> am = Class.forName("android.app.ActivityManager");
            Method getService = am.getMethod("getService");
            return getService.invoke(null);
        } catch (Exception e) {
            Log.w(TAG, "ActivityManager.getService() unavailable: " + e);
            return null;
        }
    }

    private static Method findMethod(String name, Class<?>... args) {
        try {
            Class<?> iam = Class.forName("android.app.IActivityManager");
            return args.length == 0
                    ? firstNamed(iam, name)
                    : iam.getMethod(name, args);
        } catch (Exception e) {
            return null;
        }
    }

    private static Method firstNamed(Class<?> type, String name) {
        for (Method m : type.getMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        return null;
    }

    private static boolean setTaskWindowingMode(int taskId, int windowingMode, boolean toTop) {
        Method method = findMethod("setTaskWindowingMode", int.class, int.class, boolean.class);
        Object service = activityManagerService();
        if (method == null || service == null) {
            return false;
        }
        try {
            method.invoke(service, taskId, windowingMode, toTop);
            Log.i(TAG, "setTaskWindowingMode(" + taskId + ", " + windowingMode + ", " + toTop + ")");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "setTaskWindowingMode failed: " + causeOf(e));
            return false;
        }
    }

    private static boolean setLaunchWindowingMode(ActivityOptions options, int windowingMode) {
        try {
            Method method = ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class);
            method.invoke(options, windowingMode);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "setLaunchWindowingMode unavailable: " + e);
            return false;
        }
    }

    private static void moveToFront(Activity activity, int taskId) {
        try {
            ActivityManager manager =
                    (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
            manager.moveTaskToFront(taskId, 0);
        } catch (Exception e) {
            Log.w(TAG, "moveTaskToFront(" + taskId + ") failed: " + e);
        }
    }

    /**
     * The task id of {@code packageName}'s top task.
     *
     * <p>
     * {@code getRunningTasks} only reports other apps' tasks to a caller holding
     * {@code REAL_GET_TASKS}, which this launcher has by running as system uid.
     * Without it the list comes back containing only our own task and this returns
     * null, which is why the failure is logged rather than passed over.
     */
    private static Integer findTaskId(Context context, String packageName) {
        try {
            ActivityManager manager =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningTaskInfo> tasks = manager.getRunningTasks(32);
            if (tasks == null || tasks.isEmpty()) {
                Log.w(TAG, "getRunningTasks returned nothing - missing REAL_GET_TASKS?");
                return null;
            }
            for (ActivityManager.RunningTaskInfo task : tasks) {
                if (matches(task.topActivity, packageName)
                        || matches(task.baseActivity, packageName)) {
                    return taskIdOf(task);
                }
            }
            Log.i(TAG, packageName + " has no running task among " + tasks.size());
            return null;
        } catch (Exception e) {
            Log.w(TAG, "Could not enumerate tasks: " + e);
            return null;
        }
    }

    private static boolean matches(ComponentName component, String packageName) {
        return component != null && packageName.equals(component.getPackageName());
    }

    /** {@code id} on API 28, {@code taskId} from API 29; try both. */
    private static Integer taskIdOf(ActivityManager.RunningTaskInfo task) {
        try {
            return (Integer) task.getClass().getField("id").get(task);
        } catch (Exception ignored) {
            // Field renamed; fall through.
        }
        try {
            return (Integer) task.getClass().getField("taskId").get(task);
        } catch (Exception e) {
            Log.w(TAG, "RunningTaskInfo exposes no task id: " + e);
            return null;
        }
    }

    private static int freeformMode() {
        return windowConfigurationConstant(
                "WINDOWING_MODE_FREEFORM", FALLBACK_WINDOWING_MODE_FREEFORM);
    }

    private static int fullscreenMode() {
        return windowConfigurationConstant(
                "WINDOWING_MODE_FULLSCREEN", FALLBACK_WINDOWING_MODE_FULLSCREEN);
    }

    private static int windowConfigurationConstant(String name, int fallback) {
        try {
            Class<?> config = Class.forName("android.app.WindowConfiguration");
            return config.getField(name).getInt(null);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int resizeModeSystem() {
        try {
            return ActivityManager.class.getField("RESIZE_MODE_SYSTEM").getInt(null);
        } catch (Exception e) {
            return FALLBACK_RESIZE_MODE_SYSTEM;
        }
    }
}
