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
    private static final java.util.regex.Pattern WHITE = java.util.regex.Pattern.compile("\\s");
    private static final String SCHEDULED = "+scheduled";
    private static final java.util.regex.Pattern CONTROL = java.util.regex.Pattern.compile("\\p{Cntrl}");

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

        /** What the setting accepts, for the listing and the refusal text. */
        String allowed() {
            return allowed;
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

        /** One line: the value and what it accepts (the listing's form). */
        String line() {
            return name + " = " + view(get()) + (allowed().isEmpty() ? "" : "  (" + allowed() + ")");
        }

        /** The setting in full, for a read. */
        String read() {
            return line();
        }
    }

    /** A switch or a choice kept in Prefs. */
    static class Scalar extends Setting {
        private final Supplier<String> get;
        private final Consumer<String> set;
        private final Supplier<String[]> values; // read when checked or listed: a device list, a saved server...
        private final String note;
        /** What the screen greys out, checked after the value's shape: throws IllegalArgumentException saying who fixes it. */
        Consumer<String> ready = v -> { };

        Scalar(String name, Supplier<String> get, Consumer<String> set, String note, String... values) {
            this(name, get, set, note, () -> values);
        }

        Scalar(String name, Supplier<String> get, Consumer<String> set, String note, Supplier<String[]> values) {
            super(name, "");
            this.get = get;
            this.set = set;
            this.values = values;
            this.note = note;
        }

        Scalar ready(Consumer<String> r) {
            ready = r;
            return this;
        }

        @Override
        String allowed() {
            return String.join(", ", values.get());
        }

        @Override
        String get() {
            return get.get();
        }

        @Override
        String check(String value) {
            String v = value.trim();
            for (String c : values.get()) {
                if (!c.equalsIgnoreCase(v)) continue;
                ready.accept(c);
                return c; // stored in the choice's own spelling
            }
            throw new IllegalArgumentException(name + " takes: " + allowed());
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
        l.add(new Scalar("theme", () -> Prefs.themeMode(svc), v -> {
            Prefs.setThemeMode(svc, v);
            Theme.changed(svc); // the bubble restyles now
        }, " Screens pick it up when they next open.", themes));
        l.add(onOff("update_notices", () -> Prefs.updateNotices(svc), on -> {
            Prefs.setUpdateNotices(svc, on);
            if (!on) Updates.cancel(svc);
            else Prefs.setUpdateNotified(svc, ""); // notify again about a version muted before
        }));
        agentsModels(svc, l);
        voice(svc, l);
        safety(svc, l);
        l.add(new Scalar("background_browser", () -> Prefs.browser(svc) ? "on" : "off", v -> {
            Prefs.setBrowser(svc, v.equals("on"));
            if (v.equals("on")) Browser.install(svc); // the download, now rather than in the middle of a task
            else new Thread(Browser::stop).start();
        }, "", "on", "off") {
            @Override
            String[] card(String snapshot, String value) {
                return new String[]{view(snapshot), value.equals("on") && !Browser.installed(svc)
                        ? "on: Buddy downloads Chromium (about 300 MB) now" : value};
            }
        });
        appearance(svc, l);
        return l;
    }

    /** Settings → Agents and models. */
    private static void agentsModels(BuddyService svc, List<Setting> l) {
        l.add(new Scalar("default_agent", () -> Prefs.backend(svc), v -> Prefs.setBackend(svc, v),
                " New chats use it.", "claude", "codex", "opencode", "agy", "grok").ready(v -> signedIn(svc, v)));
        l.add(new Scalar("download_server", () -> Prefs.mirror(svc).isEmpty() ? "default" : Prefs.mirror(svc), v -> {
            Prefs.setMirror(svc, v.equals("default") ? "" : v);
            new Thread(() -> Mirrors.apply(svc.getApplicationContext())).start();
        }, " Buddy's Linux downloads from it from now on.", () -> mirrorIds(!Prefs.mirrorCustom(svc, false).isEmpty())));
    }

    /** The download servers to pick; custom (the user's own, saved in Settings; its address is never shown) once one is saved. */
    static String[] mirrorIds(boolean custom) {
        List<String> ids = new ArrayList<>();
        ids.add("default");
        for (Mirrors.M m : Mirrors.ALL) if (!m.id.isEmpty()) ids.add(m.id);
        if (custom) ids.add(Mirrors.CUSTOM);
        return ids.toArray(new String[0]);
    }

    /** Settings → Appearance (the theme is among the first rows). */
    private static void appearance(BuddyService svc, List<Setting> l) {
        l.add(new Scalar("accent", () -> Prefs.accentFromWallpaper(svc) ? "wallpaper" : accentName(Prefs.accent(svc)), v -> {
            if (v.equals("wallpaper")) Prefs.setAccentFromWallpaper(svc);
            else Prefs.setAccent(svc, 0xFF000000 | Integer.parseInt(accentHex(v).substring(1), 16));
            Theme.changed(svc);
        }, " The bubble restyles at once.", () -> accentChoices()) {
            @Override
            String allowed() {
                return super.allowed() + ", or #RRGGBB (wallpaper needs Android 12+)";
            }

            @Override
            String check(String value) {
                String v = accent(value);
                if (v.equals("wallpaper") && android.os.Build.VERSION.SDK_INT < 31) {
                    throw new IllegalArgumentException("the wallpaper colour needs Android 12 or newer");
                }
                return v;
            }
        });
        String[] surfaces = new String[Theme.SURFACES.length];
        for (int i = 0; i < surfaces.length; i++) surfaces[i] = Theme.SURFACES[i][0];
        l.add(new Scalar("surface", () -> Prefs.surface(svc), v -> {
            Prefs.setSurface(svc, v);
            Theme.changed(svc);
        }, " Black applies in dark mode only.", surfaces));
        l.add(new Scalar("face", () -> Face.picture(svc) != null ? "picture" : "buddy", v -> {
            Prefs.setFace(svc, v);
            if (v.equals("picture") && Face.picture(svc) == null) { // as the screen: back to Buddy's own
                Prefs.setFace(svc, "buddy");
                throw new IllegalStateException("the saved picture can't be read");
            }
            svc.applyFace();
        }, "", "buddy", "picture").ready(v -> {
            if (v.equals("picture") && !Face.saved(svc)) {
                throw new IllegalArgumentException("no picture is saved; only the user picks one (Settings → Appearance)");
            }
        }));
    }

    private static String[] accentChoices() {
        List<String> c = new ArrayList<>();
        for (String[] a : Theme.ACCENTS) c.add(a[1].toLowerCase(Locale.ROOT));
        c.add("wallpaper");
        return c.toArray(new String[0]);
    }

    /** The accent as a preset's name (any case), wallpaper, or six hex digits (a preset's colour becomes its name). */
    static String accent(String value) {
        String v = value.trim();
        if (v.equalsIgnoreCase("wallpaper")) return "wallpaper";
        for (String[] a : Theme.ACCENTS) if (a[1].equalsIgnoreCase(v)) return a[1].toLowerCase(Locale.ROOT);
        if (!v.matches("#?[0-9A-Fa-f]{6}")) {
            throw new IllegalArgumentException("accent takes: " + String.join(", ", accentChoices()) + ", or #RRGGBB");
        }
        return accentName(Integer.parseInt(v.replace("#", ""), 16));
    }

    /** A colour's preset name, else #RRGGBB. */
    static String accentName(int color) {
        String hex = String.format("#%06X", color & 0xFFFFFF);
        for (String[] a : Theme.ACCENTS) if (a[0].equals(hex)) return a[1].toLowerCase(Locale.ROOT);
        return hex;
    }

    /** #RRGGBB for a preset's name or a hex value. */
    private static String accentHex(String v) {
        for (String[] a : Theme.ACCENTS) if (a[1].equalsIgnoreCase(v)) return a[0];
        return v;
    }

    /** Settings → Safety and approvals. */
    private static void safety(BuddyService svc, List<Setting> l) {
        l.add(new Scalar("reviewer", () -> Prefs.reviewer(svc), v -> Prefs.setReviewer(svc, v), "", "same", "claude", "codex")
                .ready(v -> {
                    if (!v.equals("same")) signedIn(svc, v);
                }));
        l.add(new Scalar("builtin_risky_words", () -> Prefs.guardBuiltin(svc) ? "on" : "off",
                v -> Prefs.setGuardBuiltin(svc, v.equals("on")), "", "on", "off") {
            @Override
            String[] card(String snapshot, String value) {
                return new String[]{view(snapshot), value.equals("off") ? "off: Send, Pay, Delete… no longer always ask" : value};
            }
        });
    }

    /** Settings → Voice. */
    private static void voice(BuddyService svc, List<Setting> l) {
        l.add(new Scalar("voice_input", () -> Prefs.voiceEngine(svc), v -> Prefs.setVoiceEngine(svc, v), "",
                "phone", "claude", "chatgpt").ready(v -> plan(svc, v.equals("claude") ? "claude" : v.equals("chatgpt") ? "codex" : null)));
        l.add(new Scalar("voice_output", () -> Prefs.speechEngine(svc), v -> Prefs.setSpeechEngine(svc, v), "",
                "phone", "chatgpt").ready(v -> plan(svc, v.equals("chatgpt") ? "codex" : null)));
        l.add(new Scalar("chatgpt_voice", () -> Prefs.speechVoice(svc), v -> {
            Prefs.setSpeechVoice(svc, v);
            Voice.stopSpeaking(); // the next speech uses it
        }, "", () -> {
            List<String> ids = new ArrayList<>();
            for (String[] v : CodexSpeech.VOICES) ids.add(v[0]);
            return ids.toArray(new String[0]);
        }) {
            @Override
            String allowed() {
                return super.allowed() + "; used while voice_output = chatgpt";
            }
        });
        l.add(new Scalar("audio_device", () -> Prefs.audioDevice(svc).isEmpty() ? "auto" : Prefs.audioDevice(svc), v -> {
            Prefs.setAudioDevice(svc, v.equals("auto") ? "" : v);
            Voice.stopSpeaking();
        }, "", () -> {
            List<String> ids = new ArrayList<>();
            ids.add("auto");
            String[][] devices = AudioRoute.choices(svc); // Android 12+ lists the devices connected now
            for (int i = 1; i < devices.length; i++) ids.add(devices[i][0]);
            return ids.toArray(new String[0]);
        }));
        l.add(voiceLanguages(() -> {
            List<String> all = new ArrayList<>(Arrays.asList(Prefs.voiceLangs(svc).split(",")));
            all.remove(Prefs.voiceLang(svc));
            all.add(0, Prefs.voiceLang(svc));
            return String.join(",", all); // the one Buddy listens for first
        }, v -> {
            Prefs.setVoiceLangs(svc, v);
            Prefs.setVoiceLang(svc, v.split(",")[0]);
        }));
        l.add(new Scalar("assist_mode", () -> Prefs.assistMode(svc), v -> Prefs.setAssistMode(svc, v), "", "talk", "live"));
        l.add(new Scalar("live_agent", () -> Prefs.liveAgent(svc).isEmpty() ? "default" : Prefs.liveAgent(svc),
                v -> Prefs.setLiveAgent(svc, v.equals("default") ? "" : v), "", "default", "claude", "codex", "opencode", "agy",
                "grok").ready(v -> {
            if (!v.equals("default")) signedIn(svc, v);
        }));
        l.add(onOff("live_continue", () -> Prefs.liveContinue(svc), on -> Prefs.setLiveContinue(svc, on)));
        // the screen saves it too, then sends the user to grant notification access
        l.add(new Scalar("pause_media", () -> Prefs.pauseMediaForSpeech(svc) ? "on" : "off",
                v -> Prefs.setPauseMediaForSpeech(svc, v.equals("on")), "", "on", "off") {
            @Override
            String set(String value) {
                super.set(value);
                return !value.equals("on") ? "" : (NotificationWatcher.enabled(svc) ? ""
                        : " It can't work yet: notification access is off, and only the user grants it.")
                        + (Prefs.speakReplies(svc) ? "" : " It only acts while read_aloud is on.");
            }
        });
    }

    static Scalar voiceLanguages(Supplier<String> get, Consumer<String> set) {
        return new Scalar("voice_languages", get, set, "", () -> {
            List<String> tags = new ArrayList<>();
            for (String[] t : Voice.LANGUAGES) tags.add(t[0]);
            return tags.toArray(new String[0]);
        }) {
            @Override
            String allowed() {
                return "comma-separated, at least one, from " + super.allowed() + "; the first is the one Buddy listens for";
            }

            @Override
            String check(String value) {
                return languages(value);
            }
        };
    }

    /** The comma-separated languages as stored: listed tags (any case), no duplicates, at least one. */
    static String languages(String csv) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String t : csv.split(",")) {
            String want = t.trim(), got = null;
            if (want.isEmpty()) continue;
            for (String[] l : Voice.LANGUAGES) if (l[0].equalsIgnoreCase(want)) got = l[0];
            if (got == null) throw new IllegalArgumentException("unknown language \"" + want + "\"; voice_languages takes tags like en-US, fr-FR");
            out.add(got);
        }
        if (out.isEmpty()) throw new IllegalArgumentException("voice_languages needs at least one language");
        return String.join(",", out);
    }

    /** Voice input/output that uses an agent's plan: it must be signed in with it (agent null: no need). */
    private static void plan(BuddyService svc, String agent) {
        if (agent != null && !SetupState.planSignedIn(svc, agent)) {
            throw new IllegalArgumentException(Account.nameOf(agent) + " isn't signed in with its plan; only the user signs in (Settings → Agents and models)");
        }
    }

    /** An agent that isn't signed in is greyed out on the screens: say who fixes it. */
    private static void signedIn(BuddyService svc, String agent) {
        if (!SetupState.signedIn(svc, agent)) {
            throw new IllegalArgumentException(Account.nameOf(agent) + " isn't signed in; only the user signs in (Settings → Agents and models)");
        }
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

    /**
     * An agent's (or the reviewer's) model: default, a model the picker lists, or a typed name (one word, no @ unless
     * listed). options: the picker's rows {id, name}, with {null, heading} for a service; services: may it offer @service/model.
     */
    static Scalar model(String name, Supplier<List<String[]>> options, boolean services, Supplier<String> get,
                        Consumer<String> set, String note) {
        return new Scalar(name, () -> get.get().isEmpty() ? "default" : get.get(),
                v -> set.accept(v.equals("default") ? "" : v), note, "default") {
            @Override
            String allowed() {
                return "default, a model from the list (read the item), or a model name";
            }

            @Override
            String check(String value) {
                String v = value.trim();
                if (v.equalsIgnoreCase("default")) v = "default";
                else if (v.isEmpty() || v.length() > 200 || WHITE.matcher(v).find()) {
                    throw new IllegalArgumentException(name + " takes: default, a listed model, or a model name (one word, up to 200 characters)");
                } else if (v.startsWith("@") && !services) {
                    throw new IllegalArgumentException(name + " takes no @service/model");
                } else {
                    String listed = null;
                    for (String[] o : options.get()) if (o[0] != null && o[0].equalsIgnoreCase(v)) listed = o[0];
                    if (listed != null) v = listed;
                    else if (v.startsWith("@")) throw new IllegalArgumentException(v + " isn't a model in the list; read " + name);
                }
                ready.accept(v);
                return v;
            }

            @Override
            String read() {
                StringBuilder b = new StringBuilder(line());
                for (String[] o : options.get()) {
                    b.append(o[0] == null ? "\n" + o[1] + ":" : "\n  " + (o[0].isEmpty() ? "default" : o[0]) + " — " + o[1]);
                }
                return b.toString();
            }
        };
    }

    /** button/&lt;package or *&gt;/&lt;label&gt; → {package or null for every app, label}, or null when it isn't one. */
    static String[] buttonName(String name) {
        if (!name.startsWith("button/")) return null;
        String rest = name.substring(7);
        int i = rest.indexOf('/');
        if (i <= 0) return null;
        String scope = rest.substring(0, i), label = rest.substring(i + 1).trim();
        if (label.isEmpty() || !scope.equals("*") && !scope.matches("[A-Za-z0-9_.]+")) return null;
        return new String[]{scope.equals("*") ? null : scope, label};
    }

    private static String buttonText(String state, String label, String where) {
        return state == null ? "on neither list" : state.equals("ask") ? "ask: always ask before an agent taps “" + label + "” in " + where
                : "allow: never ask; agents tap “" + label + "” without a card in " + where;
    }

    /**
     * A button on the always-ask or never-ask list (Settings → Safety → Button rules): ask, allow, or "" for neither.
     * get is the list holding it (null: neither). app: the name to show for pkg; installed: is the app on the phone.
     */
    static Scalar button(String pkg, String label, String app, boolean installed, Supplier<String> get, Consumer<String> set) {
        String where = pkg == null ? "every app" : app;
        return new Scalar("button/" + (pkg == null ? "*" : pkg) + "/" + label, get, set, "", "ask", "allow", "") {
            @Override
            String allowed() {
                return "ask (always ask), allow (never ask), or \"\" to take it off both lists";
            }

            @Override
            String check(String value) {
                String v = value.trim().toLowerCase(Locale.ROOT);
                if (!v.isEmpty() && !v.equals("ask") && !v.equals("allow")) throw new IllegalArgumentException(name + " takes: " + allowed());
                // a rule already on a list can be moved or removed whether or not its app is still there (the screen's chips do)
                if (!v.isEmpty() && pkg != null && !installed && get() == null) {
                    throw new IllegalArgumentException("no installed app " + pkg + ": a new rule for one app needs it installed");
                }
                return v;
            }

            @Override
            String[] card(String snapshot, String value) {
                return new String[]{buttonText(snapshot, label, where), buttonText(value.isEmpty() ? null : value, label, where)};
            }
        };
    }

    /** Writes a changed API key service (in production Providers.setOff / Providers.save with the saved key kept). */
    interface Store {
        void save(Providers.P p) throws Exception;
    }

    /**
     * An API key service's use (part ""), name or shown models, looked up live by id each time: a removed service has
     * no snapshot and is never written back. The snapshot holds the addresses, so it never leaves the row: view shows the part.
     */
    static Setting service(String id, String part, java.util.function.Function<String, Providers.P> find, Store store) {
        return new Setting("service/" + id + (part.isEmpty() ? "" : "/" + part), part.isEmpty() ? "on, off"
                : part.equals("name") ? "the service's name, one line" : "all, or model ids from the service's list, comma-separated") {
            @Override
            String get() {
                Providers.P p = find.apply(id);
                return p == null ? null : new JSONArray().put(p.name).put(p.anthropic).put(p.openai).put(p.off)
                        .put(String.join(",", p.shown)).toString();
            }

            @Override
            String view(String snapshot) {
                if (snapshot == null) return "(none)";
                JSONArray f;
                try {
                    f = new JSONArray(snapshot);
                } catch (org.json.JSONException e) {
                    throw new IllegalStateException(e);
                }
                return part.isEmpty() ? f.optBoolean(3) ? "off" : "on" : part.equals("name") ? f.optString(0)
                        : f.optString(4).isEmpty() ? "all" : f.optString(4);
            }

            @Override
            String check(String value) {
                String v = value.trim();
                if (part.isEmpty()) {
                    if (!v.equalsIgnoreCase("on") && !v.equalsIgnoreCase("off")) throw new IllegalArgumentException(name + " takes: on, off");
                    v = v.toLowerCase(Locale.ROOT);
                } else if (part.equals("name") && (v.isEmpty() || CONTROL.matcher(v).find())) {
                    throw new IllegalArgumentException(name + " takes a name: one line, not blank");
                }
                Providers.P p = find.apply(id);
                if (p == null) throw new IllegalArgumentException("no such service " + id);
                if (!part.equals("models")) return v;
                if (v.equalsIgnoreCase("all")) return "all";
                java.util.Set<String> ids = new java.util.LinkedHashSet<>();
                for (String m : v.split(",")) {
                    String want = m.trim(), got = null;
                    for (int i = 0; i < p.models.length(); i++) {
                        String have = p.models.optJSONObject(i) == null ? "" : p.models.optJSONObject(i).optString("id");
                        if (!want.isEmpty() && have.equalsIgnoreCase(want)) got = have;
                    }
                    if (got == null) throw new IllegalArgumentException("service " + id + " has no model \"" + want + "\"; read " + name);
                    ids.add(got);
                }
                return String.join(",", ids);
            }

            @Override
            Object lock() {
                return Providers.class; // every Providers writer holds it
            }

            @Override
            String set(String value) throws Exception {
                Providers.P p = find.apply(id);
                if (p == null) throw new IllegalStateException("service " + id + " was removed");
                if (part.isEmpty()) {
                    p.off = value.equals("off");
                } else if (part.equals("name")) {
                    p.name = value;
                } else {
                    p.shown.clear();
                    if (!value.equals("all")) p.shown.addAll(Arrays.asList(value.split(",")));
                }
                store.save(p);
                return "";
            }

            @Override
            String read() {
                Providers.P p = find.apply(id);
                if (p == null || !part.equals("models")) return line();
                StringBuilder b = new StringBuilder(line() + "\nthe service lists:");
                for (int i = 0; i < p.models.length(); i++) {
                    b.append("\n  ").append(p.models.optJSONObject(i) == null ? "" : p.models.optJSONObject(i).optString("id"));
                }
                return b.toString();
            }
        };
    }

    /** "service/deepseek = on  (DeepSeek; models shown: all of 12)": never an address or key. */
    static String serviceLine(Providers.P p) {
        return "service/" + p.id + " = " + (p.off ? "off" : "on") + "  (" + p.name + "; models shown: "
                + (p.shown.isEmpty() ? "all of " : p.shown.size() + " of ") + p.models.length() + ")";
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

    /**
     * An app the user allows in a chat without asking (the chat's "Allowed without asking"): on while the chat's task
     * runs, and for a scheduled chat in its scheduled runs too. app: the name to show; refusal: why "on" is refused (null: it can be allowed).
     */
    static Scalar allowedApp(String chat, String pkg, String app, String refusal, BooleanSupplier scheduled, Supplier<String> get,
                             Consumer<String> set) {
        // the scope is in the snapshot, so a schedule added while the card is open makes the write stale
        return new Scalar("chat/" + chat + "/allowed/" + pkg, () -> {
            String s = get.get();
            return s == null ? null : s + (scheduled.getAsBoolean() ? SCHEDULED : "");
        }, set, "", "on", "off") {
            @Override
            String view(String snapshot) {
                return snapshot == null ? "(none)" : snapshot.replace(SCHEDULED, "");
            }

            @Override
            String check(String value) {
                String v = super.check(value);
                if (v.equals("on") && refusal != null) throw new IllegalArgumentException(refusal);
                return v;
            }

            @Override
            String[] card(String snapshot, String value) {
                return new String[]{view(snapshot), value.equals("on") ? "on: routine approvals in " + app
                        + " go ahead without asking until this chat's task ends" + (snapshot.endsWith(SCHEDULED) ? " and in its scheduled runs" : "")
                        : value};
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

    // ------------------------------------------------------------ keys (read from a file, never shown)

    private static final int KEY_MAX = 4096;

    /**
     * The key held in a file of Buddy's Linux (root = Linux.root; inside, "/" is root and "~" is /root). The file must be
     * a regular file whose real path stays inside root, one line, no JSON. No message repeats the path or the content,
     * in case the agent passed the key itself as the path.
     */
    static String keyFrom(File root, String path) {
        String p = path.trim();
        if (p.isEmpty() || p.length() > 1000 || CONTROL.matcher(p).find() || !(p.startsWith("/") || p.startsWith("~/"))) {
            throw new IllegalArgumentException("key_file must be a path in Buddy's Linux starting with / or ~/");
        }
        String bad = "key_file: the file must hold just the key, one line (for a Bitwarden secret, its value)";
        try {
            java.nio.file.Path real = new File(root, p.startsWith("~") ? "/root" + p.substring(1) : p).toPath().toRealPath();
            if (!real.startsWith(root.toPath().toRealPath())
                    || !java.nio.file.Files.isRegularFile(real, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    || java.nio.file.Files.size(real) > KEY_MAX) {
                throw new IllegalArgumentException(bad);
            }
            java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(KEY_MAX);
            try (java.nio.channels.SeekableByteChannel ch = java.nio.file.Files.newByteChannel(real,
                    java.nio.file.StandardOpenOption.READ, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                while (b.hasRemaining() && ch.read(b) > 0) { // read to the end (a short read is not the end)
                }
            }
            String key = new String(b.array(), 0, b.position(), java.nio.charset.StandardCharsets.UTF_8).trim();
            if (key.isEmpty() || CONTROL.matcher(key).find() || key.startsWith("{") || key.startsWith("[")) {
                throw new IllegalArgumentException(bad);
            }
            return key;
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException(bad);
        }
    }

    /** The secret part of a key: what a header line "Name: value" or "Bearer value" carries (the relay's own grammar). */
    static String credential(String key) {
        String c = McpServers.header(key)[1];
        return c.regionMatches(true, 0, "Bearer ", 0, 7) ? c.substring(7) : c;
    }

    /** At most the credential's last 4 characters (only when it has 16 or more) and its length: "…ab12 (40 characters)". */
    static String mask(String key) {
        String c = credential(key);
        return (c.length() >= 16 ? "…" + c.substring(c.length() - 4) + " " : "") + "(" + c.length() + " characters)";
    }

    /** The text without the key: the whole key, its header value and its credential, longest first. */
    static String redact(String text, String key) {
        String[] parts = {key.trim(), McpServers.header(key)[1], credential(key)};
        Arrays.sort(parts, (x, y) -> y.length() - x.length());
        for (String s : parts) text = text.replace(s, "…");
        return text;
    }

    /** SHA-256 in hex, "" for no key: a snapshot compares keys without holding one. */
    static String digest(String key) {
        if (key == null || key.isEmpty()) return "";
        try {
            StringBuilder b = new StringBuilder();
            for (byte x : java.security.MessageDigest.getInstance("SHA-256").digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                b.append(String.format("%02x", x));
            }
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** May this caller read Buddy's Linux? No chat (a terminal or Remote Control session there), or a chat with Linux tools on. */
    static boolean linuxCaller(String here, Sessions.Session c) {
        return here == null || c != null && c.dev;
    }

    /**
     * A saved key of an MCP server or an API key service: set from a key file, or removed with value "". The snapshot
     * holds the record's address and a digest of its key, so the key never leaves {@link #saved}, and an address or key
     * edited while the card is open makes the write stale. The key is checked with the server after the card
     * ({@link #verify}), only at the address the card was built from.
     */
    abstract static class KeySetting extends Setting {
        private final File root;
        private final String file, gone; // file: null removes the key; gone: what to say when the record doesn't exist
        private String key, shown;       // shown: the record's address(es) as the card saw them

        KeySetting(String name, String gone, File root, String file) {
            super(name, "a key from key_file, or \"\" to remove it; never shown");
            this.gone = gone;
            this.root = root;
            this.file = file;
        }

        /** The address(es) the key goes to, as a JSONArray string; null when the record is gone. Never shown. */
        abstract String target();

        /** The saved key, or null. */
        abstract String saved();

        /** Checks the key with the server at target (off the main thread) and keeps what it returns for {@link #store}. */
        void verify(JSONArray target, String key) throws Exception {
        }

        /** Saves the key ("" removes it); returns a note. Main thread, under {@link #lock}. */
        abstract String store(String key) throws Exception;

        @Override
        String get() {
            String t = target();
            return t == null ? null : new JSONArray().put(t).put(digest(saved())).toString();
        }

        @Override
        String view(String snapshot) {
            if (snapshot == null) return "(none)";
            try {
                return new JSONArray(snapshot).getString(1).isEmpty() ? "none" : "set";
            } catch (org.json.JSONException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        String check(String value) {
            if (!value.trim().isEmpty()) throw new IllegalArgumentException(name + " takes its key from key_file, never in value; value \"\" removes it");
            if (target() == null) throw new IllegalArgumentException(gone);
            if (file == null) {
                if (saved() == null) throw new IllegalArgumentException(name + ": there is no key to remove");
                return "";
            }
            key = keyFrom(root, file);
            return mask(key) + ", from " + file.trim();
        }

        @Override
        String[] card(String snapshot, String value) {
            if (snapshot != null) {
                try {
                    shown = new JSONArray(snapshot).getString(0);
                } catch (org.json.JSONException e) {
                    throw new IllegalStateException(e);
                }
            }
            return new String[]{view(snapshot), value.isEmpty() ? "none (the key is removed)" : value};
        }

        @Override
        void prepare(String value) throws Exception {
            if (value.isEmpty()) return;
            try {
                verify(new JSONArray(shown), key);
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) { // its words can quote the stored address, which no output shows
                throw new Exception(name + ": the server refused the key or couldn't be reached (its own words aren't shown: "
                        + "they can carry its address)");
            }
        }

        @Override
        String set(String value) throws Exception {
            return store(value.isEmpty() ? "" : key);
        }
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
                        + "schedule, skill, MCP server) or takes a button off the ask/never-ask lists. Covers every Settings "
                        + "page: agents and models (default agent and models, API key services' use, name and shown models, "
                        + "download server), voice, safety (reviewer, button rules, per-app modes), browser, appearance, and a "
                        + "chat's allowed apps. Keys, tokens and server addresses are never shown or changed here.",
                Tools.props().put("name", Tools.strProp("Optional: the setting, as listed (e.g. read_aloud)"))
                        .put("value", Tools.strProp("Optional: the new value; needs name"))));
    }

    /** buddy_settings: what the agent is told. */
    String call(JSONObject a) throws Exception {
        String name = a.optString("name").trim();
        java.util.concurrent.Executor main = svc::runOnMain;
        String here = McpServer.CALLER_CHAT.get(); // a request-thread ThreadLocal: read it before going to the main thread
        if (name.isEmpty()) return onMain(main, () -> listing(here));
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
        if (name.startsWith("default_model/") && Account.known(name.substring(14))) {
            String agent = name.substring(14);
            return model(name, () -> Models.options(agent, true), true, () -> Prefs.agentDefault(svc, agent), v -> {
                Prefs.setAgentDefault(svc, agent, v);
                ChatHub.get(svc).changed();
            }, " New chats on Default use it.").ready(v -> {
                if (!SetupState.signedIn(svc, agent) && Providers.forAgent(agent).isEmpty()) {
                    throw new IllegalArgumentException(Account.nameOf(agent) + " isn't on the Chats on Default screens: it isn't "
                            + "signed in and has no API key service; only the user does that");
                }
            });
        }
        if (name.equals("reviewer_model/claude") || name.equals("reviewer_model/codex")) {
            String h = name.substring(15);
            return model(name, () -> Models.options(h, false), false, () -> Prefs.reviewerModel(svc, h),
                    v -> Prefs.setReviewerModel(svc, h, v), " The reviewer uses it from its next review.").ready(v -> signedIn(svc, h));
        }
        if (name.matches("service/[A-Za-z0-9_-]+(/(name|models))?")) {
            String[] sp = name.substring(8).split("/");
            Store store = sp.length == 1 ? q -> Providers.setOff(svc, q, q.off) : q -> Providers.save(svc, q, null);
            return service(sp[0], sp.length == 1 ? "" : sp[1], Providers::find, store);
        }
        String[] bn = buttonName(name);
        if (bn != null) return buttonRule(svc, bn[0], bn[1]);
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
        if (p.length == 4 && p[0].equals("chat") && p[2].equals("allowed") && p[3].matches("[A-Za-z0-9_.]+") && chat(svc, p[1]) != null
                && chat(svc, p[1]).parent.isEmpty()) {
            String id = p[1], pkg = p[3];
            String label = null;
            try {
                label = String.valueOf(svc.getPackageManager().getApplicationInfo(pkg, 0).loadLabel(svc.getPackageManager()));
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                // not installed: an app can still be taken off the list
            }
            // what the screen's picker offers: not a money, Settings, installer or store app
            String refusal = label == null ? "no installed app " + pkg
                    : Guard.allowable(svc, pkg) ? null : label + " can't be allowed without asking (money, Settings, installer and store apps never can)";
            return allowedApp(id, pkg, label == null ? pkg : label, refusal, () -> chat(svc, id) != null && chat(svc, id).schedule != null, () -> {
                Sessions.Session c = chat(svc, id);
                return c == null ? null : c.allowed.contains(pkg) ? "on" : "off";
            }, v -> {
                Sessions.Session c = chat(svc, id);
                if (c == null) throw new IllegalStateException("that chat is gone");
                ChatHub.get(svc).setAllowed(c, pkg, v.equals("on"));
            });
        }
        return null;
    }

    /** Which list holds the rule (ask or allow), or null; a rule is matched by its normalized label and package. */
    private static String ruleList(BuddyService svc, String pkg, String label) {
        for (String list : new String[]{"ask", "allow"}) {
            for (Guard.Rule r : Guard.rules(svc, list)) {
                if (Guard.norm(r.label).equals(Guard.norm(label)) && Objects.equals(r.pkg, pkg)) return list;
            }
        }
        return null;
    }

    private static Setting buttonRule(BuddyService svc, String pkg, String label) {
        String stored = null, installedLabel = null;
        for (String list : new String[]{"ask", "allow"}) {
            for (Guard.Rule r : Guard.rules(svc, list)) {
                if (Guard.norm(r.label).equals(Guard.norm(label)) && Objects.equals(r.pkg, pkg) && r.app != null && !r.app.isEmpty()) stored = r.app;
            }
        }
        if (pkg != null) {
            try {
                installedLabel = String.valueOf(svc.getPackageManager().getApplicationInfo(pkg, 0).loadLabel(svc.getPackageManager()));
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                // gone: the stored name, else the package
            }
        }
        String app = installedLabel != null ? installedLabel : stored != null ? stored : pkg;
        return button(pkg, label, app, installedLabel != null, () -> ruleList(svc, pkg, label), v -> {
            if (v.isEmpty()) {
                for (String list : new String[]{"ask", "allow"}) {
                    List<Guard.Rule> rs = Guard.rules(svc, list);
                    if (rs.removeIf(r -> Guard.norm(r.label).equals(Guard.norm(label)) && Objects.equals(r.pkg, pkg))) {
                        Guard.saveRules(svc, list, rs);
                    }
                }
            } else {
                Guard.addRule(svc, v, new Guard.Rule(label, pkg, pkg == null ? null : app));
            }
        });
    }

    /** The chats section of the listing; here = the calling agent's chat id (or null) gets "(this chat)". */
    static void chatLines(StringBuilder b, java.util.List<Sessions.Session> all, String here) {
        int n = 0;
        for (Sessions.Session c : all) {
            if (!c.parent.isEmpty() || n++ >= 30) continue;
            b.append("chat/").append(c.id).append(" \"").append(c.title).append("\"").append(c.id.equals(here) ? " (this chat)" : "")
                    .append(": linux_tools ").append(c.dev ? "on" : "off").append(", background ")
                    .append(c.background ? "on" : "off").append(", schedule ").append(c.schedule == null ? "none"
                            : c.schedule.paused ? "paused" : "set");
            synchronized (c.allowed) {
                b.append(", allowed: ").append(c.allowed.isEmpty() ? "none" : String.join(",", c.allowed));
            }
            b.append('\n');
        }
    }

    private String listing(String here) {
        StringBuilder b = new StringBuilder();
        for (Setting s : scalars(svc)) b.append(s.read()).append('\n');
        b.append("\nSmart reviewer's models (the quick one is default):\n");
        for (String h : new String[]{"claude", "codex"}) {
            if (SetupState.signedIn(svc, h)) b.append(find(svc, "reviewer_model/" + h).line()).append('\n');
        }
        b.append("\nButton lists (button/<package or *>/<label> = ask: always ask, allow: never ask; \"\" takes one off; "
                + "* is every app):\n");
        for (String list : new String[]{"ask", "allow"}) {
            for (Guard.Rule r : Guard.rules(svc, list)) {
                b.append("button/").append(r.pkg == null ? "*" : r.pkg).append('/').append(r.label).append(" = ").append(list).append('\n');
            }
        }
        b.append("\nDefault models (read one to see its choices):\n");
        for (Account a : Account.ALL) {
            if (SetupState.signedIn(svc, a.agent) || !Providers.forAgent(a.agent).isEmpty()) {
                b.append(find(svc, "default_model/" + a.agent).line()).append('\n');
            }
        }
        b.append("\nAPI key services (service/<id> = on, off; each also has service/<id>/name and service/<id>/models; "
                + "keys and addresses are never shown):\n");
        synchronized (Providers.class) {
            for (Providers.P p : Providers.all()) b.append(serviceLine(p)).append('\n');
        }
        b.append("\napp/<package> = hard, smart, trust or default: how freely agents may work in one app. Apps with "
                + "your choice:\n");
        for (java.util.Map.Entry<String, String> e : Prefs.appSafetyAll(svc).entrySet()) {
            b.append("app/").append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
        }
        b.append("\nChats (newest 30). Each has chat/<id>/linux_tools (on, off), chat/<id>/background (on, off), "
                + "chat/<id>/schedule (JSON) and chat/<id>/allowed/<package> (on, off: apps allowed without asking):\n");
        chatLines(b, ChatHub.get(svc).sessions.all, here);
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
