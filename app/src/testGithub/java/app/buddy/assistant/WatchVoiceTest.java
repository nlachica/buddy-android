package app.buddy.assistant;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class WatchVoiceTest {
    @Test
    public void theGithubBuildHasTheLink() {
        assertNotNull(WatchVoice.load("app.buddy.assistant.WatchVoiceLink"));
    }

    /** F-Droid and build.sh have no such class: the list leaves Watch out. */
    @Test
    public void aMissingLinkClassLoadsAsNull() {
        assertNull(WatchVoice.load("app.buddy.assistant.NoSuchLink"));
    }
}
