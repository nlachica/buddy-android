package app.buddy.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** 019: in a Routine allowed chat, ordinary settings change without a card; everything else still asks. */
public class SettingsRoutineTest {
    private static final String CARD = "Change Buddy's setting %1$s\nNow: %2$s\nNew: %3$s";
    private static final String YES = "APPROVED by the user. Go ahead.";
    private static final String SUFFIX = " (Routine allowed: no card, noted in the chat)";

    @Test
    public void ordinaryNamesSkipAndEverythingElseAsks() {
        for (String n : SettingsTool.ORDINARY) assertFalse(n, SettingsTool.sensitive(n));
        List<String> known = new ArrayList<>();
        for (SettingsTool.Setting s : SettingsTool.scalars(null)) known.add(s.name);
        assertTrue(known.containsAll(SettingsTool.ORDINARY)); // a typo in the list can't hide
        assertEquals(17, SettingsTool.ORDINARY.size());
        for (String n : new String[]{"linux_tools_new_chats", "reviewer", "builtin_risky_words", "phone_commands_ask",
                "voice_cards", "agents_tap_buddy", "ask_new_apps", "phone_commands", "download_server",
                "app/com.whatsapp", "chat/c1/approvals", "chat/c1/allowed/com.whatsapp", "chat/c1/linux_tools",
                "chat/c1/background", "chat/c1/schedule", "mcp/notes", "mcp/notes/key", "service/deepseek",
                "service/deepseek/key", "button/*/Pay", "rule/new", "skill/notes", "default_model/claude",
                "reviewer_model/claude", "some_future_setting"}) {
            assertTrue(n, SettingsTool.sensitive(n));
        }
    }

    @Test
    public void onlyRoutineAllowedSkips() {
        assertTrue(SettingsTool.routineChat("routine", false));
        assertFalse(SettingsTool.routineChat("", false));
        assertFalse(SettingsTool.routineChat("strict", false));
        assertFalse(SettingsTool.routineChat("routine", true));
    }

    // ---------------------------------------------------------------- the gate

    /** A setting kept in a field, that counts its writes; hook runs in prepare (between snapshot and write). */
    private static final class Fake extends SettingsTool.Setting {
        String value;
        int writes;
        boolean throwOnSet;
        Runnable hook = () -> { };

        Fake(String name, String value) {
            super(name, "on, off");
            this.value = value;
        }

        @Override
        String get() {
            return value;
        }

        @Override
        void prepare(String v) {
            hook.run();
        }

        @Override
        String set(String v) {
            if (throwOnSet) throw new IllegalStateException("boom");
            writes++;
            value = v;
            return "";
        }
    }

    private static final SettingsTool.Asker NEVER = card -> {
        throw new AssertionError("no card");
    };

    private static String change(Fake f, String v, SettingsTool.Asker a, List<String> notes) throws Exception {
        return SettingsTool.change(f, v, CARD, a, Runnable::run, notes == null ? null : notes::add);
    }

    @Test
    public void anOrdinarySettingChangesWithNoCardAndANote() throws Exception {
        Fake a = new Fake("audio_device", "phone:2");
        List<String> notes = new ArrayList<>();
        String r = change(a, "auto", NEVER, notes);
        assertEquals(1, a.writes);
        assertEquals("auto", a.value);
        assertEquals("Changed audio_device: phone:2 → auto" + SUFFIX, r);
        assertEquals(Arrays.asList("Change Buddy's setting audio_device\nNow: phone:2\nNew: auto"), notes);
        Fake b = new Fake("read_aloud", "off");
        notes.clear();
        assertTrue(change(b, "on", NEVER, notes).endsWith(SUFFIX));
        assertEquals("on", b.value);
        assertEquals(1, notes.size());
    }

    @Test
    public void aSensitiveSettingStillCardsInARoutineChat() throws Exception {
        for (String n : new String[]{"app/com.x", "chat/c1/approvals", "mcp/notes/key", "linux_tools_new_chats",
                "reviewer", "voice_cards"}) {
            for (String answer : new String[]{"DENIED: no", BuddyService.AUTO_APPROVED, BuddyService.RULE_APPROVED,
                    BuddyService.NOT_NOW}) {
                Fake f = new Fake(n, "off");
                List<String> cards = new ArrayList<>(), notes = new ArrayList<>();
                String r = change(f, "on", card -> {
                    cards.add(card);
                    return answer;
                }, notes);
                assertTrue(n + answer, r.startsWith("Not changed. "));
                assertEquals(n, 1, cards.size());
                assertEquals(n, 0, f.writes);
                assertTrue(notes.isEmpty());
            }
            Fake f = new Fake(n, "off");
            List<String> cards = new ArrayList<>(), notes = new ArrayList<>();
            assertEquals("Changed " + n + ": off → on", change(f, "on", card -> {
                cards.add(card);
                return YES;
            }, notes));
            assertEquals(1, cards.size());
            assertEquals(1, f.writes);
            assertTrue("the card path notes by itself", notes.isEmpty());
        }
    }

    @Test
    public void withoutRoutineAnOrdinarySettingCards() throws Exception {
        Fake f = new Fake("read_aloud", "off");
        List<String> cards = new ArrayList<>();
        assertTrue(change(f, "on", card -> {
            cards.add(card);
            return "DENIED: no";
        }, null).startsWith("Not changed. "));
        assertEquals(0, f.writes);
        assertEquals(1, cards.size());
        assertEquals("Changed read_aloud: off → on", change(f, "on", card -> YES, null));
        assertEquals(1, f.writes);
    }

    @Test
    public void nothingIsNotedUnlessSomethingWasWritten() throws Exception {
        List<String> notes = new ArrayList<>();
        assertEquals("Already read_aloud = off", change(new Fake("read_aloud", "off"), "off", NEVER, notes));
        assertTrue(SettingsTool.change(SettingsTool.find(null, "read_aloud"), "maybe", CARD, NEVER, Runnable::run,
                notes::add).startsWith("Not changed: "));
        Fake stale = new Fake("read_aloud", "off");
        stale.hook = () -> stale.value = "other";
        assertTrue(change(stale, "on", NEVER, notes).contains("changed while the card was open"));
        Fake bad = new Fake("read_aloud", "off");
        bad.throwOnSet = true;
        assertTrue(change(bad, "on", NEVER, notes).startsWith("Not changed: "));
        assertEquals(0, stale.writes + bad.writes);
        assertTrue(notes.toString(), notes.isEmpty());
    }

    @Test
    public void theApprovalsCardTellsRoutineAllowedAlsoSkipsOrdinarySettings() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> stored = new java.util.concurrent.atomic.AtomicReference<>("");
        SettingsTool.Setting s = SettingsTool.approvals("c1", "Trip", null, stored::get, stored::set);
        List<String> cards = new ArrayList<>();
        SettingsTool.change(s, "routine", CARD, card -> {
            cards.add(card);
            return YES;
        }, Runnable::run);
        assertTrue(cards.get(0), cards.get(0).contains("Ordinary Buddy settings"));
        assertTrue(cards.get(0), cards.get(0).contains("steps go ahead in every app"));
    }
}
