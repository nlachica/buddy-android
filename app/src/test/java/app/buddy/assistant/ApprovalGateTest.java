package app.buddy.assistant;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ApprovalGateTest {
    private static boolean blocked(String cmd) {
        return Tools.SHELL_NO.matcher(cmd).find();
    }

    @Test
    public void shellTripwireBlocksScreenAndBuddyCommands() {
        for (String cmd : new String[]{"input tap 1 2", "/system/bin/input swipe 1 2 3 4", "cmd input tap 1 2",
                "sendevent /dev/input/event0 1 2 3", "monkey -p x 1", "uiautomator dump",
                "am start -n app.buddy.assistant/.MainActivity",
                "settings put secure enabled_accessibility_services x"}) {
            assertTrue(cmd, blocked(cmd));
        }
    }

    @Test
    public void shellTripwireLetsPlainCommandsThrough() {
        assertFalse(blocked("pm list packages"));
        assertFalse(blocked("dumpsys battery"));
    }
}
