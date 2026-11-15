package app.buddy.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ApkStagingTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static File stage(File cache, String bytes) throws Exception {
        File f = FilesProvider.newApk(cache);
        Files.write(f.toPath(), bytes.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    @Test
    public void everyAttemptKeepsItsOwnFile() throws Exception {
        File cache = tmp.newFolder();
        File a = stage(cache, "A");
        File old = stage(cache, "old");
        assertTrue(old.setLastModified(System.currentTimeMillis() - 25 * 3600_000L));
        File b = stage(cache, "B");   // a second successful attempt; staging also removed the day-old file
        File c = stage(cache, "C");   // a refused attempt deletes its own file only
        assertTrue(c.delete());
        assertFalse(old.exists());
        assertEquals(a, FilesProvider.apkFor(cache, a.getName()));
        assertEquals("A", new String(Files.readAllBytes(FilesProvider.apkFor(cache, a.getName()).toPath()), StandardCharsets.UTF_8));
        assertEquals("B", new String(Files.readAllBytes(b.toPath()), StandardCharsets.UTF_8));
        assertFalse(a.equals(b));
    }

    @Test
    public void onlyAStagedNameMapsToAFile() throws Exception {
        File cache = tmp.newFolder();
        assertNull(FilesProvider.apkFor(cache, "../x.apk"));
        assertNull(FilesProvider.apkFor(cache, ".hidden"));
    }
}
