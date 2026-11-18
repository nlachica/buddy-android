package app.buddy.assistant;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 027: element ids from one chat's read_screen don't work for another chat. */
public class ElementIdsTest {
    private String readKey;
    private int readDisplay;

    private void read(String key, int display) {
        readKey = key;
        readDisplay = display;
    }

    private boolean lookup(String key, int display) {
        return Tools.sameRead(readKey, readDisplay, key, display);
    }

    @Test public void interleavedReadsFlipWhoseIdsWork() {
        read("a", 0);
        assertTrue(lookup("a", 0));
        read("b", 7); // B reads on its own display
        assertFalse(lookup("a", 0));
        assertTrue(lookup("b", 7));
        read("a", 0);
        assertTrue(lookup("a", 0));
        assertFalse(lookup("b", 7));
    }

    @Test public void theSameChatOnAnotherDisplayIsRefused() {
        read("a", 7);
        assertFalse(lookup("a", 0));
    }

    @Test public void anOutsideSessionMatchesOnlyAReadWithNoKey() {
        read(null, 0);
        assertTrue(lookup(null, 0));
        assertFalse(lookup("a", 0));
        read("a", 0);
        assertFalse(lookup(null, 0));
    }
}
