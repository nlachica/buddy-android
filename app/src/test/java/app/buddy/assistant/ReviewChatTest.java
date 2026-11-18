package app.buddy.assistant;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** 027: Smart review reads the calling chat's request, not the newest chat's. */
public class ReviewChatTest {
    private final Sessions.Session a = new Sessions.Session("a", "claude");
    private final Sessions.Session b = new Sessions.Session("b", "claude");
    private final Sessions.Session c = new Sessions.Session("c", "claude");
    private final List<Sessions.Session> all = Arrays.asList(a, b, c);

    public ReviewChatTest() {
        a.running = true;
        b.running = true;
        a.lastEventAt = 2000; // a is the newest: latestRunning() would pick it
        b.lastEventAt = 1000;
        c.parent = "b";
    }

    @Test public void theCallingChatIsReviewed() {
        assertSame(b, ChatHub.reviewed(all, "b"));
    }

    @Test public void aSubAgentWorksForItsParent() {
        assertSame(b, ChatHub.reviewed(all, "c"));
    }

    @Test public void noOrUnknownCallerFallsBackToToday() {
        assertNull(ChatHub.reviewed(all, null));
        assertNull(ChatHub.reviewed(all, "gone"));
    }
}
