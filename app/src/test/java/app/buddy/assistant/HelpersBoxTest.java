package app.buddy.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class HelpersBoxTest {
    private static Sessions.Session s(String id, boolean running) {
        Sessions.Session x = new Sessions.Session(id, "claude");
        x.running = running;
        return x;
    }

    @Test public void roundNamesTheIds() {
        assertEquals("a|b|", Helpers.round(Arrays.asList(s("a", false), s("b", false))));
        assertEquals("", Helpers.round(new ArrayList<Sessions.Session>()));
    }

    @Test public void emptyOrUnclosedIsNotClosed() {
        assertFalse(Helpers.boxClosed("", new ArrayList<Sessions.Session>()));
        assertFalse(Helpers.boxClosed("", Arrays.asList(s("a", false))));
    }

    @Test public void closedWhenKeyMatchesAndNoneRuns() {
        List<Sessions.Session> l = Arrays.asList(s("a", false), s("b", false));
        assertTrue(Helpers.boxClosed(Helpers.round(l), l));
    }

    @Test public void shownWhileOneRuns() {
        List<Sessions.Session> l = Arrays.asList(s("a", false), s("b", true));
        assertFalse(Helpers.boxClosed(Helpers.round(l), l));
    }

    @Test public void nextRoundShowsTheBoxAgain() {
        List<Sessions.Session> old = Arrays.asList(s("a", false));
        List<Sessions.Session> next = Arrays.asList(s("c", false));
        assertNotEquals(Helpers.round(old), Helpers.round(next));
        assertFalse(Helpers.boxClosed(Helpers.round(old), next));
    }
}
