package app.buddy.assistant;

import android.app.job.JobParameters;
import android.app.job.JobService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

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

    @Override
    public boolean onStartJob(JobParameters p) {
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters p) {
        return false;
    }
}
