package app.buddy.assistant;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The fork's buddy_settings tool (github flavor only): the agent reads Buddy's settings and changes one, including
 * those upstream keeps for the user alone. Every change goes through {@link #change}: the user approves it on a card
 * that names the setting with its old and new value, and nothing is written unless that card (and no other answer)
 * said yes and the setting is still as the card showed it. Keys, tokens and server addresses are never shown.
 */
final class SettingsTool {
    /** Longest value the tool takes; the card shows values whole, so nothing hides past a limit. */
    private static final int MAX = 20_000;

    /** Shows the user a card and returns their answer (BuddyService.askConfirm's words). */
    interface Asker {
        String ask(String card) throws InterruptedException;
    }

    /** One setting, or one item of a family (a chat's switch, a rule, a skill...). Its methods run on the main thread. */
    abstract static class Setting {
        final String name, allowed;

        Setting(String name, String allowed) {
            this.name = name;
            this.allowed = allowed;
        }

        /** Everything the setting holds right now, to compare before and after the card; null when it doesn't exist. */
        abstract String get();

        /** What a snapshot looks like to the user and the agent (never a secret). */
        String view(String snapshot) {
            return snapshot == null ? "(none)" : snapshot;
        }

        /** The value in the form {@link #set} takes, or IllegalArgumentException saying what is wrong. */
        String check(String value) {
            return value.trim();
        }

        /** Network work that follows the approval, off the main thread. */
        void prepare(String value) throws Exception {
        }

        /** Held while the snapshot is compared and the value is written. */
        Object lock() {
            return this;
        }

        /** Writes the checked value; returns a note on when it takes effect ("" for none). */
        abstract String set(String value) throws Exception;

        /** {now, new} as the card shows them. */
        String[] card(String snapshot, String value) {
            return new String[]{view(snapshot), value.isEmpty() ? "(deleted)" : value};
        }

        /** The setting in full, for a read. */
        String read() {
            return name + " = " + view(get()) + (allowed.isEmpty() ? "" : "  (" + allowed + ")");
        }
    }

    /** A switch or a choice kept in Prefs. */
    static class Scalar extends Setting {
        private final Supplier<String> get;
        private final Consumer<String> set;
        private final String[] values;
        private final String note;

        Scalar(String name, Supplier<String> get, Consumer<String> set, String note, String... values) {
            super(name, String.join(", ", values));
            this.get = get;
            this.set = set;
            this.values = values;
            this.note = note;
        }

        @Override
        String get() {
            return get.get();
        }

        @Override
        String check(String value) {
            String v = value.trim().toLowerCase(Locale.ROOT);
            if (!Arrays.asList(values).contains(v)) throw new IllegalArgumentException(name + " takes: " + allowed);
            return v;
        }

        @Override
        String set(String value) {
            set.accept(value);
            return note;
        }
    }

    private static Scalar onOff(String name, BooleanSupplier get, Consumer<Boolean> set) {
        return new Scalar(name, () -> get.getAsBoolean() ? "on" : "off", v -> set.accept(v.equals("on")), "", "on", "off");
    }

    /** The scalar settings. */
    static List<Setting> scalars(BuddyService svc) {
        List<Setting> l = new ArrayList<>();
        l.add(onOff("linux_tools_new_chats", () -> Prefs.devMode(svc), on -> Prefs.setDevMode(svc, on)));
        l.add(onOff("agents_tap_buddy", () -> Prefs.agentsTapBuddy(svc), on -> Prefs.setAgentsTapBuddy(svc, on)));
        l.add(onOff("background_new_chats", () -> Prefs.backgroundDefault(svc), on -> Prefs.setBackgroundDefault(svc, on)));
        l.add(new Scalar("phone_commands", () -> Prefs.phoneShell(svc) ? "on" : "off",
                v -> Prefs.setPhoneShell(svc, v.equals("on")), "", "on", "off") {
            @Override
            String check(String value) {
                return paired(svc, super.check(value));
            }
        });
        // the approval gate for phone commands: only the user changes it
        l.add(new Scalar("phone_commands_ask", () -> Prefs.phoneShellAsk(svc) ? "on" : "off", v -> { }, "", "on", "off") {
            @Override
            String check(String value) {
                throw new IllegalArgumentException(name + " is read-only: it is the approval card for phone commands, "
                        + "and only the user changes it");
            }
        });
        l.add(onOff("notification_rules", () -> Prefs.rulesOn(svc), on -> Prefs.setRulesOn(svc, on)));
        l.add(onOff("chat_bubble", () -> Prefs.bubbleEnabled(svc), on -> {
            Prefs.setBubbleEnabled(svc, on);
            if (on) HiddenNotice.cancel(svc);
            svc.applyBubbleSetting();
        }));
        l.add(onOff("read_aloud", () -> Prefs.speakReplies(svc), on -> {
            Prefs.setSpeakReplies(svc, on);
            if (!on) Voice.stopSpeaking();
        }));
        l.add(onOff("voice_cards", () -> Prefs.voiceCards(svc), on -> Prefs.setVoiceCards(svc, on)));
        l.add(new Scalar("tap_action", () -> Prefs.tapAction(svc), v -> Prefs.setTapAction(svc, v), "", "panel", "talk", "app"));
        l.add(new Scalar("panel_size", () -> Prefs.panelSize(svc), v -> Prefs.setPanelSize(svc, v), "", "full", "compact"));
        String[] themes = new String[Theme.MODES.length];
        for (int i = 0; i < themes.length; i++) themes[i] = Theme.MODES[i][0];
        l.add(new Scalar("theme", () -> Prefs.themeMode(svc), v -> Prefs.setThemeMode(svc, v),
                " Screens pick it up when they next open.", themes));
        l.add(onOff("update_notices", () -> Prefs.updateNotices(svc), on -> {
            Prefs.setUpdateNotices(svc, on);
            if (!on) Updates.cancel(svc);
            else Prefs.setUpdateNotified(svc, ""); // notify again about a version muted before
        }));
        return l;
    }

    /** "on" for background mode or phone commands waits until the user has paired the phone. */
    private static String paired(BuddyService svc, String v) {
        if (v.equals("on") && !Background.setUp(svc)) {
            throw new IllegalArgumentException("background mode isn't set up yet; only the user pairs it "
                    + "(Settings → Background mode)");
        }
        return v;
    }

    /** An app's mode: hard, smart or trust, or default (Buddy's suggestion; stored as no choice). */
    static Setting app(BuddyService svc, String pkg) {
        return new Setting("app/" + pkg, "hard, smart, trust, default") {
            @Override
            String get() {
                return Prefs.appSafety(svc, pkg);
            }

            @Override
            String view(String snapshot) {
                return snapshot != null ? snapshot : "default (" + Guard.modeWhy(svc, pkg, null)[0] + ")";
            }

            @Override
            String check(String value) {
                String v = value.trim().toLowerCase(Locale.ROOT);
                if (!Arrays.asList("hard", "smart", "trust", "default").contains(v)) {
                    throw new IllegalArgumentException(name + " takes: " + allowed);
                }
                try {
                    svc.getPackageManager().getApplicationInfo(pkg, 0);
                } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                    throw new IllegalArgumentException("no installed app " + pkg);
                }
                return v.equals("default") ? "" : v;
            }

            @Override
            String[] card(String snapshot, String value) {
                return new String[]{view(snapshot), value.isEmpty() ? "default (Buddy's suggestion)" : value};
            }

            @Override
            String set(String value) {
                Prefs.setAppSafety(svc, pkg, value.isEmpty() ? null : value);
                return "";
            }

            @Override
            String read() {
                return name + " = " + view(get()) + "  (" + allowed + ")\n" + Guard.modeWhy(svc, pkg, null)[1];
            }
        };
    }

    private static Sessions.Session chat(BuddyService svc, String id) {
        return ChatHub.get(svc).sessions.find(id);
    }

    /** A chat's switch: Linux tools or background mode. */
    private static Setting chatSwitch(BuddyService svc, String id, String what, Predicate<Sessions.Session> get,
                                      BiConsumer<Sessions.Session, Boolean> set, String note) {
        return new Scalar("chat/" + id + "/" + what, () -> {
            Sessions.Session c = chat(svc, id);
            return c == null ? null : get.test(c) ? "on" : "off";
        }, v -> {
            Sessions.Session c = chat(svc, id);
            if (c == null) throw new IllegalStateException("that chat is gone");
            set.accept(c, v.equals("on"));
        }, note, "on", "off") {
            @Override
            String check(String value) {
                String v = super.check(value);
                return what.equals("background") ? paired(svc, v) : v;
            }
        };
    }

    /** A chat's scheduled task, as Schedules.Plan's JSON; "" removes it. */
    static Setting schedule(BuddyService svc, String id) {
        return new Setting("chat/" + id + "/schedule", "a schedule as JSON, or \"\" to remove it") {
            @Override
            String get() {
                Sessions.Session c = chat(svc, id);
                return c == null || c.schedule == null ? null : c.schedule.toJson().toString();
            }

            @Override
            String check(String value) {
                if (value.trim().isEmpty()) return "";
                Schedules.Plan p = Schedules.Plan.of(json(value));
                if (p == null) {
                    throw new IllegalArgumentException("not a schedule. Shape: {\"minutes\":[540],\"days\":127,\"text\":"
                            + "\"the message to send\",\"wake\":false,\"quiet\":false,\"paused\":false}; minutes after "
                            + "midnight, days as bits (Sunday = 1, Saturday = 64), text required");
                }
                return p.toJson().toString();
            }

            @Override
            String[] card(String snapshot, String value) {
                return diff(snapshot, value);
            }

            @Override
            String set(String value) throws Exception {
                Sessions.Session c = chat(svc, id);
                if (c == null) throw new IllegalStateException("that chat is gone");
                ChatHub.get(svc).setSchedule(c, value.isEmpty() ? null : Schedules.Plan.of(new JSONObject(value)));
                return "";
            }
        };
    }

    /** A JSON object from the agent's text, or IllegalArgumentException. */
    static JSONObject json(String text) {
        try {
            return new JSONObject(text);
        } catch (org.json.JSONException e) {
            throw new IllegalArgumentException("the value isn't a JSON object");
        }
    }

    /** {now, new} for JSON items: only the fields that differ (all of a new item's, all of a deleted one's). */
    static String[] diff(String oldJson, String newJson) {
        if (newJson.isEmpty()) return new String[]{fields(oldJson, null), "(deleted)"};
        JSONObject n = json(newJson), o = oldJson == null ? null : json(oldJson);
        return new String[]{o == null ? "(none)" : fields(oldJson, n), fields(newJson, o)};
    }

    /** "key: value" lines of json, leaving out the fields whose value equals the same field of other. */
    private static String fields(String json, JSONObject other) {
        JSONObject o = json(json);
        StringBuilder b = new StringBuilder();
        for (String k : new java.util.TreeSet<>(keys(o))) {
            if (other != null && String.valueOf(o.opt(k)).equals(String.valueOf(other.opt(k)))) continue;
            b.append(b.length() == 0 ? "" : "\n").append(k).append(": ").append(o.opt(k));
        }
        return b.length() == 0 ? "(nothing)" : b.toString();
    }

    private static List<String> keys(JSONObject o) {
        List<String> l = new ArrayList<>();
        for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) l.add(it.next());
        return l;
    }


    // ------------------------------------------------------------ rules

    /** A rule from the agent's JSON, checked as the rule editor checks it. pathId: the rule's id, or null for a new one. */
    static Rules.Rule buildRule(String pathId, JSONObject o, java.util.Collection<String> agents) {
        if (o.has("id") && (pathId == null || !o.optString("id").equals(pathId))) {
            throw new IllegalArgumentException(pathId == null ? "rule/new takes no id; Buddy gives it one"
                    : "the id in the value isn't " + pathId + ": rule/" + pathId + " only changes that rule");
        }
        Rules.Rule r = Rules.Rule.of(o);
        if (pathId != null) r.id = pathId;
        r.instruction = r.instruction.trim();
        r.from = r.from.trim();
        r.words = r.words.trim();
        if (r.pkg.isEmpty()) throw new IllegalArgumentException("pkg (the app whose notifications it watches) is needed");
        if (r.instruction.isEmpty()) throw new IllegalArgumentException("instruction is needed");
        if (!Arrays.asList("ask", "countdown", "now").contains(r.approval)) {
            throw new IllegalArgumentException("approval takes: ask, countdown, now");
        }
        if (Arrays.binarySearch(sorted(Rules.SECONDS), r.seconds) < 0) {
            throw new IllegalArgumentException("seconds takes: " + Arrays.toString(Rules.SECONDS));
        }
        if (Arrays.binarySearch(sorted(Rules.LIMITS), r.limit) < 0) {
            throw new IllegalArgumentException("limit takes: " + Arrays.toString(Rules.LIMITS) + " (0 is no limit)");
        }
        if (!agents.contains(r.backend)) throw new IllegalArgumentException("backend takes: " + String.join(", ", agents));
        return r;
    }

    private static int[] sorted(int[] a) {
        int[] b = a.clone();
        Arrays.sort(b);
        return b;
    }

    /** rule/&lt;id&gt; or rule/new (id null): the rule's JSON; "" deletes it. */
    static Setting rule(BuddyService svc, String id) {
        return new Setting("rule/" + (id == null ? "new" : id), "the rule as JSON (pkg, instruction, from, words, backend, "
                + "model, approval ask|countdown|now, seconds, limit, on), or \"\" to delete it") {
            private String added = "";

            @Override
            String get() {
                Rules.Rule r = id == null ? null : Rules.find(svc, id);
                return r == null ? null : ruleJson(r);
            }

            @Override
            String check(String value) {
                if (id != null && Rules.find(svc, id) == null) throw new IllegalArgumentException("no rule " + id + "; use rule/new");
                if (value.trim().isEmpty()) {
                    if (id == null) throw new IllegalArgumentException("rule/new needs the rule's JSON");
                    return "";
                }
                return ruleJson(buildRule(id, json(value), ruleAgents()));
            }

            @Override
            String[] card(String snapshot, String value) {
                return diff(snapshot, value);
            }

            @Override
            String set(String value) {
                if (value.isEmpty()) {
                    Rules.remove(svc, id);
                } else {
                    Rules.Rule r = Rules.Rule.of(json(value));
                    if (id == null) { // a new rule: its id must be unused by the time it is saved
                        while (Rules.find(svc, r.id) != null) r.id = Long.toString(Long.parseLong(r.id, 36) + 1, 36);
                        added = " (id " + r.id + ")";
                    }
                    Rules.put(svc, r);
                }
                Triggers.get(svc).rulesChanged();
                return added;
            }

            @Override
            String read() {
                String j = get();
                return name + " = " + (j == null ? "(none)" : j) + "\n" + allowed;
            }
        };
    }

    /** The agents that can run a rule (the editor offers these). */
    private static List<String> ruleAgents() {
        List<String> l = new ArrayList<>();
        for (Account a : Account.ALL) if (a.runsRules()) l.add(a.agent);
        return l;
    }

    private static String ruleJson(Rules.Rule r) {
        try {
            return r.json().toString();
        } catch (org.json.JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------ skills

    /**
     * A skill's SKILL.md, if the skill is Buddy's to read and change: a plain skill folder inside root. A link (the
     * folder or the file) is refused, and so is anything whose real path leaves root, so a skill can't reach a file
     * elsewhere on the phone.
     */
    static File skillFile(File root, String name) throws java.io.IOException {
        if (name.isEmpty() || !name.equals(Skills.slug(name))) {
            throw new IllegalArgumentException("a skill's name is lower-case letters, digits and dashes");
        }
        File d = new File(root, name), md = new File(d, "SKILL.md");
        if (Skills.isLink(d) || Skills.isLink(md)) {
            throw new IllegalArgumentException("skill " + name + " is linked: it is not read or changed here");
        }
        if (!md.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)) {
            throw new IllegalArgumentException("skill " + name + " is outside the skills folder");
        }
        return md;
    }

    /** skill/&lt;name&gt;: the whole SKILL.md (name and description up top); "" deletes the skill's folder. */
    static Setting skill(File root, String skillName) {
        return new Setting("skill/" + skillName, "the skill's whole SKILL.md (its front matter name must be " + skillName
                + "), or \"\" to delete the skill's folder") {
            @Override
            String get() {
                synchronized (Skills.LOCK) { // text, then its time: an import that only added files still rewrote SKILL.md
                    try {
                        File md = skillFile(root, skillName);
                        return md.isFile() ? Skills.read(md) + "\u0000" + md.lastModified() : null;
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException(e.getMessage());
                    }
                }
            }

            @Override
            String view(String snapshot) {
                return snapshot == null ? "(none)" : snapshot.substring(0, snapshot.lastIndexOf('\u0000'));
            }

            @Override
            String check(String value) {
                try {
                    skillFile(root, skillName);
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e.getMessage());
                }
                if (value.isEmpty()) return "";
                String[] fm = Skills.frontMatter(value);
                if (!fm[0].equals(skillName) || fm[2].isEmpty()) {
                    throw new IllegalArgumentException("the SKILL.md must start with front matter (---, name: " + skillName
                            + ", description: ..., ---) and have instructions");
                }
                return value;
            }

            @Override
            Object lock() {
                return Skills.LOCK;
            }

            @Override
            String[] card(String snapshot, String value) {
                return new String[]{view(snapshot), value.isEmpty() ? "(deleted: the skill's whole folder goes)" : value};
            }

            @Override
            String set(String value) throws Exception {
                File md = skillFile(root, skillName); // again, right before the write
                if (value.isEmpty()) Skills.delete(new Skills.Skill(skillName, "", md.getParentFile()));
                else Linux.write(md, value);
                return "";
            }

            @Override
            String read() {
                return name + ":\n" + view(get());
            }
        };
    }

    /** A line per skill in the listing: its description, or that it is linked. */
    private static String skillLines(File root) {
        StringBuilder b = new StringBuilder();
        File[] ds = root.listFiles();
        if (ds != null) java.util.Arrays.sort(ds);
        for (File d : ds == null ? new File[0] : ds) {
            if (!d.getName().equals(Skills.slug(d.getName())) || !d.isDirectory() && !Skills.isLink(d)) continue;
            try {
                File md = skillFile(root, d.getName());
                if (md.isFile()) b.append("skill/").append(d.getName()).append(" — ").append(Skills.frontMatter(Skills.read(md))[1]).append('\n');
            } catch (IllegalArgumentException | java.io.IOException e) {
                b.append("skill/").append(d.getName()).append(" (linked: not read or changed here)\n");
            }
        }
        return b.toString();
    }

    // ------------------------------------------------------------ MCP servers

    /** "mcp/notes = on  (key: set, 3 tools)": never the server's address. */
    static String mcpLine(McpServers.S s, boolean keySet) {
        return "mcp/" + s.id + " = " + (s.on ? "on" : "off") + "  (key: " + (keySet ? "set" : "none") + ", " + s.tools.length()
                + " tools)";
    }

    /** mcp/&lt;id&gt;: an existing server takes on, off or ""; a new id takes the server's https address (no key). */
    static Setting mcp(BuddyService svc, String id, McpServers.S existing, boolean keySet) {
        return new Setting("mcp/" + id, "on, off or \"\" to remove it; a new id takes the server's address (https://…) and "
                + "is added without a key") {
            private JSONArray found = new JSONArray();

            @Override
            String get() {
                McpServers.S s = McpServers.find(id);
                if (s == null) return null;
                try {
                    return new JSONObject().put("id", s.id).put("url", s.url).put("on", s.on).put("tools", s.tools).toString();
                } catch (org.json.JSONException e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            String view(String snapshot) { // the snapshot holds the address: only on/off comes out of it
                return snapshot == null ? "(none)" : json(snapshot).optBoolean("on") ? "on" : "off";
            }

            @Override
            String check(String value) {
                String v = value.trim();
                if (existing != null) {
                    if (!Arrays.asList("on", "off", "").contains(v.toLowerCase(Locale.ROOT))) {
                        throw new IllegalArgumentException("mcp/" + id + " exists: its address can't be changed here "
                                + "(its key would follow it). Use on, off or \"\"");
                    }
                    return v.toLowerCase(Locale.ROOT);
                }
                if (!McpServers.slug(id).equals(id) || !McpServers.free(id)) {
                    throw new IllegalArgumentException("no server " + id + ", and that isn't a free name (lower-case letters, digits, _)");
                }
                if (!McpServers.addressOk(v)) throw new IllegalArgumentException("the address must be https://… (or http on this phone or the user's own network)");
                return v;
            }

            @Override
            String[] card(String snapshot, String value) {
                if (snapshot == null) return new String[]{"(none)", "add the server at " + value + " (no key)"};
                return new String[]{view(snapshot), value.isEmpty() ? "(deleted)" : value};
            }

            @Override
            void prepare(String value) throws Exception {
                if (existing == null) found = McpServers.discover(value, null);
            }

            @Override
            String set(String value) throws Exception {
                if (existing == null) {
                    if (!McpServers.free(id)) throw new IllegalStateException("the name " + id + " was taken meanwhile");
                    McpServers.S s = new McpServers.S(id);
                    s.url = value;
                    s.tools = found;
                    McpServers.save(svc, s, ""); // "" so no saved key can follow it
                } else if (value.isEmpty()) {
                    McpServers.remove(svc, id);
                } else {
                    McpServers.setOn(svc, McpServers.find(id), value.equals("on"));
                }
                return "";
            }

            @Override
            String read() {
                if (existing == null) return name + " = (none)";
                StringBuilder tools = new StringBuilder();
                for (int i = 0; i < existing.tools.length(); i++) {
                    tools.append(i == 0 ? "" : ", ").append(existing.tools.optJSONObject(i).optString("name"));
                }
                return mcpLine(existing, keySet) + (tools.length() == 0 ? "" : "\ntools: " + tools);
            }
        };
    }

    // ------------------------------------------------------------ the gate

    /** The answer when the user (and nothing standing in for them) approved, else null. */
    static String approval(Asker asker, String card) throws InterruptedException {
        String answer = asker.ask(card);
        return answer.startsWith("APPROVED") && !BuddyService.selfApproved(answer) ? null : answer;
    }

    /**
     * Changes a setting: check, snapshot, card, then, only if the user approved and nothing changed meanwhile, write.
     * cardFormat: R.string.tools_card_setting (name, now, new). main runs the snapshot and the write on the main
     * thread, where the settings screens write too, so the compare-and-write can't interleave with them.
     */
    static String change(Setting s, String value, String cardFormat, Asker asker, Executor main) throws Exception {
        if (value.length() > MAX) return "Not changed: the value is over " + MAX + " characters.";
        String v, old;
        String[] nn;
        try {
            v = s.check(value);
            old = onMain(main, s::get);
            if (v.isEmpty() ? old == null : v.equals(s.view(old))) return "Already " + s.name + " = " + s.view(old);
            nn = s.card(old, v);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "Not changed: " + e.getMessage();
        }
        String no = approval(asker, String.format(cardFormat, s.name, nn[0], nn[1]));
        if (no != null) return "Not changed. " + no;
        try {
            s.prepare(v);
            return onMain(main, () -> {
                synchronized (s.lock()) {
                    String now = s.get();
                    if (!Objects.equals(now, old)) {
                        return "Not changed: " + s.name + " changed while the card was open (now: " + s.view(now)
                                + "). Ask again.";
                    }
                    return "Changed " + s.name + ": " + nn[0] + " → " + nn[1] + s.set(v);
                }
            });
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            return "Not changed: " + e.getMessage();
        }
    }

    private static <T> T onMain(Executor main, Callable<T> c) throws Exception {
        FutureTask<T> t = new FutureTask<>(c);
        main.execute(t);
        try {
            return t.get();
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
        }
    }

    // ------------------------------------------------------------ the tool

    private final BuddyService svc;

    SettingsTool(BuddyService svc) {
        this.svc = svc;
    }

    /** The tool's description, for the agent. */
    static void list(JSONArray t) throws org.json.JSONException {
        t.put(Tools.tool("buddy_settings",
                "Read and change Buddy's own settings: this is how to do it, not by tapping Buddy's screens. With no "
                        + "arguments, lists every setting with its value and what it accepts. With name, shows that one in full. "
                        + "With name and value, changes it. Buddy shows the user an approval card for each change itself, so "
                        + "don't call confirm first; if they deny it, nothing changes. value \"\" deletes an item (rule, "
                        + "schedule, skill, MCP server). Keys, tokens and server addresses are never shown or changed here.",
                Tools.props().put("name", Tools.strProp("Optional: the setting, as listed (e.g. read_aloud)"))
                        .put("value", Tools.strProp("Optional: the new value; needs name"))));
    }

    /** buddy_settings: what the agent is told. */
    String call(JSONObject a) throws Exception {
        String name = a.optString("name").trim();
        java.util.concurrent.Executor main = svc::runOnMain;
        if (name.isEmpty()) return onMain(main, this::listing);
        if (!a.has("value")) {
            return onMain(main, () -> {
                try {
                    Setting s = find(svc, name);
                    return s == null ? unknown(name) : s.read();
                } catch (IllegalArgumentException | IllegalStateException e) {
                    return e.getMessage();
                }
            });
        }
        Setting s = onMain(main, () -> find(svc, name));
        if (s == null) return unknown(name);
        return change(s, a.optString("value"), svc.getString(R.string.tools_card_setting),
                card -> svc.askConfirm(card, false), main);
    }

    private static String unknown(String name) {
        return "Unknown setting \"" + name + "\". Call buddy_settings with no arguments for the list.";
    }

    /** The setting with this name, or null (main thread). */
    static Setting find(BuddyService svc, String name) {
        for (Setting s : scalars(svc)) if (s.name.equals(name)) return s;
        if (name.startsWith("app/") && name.substring(4).matches("[A-Za-z0-9_.]+")) return app(svc, name.substring(4));
        if (name.startsWith("rule/")) {
            String id = name.substring(5);
            return id.matches("[A-Za-z0-9]+") ? rule(svc, id.equals("new") ? null : id) : null;
        }
        if (name.startsWith("skill/")) return skill(Skills.dir(svc), name.substring(6));
        if (name.startsWith("mcp/") && name.substring(4).matches("[a-z0-9_]+")) {
            String id = name.substring(4);
            McpServers.S s = McpServers.find(id);
            return mcp(svc, id, s, s != null && McpServers.header(svc, id) != null);
        }
        String[] p = name.split("/");
        if (p.length == 3 && p[0].equals("chat") && chat(svc, p[1]) != null && chat(svc, p[1]).parent.isEmpty()) {
            switch (p[2]) {
                case "linux_tools":
                    return chatSwitch(svc, p[1], p[2], c -> c.dev, (c, on) -> ChatHub.get(svc).setDev(c, on),
                            " It applies from the chat's next message.");
                case "background":
                    return chatSwitch(svc, p[1], p[2], c -> c.background, (c, on) -> ChatHub.get(svc).setBackground(c, on), "");
                case "schedule":
                    return schedule(svc, p[1]);
                default:
                    return null;
            }
        }
        return null;
    }

    private String listing() {
        StringBuilder b = new StringBuilder();
        for (Setting s : scalars(svc)) b.append(s.read()).append('\n');
        b.append("\napp/<package> = hard, smart, trust or default: how freely agents may work in one app. Apps with "
                + "your choice:\n");
        for (java.util.Map.Entry<String, String> e : Prefs.appSafetyAll(svc).entrySet()) {
            b.append("app/").append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
        }
        b.append("\nChats (newest 30). Each has chat/<id>/linux_tools (on, off), chat/<id>/background (on, off) and "
                + "chat/<id>/schedule (JSON):\n");
        String here = McpServer.CALLER_CHAT.get();
        int n = 0;
        for (Sessions.Session c : ChatHub.get(svc).sessions.all) {
            if (!c.parent.isEmpty() || n++ >= 30) continue;
            b.append("chat/").append(c.id).append(" \"").append(c.title).append("\"").append(c.id.equals(here) ? " (this chat)" : "")
                    .append(": linux_tools ").append(c.dev ? "on" : "off").append(", background ")
                    .append(c.background ? "on" : "off").append(", schedule ").append(c.schedule == null ? "none"
                            : c.schedule.paused ? "paused" : "set").append('\n');
        }
        b.append("\nNotification rules (rule/<id>; add one with rule/new):\n");
        for (Rules.Rule r : Rules.all(svc)) {
            b.append("rule/").append(r.id).append(" = ").append(r.on ? "on" : "off").append("  ").append(r.pkg).append(": ")
                    .append(r.instruction.length() > 60 ? r.instruction.substring(0, 60) + "…" : r.instruction)
                    .append("  (approval ").append(r.approval).append(")\n");
        }
        b.append("\nSkills (skill/<name>):\n").append(skillLines(Skills.dir(svc)));
        b.append("\nMCP servers (mcp/<id>; a new id with an address adds one):\n");
        for (McpServers.S m : McpServers.all()) b.append(mcpLine(m, McpServers.header(svc, m.id) != null)).append('\n');
        return b.toString().trim();
    }
}
