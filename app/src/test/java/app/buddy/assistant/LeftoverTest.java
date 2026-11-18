package app.buddy.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** 023: text the agent never got is delivered once, or goes back to the box; nothing goes live after Stop. */
public class LeftoverTest {
    private static Sessions.Session s(boolean running, String outcome) {
        Sessions.Session x = new Sessions.Session("a", "claude");
        x.running = running;
        x.outcome = outcome;
        return x;
    }

    /** The hub's part: a "send" records the text and starts a run; a run's end drains its notes back through placeLeftover. */
    private static class Hub {
        final List<String> sent = new ArrayList<>();
        final Sessions.Session ses;
        Hub(Sessions.Session ses) { this.ses = ses; }

        void leftover(String text) {
            if (ChatHub.placeLeftover(ses, text, true, -1) == ChatHub.LEFT_SEND) {
                sent.add(text);
                ses.running = true;
            }
        }

        void runEnds() {
            ses.running = false;
            String left = null;
            synchronized (ses.notes) {
                if (!ses.notes.isEmpty()) left = String.join("\n", ses.notes);
                ses.notes.clear();
            }
            if (left != null) leftover(left);
        }
    }

    @Test public void routes() {
        Sessions.Session gone = s(true, null);
        assertEquals(ChatHub.LEFT_DROP, ChatHub.placeLeftover(gone, "x", false, -1));
        assertTrue(gone.notes.isEmpty());
        assertNull(gone.unsent);

        Sessions.Session running = s(true, null);
        assertEquals(ChatHub.LEFT_NOTES, ChatHub.placeLeftover(running, "x", true, -1));
        assertEquals("x", running.notes.get(0));

        Sessions.Session stopped = s(false, "stopped");
        stopped.unsent = "first";
        assertEquals(ChatHub.LEFT_UNSENT, ChatHub.placeLeftover(stopped, "x", true, -1));
        assertEquals("first\nx", stopped.unsent);
        Sessions.Session fresh = s(false, "stopped");
        ChatHub.placeLeftover(fresh, "x", true, -1);
        assertEquals("x", fresh.unsent);

        Sessions.Session idle = s(false, null);
        assertEquals(ChatHub.LEFT_SEND, ChatHub.placeLeftover(idle, "x", true, -1));
        assertTrue(idle.notes.isEmpty());
        assertNull(idle.unsent);
    }

    // A was sent live and failed; B was a note that buddy_done drained: either can arrive first, each is sent once
    @Test public void eachIsSentOnceWhicheverArrivesFirst() {
        for (String[] o : new String[][]{{"A", "B"}, {"B", "A"}}) {
            Hub hub = new Hub(s(false, null));
            hub.leftover(o[0]);   // starts a run with it
            hub.leftover(o[1]);   // a run is going: joins its notes
            assertEquals(1, hub.sent.size());
            hub.runEnds();        // the run's end sends the note
            assertEquals(2, hub.sent.size());
            assertEquals(o[0], hub.sent.get(0));
            assertEquals(o[1], hub.sent.get(1));
            hub.runEnds();
            assertEquals(2, hub.sent.size());
            assertTrue(hub.ses.notes.isEmpty());
        }
    }

    // A's live send (from run 0) fails late; run 1 started meanwhile and its turn is over: A goes live, not into notes
    @Test public void aLateFailedSendGoesLiveToAnIdleReplacementRun() {
        Sessions.Session x = s(true, null);
        x.runId = 1;
        ChatHub.trackTurn(x, "result", "success");
        assertEquals(ChatHub.LEFT_LIVE, ChatHub.placeLeftover(x, "A", true, 0));
        assertTrue(x.notes.isEmpty());
        // the same run's input is what failed: no retry there; and a replacement still mid-turn takes it as a note
        assertEquals(ChatHub.LEFT_NOTES, ChatHub.placeLeftover(x, "A", true, 1));
        Sessions.Session busy = s(true, null);
        busy.runId = 1;
        assertEquals(ChatHub.LEFT_NOTES, ChatHub.placeLeftover(busy, "A", true, 0));
        // after Stop it is never live: notes, which buddy_done sends back to the box
        x.notes.clear();
        x.outcome = "stopped";
        assertEquals(ChatHub.LEFT_NOTES, ChatHub.placeLeftover(x, "A", true, 0));
    }

    @Test public void afterStopNothingIsSentAndBothGoBack() {
        for (String[] o : new String[][]{{"A", "B"}, {"B", "A"}}) {
            Hub hub = new Hub(s(false, "stopped"));
            hub.leftover(o[0]);
            hub.leftover(o[1]);
            assertTrue(hub.sent.isEmpty());
            assertEquals(o[0] + "\n" + o[1], hub.ses.unsent);
        }
    }

    @Test public void stopBeforeTheTurnEndsSendsNothingLive() {
        Sessions.Session x = s(true, null);
        x.unsent = "X";
        x.notes.add("B");
        x.outcome = "stopped"; // as stop() does
        ChatHub.trackTurn(x, "result", "success");
        assertTrue(x.turnOver);
        assertFalse(ChatHub.liveInput(x));
        assertNull(ChatHub.turnEndNotes(x));
        assertEquals(1, x.notes.size());
        Hub hub = new Hub(x);
        hub.runEnds(); // buddy_done
        assertTrue(hub.sent.isEmpty());
        assertEquals("X\nB", x.unsent);
    }

    @Test public void withoutStopTheTurnEndTakesTheNotes() {
        Sessions.Session x = s(true, null);
        x.notes.add("B");
        x.notes.add("C");
        ChatHub.trackTurn(x, "result", "success");
        assertTrue(ChatHub.liveInput(x));
        assertEquals("B\nC", ChatHub.turnEndNotes(x));
        assertTrue(x.notes.isEmpty());
        assertNull(ChatHub.turnEndNotes(x));
    }
}
