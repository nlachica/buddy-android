package app.buddy.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class CodexLiveTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void liveLogsRotateKeepingFive() throws Exception {
        File log = new File(tmp.getRoot(), "live.log");
        CodexLive.rotate(log, CodexLive.LOGS_KEPT); // no files: nothing happens
        for (int k = 1; k <= 7; k++) {
            CodexLive.rotate(log, CodexLive.LOGS_KEPT);
            Files.write(log.toPath(), ("call " + k).getBytes(StandardCharsets.UTF_8));
        }
        assertEquals("call 7", new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8));
        for (int i = 1; i <= 5; i++) {
            assertEquals("call " + (7 - i), new String(Files.readAllBytes(new File(tmp.getRoot(), "live.log." + i).toPath()), StandardCharsets.UTF_8));
        }
        assertFalse(new File(tmp.getRoot(), "live.log.6").exists());
    }
}
