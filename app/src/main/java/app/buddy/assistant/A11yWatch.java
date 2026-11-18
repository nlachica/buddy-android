package app.buddy.assistant;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 025 (fork): notices when Buddy's accessibility service is off, or on but not bound (Android didn't rebind it after
 * the process restarted), posts one notice, and logs the events to ~/.buddy/a11y.log in Buddy's Linux.
 */
public final class A11yWatch extends JobService {
    enum State {CLEAR, DEAD, OFF}

    /** The same predicate as ChatHub.send's hub_accessibility_dead / _off. */
    static State decide(boolean bound, boolean enabled) {
        return bound ? State.CLEAR : enabled ? State.DEAD : State.OFF;
    }

    private static final String[] REASONS = {"UNKNOWN", "EXIT_SELF", "SIGNALED", "LOW_MEMORY", "CRASH", "CRASH_NATIVE",
            "ANR", "INITIALIZATION_FAILURE", "PERMISSION_CHANGE", "EXCESSIVE_RESOURCE_USAGE", "USER_REQUESTED",
            "USER_STOPPED", "DEPENDENCY_DIED", "OTHER", "FREEZER", "PACKAGE_STATE_CHANGE", "PACKAGE_UPDATED"};

    static String reasonName(int code) {
        return code >= 0 && code < REASONS.length ? REASONS[code] : String.valueOf(code);
    }

    /** Index of the first (newest) entry equal to own, or -1. */
    static int ownIndex(String[] processNames, String own) {
        for (int i = 0; i < processNames.length; i++) if (own != null && own.equals(processNames[i])) return i;
        return -1;
    }

    static String exitText(String process, int pid, int reason, int status, int importance, String desc) {
        return "last exit " + process + " pid=" + pid + " " + reasonName(reason) + "(" + reason + ") status=" + status
                + " importance=" + importance + " \"" + (desc == null ? "" : desc.replace('\n', ' ')) + "\"";
    }

    /** Appends a line; at max bytes or more the file is first renamed to name.1 (replacing an old one). */
    static void append(File f, String line, long max) throws IOException {
        if (f.length() >= max) {
            File old = new File(f.getParentFile(), f.getName() + ".1");
            old.delete();
            f.renameTo(old);
        }
        try (FileOutputStream o = new FileOutputStream(f, true)) {
            o.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    // ------------------------------------------------------------------ Android side

    private static final int JOB_ID = 2501;
    private static final int NOTICE_ID = 12;
    private static final String CHANNEL = "a11y";
    private static final long CHECK_MS = 40_000, DEADLINE_MS = 60_000, LOG_MAX = 64 * 1024;
    private static final ExecutorService logger = Executors.newSingleThreadExecutor();

    /** One timestamped line in ~/.buddy/a11y.log in Buddy's Linux (skipped when it isn't installed), any thread. */
    static void log(Context c, String what) {
        Context app = c.getApplicationContext();
        String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(new Date()) + " "
                + what.replace('\n', ' ');
        logger.execute(() -> {
            try {
                if (!Linux.installed(app)) return;
                File f = new File(Linux.root(app), "root/.buddy/a11y.log");
                f.getParentFile().mkdirs();
                append(f, line, LOG_MAX);
            } catch (Exception ignored) {
            }
        });
    }

    /** Application.onCreate: log the previous process's exit and make sure a check is coming. */
    static void processStarted(Context c) {
        Context app = c.getApplicationContext();
        String own = Application.getProcessName();
        logger.execute(() -> {
            String what;
            try {
                List<ApplicationExitInfo> all = app.getSystemService(ActivityManager.class)
                        .getHistoricalProcessExitReasons(app.getPackageName(), 0, 0);
                String[] names = new String[all.size()];
                for (int i = 0; i < names.length; i++) names[i] = all.get(i).getProcessName();
                int i = ownIndex(names, own);
                if (i < 0) {
                    what = "last exit of " + own + " unavailable";
                } else {
                    ApplicationExitInfo e = all.get(i);
                    what = exitText(e.getProcessName(), e.getPid(), e.getReason(), e.getStatus(), e.getImportance(),
                            e.getDescription()) + " at " + new Date(e.getTimestamp());
                }
            } catch (RuntimeException e) {
                what = "last exit error " + e.getClass().getSimpleName();
            }
            log(app, "start: " + what);
        });
        ensureCheck(app);
    }

    /** BuddyService.onServiceConnected. */
    static void connected(Context c) {
        log(c, "connected (" + (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 1000
                + " s after process start)");
        c.getSystemService(NotificationManager.class).cancel(NOTICE_ID);
    }

    /** BuddyService.onUnbind / onDestroy. */
    static void lost(Context c, String event) {
        log(c, event);
        ensureCheck(c);
    }

    /** One pending check at most: a job that is starting or running still counts as pending. */
    private static void ensureCheck(Context c) {
        try {
            JobScheduler js = c.getSystemService(JobScheduler.class);
            if (js.getPendingJob(JOB_ID) != null) return;
            js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, A11yWatch.class))
                    .setMinimumLatency(CHECK_MS).setOverrideDeadline(DEADLINE_MS).build());
        } catch (RuntimeException e) { // must never crash Application.onCreate
            log(c, "check not scheduled: " + e);
        }
    }

    private static void check(Context c) {
        State s = decide(BuddyService.get() != null, SetupState.accessibilityEnabled(c));
        if (s == State.CLEAR) c.getSystemService(NotificationManager.class).cancel(NOTICE_ID);
        else log(c, "check: " + (s == State.DEAD ? "enabled, not bound" : "off") + " -> " + notice(c, s));
    }

    /** Posts the notice (same ID: a repeat updates it quietly). Returns what happened, for the log. */
    private static String notice(Context c, State s) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (!nm.areNotificationsEnabled()) return "notifications off";
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, c.getString(R.string.a11y_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT));
        String body = c.getString(s == State.DEAD ? R.string.hub_accessibility_dead : R.string.hub_accessibility_off);
        // The list, with Buddy's row highlighted where Settings honors the extra: the details page needs a system permission.
        PendingIntent open = PendingIntent.getActivity(c, 0,
                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(":settings:fragment_args_key", new ComponentName(c, BuddyService.class).flattenToString()),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            nm.notify(NOTICE_ID, new Notification.Builder(c, CHANNEL)
                    .setSmallIcon(R.drawable.ic_launcher_monochrome)
                    .setContentTitle(c.getString(R.string.a11y_notice_title))
                    .setContentText(body)
                    .setStyle(new Notification.BigTextStyle().bigText(body))
                    .setContentIntent(open)
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(false)
                    .build());
            return "notified";
        } catch (SecurityException e) {
            return "notifications off";
        }
    }

    @Override
    public boolean onStartJob(JobParameters p) {
        check(this);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters p) {
        return false;
    }
}
