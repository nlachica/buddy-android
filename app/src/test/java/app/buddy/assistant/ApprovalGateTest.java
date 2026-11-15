package app.buddy.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
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

    // ---------------------------------------------------------------- the gate

    private static final String CARD = "Change Buddy's setting %1$s\nNow: %2$s\nNew: %3$s";

    /** A setting kept in a field, that counts its writes. */
    private static final class Fake extends SettingsTool.Setting {
        String value;
        int writes;

        Fake(String value) {
            super("read_aloud", "on, off");
            this.value = value;
        }

        @Override
        String get() {
            return value;
        }

        @Override
        String set(String v) {
            writes++;
            value = v;
            return "";
        }
    }

    private static String change(Fake f, String value, SettingsTool.Asker asker) throws Exception {
        return SettingsTool.change(f, value, CARD, asker, Runnable::run);
    }

    @Test
    public void onlyTheUsersApprovalWritesTheChange() throws Exception {
        for (String answer : new String[]{"DENIED: no", "CHANGES REQUESTED: “x”", "DENIED: the user did not answer within "
                + "5 minutes. Do not do it.", BuddyService.NOT_NOW, BuddyService.AUTO_APPROVED, BuddyService.RULE_APPROVED}) {
            Fake f = new Fake("off");
            assertTrue(answer, change(f, "on", card -> answer).startsWith("Not changed. "));
            assertEquals(0, f.writes);
            assertEquals("off", f.value);
        }
        Fake f = new Fake("off");
        List<String> cards = new ArrayList<>();
        String r = change(f, "on", card -> {
            cards.add(card);
            return "APPROVED by the user. Go ahead.";
        });
        assertEquals("Changed read_aloud: off → on", r);
        assertEquals(1, f.writes);
        assertEquals("on", f.value);
        assertEquals("Change Buddy's setting read_aloud\nNow: off\nNew: on", cards.get(0));
    }

    @Test
    public void aValueThatChangesNothingAsksNoCard() throws Exception {
        Fake f = new Fake("off");
        assertEquals("Already read_aloud = off", change(f, "off", card -> {
            throw new AssertionError("no card");
        }));
    }

    @Test
    public void aChangeMadeWhileTheCardWasOpenIsNotOverwritten() throws Exception {
        Fake edited = new Fake("off");
        String r = change(edited, "on", card -> {
            edited.value = "other";
            return "APPROVED by the user. Go ahead.";
        });
        assertTrue(r, r.contains("changed while the card was open"));
        Fake deleted = new Fake("off");
        r = change(deleted, "on", card -> {
            deleted.value = null;
            return "APPROVED by the user. Go ahead.";
        });
        assertTrue(r, r.contains("changed while the card was open"));
        assertEquals(0, edited.writes + deleted.writes);
    }

    @Test
    public void unknownSecretAndReadOnlySettingsAreRefusedBeforeAnyCard() throws Exception {
        SettingsTool.Asker never = card -> {
            throw new AssertionError("no card");
        };
        for (String secret : new String[]{"token", "bg_token", "api_key", "claude_key", "mcp_key"}) {
            assertNull(secret, SettingsTool.find(null, secret));
        }
        assertTrue(SettingsTool.change(SettingsTool.find(null, "phone_commands_ask"), "off", CARD, never, Runnable::run)
                .contains("read-only"));
        assertTrue(SettingsTool.change(SettingsTool.find(null, "read_aloud"), "maybe", CARD, never, Runnable::run)
                .startsWith("Not changed: "));
    }

    @Test
    public void theTableCoversTheOrdinaryAndUserOnlySwitches() {
        List<String> names = new ArrayList<>();
        for (SettingsTool.Setting s : SettingsTool.scalars(null)) names.add(s.name);
        for (String n : new String[]{"linux_tools_new_chats", "background_new_chats", "notification_rules", "read_aloud",
                "theme", "phone_commands_ask"}) {
            assertTrue(n, names.contains(n));
        }
    }

    @Test
    public void jsonCardsListOnlyTheFieldsThatDiffer() {
        String[] d = SettingsTool.diff("{\"approval\":\"ask\",\"on\":true}", "{\"approval\":\"now\",\"on\":true}");
        assertEquals("approval: ask", d[0]);
        assertEquals("approval: now", d[1]);
        d = SettingsTool.diff(null, "{\"on\":true}");
        assertEquals("(none)", d[0]);
        assertEquals("on: true", d[1]);
        d = SettingsTool.diff("{\"on\":true}", "");
        assertEquals("on: true", d[0]);
        assertEquals("(deleted)", d[1]);
    }

    @Test
    public void scheduleAndAppValuesAreChecked() {
        SettingsTool.Setting sched = SettingsTool.schedule(null, "c1");
        assertEquals("", sched.check(" "));
        for (String bad : new String[]{"nope", "{\"text\":\"\",\"days\":127}", "{\"text\":\"hi\",\"days\":0}"}) {
            try {
                sched.check(bad);
                throw new AssertionError(bad);
            } catch (IllegalArgumentException expected) {
                // refused
            }
        }
        assertTrue(sched.check("{\"text\":\"hi\",\"days\":127,\"minutes\":[540]}").contains("\"text\":\"hi\""));
        try {
            SettingsTool.app(null, "com.example").check("bogus");
            throw new AssertionError("bogus mode");
        } catch (IllegalArgumentException expected) {
            // refused before the phone is asked about the package
        }
    }
}
