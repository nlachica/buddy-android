package app.buddy.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.After;
import org.junit.Test;

public class CodexDictationTest {
    private static final class L implements Voice.Listener {
        String ended;
        int ends;

        @Override
        public void onPartial(String text) {
        }

        @Override
        public void onFinal(String text) {
        }

        @Override
        public void onEnd(String message) {
            ends++;
            ended = message;
        }
    }

    @After
    public void clear() {
        CodexDictation.listener = null;
        CodexDictation.callThread = null;
    }

    @Test
    public void anErrorQueuedForAReplacedCallLeavesTheNewOneAlone() {
        L b = new L(); // call A's error was queued, then A was replaced by B
        CodexDictation.listener = b;
        CodexDictation.callThread = "B";
        CodexDictation.callError("A", "late");
        assertEquals(0, b.ends);
        assertEquals(b, CodexDictation.listener);
        CodexDictation.callError("B", "now"); // its own call's error still ends it
        assertEquals("now", b.ended);
        assertNull(CodexDictation.listener);
    }
}
