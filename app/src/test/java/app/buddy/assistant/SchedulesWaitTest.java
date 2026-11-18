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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
