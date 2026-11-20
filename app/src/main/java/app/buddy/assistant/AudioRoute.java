package app.buddy.assistant;

import android.annotation.TargetApi;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

/**
 * Which audio device Buddy's voice uses: Auto or one of the devices Android offers for calls (speaker, earpiece, a
 * Bluetooth watch or headset). Auto is the order phones use: a connected media headset (Android's own routing, the
 * latest one), then the Galaxy Watch for read aloud, then the phone speaker (docs/features/016-audio-auto-priority).
 * 031: while you dictate, Auto uses a connected Bluetooth headset's call link, so its microphone is the input. While Buddy's voice is on and a device is chosen, Buddy runs as a
 * call: that device becomes Android's communication device and the phone is in communication mode, which also moves
 * media (read aloud) and the recognizers' input along. A voice session is that time plus a quiet tail; a poll ends it
 * and puts everything back, so no end of voice needs a hook. Android 12+ (setCommunicationDevice); below, only Auto.
 * Watch (github build) is not an Android device: for routing it is Auto, and read aloud goes to the watch (WatchVoice).
 * A phone live call (017, 030, Android 12+) never uses the earpiece, whatever the choice: the chosen device while it is
 * connected (not the earpiece), else a headset's call device, else the speaker.
 * A picked device that is away is Auto until it is back.
 * All state lives on the main thread.
 */
final class AudioRoute {
    private static final long POLL_MS = 300, QUIET_MS = 1500, NEVER_STARTED_MS = 10_000, CONFIRM_MS = 2000, SETUP_MS = 30_000, // Android documents up to 30 s for a Bluetooth call device
            EARPIECE_MS = 5000; // 031: LS2's call link took about 2.7 s, all of it with the earpiece reported
    private static final Handler main = new Handler(Looper.getMainLooper());

    private static Context app;
    private static boolean on, other, ownMode, dirty, sawLive, heard, stuck, seen, speakerSlipped;
    private static String applied, refused;
    private static long began, quietSince, appliedAt;
    /** 017: devices a phone live call couldn't use; kept until use() or until the device leaves the list (030). */
    private static final Set<String> liveRefused = new HashSet<>();

    private AudioRoute() {
    }

    /** {key, label} pairs: Auto first (key ""), then the devices Android offers for calls now, then Watch if this build has it. */
    static String[][] choices(Context c) {
        ArrayList<String[]> l = new ArrayList<>();
        l.add(new String[]{"", c.getString(R.string.audio_auto)});
        if (Build.VERSION.SDK_INT >= 31) {
            for (AudioDeviceInfo d : c.getSystemService(AudioManager.class).getAvailableCommunicationDevices()) {
                l.add(new String[]{key(d), label(c, d)});
            }
        }
        if (WatchVoice.link() != null) l.add(new String[]{WatchVoice.KEY, c.getString(R.string.audio_watch)});
        return l.toArray(new String[0][]);
    }

    /** Media outputs Android plays on before the phone itself. Not BLUETOOTH_SCO: a call-only link gets no media. */
    static boolean headset(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_HEARING_AID:
            case 26: // TYPE_BLE_HEADSET (API 31)
            case 27: // TYPE_BLE_SPEAKER (API 31)
                return true;
            default:
                return false;
        }
    }

    static final int NONE = -1, UNNAMED = -2;

    /**
     * The headset Android plays Buddy's voice on, from output types. routed: Android's own answer for Buddy's voice
     * (API 33+), null or empty when there is none; it wins, even when it names only the speaker. Without it, any
     * connected headset counts, but which one Android uses can't be seen. Returns the index in routed, UNNAMED, or
     * NONE (the phone).
     */
    static int headsetIn(int[] routed, int[] connected) {
        if (routed != null && routed.length > 0) {
            for (int i = 0; i < routed.length; i++) if (headset(routed[i])) return i;
            return NONE;
        }
        for (int t : connected) if (headset(t)) return UNNAMED;
        return NONE;
    }

    /** Where Buddy's voice goes. Returns the saved key, WatchVoice.KEY, or "" (Android's own routing). */
    static String pick(String saved, boolean savedHere, boolean headset, boolean watch) {
        if (!saved.isEmpty() && savedHere) return saved; // a pick in effect (Watch: when this build has the link)
        if (headset || !watch) return "";                // a headset (Android picks the latest), or no watch link
        return WatchVoice.KEY;                           // the watch; the phone (speaker) when none answers
    }

    // 016: the label of the headset Android plays Buddy's voice on now ("Headset" when it can't be named), or null
    private static String headsetNow(Context c) {
        AudioManager am = c.getSystemService(AudioManager.class);
        List<AudioDeviceInfo> r = null;
        if (Build.VERSION.SDK_INT >= 33) {
            r = am.getAudioDevicesForAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        }
        int i = headsetIn(types(r), types(java.util.Arrays.asList(am.getDevices(AudioManager.GET_DEVICES_OUTPUTS))));
        if (i >= 0) return label(c, r.get(i));
        return i == UNNAMED ? c.getString(R.string.audio_headset) : null;
    }

    private static int[] types(List<AudioDeviceInfo> l) {
        if (l == null) return null;
        int[] t = new int[l.size()];
        for (int i = 0; i < t.length; i++) t[i] = l.get(i).getType();
        return t;
    }

    private static String[] names(List<AudioDeviceInfo> l) {
        String[] n = new String[l.size()];
        for (int i = 0; i < n.length; i++) n[i] = String.valueOf(l.get(i).getProductName());
        return n;
    }

    /** 016: where Buddy's voice goes now: the saved key, WatchVoice.KEY, or "" (Android's own routing). */
    static String target(Context c) {
        String saved = Prefs.audioDevice(c);
        return pick(saved, indexOf(choices(c), saved) >= 0, headsetNow(c) != null, WatchVoice.link() != null);
    }

    /**
     * 017: pick() for a phone live call, from the connected media outputs only: in communication mode Android's routed
     * answer (target()) follows the device Buddy set and hides a connected headset.
     */
    static String livePick(String saved, boolean savedHere, int[] connected, boolean watch) {
        return pick(saved, savedHere, headsetIn(null, connected) != NONE, watch);
    }

    static String liveTarget(Context c) {
        String saved = Prefs.audioDevice(c);
        AudioManager am = c.getSystemService(AudioManager.class);
        return livePick(saved, indexOf(choices(c), saved) >= 0,
                types(java.util.Arrays.asList(am.getDevices(AudioManager.GET_DEVICES_OUTPUTS))), WatchVoice.link() != null);
    }

    /** 016: what Android's routing uses now, as a label. */
    static String androidNow(Context c) {
        String h = headsetNow(c);
        return h != null ? h : c.getString(R.string.audio_speaker);
    }

    /** 016: what Auto uses right now, as a label: asks the watch when it is next in the order. */
    static void autoNow(Context c, Consumer<String> label) {
        WatchVoice.Link link = WatchVoice.link();
        if (!pick("", false, headsetNow(c) != null, link != null).equals(WatchVoice.KEY)) {
            label.accept(androidNow(c));
            return;
        }
        link.find(c, node -> label.accept(node != null ? c.getString(R.string.audio_watch) : androidNow(c)));
    }

    /** Index of the key in choices(), or -1 when that device isn't there. */
    static int indexOf(String[][] choices, String key) {
        for (int i = 0; i < choices.length; i++) if (choices[i][0].equals(key)) return i;
        return -1;
    }

    // the id changes on reconnect and a Bluetooth address can be hidden from apps: type and name are stable
    private static String key(AudioDeviceInfo d) {
        return key(d.getType(), String.valueOf(d.getProductName()));
    }

    private static String key(int type, String name) {
        return type + ":" + name;
    }

    /**
     * 017, 030: the communication device a phone live call uses: the saved device when it is connected (a SCO with no
     * media output, like the watch's, is only ever chosen this way), else a headset's own call device (wired, USB, BLE,
     * hearing aid, or the SCO of a connected media headset: same product name as its A2DP output), else the built-in
     * speaker. Never the earpiece. comm: the devices of getAvailableCommunicationDevices(), out: those of
     * getDevices(OUTPUTS). Returns an index in comm, or NONE. Devices in refused (keys) are skipped.
     */
    static int liveDevice(String saved, int[] comm, String[] commName, int[] out, String[] outName, Set<String> refused) {
        for (int i = 0; i < comm.length; i++) {
            if (comm[i] != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE && key(comm[i], commName[i]).equals(saved)
                    && !refused.contains(saved)) {
                return i;
            }
        }
        int speaker = NONE;
        for (int i = 0; i < comm.length; i++) {
            if (refused.contains(key(comm[i], commName[i]))) continue;
            if (comm[i] == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ? mediaHeadset(commName[i], out, outName) : headset(comm[i])) {
                return i;
            }
            if (comm[i] == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER && speaker == NONE) speaker = i;
        }
        return speaker;
    }

    private static boolean mediaHeadset(String name, int[] out, String[] outName) {
        for (int j = 0; j < out.length; j++) if (headset(out[j]) && outName[j].equals(name)) return true;
        return false;
    }

    /**
     * 017: liveDevice, then apply it; a device apply refuses goes into refused and the next is tried, so this ends
     * (headset, speaker, NONE at worst). A refusal lasts while Android lists the device (030): one that left and comes
     * back is tried again. Returns the index applied, or NONE.
     */
    static int liveAttempt(String saved, int[] comm, String[] commName, int[] out, String[] outName, Set<String> refused,
                           IntPredicate apply) {
        Set<String> listed = new HashSet<>();
        for (int i = 0; i < comm.length; i++) listed.add(key(comm[i], commName[i]));
        refused.retainAll(listed);
        for (;;) {
            int i = liveDevice(saved, comm, commName, out, outName, refused);
            if (i == NONE || apply.test(i)) return i;
            refused.add(key(comm[i], commName[i]));
        }
    }

    /** 017: the device applied isn't Android's communication device any more, after the time it needs to take. */
    static boolean slipped(String applied, String actual, long sinceMs, long graceMs) {
        return applied != null && sinceMs >= graceMs && !applied.equals(actual);
    }

    /**
     * 030, 031: a route seen once and the speaker get 2 s; a headset not seen yet gets 30 s while the reported output is
     * safe and 5 s while Android reports the earpiece (the reply plays there, and Android reports it for as long as a
     * call link connects). Counted from the latest request (lossFrom), then the speaker takes over.
     */
    static long grace(String applied, boolean seen, String actual) {
        if (seen || applied == null || applied.startsWith(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER + ":")) return CONFIRM_MS;
        return actual != null && actual.startsWith(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE + ":") ? EARPIECE_MS : SETUP_MS;
    }

    /**
     * 031: the time a loss counts from: now while Android reports a headset (not the speaker) as the applied device, so
     * one earpiece report late in a call is no slip; else from (the latest apply; the speaker keeps it, as in 030).
     */
    static long lossFrom(String applied, String actual, long from, long now) {
        return applied != null && applied.equals(actual) && !applied.startsWith(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER + ":")
                ? now : from;
    }

    /** 031: a refused device Android reports as the communication device (not the earpiece) is back: it connected late. */
    static void unrefuse(Set<String> refused, String actual) {
        if (actual != null && !actual.startsWith(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE + ":")) refused.remove(actual);
    }

    /** 030: a slip refuses the device, except the speaker's first since Android last reported it (applied again once). */
    static boolean refuseSlip(String applied, boolean speakerSlipped) {
        return speakerSlipped || !applied.startsWith(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER + ":");
    }

    /** 030: the pick is on only when Buddy applied it and Android reports it as the communication device. */
    static boolean pickOn(String pick, String applied, String actual) {
        return pick.equals(applied) && pick.equals(actual);
    }

    private static String label(Context c, AudioDeviceInfo d) {
        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                return c.getString(R.string.audio_speaker);
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:
                return c.getString(R.string.audio_phone);
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                return c.getString(R.string.audio_headset);
            default:
                String n = String.valueOf(d.getProductName());
                return n.isEmpty() ? c.getString(R.string.audio_headset) : n;
        }
    }

    /**
     * Buddy's voice starts now, or the choice changed: apply the choice now. The audio mode is read here, before
     * anything of Buddy's own (CodexLive.start calls this before its call exists), so a phone or app call that is on
     * already is seen and left alone for the whole session.
     */
    static void use(Context c) {
        if (Build.VERSION.SDK_INT < 31) return;
        final Context a = c.getApplicationContext();
        final int mode = a.getSystemService(AudioManager.class).getMode();
        main.post(() -> {
            app = a;
            if (!on) {
                on = true;
                other = mode != AudioManager.MODE_NORMAL;
            }
            began = System.currentTimeMillis();
            heard = false;
            quietSince = 0;
            // a device choice is forced to apply again; Auto keeps `applied` so reconcile's drop() releases it
            if (!want(a).isEmpty() || LiveWatch.noEarpiece()) applied = null;
            refused = null;
            liveRefused.clear();
            stuck = speakerSlipped = false;
            try {
                reconcile();
            } catch (Exception ignored) {
            }
            main.removeCallbacks(poll);
            main.postDelayed(poll, POLL_MS);
        });
    }

    /**
     * The chosen device as Android routing sees it: Watch is Auto. 031: Auto while dictating is a connected Bluetooth
     * headset's call link (a SCO named like a connected media output; not the watch's), so its microphone is the input;
     * "" when there is none. Read aloud isn't listening: it stays on the headset's media link.
     */
    static String want(String saved, boolean listening, int[] comm, String[] commName, int[] out, String[] outName) {
        if (WatchVoice.KEY.equals(saved)) return "";
        if (saved.isEmpty() && listening) {
            for (int i = 0; i < comm.length; i++) {
                if (comm[i] == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && mediaHeadset(commName[i], out, outName)) {
                    return key(comm[i], commName[i]);
                }
            }
        }
        return saved;
    }

    @TargetApi(31)
    private static String want(Context c) {
        AudioManager am = c.getSystemService(AudioManager.class);
        List<AudioDeviceInfo> comm = am.getAvailableCommunicationDevices();
        List<AudioDeviceInfo> out = java.util.Arrays.asList(am.getDevices(AudioManager.GET_DEVICES_OUTPUTS));
        return want(Prefs.audioDevice(c), Voice.listening(), types(comm), names(comm), types(out), names(out));
    }

    /** Buddy applied this device key and Android reports it as the communication device (030). Main thread. */
    @TargetApi(31)
    static boolean applied(String key) {
        if (!key.equals(applied)) return false;
        AudioDeviceInfo d = app.getSystemService(AudioManager.class).getCommunicationDevice();
        return pickOn(key, applied, d == null ? null : key(d));
    }

    /** Buddy itself holds communication mode (AutoPause doesn't count that as a call). */
    static boolean ownsMode() {
        return ownMode;
    }

    /**
     * 017's live rule: while a phone live call is on (noEarpiece, 030: every setting), the route is live()'s. Not gated on the
     * session latch `other`: a session that began in another mode would otherwise send Auto to drop(), the earpiece
     * (026, the W2 log).
     */
    static boolean liveRule(int mode, boolean noEarpiece) {
        return noEarpiece && (mode == AudioManager.MODE_NORMAL || mode == AudioManager.MODE_IN_COMMUNICATION);
    }

    @TargetApi(31)
    private static void reconcile() {
        AudioManager am = app.getSystemService(AudioManager.class);
        int mode = am.getMode();
        boolean live = CodexLive.on();
        afterLive(mode);
        sawLive |= live;
        if (liveRule(mode, LiveWatch.noEarpiece())) {
            live(am);
            return;
        }
        String want = want(app);
        if (want.isEmpty()) {
            drop(am);
            return;
        }
        // ringing or in a phone call: not ours to touch, whatever was applied before
        if (mode != AudioManager.MODE_NORMAL && mode != AudioManager.MODE_IN_COMMUNICATION) other = true;
        AudioDeviceInfo d = null;
        if (!other) {
            for (AudioDeviceInfo i : am.getAvailableCommunicationDevices()) if (key(i).equals(want)) d = i;
        }
        if (d == null) { // also when Android ended the selection because the device left: back on reconnect
            drop(am);
            return;
        }
        if (want.equals(applied) || want.equals(refused)) return;
        // in live voice the WebView sets and resets the mode itself
        if (mode == AudioManager.MODE_NORMAL && !live) {
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            ownMode = true;
        }
        if (am.setCommunicationDevice(d)) {
            applied = want;
            dirty = true;
        } else { // don't retry every tick: on reconnect or a new choice
            refused = want;
            dropMode(am);
        }
    }

    /**
     * 017, 030: a phone live call: the saved device when connected, else a headset's call device, else the speaker,
     * never the earpiece and never drop() (that is Android's routing, the earpiece). The WebView owns the mode, so this
     * never sets it.
     */
    @TargetApi(31)
    private static void live(AudioManager am) {
        final long now = SystemClock.elapsedRealtime();
        AudioDeviceInfo cur = am.getCommunicationDevice();
        final String curKey = cur == null ? null : key(cur);
        if (applied != null && applied.equals(curKey)) {
            seen = true;
            speakerSlipped = false;
        }
        appliedAt = lossFrom(applied, curKey, appliedAt, now); // from here on appliedAt is the time a loss counts from
        unrefuse(liveRefused, curKey);
        if (slipped(applied, curKey, now - appliedAt, grace(applied, seen, curKey))) {
            // a headset's call link that failed, or a speaker that twice didn't take: refused; else applied again
            if (refuseSlip(applied, speakerSlipped)) liveRefused.add(applied);
            else speakerSlipped = true;
            CodexLive.log("route: slipped from " + applied + " to " + (cur == null ? "none" : key(cur)));
            applied = null;
        }
        final List<AudioDeviceInfo> comm = am.getAvailableCommunicationDevices();
        final int[] ct = types(comm);
        final String[] cn = names(comm);
        List<AudioDeviceInfo> outs = java.util.Arrays.asList(am.getDevices(AudioManager.GET_DEVICES_OUTPUTS));
        int[] ot = types(outs);
        String[] outNames = names(outs);
        int i = liveAttempt(Prefs.audioDevice(app), ct, cn, ot, outNames, liveRefused, j -> {
            String k = key(ct[j], cn[j]);
            if (k.equals(applied)) return true;
            boolean ok = am.setCommunicationDevice(comm.get(j));
            CodexLive.log("route: " + k + (ok ? " applied" : " refused") + (other ? " (session began in another mode)" : ""));
            if (ok) {
                applied = k;
                appliedAt = now;
                seen = false;
                dirty = true;
            }
            return ok;
        });
        if (i == NONE && !stuck) {
            stuck = true;
            CodexLive.log("route: no safe device");
            CodexLive.end(app.getString(R.string.live_ended_no_speaker));
        }
    }

    // The WebView's teardown (after a live call ends) clears the device, then sets MODE_NORMAL: once Buddy sees
    // NORMAL, it has nothing left to undo and the choice is applied again for dictation or read aloud already going
    private static void afterLive(int mode) {
        if (sawLive && !CodexLive.on() && mode == AudioManager.MODE_NORMAL) {
            sawLive = false;
            applied = refused = null;
            liveRefused.clear();
            stuck = speakerSlipped = false;
        }
    }

    @TargetApi(31)
    private static void drop(AudioManager am) {
        if (applied != null) am.clearCommunicationDevice();
        applied = refused = null;
        liveRefused.clear();
        stuck = speakerSlipped = false;
        dropMode(am);
    }

    private static void dropMode(AudioManager am) {
        // not while ringing or in a call, and the WebView owns the mode in live voice
        if (ownMode && !CodexLive.on() && am.getMode() == AudioManager.MODE_IN_COMMUNICATION) {
            am.setMode(AudioManager.MODE_NORMAL);
        }
        ownMode = false;
    }

    private static final Runnable poll = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            if (Voice.isSpeaking() || Voice.listening() || CodexLive.on()) {
                heard = true;
                quietSince = 0;
                try {
                    reconcile();
                } catch (Exception ignored) {
                }
            } else {
                if (quietSince == 0) quietSince = now;
                if (heard ? now - quietSince > QUIET_MS : now - began > NEVER_STARTED_MS) {
                    finish();
                    return;
                }
                if (sawLive) afterLive(app.getSystemService(AudioManager.class).getMode());
                else if (applied != null && want(app).isEmpty()) drop(app.getSystemService(AudioManager.class)); // 031: Auto's dictation link
            }
            main.postDelayed(this, POLL_MS);
        }
    };

    // Also clears what Chromium may have selected again at hang-up (it restores the state it saved at call start).
    @TargetApi(31)
    private static void finish() {
        try {
            AudioManager am = app.getSystemService(AudioManager.class);
            if (dirty) am.clearCommunicationDevice();
            dropMode(am);
        } catch (Exception ignored) {
        }
        on = other = ownMode = dirty = sawLive = stuck = speakerSlipped = false;
        applied = refused = null;
        liveRefused.clear();
    }
}
