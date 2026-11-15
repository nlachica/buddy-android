package app.buddy.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Rules, skills and MCP servers of buddy_settings. */
public class SettingsFamiliesTest {
    private static final String CARD = "Change Buddy's setting %1$s\nNow: %2$s\nNew: %3$s";
    private static final String YES = "APPROVED by the user. Go ahead.";
    private static final java.util.List<String> AGENTS = java.util.Arrays.asList("claude", "codex");
    private static final String SKILL = "---\nname: demo\ndescription: A demo\n---\n\nDo the demo.\n";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // ---------------------------------------------------------------- rules

    private static void refused(String pathId, String json) throws Exception {
        try {
            SettingsTool.buildRule(pathId, new JSONObject(json), AGENTS);
            throw new AssertionError(json);
        } catch (IllegalArgumentException expected) {
            // refused
        }
    }

    @Test
    public void thePathIdOfARuleWins() throws Exception {
        String ok = "\"pkg\":\"com.chat\",\"instruction\":\" reply \"";
        refused("A", "{\"id\":\"B\"," + ok + "}");
        refused(null, "{\"id\":\"B\"," + ok + "}");
        assertEquals("A", SettingsTool.buildRule("A", new JSONObject("{" + ok + "}"), AGENTS).id);
        assertEquals("A", SettingsTool.buildRule("A", new JSONObject("{\"id\":\"A\"," + ok + "}"), AGENTS).id);
        assertEquals("reply", SettingsTool.buildRule(null, new JSONObject("{" + ok + "}"), AGENTS).instruction);
    }

    @Test
    public void aRuleIsCheckedAsTheEditorChecksIt() throws Exception {
        refused("A", "{\"pkg\":\"com.chat\",\"instruction\":\"  \"}");
        refused("A", "{\"pkg\":\"\",\"instruction\":\"x\"}");
        refused("A", "{\"pkg\":\"p\",\"instruction\":\"x\",\"approval\":\"never\"}");
        refused("A", "{\"pkg\":\"p\",\"instruction\":\"x\",\"seconds\":7}");
        refused("A", "{\"pkg\":\"p\",\"instruction\":\"x\",\"limit\":3}");
        refused("A", "{\"pkg\":\"p\",\"instruction\":\"x\",\"backend\":\"nobody\"}");
    }

    // ---------------------------------------------------------------- skills

    private File root() throws Exception {
        File root = tmp.newFolder();
        File d = new File(root, "demo");
        d.mkdirs();
        Files.write(new File(d, "SKILL.md").toPath(), SKILL.getBytes(StandardCharsets.UTF_8));
        return root;
    }

    private static String edit(SettingsTool.Setting s, String v, SettingsTool.Asker asker) throws Exception {
        return SettingsTool.change(s, v, CARD, asker, Runnable::run);
    }

    @Test
    public void linkedSkillsAreRefusedEverywhere() throws Exception {
        File root = root();
        File outside = tmp.newFolder("outside");
        File secret = new File(outside, "secret.txt");
        Files.write(secret.toPath(), "TOKEN-123".getBytes(StandardCharsets.UTF_8));
        File linkedDir = new File(outside, "evil");
        linkedDir.mkdirs();
        Files.write(new File(linkedDir, "SKILL.md").toPath(), SKILL.replace("demo", "evil").getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(new File(root, "evil").toPath(), linkedDir.toPath());
        new File(root, "peek").mkdirs();
        Files.createSymbolicLink(new File(root, "peek/SKILL.md").toPath(), secret.toPath());

        SettingsTool.Asker never = card -> {
            throw new AssertionError("no card");
        };
        for (String name : new String[]{"evil", "peek"}) {
            try {
                SettingsTool.skillFile(root, name);
                throw new AssertionError(name);
            } catch (IllegalArgumentException expected) {
                // refused
            }
            SettingsTool.Setting s = SettingsTool.skill(root, name);
            for (String v : new String[]{"", SKILL.replace("demo", name)}) {
                String r = edit(s, v, never);
                assertTrue(r, r.startsWith("Not changed: "));
                assertFalse(r, r.contains("TOKEN-123"));
            }
            try {
                s.read();
                throw new AssertionError("read " + name);
            } catch (IllegalArgumentException expected) {
                assertFalse(expected.getMessage().contains("TOKEN-123"));
            }
        }
        assertEquals("TOKEN-123", new String(Files.readAllBytes(secret.toPath()), StandardCharsets.UTF_8));
        assertTrue(new File(linkedDir, "SKILL.md").exists());
        assertTrue(SettingsTool.skillFile(root, "demo").isFile()); // a plain skill passes
    }

    @Test
    public void aSkillCanBeCreatedEditedAndDeleted() throws Exception {
        File root = root();
        String text = SKILL.replace("Do the demo.", "Do it twice.");
        assertEquals("Changed skill/demo: " + SKILL + " → " + text, edit(SettingsTool.skill(root, "demo"), text, card -> YES));
        assertEquals(text, new String(Files.readAllBytes(new File(root, "demo/SKILL.md").toPath()), StandardCharsets.UTF_8));
        String made = edit(SettingsTool.skill(root, "fresh"), SKILL.replace("demo", "fresh"), card -> YES);
        assertTrue(made, made.startsWith("Changed skill/fresh"));
        assertTrue(new File(root, "fresh/SKILL.md").isFile());
        edit(SettingsTool.skill(root, "fresh"), "", card -> YES);
        assertFalse(new File(root, "fresh").exists());
        String wrongName = edit(SettingsTool.skill(root, "other"), SKILL, card -> YES); // front matter name must match
        assertTrue(wrongName, wrongName.startsWith("Not changed: "));
    }

    private static byte[] zip(String skillMd, String extraName) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(o)) {
            z.putNextEntry(new ZipEntry("demo/SKILL.md"));
            z.write(skillMd.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            if (extraName != null) {
                z.putNextEntry(new ZipEntry("demo/" + extraName));
                z.write("extra".getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return o.toByteArray();
    }

    @Test
    public void anImportDuringTheCardIsNotOverwrittenOrDeleted() throws Exception {
        for (boolean sameText : new boolean[]{false, true}) {
            File root = root();
            File md = new File(root, "demo/SKILL.md");
            md.setLastModified(System.currentTimeMillis() - 60_000); // a rewrite in the same millisecond must still show
            String imported = sameText ? SKILL : SKILL.replace("Do the demo.", "Imported.");
            SettingsTool.Asker asker = card -> {
                try {
                    Skills.importBytes(root, zip(imported, "extra.txt"), "bad");
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                return YES;
            };
            String r = edit(SettingsTool.skill(root, "demo"), sameText ? "" : SKILL.replace("Do the demo.", "Mine."), asker);
            assertTrue(r, r.contains("changed while the card was open"));
            assertTrue(new File(root, "demo/extra.txt").isFile()); // the import's files are kept
            assertEquals(imported, new String(Files.readAllBytes(md.toPath()), StandardCharsets.UTF_8));
        }
    }

    @Test
    public void aSkillCommitAndAnImportWaitForTheLock() throws Exception {
        File root = root();
        File md = new File(root, "demo/SKILL.md");
        String mine = SKILL.replace("Do the demo.", "Mine.");
        CountDownLatch asked = new CountDownLatch(1), go = new CountDownLatch(1);
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread tool = new Thread(() -> {
            try {
                edit(SettingsTool.skill(root, "demo"), mine, card -> {
                    asked.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        throw new AssertionError(e);
                    }
                    return YES;
                });
            } catch (Throwable t) {
                failed.set(t);
            }
        });
        Thread imp = new Thread(() -> {
            try {
                Skills.importBytes(root, zip(SKILL.replace("Do the demo.", "Imported."), null), "bad");
            } catch (Throwable t) {
                failed.set(t);
            }
        });
        tool.start();
        asked.await();
        synchronized (Skills.LOCK) {
            imp.start();
            go.countDown();
            waitBlocked(tool);
            waitBlocked(imp);
            assertEquals(SKILL, new String(Files.readAllBytes(md.toPath()), StandardCharsets.UTF_8));
        }
        tool.join(5000);
        imp.join(5000);
        assertEquals(null, failed.get());
        assertFalse(tool.isAlive() || imp.isAlive());
    }

    private static void waitBlocked(Thread t) throws InterruptedException {
        for (int i = 0; i < 500 && t.getState() != Thread.State.BLOCKED; i++) Thread.sleep(10);
        assertEquals(Thread.State.BLOCKED, t.getState());
    }

    // ---------------------------------------------------------------- MCP servers

    @Test
    public void mcpServersNeverShowTheirAddressOrKey() throws Exception {
        for (String url : new String[]{"https://u:p@h.example/mcp", "https://h.example/mcp?token=abc",
                "https://h.example/sk-123/mcp", "https://opaque-subdomain.h.example/mcp"}) {
            McpServers.S s = new McpServers.S("notes");
            s.url = url;
            s.tools = new JSONArray().put(new JSONObject().put("name", "search"));
            SettingsTool.Setting set = SettingsTool.mcp(null, "notes", s, true);
            String snapshot = new JSONObject().put("id", "notes").put("url", url).put("on", true).put("tools", s.tools).toString();
            String shown = SettingsTool.mcpLine(s, true) + "\n" + set.read() + "\n" + String.join("\n", set.card(snapshot, "off"))
                    + "\n" + String.join("\n", set.card(snapshot, "")) + "\n" + set.view(snapshot);
            for (String part : new String[]{"u:p", "h.example", "token", "abc", "sk-123", "opaque", "https://"}) {
                assertFalse(url + " leaked " + part + " in: " + shown, shown.contains(part));
            }
            assertTrue(shown, shown.contains("key: set") && shown.contains("search"));
            try {
                set.check(url); // a URL for an existing id (its key would follow it)
                throw new AssertionError("url accepted");
            } catch (IllegalArgumentException expected) {
                assertEquals("off", set.check(" OFF "));
                assertEquals("", set.check(""));
            }
        }
    }

    // ---------------------------------------------------------------- chat listing

    @Test
    public void listingMarksTheCallersChatAcrossThreads() throws Exception {
        Sessions.Session a = new Sessions.Session("aaa", "claude");
        Sessions.Session b = new Sessions.Session("bbb", "claude");
        a.title = b.title = "New chat"; // same title: only the id tells them apart
        java.util.List<Sessions.Session> all = java.util.Arrays.asList(a, b);
        McpServer.CALLER_CHAT.set("bbb"); // as McpServer does on the request thread
        try {
            String here = McpServer.CALLER_CHAT.get(); // what call() does before it hands over to the main thread
            java.util.concurrent.ExecutorService main = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                assertEquals(null, main.submit(() -> McpServer.CALLER_CHAT.get()).get()); // the trap: null over there
                StringBuilder out = new StringBuilder();
                main.submit(() -> SettingsTool.chatLines(out, all, here)).get();
                assertTrue(out.toString(), out.toString().contains("chat/bbb \"New chat\" (this chat):"));
                assertFalse(out.toString(), out.toString().contains("chat/aaa \"New chat\" (this chat)"));
            } finally {
                main.shutdown();
            }
        } finally {
            McpServer.CALLER_CHAT.remove();
        }
    }
}
