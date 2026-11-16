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
        String all = "  (ask (Ask), routine (Routine allowed), strict (Always ask))";
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
                assertTrue(e.getMessage(), e.getMessage().contains("takes: ask (Ask), routine (Routine allowed), strict (Always ask)"));
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
}
