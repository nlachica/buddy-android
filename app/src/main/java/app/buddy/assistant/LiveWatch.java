package app.buddy.assistant;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * A phone live voice call and the Galaxy Watch (docs/features/017-live-voice-watch/plan.md): the call's voice can play
 * on the watch, and a phone call never falls to the earpiece, whatever the audio device (AudioRoute.live, 030). The voice
 * goes to the watch as one 012 reading per utterance (the silence gate cuts them), and the phone's mic stays closed from
 * the first audible block until the watch says that reading is quiet. Google-free (the watch link is WatchVoice's), so
 * it is unit-tested through {@link Call}. 033: Auto uses the watch only while it is worn (WatchVoice.find asks; the watch
 * pushes its state while a reading plays, and a call that is Auto leaves the watch when it is taken off). Calls started from the watch have a Remote (007) and never come here.
 */
final class LiveWatch {
    static final long POLL_MS = 500, TAIL_MS = 300, FAIL_TAIL_MS = 1500, ANSWER_MS = 30_000, QUIET_MS = 1000, RETRY_MS = 5000;
    static final int RETRIES = 3;

    private static volatile Call call;

    private LiveWatch() {
    }

    /** Android's end of {@link Call}; a fake in the tests. */
    interface Out {
        /** Prefs.audioDevice: the setting now (cheap, polled). */
        String saved();

        /** The watch is first in the order now (AudioRoute.liveTarget). */
        boolean watchWins();

        /** AudioRoute has applied this saved device key for the call (it is connected and chosen, 030). */
        boolean pickApplied(String saved);

        /** A reachable watch's node, or null (Auto: only a worn one, 033). */
        void find(Consumer<String> node);

        /** The node said "worn" a moment ago (WatchVoice.worn); the tests' fake says yes. */
        default boolean worn(String node) {
            return true;
        }

        /** One 012 reading; attached runs when its channel is open. */
        ReadQueue.Stream open(String node, ReadQueue.Events e, Runnable attached);

        void after(long ms, Runnable r);

        long now();

        void log(String line);
    }

    /** A phone live call starts (main thread). Android 12+ only: 001's platform rule, below it a call is as before. */
    static void begin(Context c) {
        if (Build.VERSION.SDK_INT < 31) return;
        if (call == null) call = new Call(new Phone(c.getApplicationContext()));
        call.begin();
    }

    static void end() {
        Call k = call;
        if (k != null) k.end();
    }

    /** A phone live call is on: AudioRoute keeps it off the earpiece, whatever the audio device (030). */
    static boolean noEarpiece() {
        Call k = call;
        return k != null && k.noEarpiece();
    }

    /** The watch pushed its wrist state (main thread, 033): off the wrist, an Auto call goes to the phone. */
    static void worn(String node, boolean worn) {
        Call k = call;
        if (k != null) k.worn(node, worn);
    }

    /** The voice (16-bit mono, 16 kHz), from the call's page (any thread). */
    static void pcm(byte[] b) {
        Call k = call;
        if (k != null) k.pcm(b);
    }

    /**
     * What the page does with the voice: bit 1 = it plays on the watch, bit 2 = the mic is closed. voicePeak: the
     * voice's peak this tick (0..1), which says when it is quiet (the pause a retry waits for, 026).
     */
    static int toWatch(float voicePeak) {
        Call k = call;
        if (k == null) return 0;
        k.voicePeak(voicePeak);
        return k.toWatch();
    }

    /**
     * The call's rules. Main thread, except pcm() and toWatch() (the page's bridge thread): all state is under the
     * instance lock. The reading streams never call back from inside their own methods, so holding it is safe.
     */
    static final class Call {
        private final Out out;
        private final Gate gate = new Gate();
        private final ArrayList<byte[]> held = new ArrayList<>();
        private boolean on, watch, fed, heldEnded, off;
        private int gen, count, retries;
        private String saved, node;
        private Object turn;
        /** cur: the open reading that takes audio now. drain: the finished reading whose answer isn't here yet. Never both. */
        private ReadQueue.Stream cur, drain;
        private long openAt, endedAt, loudAt, retryAt;
        /** retryTurn: the turn a retry made. pending: its channel is open, the voice waits for a pause to move (026). */
        private Object retryTurn, pending;

        Call(Out out) {
            this.out = out;
        }

        synchronized void begin() {
            clear();
            retries = 0;
            on = true;
            off = false;
            final int g = ++gen;
            saved = out.saved();
            decide();
            out.after(POLL_MS, () -> poll(g));
        }

        synchronized void end() {
            clear();
            on = false;
            gen++;
        }

        private void clear() {
            if (cur != null) cur.cancel();
            if (drain != null) drain.cancel();
            cur = drain = null;
            held.clear();
            watch = fed = heldEnded = false;
            turn = retryTurn = pending = null;
            openAt = retryAt = 0;
        }

        synchronized boolean noEarpiece() {
            return on;
        }

        private synchronized void poll(int g) {
            if (g != gen || !on) return;
            String s = out.saved();
            if (!s.equals(saved)) { // a change of the setting decides again
                out.log("setting " + show(saved) + " -> " + show(s));
                final boolean there = turn != null && node != null && WatchVoice.KEY.equals(saved); // a find still running isn't on the watch yet
                saved = s;
                off = false;
                retryAt = 0; // the change is the fresh decision, not a retry
                decide();
                if (there && !out.worn(node)) leave(); // Watch is now Auto: it stays on the watch only while worn
            } else if (watch && out.pickApplied(saved)) { // an explicit pick that is back takes the voice from the watch
                out.log(saved + " is here: phone");
                decide();
            } else if (pending != null && pending == turn && quiet()) {
                move(pending);
            } else if (retryAt != 0 && turn == null && drain == null && out.now() >= retryAt && quiet() && want()) {
                retryAt = 0;
                retries++;
                out.log("retry " + retries + " (quiet " + (out.now() - loudAt) + " ms)");
                attempt(true);
            }
            out.after(POLL_MS, () -> poll(g));
        }

        private static String show(String v) {
            return v.isEmpty() ? "auto" : v;
        }

        private void decide() {
            boolean want = want();
            if (want && turn == null && drain == null) attempt(false);
            else if (!want && turn != null) stop();
        }

        private boolean want() {
            return !off && out.watchWins();
        }

        /** The watch pushed its wrist state (main thread). */
        synchronized void worn(String n, boolean w) {
            if (on && !w && n.equals(node)) leave();
        }

        /** The watch is off the wrist: an Auto call goes to the phone and stays there until it ends or the setting moves. */
        private void leave() {
            if (turn == null || WatchVoice.KEY.equals(out.saved())) return; // a picked Watch is used worn or not
            out.log("not worn");
            off = true; // the call stays on the phone until it ends or the setting moves
            stop();
        }

        /** Retries run again after a fallback: RETRIES per call, RETRY_MS apart, in a pause of the voice (026). */
        private void arm() {
            retryAt = retries < RETRIES ? out.now() + RETRY_MS : 0;
        }

        private void attempt(boolean retry) {
            final Object t = turn = new Object();
            node = null;
            if (retry) retryTurn = t; // before find: the fake answers at once
            out.log("looking");
            out.find(n -> found(t, n));
        }

        private synchronized void found(Object t, String n) {
            if (turn != t) return;
            if (n != null && !out.watchWins()) n = null; // a headset came while the watch was asked: the voice stays on the phone
            if (n == null) {
                turn = null;
                out.log("none found: phone");
                if (t == retryTurn) arm(); // a watch that comes back between tries is caught
                return;
            }
            node = n;
            openNext(t);
        }

        /** The next reading, opened ahead of the voice; blocks that waited for it go in. */
        private void openNext(Object t) {
            final Ev e = new Ev(t);
            cur = e.s = out.open(node, e, () -> attached(t));
            fed = !held.isEmpty();
            for (byte[] b : held) cur.pcm(b);
            held.clear();
            if (heldEnded) {
                heldEnded = false;
                finishCur();
            }
        }

        /** The channel is open: from now the phone is muted. Not before, so an unreachable watch never silences it. */
        private synchronized void attached(Object t) {
            if (turn != t || watch) return;
            if (t == retryTurn && !quiet()) { // a retry moves the voice only in a pause; the channel idles meanwhile
                pending = t;
                out.log("waiting for a pause");
                return;
            }
            move(t);
        }

        private void move(Object t) {
            pending = null;
            gate.reset();
            watch = true;
            out.log(t == retryTurn ? "playing there (quiet " + (out.now() - loudAt) + " ms)" : "playing there");
        }

        /** The utterance is over: the reading ends, and the watch's answer says when it is quiet. */
        private void finishCur() {
            final ReadQueue.Stream s = cur;
            s.close();
            drain = s;
            cur = null;
            fed = false;
            endedAt = out.now();
            count++;
            out.after(ANSWER_MS, () -> giveUp(s));
        }

        private final class Ev implements ReadQueue.Events {
            final Object t;
            ReadQueue.Stream s;
            final int n = count + 1; // the utterance this reading will carry

            Ev(Object t) {
                this.t = t;
            }

            @Override
            public void played() {
                answered(this, "played", TAIL_MS, true);
            }

            @Override
            public void refused() {
                answered(this, "refused", TAIL_MS, false);
            }

            @Override
            public void broken() {
                broken(false);
            }

            @Override
            public void broken(boolean watchStopped) {
                answered(this, watchStopped ? "stopped by the watch" : "broken", watchStopped ? TAIL_MS : FAIL_TAIL_MS, false);
            }
        }

        private synchronized void answered(Ev e, String what, long tail, boolean played) {
            if (e.s == drain && drain != null) {
                drain = null;
                openAt = out.now() + tail;
                out.log(what + " " + e.n + " (+" + (out.now() - endedAt) + " ms after its end)");
                if (e.t != turn) decide(); // the setting moved meanwhile
                else if (played) openNext(e.t);
                else fallback(what, 0);
            } else if (e.s == cur && cur != null && e.t == turn && !played) {
                fallback(what, tail);
            }
        }

        /** The watch can't play it (refused, broken, silent): the phone's own route; retried in a pause (026), and on a setting change. */
        private void fallback(String why, long tail) {
            if (cur != null) cur.cancel();
            cur = null;
            held.clear();
            heldEnded = false;
            turn = null;
            watch = false;
            if (fed) { // the watch's player was going: the mic waits for it to be silent
                fed = false;
                openAt = out.now() + tail;
            }
            out.log("fallback: " + why);
            arm();
        }

        /** The setting moved away: the voice is back on the phone, and the mic stays closed until the watch is quiet. */
        private void stop() {
            turn = null;
            watch = false;
            held.clear();
            heldEnded = false;
            if (cur != null) {
                if (fed) {
                    finishCur();
                } else {
                    cur.cancel();
                    cur = null;
                }
            }
            out.log("stop: the setting moved");
        }

        /** No answer for ANSWER_MS: a link that neither plays nor drops. Cancelling closes the channel, which stops the watch. */
        private synchronized void giveUp(ReadQueue.Stream s) {
            if (drain != s) return;
            drain.cancel();
            drain = null;
            held.clear();
            heldEnded = false;
            openAt = out.now() + FAIL_TAIL_MS;
            out.log("no answer: phone");
            if (watch) fallback("no answer", 0);
            else decide();
        }

        /** The voice, from the page's tap (bridge thread). */
        synchronized void pcm(byte[] b) {
            if (!watch) return;
            if (gate.keep(b)) {
                if (cur != null) {
                    cur.pcm(b);
                    fed = true;
                } else if (drain != null) {
                    held.add(b);
                    heldEnded = false; // describes the end of the audio held now, not of an earlier utterance
                }
            } else if (fed) {
                finishCur();
            } else if (drain != null && !held.isEmpty()) {
                heldEnded = true;
            }
        }

        /** The voice's peak this tick (bridge thread): audible by the gate's own threshold stamps the time. */
        synchronized void voicePeak(float p) {
            if (p > Gate.AUDIBLE) loudAt = out.now();
        }

        /** No audible voice for QUIET_MS (never reset: an old stamp only reads as quiet). */
        private boolean quiet() {
            return out.now() - loudAt >= QUIET_MS;
        }

        synchronized int toWatch() {
            boolean closed = fed || drain != null || !held.isEmpty() || out.now() < openAt;
            return (watch ? 1 : 0) | (closed ? 2 : 0);
        }
    }

    /** Cuts the voice into utterances: passes audible blocks and 1 s after the last one; starts closed. */
    static final class Gate {
        static final int HANGOVER = 32_000; // bytes: 1 s
        private long quiet = HANGOVER + 1;

        /** Peak above 0.01 of full scale: the watch Speaker's AUDIBLE. */
        static final double AUDIBLE = 0.01;

        static boolean loud(byte[] pcm16) {
            return peak(pcm16) > AUDIBLE * 32768;
        }

        /** The loudest sample's magnitude (0..32768). */
        static int peak(byte[] pcm16) {
            int m = 0;
            for (int i = 0; i + 1 < pcm16.length; i += 2) {
                m = Math.max(m, Math.abs((short) ((pcm16[i] & 0xff) | (pcm16[i + 1] << 8))));
            }
            return m;
        }

        boolean keep(byte[] pcm16) {
            if (loud(pcm16)) quiet = 0;
            else quiet += pcm16.length;
            return quiet <= HANGOVER;
        }

        void reset() {
            quiet = HANGOVER + 1;
        }
    }

    private static final class Phone implements Out {
        private final Context app;
        private final Handler main = new Handler(Looper.getMainLooper());

        Phone(Context app) {
            this.app = app;
        }

        @Override
        public String saved() {
            return Prefs.audioDevice(app);
        }

        @Override
        public boolean watchWins() {
            return WatchVoice.KEY.equals(AudioRoute.liveTarget(app));
        }

        @Override
        public boolean pickApplied(String saved) {
            return AudioRoute.applied(saved);
        }

        @Override
        public void find(Consumer<String> node) {
            WatchVoice.find(app, node);
        }

        @Override
        public boolean worn(String node) {
            return WatchVoice.worn(node);
        }

        @Override
        public ReadQueue.Stream open(String node, ReadQueue.Events e, Runnable attached) {
            return WatchVoice.link().open(app, node, e, attached);
        }

        @Override
        public void after(long ms, Runnable r) {
            main.postDelayed(r, ms);
        }

        @Override
        public long now() {
            return SystemClock.elapsedRealtime();
        }

        @Override
        public void log(String line) {
            CodexLive.log("watch: " + line);
        }
    }
}
