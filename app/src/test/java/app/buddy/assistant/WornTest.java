package app.buddy.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class WornTest {
    private static final class Fake implements Worn.Port {
        long now;
        boolean failSend;
        final List<Long> sent = new ArrayList<>();
        final List<String> log = new ArrayList<>();
        private final List<long[]> due = new ArrayList<>();
        private final List<Runnable> timers = new ArrayList<>();

        @Override public void send(String node, long id, Runnable failed) {
            sent.add(id);
            if (failSend) failed.run();
        }

        @Override public void after(long ms, Runnable r) {
            due.add(new long[]{now + ms, timers.size()});
            timers.add(r);
        }

        @Override public long now() { return now; }
        @Override public void log(String line) { log.add(line); }

        void advance(long ms) {
            long end = now + ms;
            while (true) {
                long[] next = null;
                for (long[] d : due) if (d[0] <= end && (next == null || d[0] < next[0])) next = d;
                if (next == null) break;
                due.remove(next);
                now = Math.max(now, next[0]);
                timers.get((int) next[1]).run();
            }
            now = end;
        }

        long last() { return sent.get(sent.size() - 1); }
    }

    private final Fake f = new Fake();
    private final Worn w = new Worn(f);
    private Boolean got;

    private void check() {
        got = null;
        w.check("n1", v -> got = v);
    }

    @Test
    public void aWornReplyIsWorn() {
        check();
        w.reply("n1", f.last(), true, 0, null);
        assertTrue(got);
        assertTrue(w.on("n1"));
        assertEquals("worn", f.log.get(0));
    }

    @Test
    public void notWornAndCantTellAreNotWorn() {
        check();
        w.reply("n1", f.last(), false, 0, null);
        assertFalse(got);
        check();
        w.reply("n1", f.last(), null, 0, "no sensor");
        assertFalse(got);
        assertFalse(w.on("n1"));
        assertEquals("can't tell: no sensor", f.log.get(1));
    }

    @Test
    public void aDelayedPositiveIsWornAtThreeSecondsAndNotAfter() {
        check();
        f.now = Worn.FRESH_MS;
        w.reply("n1", f.last(), true, 0, null);
        assertTrue(got);
        check();
        f.now += Worn.FRESH_MS + 1;
        w.reply("n1", f.last(), true, 0, null);
        assertFalse(got);
        assertEquals("stale (age 3001 ms)", f.log.get(1));
    }

    @Test
    public void aSlowSensorIsWornWhenItsSampleIsRecent() {
        check();
        f.now = 6000;
        w.reply("n1", f.last(), true, 4000, null); // sampled at 4 s, arrives at 6 s
        assertTrue(got);
    }

    @Test
    public void aSampleTimeAfterTheReplyIsClamped() {
        check();
        f.now = 5000;
        w.reply("n1", f.last(), true, 99_000, null);
        assertTrue(got);
        f.now += Worn.FRESH_MS + 1; // at = 5000, not later
        assertFalse(w.on("n1"));
    }

    @Test
    public void everyCheckAsksAgainAndTheNewAnswerWins() {
        check();
        w.reply("n1", f.last(), true, 0, null);
        f.now = 1000;
        check();
        assertEquals(2, f.sent.size());
        w.reply("n1", f.last(), false, 0, null);
        assertFalse(got);
        assertFalse(w.on("n1"));
    }

    @Test
    public void callersDuringOneAskShareIt() {
        List<Boolean> all = new ArrayList<>();
        w.check("n1", all::add);
        w.check("n1", all::add);
        assertEquals(1, f.sent.size());
        w.reply("n1", f.last(), true, 0, null);
        assertEquals(2, all.size());
        assertTrue(all.get(0) && all.get(1));
    }

    @Test
    public void noReplyIsNotWornAtEightSeconds() {
        check();
        f.advance(Worn.ASK_MS - 1);
        assertNull(got);
        f.advance(1);
        assertFalse(got);
        assertEquals("no reply", f.log.get(0));
    }

    @Test
    public void aFailedSendIsNotWornAtOnce() {
        f.failSend = true;
        check();
        assertFalse(got);
        assertEquals("send failed", f.log.get(0));
    }

    @Test
    public void aLateReplyToAnOlderAskIsDropped() {
        check();
        long a = f.last();
        f.advance(Worn.ASK_MS);
        check();
        w.reply("n1", a, true, 0, null); // A's late "worn"
        assertNull(got);
        w.reply("n1", f.last(), false, 0, null);
        assertFalse(got);
        assertFalse(w.on("n1"));
    }

    @Test
    public void anotherNodesReplyIsDropped() {
        check();
        w.reply("n2", f.last(), true, 0, null);
        assertNull(got);
    }

    @Test
    public void aRefusedRegistrationSaysSo() {
        check();
        w.reply("n1", f.last(), null, 0, "refused");
        assertEquals("can't tell: refused", f.log.get(0));
    }

    // find: Auto's "a watch answers"
    private String found;
    private boolean picked;
    private Consumer<String> held;

    private void find(String finderNode, boolean hold) {
        found = "unset";
        w.find(out -> {
            if (hold) held = out;
            else out.accept(finderNode);
        }, () -> picked, n -> found = n);
    }

    @Test
    public void findKeepsAWornNodeAndDropsTheRest() {
        find("n1", false);
        assertEquals("unset", found); // asked, not answered yet
        w.reply("n1", f.last(), true, 0, null);
        assertEquals("n1", found);
        find("n1", false);
        w.reply("n1", f.last(), false, 0, null);
        assertNull(found);
        find("n1", false);
        w.reply("n1", f.last(), null, 0, "no event");
        assertNull(found);
        find("n1", false);
        f.advance(Worn.ASK_MS);
        assertNull(found);
    }

    @Test
    public void findWithNoNodeDoesNotAsk() {
        find(null, false);
        assertNull(found);
        assertTrue(f.sent.isEmpty());
    }

    @Test
    public void aPickedWatchIsUsedWithoutAsking() {
        picked = true;
        find("n1", false);
        assertEquals("n1", found);
        assertTrue(f.sent.isEmpty());
    }

    @Test
    public void aWatchPickedWhileAskedIsKeptWhenNotWorn() {
        find("n1", false);
        picked = true;
        w.reply("n1", f.last(), false, 0, null);
        assertEquals("n1", found);
    }

    @Test
    public void aWatchSwitchedToAutoWhileTheFinderRunsIsAsked() {
        picked = true;
        find(null, true);
        picked = false;
        held.accept("n1");
        assertEquals(1, f.sent.size());
        w.reply("n1", f.last(), false, 0, null);
        assertNull(found);
    }

    @Test
    public void aRemovalPushVoidsAnEarlierWornAnswerAndAWornPushIsFresh() {
        check();
        w.reply("n1", f.last(), true, 0, null);
        assertTrue(w.on("n1"));
        w.push("n1", false, 0);
        assertFalse(w.on("n1"));
        w.push("n1", true, 0);
        assertTrue(w.on("n1"));
        f.advance(Worn.FRESH_MS + 1);
        assertFalse(w.on("n1"));
    }

    @Test
    public void aPositivePushIsAsOldAsItsSampleNotAsItsArrival() {
        w.push("n1", true, 4000); // the event was 4 s old when the watch sent it
        assertFalse(w.on("n1"));
        w.push("n1", true, 2000);
        assertTrue(w.on("n1"));
        f.advance(1001);
        assertFalse(w.on("n1"));
    }

    @Test
    public void aRemovalSettlesThePendingAskAndItsOlderPositiveReplyIsDropped() {
        check();
        w.push("n1", false, 0); // removal reaches the phone first
        assertFalse(got);
        w.reply("n1", f.last(), true, 0, null); // the positive sampled before it
        assertFalse(w.on("n1"));
        assertEquals("removed", f.log.get(0));
        assertEquals(1, f.log.size());
    }

    @Test
    public void aReplyFromThePreviousPhoneProcessCannotSettleTheNewAsk() {
        Worn old = new Worn(f, 1000);
        old.check("n1", v -> { });
        long oldId = f.last();
        Worn fresh = new Worn(f, 2000);
        fresh.check("n1", v -> got = v);
        got = null;
        fresh.reply("n1", oldId, true, 0, null);
        assertNull(got);
        fresh.reply("n1", f.last(), true, 0, null);
        assertTrue(got);
    }
}
