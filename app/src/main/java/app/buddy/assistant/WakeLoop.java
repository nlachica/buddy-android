package app.buddy.assistant;

import java.util.ArrayList;
import java.util.Locale;

/**
 * What the detector's capture thread does with each block of audio, with no android.* in it so it is unit-tested.
 * DETECT: blocks go to {@link WakeFeatures}; nothing leaves the loop. On a detection (N consecutive hops at or above the {@link Rule}'s threshold): HOLD, and the host hears
 * {@link Host#onWake} (the main thread takes it from there). HOLD: the loop keeps everything heard, pre-roll (the wake
 * phrase included) first, however long the main thread or a transcript takes, up to {@link #HOLD_LIMIT}. FEED: what is
 * held, then every new block, goes to the sink; when the sink refuses (the end of input was marked) it is HOLD again,
 * starting with the refused block, so what is said while a transcript is awaited is kept for the next window.
 * The main thread moves it with {@link #feedTo} and {@link #drop}; every method is synchronized.
 */
final class WakeLoop {
    enum Mode { DETECT, HOLD, FEED }

    interface Sink {
        /** Audio of a capture, 16 kHz mono PCM16 little-endian; false when it is not wanted any more. */
        boolean feed(int capture, byte[] pcm);
    }

    /** What counts as a wake: {@code hops} consecutive scored hops (120 ms each) at or above {@code threshold}. */
    static final class Rule {
        static final Rule LOW = new Rule("low", 0.7f, 3), MEDIUM = new Rule("medium", 0.5f, 3), HIGH = new Rule("high", 0.3f, 2);
        final String name;
        final float threshold;
        final int hops;

        Rule(String name, float threshold, int hops) {
            this.name = name;
            this.threshold = threshold;
            this.hops = hops;
        }

        /** low or high; anything else (null too) is medium. */
        static Rule of(String s) {
            return "low".equals(s) ? LOW : "high".equals(s) ? HIGH : MEDIUM;
        }

        @Override
        public String toString() {
            return name + String.format(Locale.ROOT, " (%.2f x %d)", threshold, hops);
        }
    }

    interface Host {
        /** The rule for the next hop (capture thread, every hop): the sensitivity setting, read as it is now. */
        Rule rule();

        /** One line for the log: a wake or a near miss, with its scores. */
        void log(String line);

        /** A detection (capture thread): {@code detectedAt} is the clock the blocks were stamped with. */
        void onWake(int seq, long detectedAt);

        /** The hold, or a feed, ran past its limit: the audio is dropped, the loop detects again; cancel the turn. */
        void onHoldExpired(int seq);

        /** The models threw while scoring (capture thread): the loop is dead from now on; stop the detector. */
        void onFailed(RuntimeException e);
    }

    /** 90 s: longer than any engine lets a transcript take (Claude's own waits add up to 80 s). */
    static final int HOLD_LIMIT = 90 * 16000 * 2;
    /** A feed longer than 2 minutes is not a request. */
    static final int FEED_LIMIT = 120 * 16000 * 2;
    private static final int CHUNK = 640; // samples: 40 ms

    private final WakeFeatures features;
    private final Sink sink;
    private final Host host;
    private Mode mode = Mode.DETECT;
    private final ArrayList<byte[]> held = new ArrayList<>();
    private int heldBytes, fedBytes, capture, seq;
    private boolean failed;
    private Rule rule;
    private int run;
    private float peak;
    private final StringBuilder hops = new StringBuilder();

    WakeLoop(WakeFeatures features, Sink sink, Host host) {
        this.features = features;
        this.sink = sink;
        this.host = host;
    }

    synchronized Mode mode() {
        return mode;
    }

    /** Capture thread: one block of n samples, stamped {@code now} (ms). */
    synchronized void block(short[] pcm, int n, long now) {
        if (failed) return;
        switch (mode) {
            case DETECT:
                float score;
                try {
                    score = features.feed(pcm, n);
                } catch (RuntimeException e) { // OnnxWakeEngine.run: a model that can't run
                    failed = true;
                    host.onFailed(e);
                    break;
                }
                if (score < 0) break; // no hop completed in this block (a block is 40 ms, a hop 120 ms)
                Rule r = host.rule();
                if (r != rule) { // the sensitivity changed: start over
                    rule = r;
                    run = 0;
                }
                if (score >= r.threshold) {
                    if (run++ == 0) {
                        peak = 0;
                        hops.setLength(0);
                    }
                    peak = Math.max(peak, score);
                    hops.append(' ').append(fmt(score));
                    if (run >= r.hops) {
                        run = 0;
                        mode = Mode.HOLD;
                        short[] pre = features.preRoll();
                        for (int i = 0; i < pre.length; i += CHUNK) hold(bytes(pre, i, Math.min(CHUNK, pre.length - i)));
                        host.log("wake " + (seq + 1) + ": " + r + " peak " + fmt(peak) + " hops" + hops);
                        host.onWake(++seq, now);
                    }
                } else if (run > 0) {
                    run = 0;
                    host.log("near miss: " + r + " peak " + fmt(peak) + " hops" + hops + " then " + fmt(score));
                }
                break;
            case HOLD:
                hold(bytes(pcm, 0, n));
                if (heldBytes > HOLD_LIMIT) expire();
                break;
            case FEED:
                byte[] b = bytes(pcm, 0, n);
                if (!sink.feed(capture, b)) {
                    mode = Mode.HOLD; // the end of input was marked: keep what comes, starting with this block
                    fedBytes = 0;
                    hold(b);
                } else if ((fedBytes += b.length) > FEED_LIMIT) {
                    expire();
                }
                break;
        }
    }

    /** Main thread: send what is held, and from now on every block, to {@code capture}. False when nothing was waiting. */
    synchronized boolean feedTo(int capture) {
        if (mode == Mode.DETECT) return false;
        this.capture = capture;
        fedBytes = 0;
        mode = Mode.FEED;
        while (!held.isEmpty()) {
            byte[] b = held.get(0);
            if (!sink.feed(capture, b)) { // not wanted (a stale capture, or input already ended): keep the rest
                mode = Mode.HOLD;
                return true;
            }
            held.remove(0);
            heldBytes -= b.length;
            fedBytes += b.length;
        }
        return true;
    }

    /** Main thread: forget what is held and detect again; nothing heard before this can trigger. */
    synchronized void drop() {
        mode = Mode.DETECT;
        held.clear();
        heldBytes = fedBytes = run = 0;
        features.reset();
    }

    private void hold(byte[] b) {
        held.add(b);
        heldBytes += b.length;
    }

    private void expire() {
        drop();
        host.onHoldExpired(seq);
    }

    private static String fmt(float score) {
        return String.format(Locale.ROOT, "%.3f", score);
    }

    private static byte[] bytes(short[] pcm, int from, int n) {
        byte[] b = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            b[2 * i] = (byte) pcm[from + i];
            b[2 * i + 1] = (byte) (pcm[from + i] >> 8);
        }
        return b;
    }
}
