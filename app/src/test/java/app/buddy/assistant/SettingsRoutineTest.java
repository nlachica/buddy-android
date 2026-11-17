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
}
