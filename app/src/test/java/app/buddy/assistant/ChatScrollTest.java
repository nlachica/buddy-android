package app.buddy.assistant;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** 017: view behaviour isn't JVM-testable here, so this fails if a merge brings the old scroll calls back. */
public class ChatScrollTest {
    private static String src(String name) throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/main/java/app/buddy/assistant/" + name)), StandardCharsets.UTF_8);
    }

    @Test
    public void noFullScrollFocusesAMessage() throws Exception {
        assertFalse(src("Bubble.java").contains("fullScroll("));
        assertFalse(src("ChatActivity.java").contains("fullScroll("));
    }

    @Test
    public void focusGuardsStay() throws Exception {
        assertTrue(src("Bubble.java").contains("FOCUS_BLOCK_DESCENDANTS"));
        assertTrue(src("ChatActivity.java").contains("requestChildRectangleOnScreen"));
    }
}
