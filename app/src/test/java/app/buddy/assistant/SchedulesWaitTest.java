package app.buddy.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 027: a due scheduled task waits only for its own chat (however long), a pause, Stop or no internet. */
public class SchedulesWaitTest {
    private static final long H = 3600_000L, T0 = 1_000_000L;
    private static final String[] KEYS = {"b", "b/x"}; // a Schedule, and the agent's own message

    private final Sessions.Session a = new Sessions.Session("a", "claude"); // the pipeline
    private final Sessions.Session b = new Sessions.Session("b", "claude"); // the task's chat
    private final List<Sessions.Session> all = Arrays.asList(a, b);
    private final Map<String, Long> waiting = new LinkedHashMap<>();

    /** One look, as pump() makes it. */
    private Schedules.Due look(String key, boolean paused, boolean stopped, boolean online, long now) {
        Sessions.Session s = Schedules.chat(all, key);
        assertSame(b, s);
        return Schedules.due(waiting, key, s, paused, stopped, online, now);
    }

    @Test public void anotherChatAtWorkDoesNotHoldATaskUp() {
        // the old busy() held this task, because runningCount() was 1
        for (String key : KEYS) {
            a.running = true;
            waiting.put(key, T0 + H);
            assertEquals(Schedules.Due.START, look(key, false, false, true, T0));
            assertFalse(waiting.containsKey(key));
        }
    }

    @Test public void ownChatAtWorkWaitsPastTheHourThenStartsOnce() {
        for (String key : KEYS) {
            a.running = true;
            b.running = true;
            waiting.put(key, T0 + H);
            assertEquals(Schedules.Due.WAIT, look(key, false, false, true, T0));
            assertEquals(Schedules.Due.WAIT, look(key, false, false, true, T0 + 3 * H));
            assertTrue(waiting.containsKey(key));
            b.running = false;
            assertEquals(Schedules.Due.START, look(key, false, false, true, T0 + 3 * H + 1000));
            assertFalse(waiting.containsKey(key));
        }
    }

    @Test public void theHourCountsFromWhenItsChatIsFree() {
        for (String key : KEYS) {
            b.running = true;
            waiting.put(key, T0 + H);
            assertEquals(Schedules.Due.WAIT, look(key, false, false, true, T0 + 3 * H));
            b.running = false; // Stop ended the run: its minute, past the old deadline
            assertEquals(Schedules.Due.WAIT, look(key, false, true, true, T0 + 3 * H + 1000));
        }
    }

    @Test public void pauseStopOrOfflineWaitAnHourAtMost() {
        boolean[][] held = {{true, false, true}, {false, true, true}, {false, false, false}};
        for (String key : KEYS) {
            for (boolean[] h : held) {
                waiting.put(key, T0 + H);
                assertEquals(Schedules.Due.WAIT, look(key, h[0], h[1], h[2], T0 + H - 1));
                assertEquals(Schedules.Due.GIVE_UP, look(key, h[0], h[1], h[2], T0 + H));
                assertFalse(waiting.containsKey(key));
            }
        }
    }

    private static Set<String> set(String... ids) {
        return new HashSet<>(Arrays.asList(ids));
    }

    @Test public void aWaitingScheduleSurvivesARestart() {
        a.running = true;
        b.running = true;
        waiting.put("b", T0 + H);
        assertEquals(Schedules.Due.WAIT, look("b", false, false, true, T0));
        Set<String> stored = Schedules.kept(Collections.emptySet(), waiting, "b");
        assertEquals(set("b"), stored);
        // a restart: a new map, the stored ids come back, and the chat is free
        waiting.clear();
        Schedules.restore(waiting, stored, T0 + 5 * H);
        b.running = false;
        assertEquals(Schedules.Due.START, look("b", false, false, true, T0 + 5 * H + 1000));
        assertEquals(Collections.emptySet(), Schedules.kept(stored, waiting, "b"));
    }

    @Test public void aBootCancelOfAnotherChatKeepsTheStoredRun() {
        // cold process: b is stored, nothing restored yet, and boot's sync() cancels a paused schedule a
        Set<String> stored = set("b");
        assertEquals(set("b"), Schedules.kept(stored, waiting, "a"));
        // then the service starts
        Schedules.restore(waiting, stored, T0);
        assertEquals(Schedules.Due.START, look("b", false, false, true, T0 + 1000));
        assertEquals(Collections.emptySet(), Schedules.kept(stored, waiting, "b"));
    }

    @Test public void agentMessagesAreNotHeldInPrefs() {
        waiting.put("b/x", T0 + H);
        assertEquals(Collections.emptySet(), Schedules.kept(Collections.emptySet(), waiting, "b"));
    }

    @Test public void editingAScheduleEndsTheOldRunThatWaits() throws Exception {
        // supersede() takes the key out of waiting and out of the stored set; pump() drops what it doesn't find
        waiting.put("b", T0 + H);
        waiting.remove("b");
        assertEquals(Collections.emptySet(), Schedules.kept(set("b"), waiting, "b"));
        String hub = src("ChatHub.java");
        int sup = hub.indexOf("Schedules.supersede(app, ses.id)");
        assertTrue("setSchedule clears the old run", sup > 0);
        assertTrue("before the new alarm", sup < hub.indexOf("Schedules.set(app, ses)", sup));
    }

    @Test public void savingWithoutChangingTheRunKeepsTheWaitingOne() throws Exception {
        Schedules.Plan old = new Schedules.Plan();
        old.text = "check in";
        Schedules.Plan same = old.copy();
        same.quiet = true; // an option, not the run
        same.wake = true;
        assertTrue(old.sameRun(same));
        Schedules.Plan text = old.copy(), time = old.copy(), days = old.copy();
        text.text = "other";
        time.minutes = new int[]{8 * 60};
        days.days = Schedules.Plan.WEEKDAYS;
        assertFalse(old.sameRun(text));
        assertFalse(old.sameRun(time));
        assertFalse(old.sameRun(days));
        assertFalse(old.sameRun(null));
        String hub = src("ChatHub.java");
        assertTrue("setSchedule asks sameRun", hub.contains("!plan.sameRun(ses.schedule)) Schedules.supersede"));
    }

    @Test public void aRefusedRunWaitsForTheEarlierOneThenStartsOnce() throws Exception {
        // busy() puts the occurrence back in waiting; its chat is working (the earlier run) until that ends
        b.running = true;
        waiting.put("b", T0 + H);
        assertEquals(Schedules.Due.WAIT, look("b", false, false, true, T0 + 2 * H));
        b.running = false;
        assertEquals(Schedules.Due.START, look("b", false, false, true, T0 + 2 * H + 1000));
        assertFalse(waiting.containsKey("b"));
        // the refusal is handled before the "chat is idle or has a new run" return: the earlier run may end first
        String hub = src("ChatHub.java");
        int gone = hub.indexOf("private void helperGone");
        assertTrue(hub.indexOf("busy.run()", gone) > 0 && hub.indexOf("busy.run()", gone) < hub.indexOf("if (!ses.running", gone));
    }

    @Test public void aRefusedRunIsRetriedAsWhatWasSentAndOnlyIfStillWanted() {
        Schedules.Plan sent = new Schedules.Plan();
        sent.text = "17:00 report";
        Schedules.Later oneShot = new Schedules.Later("x", T0, "08:00 reminder");
        // the one-shot is refused in a chat with a different Schedule: it waits under its own key, not the Schedule's
        b.schedule = sent.copy();
        assertEquals("b/x", Schedules.retryKey(b, oneShot, null));
        // the Schedule is refused: wanted while unchanged (options aside), not after an edit, a pause or its removal
        assertEquals("b", Schedules.retryKey(b, null, sent));
        b.schedule.quiet = true;
        assertEquals("b", Schedules.retryKey(b, null, sent));
        b.schedule.text = "other";
        assertEquals(null, Schedules.retryKey(b, null, sent));
        b.schedule = sent.copy();
        b.schedule.paused = true;
        assertEquals(null, Schedules.retryKey(b, null, sent));
        b.schedule = null;
        assertEquals(null, Schedules.retryKey(b, null, sent));
    }

    @Test public void aWaitingTaskStartsAfterTheOtherListenersSawTheRunEnd() throws Exception {
        // posted twice, so after the end check the watch posts in the same notification, whichever listener is first
        String s = src("Schedules.java");
        assertTrue(s.contains("lookLater = () -> main.post(look)"));
        int l = s.indexOf("hubListener");
        String listener = s.substring(l, s.indexOf("private static void listen"));
        assertTrue(listener.contains("main.post(lookLater)"));
        assertFalse(listener.contains("pump()"));
    }

    @Test public void aListenerStartingARunCannotTakeTheChatFromTheOneShotBeingSent() throws Exception {
        // the change notification comes after start() has claimed the chat, not before
        String s = src("Schedules.java");
        int pump = s.indexOf("private static void pump()");
        int start = s.indexOf("else start(s, l);", pump);
        assertTrue(start > 0 && s.indexOf("hub.changed()", start) > start && s.indexOf("hub.changed()", start) < s.indexOf("main.removeCallbacks(look)", pump));
        assertFalse(s.substring(pump, start).contains("hub.changed()"));
    }

    private static String src(String name) throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/main/java/app/buddy/assistant/" + name)), StandardCharsets.UTF_8);
    }

    @Test public void oldWaitsDoNotComeBack() throws Exception {
        String s = src("Schedules.java");
        String why = " - an upstream sync brought back a wait or skip that feature 027 removed, see "
                + "docs/features/027-missed-schedules";
        assertFalse("runningCount(" + why, s.contains("runningCount("));
        assertFalse("confirming(" + why, s.contains("confirming("));
        assertFalse("schedule_skip_running" + why, s.contains("schedule_skip_running"));
    }
}
