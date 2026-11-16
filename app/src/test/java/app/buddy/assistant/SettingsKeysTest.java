package app.buddy.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** buddy_settings keys: reading a key file, masking, redaction. */
public class SettingsKeysTest {
    private static final String KEY = "ck_0123456789abcdefghijklmnopqrstuvwxyzA"; // 40 characters

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File root() throws Exception {
        File r = Files.createTempDirectory(tmp.getRoot().toPath(), "linux").toFile();
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

    // ---------------------------------------------------------------- KeySetting at the gate

    private static final String CARD = "Change Buddy's setting %1$s\nNow: %2$s\nNew: %3$s";
    private static final String YES = "APPROVED by the user. Go ahead.";
    private static final String OLD_URL = "https://old.example/sk-path-123/mcp";

    /** A map-backed record: what verify got, how often store ran. */
    private static final class Mem extends SettingsTool.KeySetting {
        String url = OLD_URL, stored;
        Exception verifyError;
        Runnable duringVerify = () -> { };
        final List<String> verified = new ArrayList<>();
        final List<String> stores = new ArrayList<>();

        Mem(File root, String file) {
            super("mcp/composio/key", "no MCP server composio", root, file);
        }

        @Override
        String target() {
            return url == null ? null : new JSONArray().put(url).toString();
        }

        @Override
        String saved() {
            return stored;
        }

        @Override
        void verify(JSONArray t, String key) throws Exception {
            verified.add(t.getString(0) + " " + key);
            duringVerify.run();
            if (verifyError != null) throw verifyError;
        }

        @Override
        String store(String key) {
            stores.add(key);
            stored = key.isEmpty() ? null : key;
            return " It lists 3 tools.";
        }
    }

    private static String change(Mem m, String value, List<String> cards, java.util.function.Supplier<String> answer) throws Exception {
        return SettingsTool.change(m, value, CARD, card -> {
            cards.add(card);
            return answer.get();
        }, Runnable::run);
    }

    private File withKey() throws Exception {
        File r = root();
        write(r, "root/k", KEY + "\n");
        return r;
    }

    @Test
    public void setAsksOneCardMasksTheKeyAndStoresIt() throws Exception {
        for (String had : new String[]{null, "old-key-0000000000"}) {
            Mem m = new Mem(withKey(), "~/k");
            m.stored = had;
            List<String> cards = new ArrayList<>();
            String r = change(m, "", cards, () -> YES);
            assertEquals(1, cards.size());
            assertTrue(cards.get(0), cards.get(0).contains("Now: " + (had == null ? "none" : "set")));
            assertTrue(cards.get(0), cards.get(0).contains("New: …xyzA (40 characters), from ~/k"));
            assertEquals(java.util.Collections.singletonList(KEY), m.stores);
            assertEquals(OLD_URL + " " + KEY, m.verified.get(0));
            assertEquals("Changed mcp/composio/key: " + (had == null ? "none" : "set") + " → …xyzA (40 characters), from ~/k It lists 3 tools.", r);
        }
    }

    @Test
    public void removeAsksOneCardAndReadsNoFile() throws Exception {
        Mem m = new Mem(new File("/nonexistent"), null);
        m.stored = "old-key-0000000000";
        List<String> cards = new ArrayList<>();
        assertEquals("Changed mcp/composio/key: set → none (the key is removed)", change(m, "", cards, () -> YES).replace(" It lists 3 tools.", ""));
        assertEquals(1, cards.size());
        assertEquals(java.util.Collections.singletonList(""), m.stores);
        assertTrue(m.verified.isEmpty());
        Mem none = new Mem(new File("/nonexistent"), null);
        String r = change(none, "", cards, () -> {
            throw new AssertionError("no card");
        });
        assertEquals("Not changed: mcp/composio/key: there is no key to remove", r);
    }

    @Test
    public void aDeniedKeyChangesAndSendsNothing() throws Exception {
        for (String answer : new String[]{"DENIED: no", "CHANGES REQUESTED: “x”", BuddyService.NOT_NOW, BuddyService.AUTO_APPROVED,
                BuddyService.RULE_APPROVED}) {
            Mem m = new Mem(withKey(), "~/k");
            List<String> cards = new ArrayList<>();
            assertTrue(answer, change(m, "", cards, () -> answer).startsWith("Not changed. "));
            assertEquals(1, cards.size());
            assertTrue(answer, m.verified.isEmpty() && m.stores.isEmpty());
        }
    }

    @Test
    public void theKeyGoesOnlyToTheCardsAddressAndAStaleWriteIsRefused() throws Exception {
        // the address is edited while the card is open
        Mem m = new Mem(withKey(), "~/k");
        String r = change(m, "", new ArrayList<>(), () -> {
            m.url = "https://new.example/mcp";
            return YES;
        });
        assertEquals(1, m.verified.size());
        assertTrue(m.verified.get(0), m.verified.get(0).startsWith(OLD_URL + " "));
        assertTrue(r, r.startsWith("Not changed: mcp/composio/key changed while the card was open"));
        assertTrue(m.stores.isEmpty());
        // ... or during verify
        Mem d = new Mem(withKey(), "~/k");
        d.duringVerify = () -> d.url = "https://new.example/mcp";
        r = change(d, "", new ArrayList<>(), () -> YES);
        assertTrue(d.verified.get(0), d.verified.get(0).startsWith(OLD_URL + " "));
        assertTrue(r, r.contains("changed while the card was open") && d.stores.isEmpty());
        // the saved key is swapped, or the record removed
        Mem k = new Mem(withKey(), "~/k");
        r = change(k, "", new ArrayList<>(), () -> {
            k.stored = "someone-elses-key";
            return YES;
        });
        assertTrue(r, r.contains("changed while the card was open") && k.stores.isEmpty());
        Mem g = new Mem(withKey(), "~/k");
        r = change(g, "", new ArrayList<>(), () -> {
            g.url = null;
            return YES;
        });
        assertTrue(r, r.contains("changed while the card was open (now: (none))") && g.stores.isEmpty());
    }

    @Test
    public void aFailedVerifyNeverShowsTheServersWordsOrTheStoredAddress() throws Exception {
        String[] words = {"400 at " + OLD_URL, "GET /sk-path-123/mcp failed", "unknown host old.example", "bad key " + KEY + " Bearer " + KEY};
        for (String w : words) {
            Mem m = new Mem(withKey(), "~/k");
            m.verifyError = new java.io.IOException(w);
            String r = change(m, "", new ArrayList<>(), () -> YES);
            assertTrue(r, r.startsWith("Not changed: mcp/composio/key: the server refused the key"));
            for (String leak : new String[]{"old.example", "sk-path-123", "https://", KEY, "400", "GET"}) assertFalse(w + " -> " + r, r.contains(leak));
            assertTrue(m.stores.isEmpty());
        }
    }

    @Test
    public void aLiteralKeyInValueIsRefusedUnreadAndUnrepeated() throws Exception {
        for (String file : new String[]{null, "~/k"}) {
            Mem m = new Mem(new File("/nonexistent"), file);
            String r = change(m, KEY, new ArrayList<>(), () -> {
                throw new AssertionError("no card");
            });
            assertTrue(r, r.startsWith("Not changed: mcp/composio/key takes its key from key_file"));
            assertFalse(r, r.contains("ck_") || r.contains("0123"));
        }
        Mem gone = new Mem(withKey(), "~/k");
        gone.url = null;
        assertEquals("Not changed: no MCP server composio", change(gone, "", new ArrayList<>(), () -> YES));
    }

    @Test
    public void noStringTheToolReturnsHoldsMoreThanTheLast4() throws Exception {
        for (String line : new String[]{"x-consumer-api-key: " + KEY, "Authorization: Bearer " + KEY, KEY}) {
            File r = root();
            write(r, "root/k", line);
            Mem m = new Mem(r, "~/k");
            m.stored = line;
            List<String> cards = new ArrayList<>();
            String all = m.read() + "\n" + m.line() + "\n" + m.view(m.get()) + "\n" + change(m, "", cards, () -> YES) + "\n" + String.join("\n", cards);
            all += "\n" + change(m, "", cards, () -> { // approved again: the same
                m.url = null;
                return YES;
            }) + String.join("\n", cards);
            for (String leak : new String[]{KEY.substring(0, 36), "ck_0123", "x-consumer-api-key", OLD_URL, "Bearer"}) {
                assertFalse(leak + " in: " + all, all.contains(leak));
            }
            assertTrue(all, all.contains("…xyzA (40 characters)"));
        }
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
