package app.buddy.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** buddy_settings keys: reading a key file, masking, redaction. */
public class SettingsKeysTest {
    private static final String KEY = "ck_0123456789abcdefghijklmnopqrstuvwxyzA"; // 40 characters

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File root() throws Exception {
        File r = tmp.newFolder("linux");
        new File(r, "root").mkdirs();
        return r;
    }

    private static void write(File root, String path, String text) throws Exception {
        File f = new File(root, path);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static String refusal(File root, String path) {
        try {
            SettingsTool.keyFrom(root, path);
            throw new AssertionError("accepted " + path);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    // ---------------------------------------------------------------- keyFrom

    @Test
    public void keyFromReadsAbsoluteAndHomePathsAndTrims() throws Exception {
        File r = root();
        write(r, "root/k", KEY + "\n");
        assertEquals(KEY, SettingsTool.keyFrom(r, "/root/k"));
        assertEquals(KEY, SettingsTool.keyFrom(r, " ~/k "));
    }

    @Test
    public void keyFromRefusesWithoutEchoingPathOrContent() throws Exception {
        File r = root();
        File outside = tmp.newFile("outside");
        Files.write(outside.toPath(), "TOKEN-123\n".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(new File(r, "root/abs").toPath(), outside.toPath());
        Files.createSymbolicLink(new File(r, "root/up").toPath(), new File("../../outside").toPath());
        new File(r, "root/dir").mkdirs();
        write(r, "root/two", KEY + "\nsecond\n");
        write(r, "root/empty", "  \n");
        write(r, "root/big", new String(new char[5000]).replace('\0', 'a'));
        write(r, "root/json", "{\"id\":\"1\",\"key\":\"composio\",\"value\":\"" + KEY + "\"}");
        write(r, "root/arr", "[\"" + KEY + "\"]");
        for (String path : new String[]{"relative/k", "sk-abc-secret-key-123", "/root/missing-sk-xyz", "/root/dir", "/root/abs",
                "/root/up", "/root/two", "/root/empty", "/root/big", "/root/json", "/root/arr", "", "/root/k\nx"}) {
            String m = refusal(r, path);
            for (String leak : new String[]{"sk-abc", "missing-sk", "TOKEN-123", KEY, "composio", "/root/"}) {
                assertFalse(path + " -> " + m, m.contains(leak));
            }
        }
    }

    // ---------------------------------------------------------------- mask, redact

    @Test
    public void maskShowsTheCredentialsLast4OnlyWhenLongAndItsLength() {
        assertEquals("…xyzA (40 characters)", SettingsTool.mask(KEY));
        assertEquals("(10 characters)", SettingsTool.mask("0123456789"));
        assertEquals("(3 characters)", SettingsTool.mask("x-consumer-api-key: abc"));
        assertEquals("(3 characters)", SettingsTool.mask("Authorization: Bearer abc"));
        assertEquals("(3 characters)", SettingsTool.mask("bearer abc"));
        assertEquals("…xyzA (40 characters)", SettingsTool.mask("x-consumer-api-key: " + KEY));
    }

    @Test
    public void redactRemovesKeyHeaderValueAndBareToken() {
        assertEquals("a … b", SettingsTool.redact("a " + KEY + " b", KEY));
        assertEquals("sent … ok", SettingsTool.redact("sent Bearer " + KEY + " ok", "Bearer " + KEY));
        assertEquals("x … y", SettingsTool.redact("x v123 y", "X-Api-Key: v123"));
        String out = SettingsTool.redact("bad Bearer abc and abc", "X-Token: Bearer abc");
        assertFalse(out, out.contains("abc"));
        assertEquals("bad … only", SettingsTool.redact("bad abc only", "Authorization: Bearer abc"));
    }

    @Test
    public void digestHidesTheKeyAndTellsKeysApart() {
        assertEquals("", SettingsTool.digest(null));
        assertEquals("", SettingsTool.digest(""));
        assertEquals(64, SettingsTool.digest(KEY).length());
        assertFalse(SettingsTool.digest(KEY).contains("ck_"));
        assertNotEquals(SettingsTool.digest(KEY), SettingsTool.digest(KEY + "x"));
    }

    // ---------------------------------------------------------------- linuxCaller

    @Test
    public void onlyCallersWhoCanReadLinuxMayPassAKeyFile() {
        Sessions.Session on = new Sessions.Session("aaa", "claude"), off = new Sessions.Session("bbb", "claude");
        on.dev = true;
        assertTrue(SettingsTool.linuxCaller(null, null));
        assertTrue(SettingsTool.linuxCaller("aaa", on));
        assertFalse(SettingsTool.linuxCaller("bbb", off));
        assertFalse(SettingsTool.linuxCaller("zzz", null));
    }
}
