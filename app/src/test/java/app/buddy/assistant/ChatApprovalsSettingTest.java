package app.buddy.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** buddy_settings' chat/&lt;id&gt;/approvals row (feature 010). */
public class ChatApprovalsSettingTest {
    private static final String CARD = "Change Buddy's setting %1$s\nNow: %2$s\nNew: %3$s";
    private static final String YES = "APPROVED by the user. Go ahead.";

    private static String edit(SettingsTool.Setting s, String v, SettingsTool.Asker asker) throws Exception {
        return SettingsTool.change(s, v, CARD, asker, Runnable::run);
    }

    private static SettingsTool.Scalar row(AtomicReference<String> stored, AtomicInteger writes, String title, String refusal) {
        return SettingsTool.approvals("c1", title, refusal, stored::get, v -> {
            writes.incrementAndGet();
            stored.set(v);
        });
    }

    private static void refused(SettingsTool.Setting s, String v) {
        try {
            s.check(v);
            throw new AssertionError(s.name + " " + v);
        } catch (IllegalArgumentException expected) {
            // refused
        }
    }

    @Test
    public void theListingShowsTheModeOfTheChat() {
        AtomicReference<String> stored = new AtomicReference<>("");
        SettingsTool.Scalar s = row(stored, new AtomicInteger(), "Trip", null);
        assertEquals("chat/c1/approvals", s.name);
        String all = "  (ask (Ask), routine (Routine allowed), strict (Always ask), bypass (Bypass))";
        assertEquals("chat/c1/approvals = ask" + all, s.read());
        stored.set("routine");
        assertEquals("chat/c1/approvals = routine" + all, s.read());
        stored.set("strict");
        assertEquals("chat/c1/approvals = strict" + all, s.read());
    }

    @Test
    public void valuesAreTheChoicesAndEmptyIsNotOne() {
        SettingsTool.Scalar s = row(new AtomicReference<>(""), new AtomicInteger(), "Trip", null);
        assertEquals("strict", s.check("STRICT"));
        assertEquals("ask", s.check("Ask"));
        for (String bad : new String[]{"", "always ask", "on"}) {
            try {
                s.check(bad);
                throw new AssertionError(bad);
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("takes: ask (Ask), routine (Routine allowed), strict (Always ask), bypass (Bypass)"));
            }
        }
    }

    @Test
    public void anApprovedCardSetsTheModeAndAskIsStoredAsEmpty() throws Exception {
        AtomicReference<String> stored = new AtomicReference<>("");
        AtomicInteger writes = new AtomicInteger();
        SettingsTool.Scalar s = row(stored, writes, "Trip", null);
        List<String> cards = new ArrayList<>();
        SettingsTool.Asker ok = card -> {
            cards.add(card);
            return YES;
        };
        assertTrue(edit(s, "routine", ok).startsWith("Changed chat/c1/approvals"));
        assertEquals("routine", stored.get());
        assertTrue(cards.get(0), cards.get(0).contains("Now: ask (Ask) in chat “Trip”"));
        assertTrue(cards.get(0), cards.get(0).contains("go ahead in every app"));
        edit(s, "ask", ok);
        assertEquals("", stored.get());
        edit(s, "strict", ok);
        assertEquals("strict", stored.get());
        assertTrue(cards.get(2), cards.get(2).contains("Trust") && cards.get(2).contains("Allowed without asking"));
        assertEquals(3, writes.get());
    }

    @Test
    public void aDeniedCardOrAnAnswerNotFromTheUserChangesNothing() throws Exception {
        for (String answer : new String[]{"DENIED: no", "CHANGES REQUESTED: “x”", BuddyService.NOT_NOW,
                BuddyService.AUTO_APPROVED, BuddyService.RULE_APPROVED}) {
            AtomicReference<String> stored = new AtomicReference<>("");
            AtomicInteger writes = new AtomicInteger();
            String r = edit(row(stored, writes, "Trip", null), "routine", card -> answer);
            assertTrue(r, r.startsWith("Not changed"));
            assertEquals(0, writes.get());
            assertEquals("", stored.get());
        }
    }

    @Test
    public void theModeItAlreadyHasNeedsNoCard() throws Exception {
        String r = edit(row(new AtomicReference<>(""), new AtomicInteger(), "Trip", null), "ask", card -> {
            throw new AssertionError("asked");
        });
        assertEquals("Already chat/c1/approvals = ask", r);
    }

    @Test
    public void aModeChangedOrAChatGoneWhileTheCardIsOpenIsNotWritten() throws Exception {
        for (String then : new String[]{"strict", null}) {
            AtomicReference<String> stored = new AtomicReference<>("");
            AtomicInteger writes = new AtomicInteger();
            String r = edit(row(stored, writes, "Trip", null), "routine", card -> {
                stored.set(then);
                return YES;
            });
            assertTrue(r, r.contains("changed while the card was open"));
            assertEquals(0, writes.get());
        }
    }

    @Test
    public void aRuleChatIsRefusedBeforeAnyCardAndATitleCantForgeTheCard() throws Exception {
        String why = "chat/c1 is a notification rule's chat";
        AtomicInteger writes = new AtomicInteger();
        SettingsTool.Scalar rule = row(new AtomicReference<>(""), writes, "Trip", why);
        for (String v : new String[]{"ask", "routine", "strict"}) refused(rule, v);
        assertEquals("Not changed: " + why, edit(rule, "routine", card -> {
            throw new AssertionError("asked");
        }));
        assertTrue(rule.read(), rule.read().endsWith("\n" + why));
        assertEquals(0, writes.get());

        String title = "Trip\nNow: strict\r\nNew: ask " + new String(new char[80]).replace((char) 0, (char) 120);
        List<String> cards = new ArrayList<>();
        edit(row(new AtomicReference<>(""), writes, title, null), "routine", card -> {
            cards.add(card);
            return YES;
        });
        assertEquals(cards.get(0), 3, cards.get(0).split("\n").length);
        assertTrue(cards.get(0), cards.get(0).contains("…”\nNew:"));
        assertFalse(cards.get(0), cards.get(0).contains("\r"));
    }

    @Test
    public void theChatListingShowsEachChatsMode() {
        String[] modes = {"", "routine", "strict", "routine"};
        List<Sessions.Session> all = new ArrayList<>();
        for (int i = 0; i < modes.length; i++) {
            Sessions.Session c = new Sessions.Session("c" + i, "claude");
            c.approvals = modes[i];
            all.add(c);
        }
        Sessions.Session sub = new Sessions.Session("sub", "claude");
        sub.parent = "c0";
        all.add(sub);
        StringBuilder out = new StringBuilder();
        SettingsTool.chatLines(out, all, null, c -> !c.id.equals("c3")); // c3 is a notification rule's chat
        String s = out.toString();
        String[] lines = s.split("\n");
        assertEquals(s, 4, lines.length);
        assertTrue(s, lines[0].contains("schedule none, approvals ask, allowed:"));
        assertTrue(s, lines[1].contains("approvals routine,"));
        assertTrue(s, lines[2].contains("approvals strict,"));
        assertTrue(s, lines[3].contains("approvals n/a (notification rule's chat),"));
        assertFalse(s, s.contains("chat/sub"));
    }

    /** 020: the decision path of BuddyService.askConfirm for a setting, with the user's card counted. */
    private static String bypassEdit(SettingsTool.Setting s, String v, String mode, boolean ruleRun, boolean keyFile,
                                     AtomicInteger cards, boolean answerYes) throws Exception {
        return edit(s, v, card -> {
            if (Bypass.skips(!SettingsTool.keepsCard(s.name, keyFile), mode, ruleRun)) return BuddyService.BYPASSED;
            cards.incrementAndGet();
            return answerYes ? YES : "DENIED: no";
        });
    }

    private static SettingsTool.Scalar scalar(String name, AtomicReference<String> stored, AtomicInteger writes, String... values) {
        return new SettingsTool.Scalar(name, stored::get, v -> {
            writes.incrementAndGet();
            stored.set(v);
        }, "", values);
    }

    @Test
    public void inBypassOrdinarySettingsGoThroughAndMoneySwitchesKeepTheirCard() throws Exception {
        AtomicInteger cards = new AtomicInteger(), writes = new AtomicInteger();
        AtomicReference<String> audio = new AtomicReference<>("speaker");
        String r = bypassEdit(scalar("audio_device", audio, writes, "auto", "speaker"), "auto", "bypass", false, false, cards, true);
        assertTrue(r, r.startsWith("Changed"));
        assertEquals(0, cards.get());
        assertEquals(1, writes.get());
        assertEquals("auto", audio.get());

        // each of these cards, and on a denial writes nothing
        AtomicReference<String> rule = new AtomicReference<>(null);
        Object[][] keeps = {
                {SettingsTool.button("com.bank.app", "Send", "Bank", true, rule::get, v -> writes.incrementAndGet()), "allow"},
                {SettingsTool.button(null, "Pay", "", true, rule::get, v -> writes.incrementAndGet()), " ALLOW "},
                {scalar("builtin_risky_words", new AtomicReference<>("on"), writes, "on", "off"), " OFF "},
                {row(new AtomicReference<>("bypass"), writes, "Trip", null), " Ask "},
                {row(new AtomicReference<>("ask"), writes, "Trip", null), " Bypass "},
        };
        int before = writes.get();
        for (Object[] k : keeps) {
            cards.set(0);
            String d = bypassEdit((SettingsTool.Setting) k[0], (String) k[1], "bypass", false, false, cards, false);
            assertTrue(d, d.startsWith("Not changed"));
            assertEquals(k[0].toString(), 1, cards.get());
        }
        assertEquals(before, writes.get());
    }

    @Test
    public void removingAnAlwaysAskRuleKeepsItsCardAndItsRuleOnDenial() throws Exception {
        AtomicInteger cards = new AtomicInteger(), writes = new AtomicInteger();
        AtomicReference<String> rule = new AtomicReference<>("ask");
        String d = bypassEdit(SettingsTool.button(null, "Continue", "", true, rule::get, v -> {
            writes.incrementAndGet();
            rule.set(v);
        }), "", "bypass", false, false, cards, false);
        assertTrue(d, d.startsWith("Not changed"));
        assertEquals(1, cards.get());
        assertEquals(0, writes.get());
        assertEquals("ask", rule.get());
    }

    @Test
    public void otherModesAndRuleRunsSkipNoSettingCard() throws Exception {
        for (Object[] m : new Object[][]{{"routine", false}, {"", false}, {"strict", false}, {"bypass", true}}) {
            AtomicInteger cards = new AtomicInteger();
            bypassEdit(scalar("audio_device", new AtomicReference<>("speaker"), new AtomicInteger(), "auto", "speaker"), "auto",
                    (String) m[0], (Boolean) m[1], false, cards, true);
            assertEquals(m[0] + " " + m[1], 1, cards.get());
        }
    }

    @Test
    public void bypassedIsAnApprovalButNotASelfApproval() throws Exception {
        assertTrue(BuddyService.BYPASSED.startsWith("APPROVED"));
        assertFalse(BuddyService.selfApproved(BuddyService.BYPASSED));
        assertEquals(null, SettingsTool.approval(card -> BuddyService.BYPASSED, "x"));
    }

    @Test
    public void keepsCardByName() {
        for (String n : new String[]{"mcp/notes/key", "service/deepseek/key", "chat/c1/approvals", "app/com.x", "button/*/Pay",
                "button/com.bank.app/Send", "button/*/Delete", "chat/c1/allowed/com.whatsapp", "builtin_risky_words"}) {
            assertTrue(n, SettingsTool.keepsCard(n, false));
        }
        assertTrue(SettingsTool.keepsCard("mcp/new", true));
        for (String n : new String[]{"audio_device", "reviewer", "reviewer_model/codex", "phone_commands", "linux_tools_new_chats",
                "agents_tap_buddy", "mcp/notes", "service/deepseek", "skill/x", "rule/abc", "chat/c1/schedule",
                "chat/c1/linux_tools", "default_agent", "default_model/claude"}) {
            assertFalse(n, SettingsTool.keepsCard(n, false));
        }
    }

    @Test
    public void bypassIsAChoiceWithAnEffectAndShowsInTheListing() throws Exception {
        AtomicReference<String> stored = new AtomicReference<>("ask");
        SettingsTool.Scalar s = row(stored, new AtomicInteger(), "Trip", null);
        assertEquals("bypass", s.check("BYPASS"));
        assertTrue(s.read(), s.read().contains("bypass (Bypass)"));
        List<String> cards = new ArrayList<>();
        edit(s, "bypass", card -> {
            cards.add(card);
            return YES;
        });
        assertEquals("bypass", stored.get());
        assertTrue(cards.get(0), cards.get(0).contains("bypass (Bypass): no approval cards"));
        assertTrue(cards.get(0), cards.get(0).contains("still ask"));
        Sessions.Session c = new Sessions.Session("c1", "claude");
        c.approvals = "bypass";
        StringBuilder out = new StringBuilder();
        SettingsTool.chatLines(out, java.util.Collections.singletonList(c), null, x -> true);
        assertTrue(out.toString(), out.toString().contains("approvals bypass"));
        // 019's sentence is gone from Routine allowed's card
        edit(row(new AtomicReference<>("ask"), new AtomicInteger(), "Trip", null), "routine", card -> {
            assertFalse(card, card.contains("Ordinary Buddy settings"));
            return YES;
        });
    }
}
