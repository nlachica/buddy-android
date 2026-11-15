package app.buddy.assistant;

import org.json.JSONArray;
import org.json.JSONObject;

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
        String v;
        try {
            v = s.check(value);
        } catch (IllegalArgumentException e) {
            return "Not changed: " + e.getMessage();
        }
        String old = onMain(main, s::get);
        if (v.isEmpty() ? old == null : v.equals(s.view(old))) return "Already " + s.name + " = " + s.view(old);
        String[] nn = s.card(old, v);
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
        } catch (IllegalArgumentException | IllegalStateException e) {
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
                Setting s = find(svc, name);
                return s == null ? unknown(name) : s.read();
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
        return b.toString().trim();
    }
}
