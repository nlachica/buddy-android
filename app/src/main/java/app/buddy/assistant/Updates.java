package app.buddy.assistant;

import android.app.Activity;
import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.function.BooleanSupplier;

import static app.buddy.assistant.Ui.*;

/**
 * Buddy's own updates, only ever from this fork's GitHub release, which the fork's CI builds and signs (Android also
 * refuses an APK that isn't signed like the installed one). Official Buddy's releases are only read for their version,
 * to say when the fork lacks one (see {@link #upstreamNote}); nothing is installed from there. Used by the update_buddy tool, the daily check behind the "update available"
 * notification (Settings → Update notices), and the sheet that notification opens.
 */
public final class Updates extends BroadcastReceiver {
    static final String REPO = "https://github.com/nlachica/buddy-android";
    /** For the agent (update_buddy), so English; the sheet shows R.string.update_failed_manual instead. */
    static final String MANUAL = "You can also get it at " + REPO + "/releases/latest (buddy.apk), or with Obtainium "
            + "for automatic updates (see " + REPO + "#updating).";
    private static final String API = "https://api.github.com/repos/nlachica/buddy-android/releases/latest";
    private static final String UPSTREAM_API = "https://api.github.com/repos/ghorbelhamdi/buddy-android/releases/latest";
    private static final String CHANNEL = "updates", ACTION_MUTE = "app.buddy.assistant.MUTE_UPDATES";
    private static final int ID = 3;
    private static final long DAY = 24 * 3600_000L;

    /** The latest release. */
    static final class Release {
        final String version, notes, apkUrl;

        Release(String version, String notes, String apkUrl) {
            this.version = version;
            this.notes = notes;
            this.apkUrl = apkUrl;
        }

        boolean newer() {
            return compare(version, BuildInfo.VERSION) > 0;
        }
    }

    /** Asks GitHub (network: call off the main thread). */
    static Release latest() throws Exception {
        JSONObject rel = new JSONObject(get(API));
        String apk = null;
        JSONArray assets = rel.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            String url = a.optString("browser_download_url");
            if ("buddy.apk".equals(a.optString("name")) && url.startsWith(REPO + "/releases/download/")) apk = url;
        }
        String notes = rel.optString("body").trim();
        if (notes.length() > 1200) notes = notes.substring(0, 1200) + "…";
        return new Release(rel.optString("tag_name").replaceFirst("^v", ""), notes, apk);
    }

    /** Official Buddy's latest release version, or null if GitHub can't be reached (network: off the main thread). */
    static String upstreamVersion() {
        try {
            return new JSONObject(get(UPSTREAM_API)).optString("tag_name").replaceFirst("^v", "");
        } catch (Exception e) {
            return null;
        }
    }

    /** Official Buddy has a release newer than the fork's latest (its "-fork.N" ignored). */
    static boolean upstreamAhead(String upstream, String forkLatest) {
        return upstream != null && !upstream.isEmpty()
                && compare(upstream, forkLatest.replaceFirst("-fork\\.\\d+$", "")) > 0;
    }

    /** For the agent: what to do when official Buddy is ahead, or "". */
    static String upstreamNote(String upstream, String forkLatest) {
        if (!upstreamAhead(upstream, forkLatest)) return "";
        return "Official Buddy " + upstream + " is out and isn't in this fork yet. A chat with Linux tools brings it in by "
                + "following \"Bringing in official Buddy\" in the buddy-fork repo's AGENTS.md (/root/.buddy/work/buddy-fork, "
                + "or gh repo clone nlachica/buddy-android); the user confirms, and the fork's CI publishes a new fork release.";
    }

    /** What update_buddy tells the agent, or null when the install should go ahead (card + installer). */
    static String report(String installed, Release r, String note, boolean install) {
        String head = "Installed: Buddy " + installed + ". Latest release: " + r.version + ".";
        boolean newer = compare(r.version, installed) > 0;
        if (note.isEmpty()) {
            if (!newer) return head + " Buddy is up to date.";
            if (install) return null;
            return head + " An update is available. What's new:\n" + r.notes + "\n\nAsk the user if they want it now; "
                    + "if yes, call update_buddy with install=true.";
        }
        if (!newer) {
            return head + " Buddy is up to date. " + note + " Ask whether to bring it in now; afterwards call "
                    + "update_buddy with install=true.";
        }
        return head + (install ? " Not installed yet." : "") + " An update is available. What's new:\n" + r.notes + "\n\n"
                + note + " That release will contain " + r.version + " too, so recommend bringing it in first; if the user "
                + "wants " + r.version + " now anyway, or bringing it in stopped, call update_buddy with install=true and "
                + "skip_upstream=true.";
    }

    /** Result of {@link #install}: the installer is open, or Android's "allow installs" page is (then try again). */
    static final String OPENED = "opened", NEEDS_PERMISSION = "permission";
    /**
     * Only from {@link #openChecked} (the agent path): the installer came up on the second start / it never showed, so
     * a "ready to install" notification waits for the user's tap / it never showed and notifications are off.
     */
    static final String OPENED_RETRY = "opened_retry", NOTIFIED = "notified", NOT_SHOWN = "not_shown";

    /** Android's own installer (Google's and Samsung's): the only window that counts as "the installer is open". */
    static boolean installerPkg(CharSequence pkg) {
        return pkg != null && pkg.toString().contains("packageinstaller");
    }

    /** Polls every 250 ms for up to waitMs until shown says true. */
    static boolean appears(BooleanSupplier shown, long waitMs) {
        long end = System.currentTimeMillis() + waitMs;
        while (!shown.getAsBoolean()) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) return false;
            try {
                Thread.sleep(Math.min(250, left));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return shown.getAsBoolean();
            }
        }
        return true;
    }

    /**
     * Starts the installer and checks it shows (within waitMs); if not, starts it once more; if still not, fallback
     * (the notification) decides NOTIFIED or NOT_SHOWN. Without a check (shown == null, the sheet) it starts once and
     * says OPENED. The first start's exception propagates; the retry's means "not shown", the first did dispatch.
     */
    static String openChecked(Runnable launch, BooleanSupplier shown, BooleanSupplier fallback, long waitMs) {
        launch.run();
        if (shown == null || appears(shown, waitMs)) return OPENED;
        try {
            launch.run();
        } catch (RuntimeException ignored) {
            // the first start went out; carry on as "not shown"
        }
        if (appears(shown, waitMs)) return OPENED_RETRY;
        return fallback.getAsBoolean() ? NOTIFIED : NOT_SHOWN;
    }

    private static final String PROTECT = " If a Play Protect or \"Open with\" screen is showing instead, the user finishes "
            + "that first, then taps Update.";

    /** What update_buddy tells the agent after {@link #install}; anything but the cases below is an error. */
    static String afterInstall(String res, String version) {
        String done = "Tell the user now, in one line, to tap Update (only they can): Buddy restarts once it's installed, "
                + "which ends this task, and their chats stay. Then stop; don't call wait_for_install.";
        switch (res) {
            case OPENED:
                return "The installer is open with Buddy " + version + ". " + done;
            case OPENED_RETRY:
                return "The installer is open with Buddy " + version + " (it came up on Buddy's second try). " + done;
            case NOTIFIED:
                return "Downloaded Buddy " + version + ", but Buddy couldn't see Android's installer on screen. A "
                        + "notification \"Buddy " + version + " is ready to install\" is waiting: ask the user to tap it, "
                        + "then tap Update (only they can)." + PROTECT + " Buddy restarts once it's installed, which ends "
                        + "this task, and their chats stay. Then stop; don't call wait_for_install.";
            case NOT_SHOWN:
                return "Downloaded Buddy " + version + ", but Buddy couldn't see Android's installer on screen, and "
                        + "notifications are off, so there is nothing to tap. Ask the user to open the Buddy app, then "
                        + "call update_buddy with install=true again." + PROTECT + " " + MANUAL;
            case NEEDS_PERMISSION:
                return "Downloaded Buddy " + version + ", but Buddy isn't allowed to install apps yet. Android's settings page "
                        + "for that is open now: ask the user to turn on 'Allow from this source' for Buddy, then call "
                        + "update_buddy with install=true again.";
            default:
                return res + " " + MANUAL;
        }
    }

    /**
     * Downloads the release's buddy.apk, checks it's a newer Buddy, and opens Android's installer (network: off the
     * main thread). Returns OPENED, NEEDS_PERMISSION, or what went wrong (in English, for the agent).
     */
    static String install(Context c, Release r, BooleanSupplier shown) {
        return install(c, r, false, shown);
    }

    /**
     * {@link #install(Context, Release, BooleanSupplier)}; shown says whether Android's installer is on screen (null:
     * don't check). With user, what went wrong is in the user's language (for the sheet).
     */
    private static String install(Context c, Release r, boolean user, BooleanSupplier shown) {
        if (r.apkUrl == null) return user ? c.getString(R.string.update_error_no_apk) : "The release has no buddy.apk yet.";
        File out = null;
        try {
            out = FilesProvider.newApk(c.getCacheDir());
            HttpURLConnection h = (HttpURLConnection) new URL(r.apkUrl).openConnection();
            h.setConnectTimeout(10000);
            h.setReadTimeout(60000);
            try (InputStream in = h.getInputStream(); OutputStream o = new FileOutputStream(out)) {
                copy(in, o);
            } finally {
                h.disconnect();
            }
        } catch (IOException e) {
            if (out != null) out.delete();
            return user ? c.getString(R.string.update_error_download, e.getMessage())
                    : "The download failed (" + e.getMessage() + ").";
        }
        android.content.pm.PackageInfo pi = c.getPackageManager().getPackageArchiveInfo(out.getPath(), 0);
        if (pi == null || !c.getPackageName().equals(pi.packageName) || pi.getLongVersionCode() <= BuildInfo.VERSION_CODE) {
            out.delete();
            return user ? c.getString(R.string.update_error_not_newer)
                    : "The downloaded file isn't a newer Buddy, so it wasn't opened.";
        }
        if (!c.getPackageManager().canRequestPackageInstalls()) {
            out.delete();
            c.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + c.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return NEEDS_PERMISSION;
        }
        final File apk = out;
        String res = null;
        try {
            res = openChecked(() -> c.startActivity(installer(apk)), shown, () -> notifyInstall(c, r.version, apk), 8000);
        } finally {
            if (res == null) out.delete(); // the first start threw; a file the installer or notification may open later stays
        }
        if (OPENED.equals(res) || OPENED_RETRY.equals(res)) cancel(c);
        return res;
    }

    private static Intent installer(File apk) {
        return new Intent(Intent.ACTION_VIEW)
                .setDataAndType(FilesProvider.apkUri(apk), "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    /** "Ready to install", whose tap starts the installer (a user's tap may start an activity from anywhere). False if it can't be shown. */
    private static boolean notifyInstall(Context c, String version, File apk) {
        NotificationManager nm = channel(c);
        if (!nm.areNotificationsEnabled() || nm.getNotificationChannel(CHANNEL).getImportance() == NotificationManager.IMPORTANCE_NONE) {
            return false;
        }
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(c.getString(R.string.update_ready_title, version))
                .setContentText(c.getString(R.string.update_ready_text))
                .setContentIntent(PendingIntent.getActivity(c, 32, installer(apk),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .setAutoCancel(true)
                .build();
        try {
            nm.notify(ID, n);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    private static NotificationManager channel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, c.getString(R.string.update_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT));
        return nm;
    }

    // ------------------------------------------------------------ daily check

    /** At most once a day (if Update notices is on): notify once per new version. */
    static void maybeCheck(Context c) {
        if (!Prefs.updateNotices(c)) return;
        long now = System.currentTimeMillis();
        if (now - Prefs.updateCheckedAt(c) < DAY) return;
        new Thread(() -> {
            try {
                Release r = latest();
                Prefs.setUpdateCheckedAt(c, now);
                if (r.newer() && r.apkUrl != null && !r.version.equals(Prefs.updateNotified(c))) {
                    notify(c, r);
                    Prefs.setUpdateNotified(c, r.version);
                }
            } catch (Exception ignored) {
                // offline: try again at the next check
            }
        }).start();
    }

    private static void notify(Context c, Release r) {
        NotificationManager nm = channel(c);
        PendingIntent open = PendingIntent.getActivity(c, 30,
                new Intent(c, MainActivity.class).putExtra("update", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent mute = PendingIntent.getBroadcast(c, 31,
                new Intent(c, Updates.class).setAction(ACTION_MUTE),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String text = c.getString(R.string.update_notification_text, BuildInfo.VERSION);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(c.getString(R.string.update_available_title, r.version))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, c.getString(R.string.update_action), open).build())
                .addAction(new Notification.Action.Builder(null, c.getString(R.string.update_mute), mute).build())
                .setAutoCancel(true)
                .build();
        try {
            nm.notify(ID, n);
        } catch (SecurityException ignored) {
            // notifications not allowed
        }
    }

    static void cancel(Context c) {
        c.getSystemService(NotificationManager.class).cancel(ID);
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        if (!ACTION_MUTE.equals(intent.getAction())) return;
        Prefs.setUpdateNotices(c, false);
        cancel(c);
        Toast.makeText(c, R.string.update_muted, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------ the sheet

    /** "Buddy X is available": what's new, and Update. Checks GitHub first; says so if Buddy is up to date. */
    static void showSheet(Activity a) {
        Handler main = new Handler(Looper.getMainLooper());
        Toast checking = Toast.makeText(a, R.string.update_checking, Toast.LENGTH_SHORT);
        checking.show();
        new Thread(() -> {
            Release r;
            try {
                r = latest();
            } catch (Exception e) {
                main.post(() -> Toast.makeText(a, R.string.update_check_failed, Toast.LENGTH_LONG).show());
                return;
            }
            String up = upstreamVersion();
            boolean ahead = upstreamAhead(up, r.version);
            main.post(() -> {
                checking.cancel();
                if (a.isFinishing() || a.isDestroyed()) return;
                if (!r.newer()) {
                    Toast.makeText(a, ahead ? a.getString(R.string.update_upstream_ahead, up)
                            : a.getString(R.string.update_up_to_date, BuildInfo.VERSION), Toast.LENGTH_LONG).show();
                    cancel(a);
                    return;
                }
                sheet(a, r, ahead ? up : null);
            });
        }).start();
    }

    private static void sheet(Activity a, Release r, String upstreamAhead) {
        LinearLayout c = column(a);
        c.addView(title(a, a.getString(R.string.update_available_title, r.version), 18));
        String subtitle = a.getString(R.string.update_sheet_text, BuildInfo.VERSION);
        if (upstreamAhead != null) subtitle += "\n" + a.getString(R.string.update_upstream_ahead, upstreamAhead);
        TextView sub = text(a, subtitle, 13, MUTED);
        sub.setPaddingRelative(0, dp(a, 4), 0, dp(a, 12));
        c.addView(sub);
        if (!r.notes.isEmpty()) {
            ScrollView sv = new ScrollView(a);
            TextView notes = text(a, r.notes.replace("**", "").replace("## ", "").replace("### ", ""), 14, TEXT);
            notes.setPaddingRelative(dp(a, 12), dp(a, 10), dp(a, 12), dp(a, 10));
            sv.addView(notes);
            sv.setBackground(rounded(a, BG, 12));
            c.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    Math.min(dp(a, 280), (int) (a.getResources().getDisplayMetrics().heightPixels * 0.4f))));
        }
        LinearLayout btns = row(a);
        Button later = button(a, a.getString(R.string.update_later), false);
        Button update = protect(button(a, a.getString(R.string.update_action), true)); // only the user starts an update
        btns.addView(later, new LinearLayout.LayoutParams(0, dp(a, 44), 1));
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(0, dp(a, 44), 1);
        up.setMarginStart(dp(a, 10));
        btns.addView(update, up);
        LinearLayout.LayoutParams bp = full();
        bp.topMargin = dp(a, 14);
        c.addView(btns, bp);
        Dialog d = bottomSheet(a, c);
        later.setOnClickListener(v -> d.dismiss());
        Handler main = new Handler(Looper.getMainLooper());
        update.setOnClickListener(v -> {
            update.setEnabled(false);
            update.setText(R.string.update_downloading);
            new Thread(() -> {
                String res = install(a, r, true, null);
                main.post(() -> {
                    if (OPENED.equals(res)) {
                        d.dismiss();
                    } else if (NEEDS_PERMISSION.equals(res)) {
                        Toast.makeText(a, R.string.update_needs_permission, Toast.LENGTH_LONG).show();
                        update.setEnabled(true);
                        update.setText(R.string.update_action);
                    } else {
                        Toast.makeText(a, a.getString(R.string.update_failed_manual, res, REPO + "/releases/latest",
                                REPO + "#updating"), Toast.LENGTH_LONG).show();
                        update.setEnabled(true);
                        update.setText(R.string.update_action);
                    }
                });
            }).start();
        });
        d.show();
    }

    // ------------------------------------------------------------ helpers

    private static String get(String url) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(10000);
        h.setReadTimeout(20000);
        h.setRequestProperty("Accept", "application/vnd.github+json");
        try (InputStream in = h.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            copy(in, b);
            return b.toString("UTF-8");
        } finally {
            h.disconnect();
        }
    }

    static void copy(InputStream in, OutputStream o) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
    }

    /** "2.1.10" vs "2.1.9": compares number by number. */
    static int compare(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D.*", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
