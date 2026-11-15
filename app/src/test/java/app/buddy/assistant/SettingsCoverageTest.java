package app.buddy.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** The settings 005 adds to buddy_settings: validation, card text and stale writes, without a phone. */
public class SettingsCoverageTest {
    private static final String CARD = "Change Buddy's setting %1$s\nNow: %2$s\nNew: %3$s";
    private static final String YES = "APPROVED by the user. Go ahead.";

    private static String edit(SettingsTool.Setting s, String v, SettingsTool.Asker asker) throws Exception {
        return SettingsTool.change(s, v, CARD, asker, Runnable::run);
    }

    private static void refused(SettingsTool.Setting s, String v) {
        try {
            s.check(v);
            throw new AssertionError(s.name + " " + v);
        } catch (IllegalArgumentException expected) {
            // refused
        }
    }

    // ---------------------------------------------------------------- Scalar choices

    @Test
    public void aScalarMatchesIgnoringCaseAndStoresTheChoicesSpelling() {
        AtomicReference<String> now = new AtomicReference<>("auto");
        String[][] devices = {{"auto", "7:Galaxy Buds2"}};
        SettingsTool.Scalar s = new SettingsTool.Scalar("audio_device", now::get, now::set, "", () -> devices[0]);
        assertEquals("7:Galaxy Buds2", s.check(" 7:galaxy buds2 "));
        assertEquals("auto", s.check("AUTO"));
        refused(s, "8:Other");
        assertTrue(s.read(), s.read().contains("auto, 7:Galaxy Buds2"));
    }

    @Test
    public void aScalarReadsItsChoicesWhenChecked() {
        String[][] saved = {{"default", "ustc"}};
        SettingsTool.Scalar s = new SettingsTool.Scalar("download_server", () -> "default", v -> { }, "", () -> saved[0]);
        refused(s, "custom");
        saved[0] = new String[]{"default", "ustc", "custom"};
        assertEquals("custom", s.check("Custom"));
        assertTrue(s.read(), s.read().contains("custom"));
    }

    @Test
    public void aScalarRefusalListsTheLiveChoices() {
        SettingsTool.Scalar s = new SettingsTool.Scalar("surface", () -> "black", v -> { }, "", "black", "live");
        try {
            s.check("purple");
            throw new AssertionError();
        } catch (IllegalArgumentException e) {
            assertEquals("surface takes: black, live", e.getMessage());
        }
        assertEquals("live", s.check("LIVE"));
    }
}
