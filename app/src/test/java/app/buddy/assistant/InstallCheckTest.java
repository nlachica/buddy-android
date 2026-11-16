package app.buddy.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public class InstallCheckTest {
    private final AtomicInteger launches = new AtomicInteger(), fallbacks = new AtomicInteger();

    private String run(java.util.function.BooleanSupplier shown, boolean notify, Runnable launch) {
        return Updates.openChecked(launch, shown, () -> {
            fallbacks.incrementAndGet();
            return notify;
        }, 50);
    }

    private String run(java.util.function.BooleanSupplier shown, boolean notify) {
        return run(shown, notify, launches::incrementAndGet);
    }

    @Test
    public void shownAtOnce() {
        assertEquals(Updates.OPENED, run(() -> true, true));
        assertEquals(1, launches.get());
    }

    @Test
    public void shownAfterSecondLaunch() {
        assertEquals(Updates.OPENED_RETRY, run(() -> launches.get() >= 2, true));
        assertEquals(2, launches.get());
        assertEquals(0, fallbacks.get());
    }

    @Test
    public void neverShownNotifies() {
        assertEquals(Updates.NOTIFIED, run(() -> false, true));
        assertEquals(2, launches.get());
        assertEquals(1, fallbacks.get());
    }

    @Test
    public void neverShownNotificationsOff() {
        assertEquals(Updates.NOT_SHOWN, run(() -> false, false));
    }

    @Test
    public void noCheckLaunchesOnce() {
        assertEquals(Updates.OPENED, run(null, true));
        assertEquals(1, launches.get());
        assertEquals(0, fallbacks.get());
    }

    @Test
    public void firstLaunchThrowsPropagates() {
        try {
            run(() -> false, true, () -> {
                launches.incrementAndGet();
                throw new SecurityException("no");
            });
            fail();
        } catch (SecurityException expected) {
            assertEquals(1, launches.get());
            assertEquals(0, fallbacks.get());
        }
    }

    @Test
    public void retryThrowsStillNotifies() {
        assertEquals(Updates.NOTIFIED, run(() -> false, true, () -> {
            if (launches.incrementAndGet() == 2) throw new IllegalStateException("late");
        }));
        assertEquals(2, launches.get());
        assertEquals(1, fallbacks.get());
    }

    @Test
    public void installerPackages() {
        assertTrue(Updates.installerPkg("com.google.android.packageinstaller"));
        assertTrue(Updates.installerPkg("com.samsung.android.packageinstaller"));
        for (String p : new String[]{"com.android.vending", "android", "com.android.chrome", null}) {
            assertFalse(String.valueOf(p), Updates.installerPkg(p));
        }
    }

    @Test
    public void agentTexts() {
        String opened = Updates.afterInstall(Updates.OPENED, "2.3.11-fork.1");
        assertTrue(opened.startsWith("The installer is open with Buddy 2.3.11-fork.1. Tell the user now, in one line, to tap "
                + "Update (only they can): Buddy restarts once it's installed, which ends this task, and their chats "
                + "stay. Then stop; don't call wait_for_install."));
        assertTrue(Updates.afterInstall(Updates.OPENED_RETRY, "2.3.11-fork.1").contains("second try"));
        String notified = Updates.afterInstall(Updates.NOTIFIED, "2.3.11-fork.1");
        assertTrue(notified.contains("2.3.11-fork.1") && notified.contains("notification") && notified.contains("Update"));
        assertFalse(notified.contains("installer is open"));
        String notShown = Updates.afterInstall(Updates.NOT_SHOWN, "2.3.11-fork.1");
        assertTrue(notShown.contains("2.3.11-fork.1") && notShown.contains("notifications are off")
                && notShown.contains("open the Buddy app") && notShown.contains(Updates.MANUAL));
        assertFalse(notShown.contains("installer is open"));
        String err = "Couldn't open Android's installer (x), so it isn't on screen.";
        assertEquals(err + " " + Updates.MANUAL, Updates.afterInstall(err, "2.3.11-fork.1"));
    }
}
