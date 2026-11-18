package app.buddy.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** 025: the accessibility watch's decision, exit-record choice and log file. */
public class A11yWatchTest {
    @Test
    public void decideMatchesChatHub() {
        assertEquals(A11yWatch.State.DEAD, A11yWatch.decide(false, true));
        assertEquals(A11yWatch.State.OFF, A11yWatch.decide(false, false));
        assertEquals(A11yWatch.State.CLEAR, A11yWatch.decide(true, true));
        assertEquals(A11yWatch.State.CLEAR, A11yWatch.decide(true, false));
    }

    @Test
    public void reasonNames() {
        assertEquals("LOW_MEMORY", A11yWatch.reasonName(3));
        assertEquals("USER_REQUESTED", A11yWatch.reasonName(10));
        assertEquals("PACKAGE_UPDATED", A11yWatch.reasonName(16));
        assertEquals("99", A11yWatch.reasonName(99));
    }

    @Test
    public void ownIndexSkipsOtherProcesses() {
        String own = "app.buddy.assistant";
        assertEquals(1, A11yWatch.ownIndex(new String[]{"com.google.android.webview:sandboxed_process0:x", own}, own));
        assertEquals(-1, A11yWatch.ownIndex(new String[]{"other"}, own));
        assertEquals(1, A11yWatch.ownIndex(new String[]{null, own}, own));
        assertEquals(-1, A11yWatch.ownIndex(new String[]{null}, null));
    }

    @Test
    public void exitTextHasProcessAndPid() {
        String t = A11yWatch.exitText("app.buddy.assistant", 123, 16, 0, 100, "a\nb");
        assertTrue(t, t.contains("app.buddy.assistant pid=123 PACKAGE_UPDATED(16)"));
        assertFalse(t, t.contains("\n"));
        assertTrue(A11yWatch.exitText("p", 1, 3, 0, 0, null).endsWith("\"\""));
    }

    @Test
    public void appendRotatesAtMax() throws Exception {
        File d = Files.createTempDirectory("a11y").toFile();
        File f = new File(d, "a11y.log");
        A11yWatch.append(f, "one", 100);
        A11yWatch.append(f, "two", 100);
        assertEquals("one\ntwo\n", read(f));
        assertFalse(new File(d, "a11y.log.1").exists());
        A11yWatch.append(f, "three", 8); // file is 8 bytes: at max, so rotate
        assertEquals("one\ntwo\n", read(new File(d, "a11y.log.1")));
        assertEquals("three\n", read(f));
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
