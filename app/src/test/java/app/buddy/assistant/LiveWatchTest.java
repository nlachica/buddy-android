package app.buddy.assistant;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LiveWatchTest {
    @Test
    public void noEarpiece() {
        LiveWatch.Call c = new LiveWatch.Call();
        assertFalse(c.noEarpiece("watch")); // no call yet
        c.begin();
        assertTrue(c.noEarpiece("watch"));
        assertFalse(c.noEarpiece("")); // Auto, the watch hasn't taken the voice
        assertFalse(c.noEarpiece("2:Speaker"));
        c.end();
        assertFalse(c.noEarpiece("watch"));
    }
}
