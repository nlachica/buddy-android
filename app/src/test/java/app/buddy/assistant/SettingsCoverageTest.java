package app.buddy.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** The settings 005 adds to buddy_settings: validation, card text and stale writes, without a phone. */
public class SettingsCoverageTest {
    private static final String CARD = "Change Buddy's setting %1$s\nNow: %2$s\nNew: %3$s";
    private static final String YES = "APPROVED by the user. Go ahead.";

    private static String edit(SettingsTool.Setting s, String v, SettingsTool.Asker asker) throws Exception {
        return SettingsTool.change(s, v, CARD, asker, Runnable::run);
    }

    private static void refused(SettingsTool.Setting s, String v) {
        try {
            s.check(v);
            throw new AssertionError(s.name + " " + v);
        } catch (IllegalArgumentException expected) {
            // refused
        }
    }

    // ---------------------------------------------------------------- Scalar choices

    @Test
    public void aScalarMatchesIgnoringCaseAndStoresTheChoicesSpelling() {
        AtomicReference<String> now = new AtomicReference<>("auto");
        String[][] devices = {{"auto", "7:Galaxy Buds2"}};
        SettingsTool.Scalar s = new SettingsTool.Scalar("audio_device", now::get, now::set, "", () -> devices[0]);
        assertEquals("7:Galaxy Buds2", s.check(" 7:galaxy buds2 "));
        assertEquals("auto", s.check("AUTO"));
        refused(s, "8:Other");
        assertTrue(s.read(), s.read().contains("auto, 7:Galaxy Buds2"));
    }

    @Test
    public void aScalarReadsItsChoicesWhenChecked() {
        String[][] saved = {{"default", "ustc"}};
        SettingsTool.Scalar s = new SettingsTool.Scalar("download_server", () -> "default", v -> { }, "", () -> saved[0]);
        refused(s, "custom");
        saved[0] = new String[]{"default", "ustc", "custom"};
        assertEquals("custom", s.check("Custom"));
        assertTrue(s.read(), s.read().contains("custom"));
    }

    @Test
    public void aScalarRefusalListsTheLiveChoices() {
        SettingsTool.Scalar s = new SettingsTool.Scalar("surface", () -> "black", v -> { }, "", "black", "live");
        try {
            s.check("purple");
            throw new AssertionError();
        } catch (IllegalArgumentException e) {
            assertEquals("surface takes: black, live", e.getMessage());
        }
        assertEquals("live", s.check("LIVE"));
    }

    private static SettingsTool.Setting named(String name) {
        SettingsTool.Setting s = SettingsTool.find(null, name);
        assertTrue(name, s != null);
        return s;
    }

    private static SettingsTool.Asker never() {
        return card -> {
            throw new AssertionError("no card: " + card);
        };
    }

    // ---------------------------------------------------------------- agents and models

    @Test
    public void theAgentsAndModelsScalarsExistAndRefuseABadShape() {
        for (String n : new String[]{"default_agent", "download_server"}) {
            assertTrue(n, SettingsTool.scalars(null).stream().anyMatch(x -> x.name.equals(n)));
        }
        refused(named("default_agent"), "nobody");
        assertEquals("[default, ustc, tuna, aliyun, yandex, mobinhost]", Arrays.toString(SettingsTool.mirrorIds(false)));
        assertEquals("custom", SettingsTool.mirrorIds(true)[6]);
    }

    private static List<String[]> options() {
        return Arrays.asList(new String[]{"", "Default"}, new String[]{"opus", "Opus"}, new String[]{null, "DeepSeek"},
                new String[]{"@ds/chat", "Chat"});
    }

    @Test
    public void aModelTakesDefaultAListedModelOrATypedName() {
        AtomicReference<String> now = new AtomicReference<>("");
        SettingsTool.Scalar m = SettingsTool.model("default_model/claude", SettingsCoverageTest::options, true, now::get, now::set, "");
        assertEquals("default", m.check("DEFAULT"));
        assertEquals("@ds/chat", m.check("@DS/chat"));
        assertEquals("opus", m.check("opus"));
        assertEquals("claude-x-5", m.check(" claude-x-5 "));
        refused(m, "@ds/other");
        refused(m, "two words");
        refused(m, "");
        refused(m, new String(new char[201]).replace('\0', 'a'));
        assertEquals("default", m.get());
        m.set("opus");
        assertEquals("opus", m.get());
        m.set("default");
        assertEquals("", now.get());
        assertTrue(m.read(), m.read().contains("DeepSeek:") && m.read().contains("@ds/chat — Chat"));
        SettingsTool.Scalar reviewer = SettingsTool.model("reviewer_model/claude", SettingsCoverageTest::options, false, now::get, now::set, "");
        refused(reviewer, "@ds/chat");
        assertEquals("opus", reviewer.check("opus"));
    }

    @Test
    public void aModelChecksTheScreensReadinessAfterItsShape() {
        SettingsTool.Scalar m = SettingsTool.model("default_model/grok", SettingsCoverageTest::options, true, () -> "", v -> { }, "");
        m.ready(v -> {
            throw new IllegalArgumentException("Grok isn't signed in");
        });
        refused(m, "two words"); // shape first
        try {
            m.check("opus");
            throw new AssertionError();
        } catch (IllegalArgumentException e) {
            assertEquals("Grok isn't signed in", e.getMessage());
        }
    }

    @Test
    public void aDefaultModelChangedElsewhereIsNotOverwritten() throws Exception {
        AtomicReference<String> now = new AtomicReference<>("");
        AtomicInteger sets = new AtomicInteger();
        SettingsTool.Scalar m = SettingsTool.model("default_model/claude", SettingsCoverageTest::options, true, now::get,
                v -> sets.incrementAndGet(), "");
        String r = edit(m, "opus", card -> {
            now.set("@ds/chat");
            return YES;
        });
        assertTrue(r, r.contains("changed while the card was open"));
        assertEquals(0, sets.get());
    }

    // ---------------------------------------------------------------- API key services

    private static final String SECRET_A = "https://u:p@h.example/v1?token=abc", SECRET_B = "https://sk-123.example";

    private static Providers.P service(String id, String name, String... models) throws Exception {
        Providers.P p = new Providers.P(id);
        p.name = name;
        p.openai = SECRET_A;
        p.anthropic = SECRET_B;
        p.models = new JSONArray();
        for (String m : models) p.models.put(new JSONObject().put("id", m).put("name", m.toUpperCase()));
        return p;
    }

    private static void noSecret(String text) {
        for (String bit : new String[]{"h.example", "token=abc", "u:p", "sk-123", "https://"}) {
            assertTrue(bit + " in " + text, !text.contains(bit));
        }
    }

    @Test
    public void aServiceIsLookedUpLiveAndRefusedWhenUnknown() throws Exception {
        Map<String, Providers.P> map = new HashMap<>();
        map.put("a", service("a", "Alpha", "m1", "m2"));
        AtomicInteger saves = new AtomicInteger();
        SettingsTool.Setting use = SettingsTool.service("zzz", "", map::get, p -> saves.incrementAndGet());
        try {
            use.check("off");
            throw new AssertionError();
        } catch (IllegalArgumentException e) {
            assertEquals("no such service zzz", e.getMessage());
        }
        try {
            use.set("off");
            throw new AssertionError();
        } catch (IllegalStateException e) {
            assertEquals(0, saves.get());
        }
        assertTrue(map.get("zzz") == null);
        SettingsTool.Setting models = SettingsTool.service("a", "models", map::get, p -> { });
        assertEquals("all", models.check("ALL"));
        assertEquals("m1,m2", models.check("m1, M2, m1"));
        refused(models, "zzz");
        refused(models, "");
        SettingsTool.Setting name = SettingsTool.service("a", "name", map::get, p -> { });
        assertEquals("Work", name.check("  Work  "));
        refused(name, "   ");
        refused(name, "two\nlines");
    }

    @Test
    public void aServiceCardShowsThePartAndNeverAnAddress() throws Exception {
        Map<String, Providers.P> map = new HashMap<>();
        map.put("a", service("a", "Alpha", "m1", "m2"));
        List<String> cards = new ArrayList<>();
        SettingsTool.Asker ok = card -> {
            cards.add(card);
            return YES;
        };
        assertEquals("Changed service/a/name: Alpha → Beta",
                edit(SettingsTool.service("a", "name", map::get, p -> { }), "Beta", ok));
        assertEquals("Changed service/a/models: all → m2", edit(SettingsTool.service("a", "models", map::get, p -> { }), "m2", ok));
        assertEquals("Changed service/a: on → off", edit(SettingsTool.service("a", "", map::get, p -> { }), "off", ok));
        assertTrue(map.get("a").off && map.get("a").name.equals("Beta") && map.get("a").shown.contains("m2"));
        for (String c : cards) noSecret(c);
        noSecret(SettingsTool.serviceLine(map.get("a")));
        assertEquals("service/a = off  (Beta; models shown: 1 of 2)", SettingsTool.serviceLine(map.get("a")));
        for (String part : new String[]{"", "name", "models"}) noSecret(SettingsTool.service("a", part, map::get, p -> { }).read());
        noSecret(edit(SettingsTool.service("a", "name", map::get, p -> { }), "Beta", never())); // Already
    }

    @Test
    public void aServiceEditedRemovedOrReplacedDuringTheCardIsNotWritten() throws Exception {
        for (String part : new String[]{"", "name", "models"}) {
            String value = part.isEmpty() ? "off" : part.equals("name") ? "Beta" : "m1";
            AtomicInteger saves = new AtomicInteger();
            Map<String, Providers.P> map = new HashMap<>();
            map.put("a", service("a", "Alpha", "m1", "m2"));
            SettingsTool.Setting s = SettingsTool.service("a", part, map::get, p -> saves.incrementAndGet());
            // edited by the screen
            String r = edit(s, value, card -> {
                if (part.isEmpty()) map.get("a").off = true;
                else if (part.equals("name")) map.get("a").name = "Gamma";
                else map.get("a").shown.add("m2");
                return YES;
            });
            assertTrue(part + r, r.contains("changed while the card was open"));
            noSecret(r);
            // removed
            map.put("a", service("a", "Alpha", "m1", "m2"));
            r = edit(s, value, card -> {
                map.remove("a");
                return YES;
            });
            assertTrue(part + r, r.contains("changed while the card was open (now: (none))"));
            noSecret(r);
            assertTrue(map.isEmpty());
            // replaced by another service with the same id
            map.put("a", service("a", "Alpha", "m1", "m2"));
            r = edit(s, value, card -> {
                try {
                    Providers.P q = service("a", "Alpha", "m1", "m2");
                    q.openai = "https://other.example";
                    map.put("a", q);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                return YES;
            });
            assertTrue(part + r, r.startsWith("Not changed"));
            noSecret(r);
            assertEquals(0, saves.get());
        }
    }

    // ---------------------------------------------------------------- voice

    @Test
    public void theVoiceScalarsExistAndRefuseABadShape() {
        for (String n : new String[]{"voice_input", "voice_output", "chatgpt_voice", "audio_device", "voice_languages", "assist_mode",
                "live_agent", "live_continue", "pause_media"}) {
            assertTrue(n, SettingsTool.scalars(null).stream().anyMatch(x -> x.name.equals(n)));
        }
        refused(named("voice_input"), "siri");
        refused(named("voice_output"), "claude");
        refused(named("assist_mode"), "shout");
        refused(named("live_agent"), "nobody");
        refused(named("live_continue"), "maybe");
        refused(named("pause_media"), "maybe");
        assertEquals("live", named("assist_mode").check("LIVE"));
        assertEquals("on", named("live_continue").check("ON"));
        assertEquals("phone", named("voice_input").check(" Phone "));
    }

    @Test
    public void languagesAreListedTagsInTheirCanonicalCase() {
        assertEquals("fr-FR,en-US", SettingsTool.languages("fr-fr, en-US, fr-FR"));
        for (String bad : new String[]{"", " , ", "xx-XX", "en-US,xx-XX"}) {
            try {
                SettingsTool.languages(bad);
                throw new AssertionError(bad);
            } catch (IllegalArgumentException expected) {
                // refused
            }
        }
    }

    @Test
    public void voiceLanguagesCardShowsTheDefaultFirstAndRefusesAStaleWrite() throws Exception {
        AtomicReference<String> now = new AtomicReference<>("en-US,fr-FR");
        SettingsTool.Scalar s = SettingsTool.voiceLanguages(now::get, now::set);
        List<String> cards = new ArrayList<>();
        assertEquals("Changed voice_languages: en-US,fr-FR → fr-FR,en-US,de-DE", edit(s, "fr-fr,en-us,DE-de", card -> {
            cards.add(card);
            return YES;
        }));
        assertEquals("fr-FR,en-US,de-DE", now.get());
        assertTrue(cards.get(0), cards.get(0).contains("Now: en-US,fr-FR\nNew: fr-FR,en-US,de-DE"));
        String r = edit(s, "en-US", card -> {
            now.set("de-DE,fr-FR"); // the screen made another the default meanwhile
            return YES;
        });
        assertTrue(r, r.contains("changed while the card was open"));
        assertEquals("de-DE,fr-FR", now.get());
    }

    // ---------------------------------------------------------------- safety and approvals

    @Test
    public void theSafetyScalarsRefuseABadShapeAndWarnOnWhatTheyLower() {
        refused(named("reviewer"), "gpt");
        refused(named("builtin_risky_words"), "maybe");
        assertEquals("same", named("reviewer").check("SAME"));
        String[] card = named("builtin_risky_words").card("on", "off");
        assertTrue(card[1], card[1].contains("Send, Pay, Delete"));
        assertEquals("on", named("builtin_risky_words").card("off", "on")[1]);
    }

    @Test
    public void aButtonNameIsPackageOrStarThenTheLabel() {
        assertEquals("[null, Log out]", Arrays.toString(SettingsTool.buttonName("button/*/Log out")));
        assertEquals("[com.x, a/b]", Arrays.toString(SettingsTool.buttonName("button/com.x/a/b")));
        assertEquals(null, SettingsTool.buttonName("button/*/ "));
        assertEquals(null, SettingsTool.buttonName("button/bad pkg/x"));
        assertEquals(null, SettingsTool.buttonName("button/Pay"));
        assertEquals("button/com.x/a/b", new Lists().row("com.x", "a/b", null, true).name);
    }

    /** A button on ask|allow|none, kept in memory. */
    private static final class Lists {
        String now;

        SettingsTool.Scalar row(String pkg, String label, String app, boolean installed) {
            return SettingsTool.button(pkg, label, app, installed, () -> now, v -> now = v.isEmpty() ? null : v);
        }
    }

    @Test
    public void aButtonCardSaysWhatEachChoiceDoes() throws Exception {
        Lists l = new Lists();
        List<String> cards = new ArrayList<>();
        SettingsTool.Asker ok = card -> {
            cards.add(card);
            return YES;
        };
        edit(l.row(null, "Pay", null, false), "allow", ok);
        assertTrue(cards.get(0), cards.get(0).contains("Now: on neither list") && cards.get(0).contains("never ask")
                && cards.get(0).contains("“Pay”") && cards.get(0).contains("every app"));
        edit(l.row("com.bank", "Pay", "My Bank", true), "ask", ok);
        assertTrue(cards.get(1), cards.get(1).contains("always ask before an agent taps “Pay” in My Bank"));
        assertEquals("ask", l.now);
        edit(l.row("com.bank", "Pay", "My Bank", true), "", ok);
        assertTrue(cards.get(2), cards.get(2).contains("New: on neither list"));
        assertEquals(null, l.now);
        refused(l.row(null, "Pay", null, true), "maybe");
    }

    @Test
    public void aNewRuleNeedsTheAppButAnOrphanedOneCanBeMovedAndRemoved() throws Exception {
        Lists l = new Lists();
        refused(l.row("com.gone", "Pay", "com.gone", false), "allow");
        assertEquals("", l.row("com.gone", "Pay", "com.gone", false).check(""));
        l.now = "ask";
        assertEquals("allow", l.row("com.gone", "Pay", "Old Bank", false).check("allow"));
        List<String> cards = new ArrayList<>();
        assertTrue(edit(l.row("com.gone", "Pay", "Old Bank", false), "", card -> {
            cards.add(card);
            return YES;
        }).startsWith("Changed "));
        assertTrue(cards.get(0), cards.get(0).contains("in Old Bank"));
        assertEquals(null, l.now);
    }

    @Test
    public void aButtonMovedOrRemovedDuringTheCardIsNotWritten() throws Exception {
        Lists l = new Lists();
        l.now = "ask";
        String r = edit(l.row(null, "Pay", null, true), "allow", card -> {
            l.now = "allow";
            return YES;
        });
        assertTrue(r, r.contains("changed while the card was open"));
        l.now = "ask";
        r = edit(l.row(null, "Pay", null, true), "allow", card -> {
            l.now = null;
            return YES;
        });
        assertTrue(r, r.contains("changed while the card was open (now: (none))"));
        assertEquals(null, l.now);
    }

    // ---------------------------------------------------------------- background and appearance

    @Test
    public void theAccentIsAPresetWallpaperOrSixHexDigits() {
        assertEquals("ocean", SettingsTool.accent("Ocean"));
        assertEquals("ocean", SettingsTool.accent("#4f8bff"));
        assertEquals("ocean", SettingsTool.accent("4F8BFF"));
        assertEquals("#123ABC", SettingsTool.accent("#123abc"));
        assertEquals("wallpaper", SettingsTool.accent(" WALLPAPER "));
        for (String bad : new String[]{"#12345", "blue", "#GGGGGG", "", "#1234567"}) {
            try {
                SettingsTool.accent(bad);
                throw new AssertionError(bad);
            } catch (IllegalArgumentException expected) {
                // refused
            }
        }
        assertEquals("#010203", SettingsTool.accentName(0xFF010203));
        assertEquals("coral", SettingsTool.accentName(0xFFD97757));
    }

    @Test
    public void theAppearanceAndBrowserScalarsRefuseABadShape() {
        for (String n : new String[]{"background_browser", "accent", "surface", "face"}) {
            assertTrue(n, SettingsTool.scalars(null).stream().anyMatch(x -> x.name.equals(n)));
        }
        refused(named("surface"), "purple");
        refused(named("background_browser"), "maybe");
        refused(named("face"), "avatar");
        refused(named("accent"), "blue");
        assertEquals("black", named("surface").check("Black"));
        assertEquals("#123ABC", named("accent").check("#123abc"));
    }

    // ---------------------------------------------------------------- chats

    @Test
    public void anAllowedAppCardSaysUntilWhen() throws Exception {
        AtomicReference<String> now = new AtomicReference<>("off");
        List<String> cards = new ArrayList<>();
        SettingsTool.Asker ok = card -> {
            cards.add(card);
            return YES;
        };
        edit(SettingsTool.allowedApp("c1", "com.chat", "Chatty", null, false, now::get, now::set), "on", ok);
        assertTrue(cards.get(0), cards.get(0).contains("on: routine approvals in Chatty go ahead without asking until this chat's task ends"));
        assertTrue(cards.get(0), !cards.get(0).contains("scheduled"));
        now.set("off");
        edit(SettingsTool.allowedApp("c1", "com.chat", "Chatty", null, true, now::get, now::set), "on", ok);
        assertTrue(cards.get(1), cards.get(1).contains("and in its scheduled runs"));
        assertEquals("chat/c1/allowed/com.chat", SettingsTool.allowedApp("c1", "com.chat", "Chatty", null, false, now::get, now::set).name);
    }

    @Test
    public void anAppThatCantBeAllowedIsRefusedForOnButCanBeTakenOff() throws Exception {
        AtomicReference<String> now = new AtomicReference<>("on");
        SettingsTool.Scalar s = SettingsTool.allowedApp("c1", "com.bank", "Bank", "Bank can't be allowed without asking", false, now::get, now::set);
        refused(s, "on");
        assertEquals("off", s.check("OFF"));
        refused(s, "maybe");
    }

    @Test
    public void anAllowedAppTurnedOnByTheScreenIsNotOverwritten() throws Exception {
        AtomicReference<String> now = new AtomicReference<>("off");
        AtomicInteger sets = new AtomicInteger();
        SettingsTool.Scalar s = SettingsTool.allowedApp("c1", "com.chat", "Chatty", null, false, now::get, v -> sets.incrementAndGet());
        String r = edit(s, "on", card -> {
            now.set("on");
            return YES;
        });
        assertTrue(r, r.contains("changed while the card was open"));
        assertEquals(0, sets.get());
    }
}
