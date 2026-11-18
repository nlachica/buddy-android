package app.buddy.assistant;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 023: the app knows when the agent's turn is over while its process lives on. */
public class TurnTrackTest {
    private static Sessions.Session s(boolean running) {
        Sessions.Session x = new Sessions.Session("a", "claude");
        x.running = running;
        return x;
    }

    @Test public void resultSetsItOnlyWhileRunning() {
        Sessions.Session x = s(true);
        ChatHub.trackTurn(x, "result", "success");
        assertTrue(x.turnOver);
        Sessions.Session y = s(false);
        ChatHub.trackTurn(y, "result", "success");
        assertFalse(y.turnOver);
    }

    @Test public void aNewTurnClearsIt() {
        Sessions.Session x = s(true);
        x.turnOver = true;
        ChatHub.trackTurn(x, "system", "init");
        assertFalse(x.turnOver);
        x.turnOver = true;
        ChatHub.trackTurn(x, "assistant", "");
        assertFalse(x.turnOver);
    }

    @Test public void otherEventsLeaveIt() {
        Sessions.Session x = s(true);
        x.turnOver = true;
        String[][] ev = {{"system", "background_tasks_changed"}, {"system", "task_notification"}, {"system", "model"},
                {"rate_limit_event", ""}, {"buddy_context", ""}};
        for (String[] e : ev) {
            ChatHub.trackTurn(x, e[0], e[1]);
            assertTrue(e[0] + "/" + e[1], x.turnOver);
        }
    }
}
