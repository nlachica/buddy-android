package app.buddy.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** A "hey buddy" request is spoken by the turn that carries it, and only by that one. */
public class SpokenAskTest {
    private static Sessions.Session running() {
        Sessions.Session s = new Sessions.Session("a", "claude");
        s.running = true;
        s.spokenAsk = "what time is it";
        return s;
    }

    @Test
    public void aQueuedNoteDoesNotMakeTheRunningTurnSpoken() {
        Sessions.Session s = running(); // sendTalk queued the note; the run's own typed answer comes first
        assertFalse(s.spokenRun);
        assertEquals("what time is it", s.spokenAsk);
    }

    @Test
    public void theAgentTakingTheNoteMakesItSpoken() {
        Sessions.Session s = running();
        ChatHub.notesTaken(s, "something else");
        assertFalse(s.spokenRun);
        ChatHub.notesTaken(s, "what time is it\nand more");
        assertTrue(s.spokenRun);
        assertNull(s.spokenAsk);
    }

    @Test
    public void aFailedDeliveryRetriedAsANewRunStillSpeaks() {
        Sessions.Session s = running(); // the live send failed: nothing consumed the request
        s.running = false;
        assertEquals(ChatHub.LEFT_SEND, ChatHub.placeLeftover(s, "what time is it", true, 0));
        ChatHub.turnStarts(s, "what time is it"); // the retry starts its own run
        assertTrue(s.spokenRun);
    }

    @Test
    public void anotherTurnClearsTheWaitWithoutSpeaking() {
        Sessions.Session s = running();
        ChatHub.turnStarts(s, "unrelated");
        assertFalse(s.spokenRun);
        assertNull(s.spokenAsk);
    }
}
