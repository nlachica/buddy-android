package app.buddy.assistant;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WatchLinkTest {
    @Test
    public void aWatchMadeIdHasThePhonesFormat() {
        assertTrue(WatchLink.newChatId("0123456789ab"));
        for (String bad : new String[] {"", "0123456789a", "0123456789abc", "0123456789AB", "../x", "a/b", "0123456789a/"})
            assertFalse(bad, WatchLink.newChatId(bad));
    }
}
