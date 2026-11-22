package app.buddy.assistant;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

public class WakeLoopTest {
    private static final class Rig implements WakeFeatures.Engine, WakeLoop.Sink, WakeLoop.Host {
        float level; // what the classifier says
        int accept = -1; // the capture the sink takes; -1: none
        final ByteArrayOutputStream got = new ByteArrayOutputStream();
        final ByteArrayOutputStream all = new ByteArrayOutputStream(); // every sample fed to the loop, as bytes
        int wakes, expired, lastSeq;
        WakeLoop.Rule rule = WakeLoop.Rule.MEDIUM;
        final List<String> lines = new ArrayList<>();
        long wakeAt;
        int next; // next sample value
        final WakeLoop loop = new WakeLoop(new WakeFeatures(this), this, this);

        @Override
        public float[] mel(float[] audio) {
            return new float[WakeFeatures.FRAMES * WakeFeatures.BINS];
        }

        @Override
        public float[] embed(float[] windows) {
            return new float[WakeFeatures.PER_HOP * WakeFeatures.EMB];
        }

        @Override
        public float score(float[] embeddings) {
            if (thrown != null) throw thrown;
            return level;
        }

        @Override
        public boolean feed(int capture, byte[] pcm) {
            if (capture != accept) return false;
            got.write(pcm, 0, pcm.length);
            return true;
        }

        @Override
        public WakeLoop.Rule rule() {
            return rule;
        }

        @Override
        public void log(String line) {
            lines.add(line);
        }

        @Override
        public void onWake(int seq, long detectedAt) {
            wakes++;
            lastSeq = seq;
            wakeAt = detectedAt;
        }

        RuntimeException thrown; // scoring throws this when set
        int failures;

        @Override
        public void onFailed(RuntimeException e) {
            failures++;
        }

        @Override
        public void onHoldExpired(int seq) {
            expired++;
            lastSeq = seq;
        }

        /** n blocks of 40 ms of a counting ramp. */
        void blocks(int n, long now) {
            for (int b = 0; b < n; b++) {
                short[] s = new short[640];
                for (int i = 0; i < s.length; i++) s[i] = (short) next++;
                for (short v : s) {
                    all.write(v);
                    all.write(v >> 8);
                }
                loop.block(s, 640, now);
            }
        }

        /** Warm-up so the next 3 blocks complete exactly one hop with four groups in. */
        void warm() {
            blocks(36, 0);
        }

        /** Detects: warms up, makes the classifier say 0.9 for three hops (blocks 39, 42, 45: the medium rule). */
        void wake(long now) {
            warm();
            level = 0.9f;
            blocks(9, now);
        }

        /** n hops (3 blocks each) at this score. */
        void hops(int n, float score) {
            level = score;
            blocks(3 * n, 0);
        }

        byte[] sentAfterWake() {
            return got.toByteArray();
        }

        /** The last n bytes of the whole audio before the detection (the pre-roll). */
        byte[] tail(byte[] everything, int from, int len) {
            byte[] t = new byte[len];
            System.arraycopy(everything, from, t, 0, len);
            return t;
        }
    }

    @Test
    public void nothingLeavesWhileDetecting() {
        Rig r = new Rig();
        r.warm();
        r.blocks(100, 0); // level 0: no detection
        assertEquals(WakeLoop.Mode.DETECT, r.loop.mode());
        assertEquals(0, r.wakes);
        assertEquals(0, r.got.size());
        assertFalse(r.loop.feedTo(1)); // nothing waiting
    }

    @Test
    public void wakeHoldsPreRollThenFeedsInOrder() {
        Rig r = new Rig();
        r.wake(5000);
        assertEquals(1, r.wakes);
        assertEquals(5000, r.wakeAt);
        assertEquals(WakeLoop.Mode.HOLD, r.loop.mode());
        r.blocks(10, 0); // the main thread is slow: held
        assertEquals(0, r.got.size());
        r.accept = 7;
        assertTrue(r.loop.feedTo(7));
        assertEquals(WakeLoop.Mode.FEED, r.loop.mode());
        r.blocks(5, 0);
        byte[] all = r.all.toByteArray(), got = r.got.toByteArray();
        int pre = 24000 * 2; // the first 36+3 blocks are 39*1280 = 49920 bytes > 48000: pre-roll is the last 1.5 s of them
        int wakeEnd = 45 * 1280;
        assertEquals(pre + 15 * 1280, got.length);
        assertArrayEquals(r.tail(all, wakeEnd - pre, pre + 15 * 1280), got);
    }

    @Test
    public void refusedBlockIsKeptAndDeliveredFirstToTheNextWindow() {
        Rig r = new Rig();
        r.wake(0);
        r.accept = 1;
        r.loop.feedTo(1);
        r.blocks(3, 0);
        int before = r.got.size();
        r.accept = -1; // the end of input was marked: the sink refuses
        r.blocks(1, 0);
        assertEquals(WakeLoop.Mode.HOLD, r.loop.mode());
        assertEquals(before, r.got.size());
        r.blocks(750, 0); // 30 s of a slow transcript: all kept
        assertEquals(0, r.expired);
        r.got.reset();
        r.accept = 2;
        assertTrue(r.loop.feedTo(2));
        byte[] all = r.all.toByteArray(), got = r.got.toByteArray();
        assertEquals(751 * 1280, got.length); // the refused block, then the 750
        assertArrayEquals(r.tail(all, all.length - 751 * 1280, 751 * 1280), got);
    }

    @Test
    public void staleCaptureKeepsTheHold() {
        Rig r = new Rig();
        r.wake(0);
        r.blocks(5, 0);
        r.accept = 9;
        assertTrue(r.loop.feedTo(8)); // refused: not the sink's capture
        assertEquals(WakeLoop.Mode.HOLD, r.loop.mode());
        assertEquals(0, r.got.size());
        assertTrue(r.loop.feedTo(9));
        assertEquals(WakeLoop.Mode.FEED, r.loop.mode());
        assertEquals(24000 * 2 + 5 * 1280, r.got.size());
    }

    @Test
    public void holdPast90SecondsDropsTheAudioAndTellsTheHostOnce() {
        Rig r = new Rig();
        r.wake(0);
        r.level = 0; // after the drop it must not detect again by itself
        r.blocks(2200, 0); // 88 s
        assertEquals(WakeLoop.Mode.HOLD, r.loop.mode());
        assertEquals(0, r.expired);
        r.blocks(100, 0); // 92 s
        assertEquals(1, r.expired);
        assertEquals(1, r.lastSeq);
        assertEquals(WakeLoop.Mode.DETECT, r.loop.mode());
        assertFalse(r.loop.feedTo(1)); // nothing held any more
        r.blocks(100, 0);
        assertEquals(1, r.expired);
    }

    @Test
    public void feedPastTwoMinutesAsksForACancel() {
        Rig r = new Rig();
        r.wake(0);
        r.level = 0;
        r.accept = 1;
        r.loop.feedTo(1);
        r.blocks(2900, 0); // 116 s
        assertEquals(0, r.expired);
        r.blocks(200, 0);
        assertEquals(1, r.expired);
        assertEquals(WakeLoop.Mode.DETECT, r.loop.mode());
    }

    @Test
    public void dropForgetsEverythingHeardSoItCannotRetrigger() {
        Rig r = new Rig();
        r.wake(0);
        r.loop.drop();
        assertEquals(WakeLoop.Mode.DETECT, r.loop.mode());
        r.blocks(3, 0); // classifier still says 0.9, but a window must fill again
        assertEquals(1, r.wakes);
        r.blocks(39, 0); // four groups, then three scored hops
        assertEquals(2, r.wakes);
    }

    @Test
    public void aScoringFailureStopsTheLoopAndTellsTheHostOnce() {
        Rig r = new Rig();
        r.thrown = new IllegalStateException("model");
        r.blocks(80, 0); // past the first hops: scoring throws, and nothing escapes block()
        assertEquals(1, r.failures);
        r.thrown = null;
        r.level = 1;
        r.blocks(80, 0); // dead: no detection and no second report
        assertEquals(1, r.failures);
        assertEquals(0, r.wakes);
    }

    @Test
    public void twoHopsThenALowOneIsANearMissAndTheCountStartsOver() {
        Rig r = new Rig();
        r.warm();
        r.hops(2, 0.9f);
        r.hops(1, 0);
        assertEquals(0, r.wakes);
        assertEquals(WakeLoop.Mode.DETECT, r.loop.mode());
        assertEquals(java.util.Collections.singletonList("near miss: medium (0.50 x 3) peak 0.900 hops 0.900 0.900 then 0.000"), r.lines);
        r.hops(2, 0.9f);
        assertEquals(0, r.wakes);
    }

    @Test
    public void threeHopsWakeOnceAndLogTheScores() {
        Rig r = new Rig();
        r.wake(0);
        assertEquals(1, r.wakes);
        assertEquals(java.util.Collections.singletonList("wake 1: medium (0.50 x 3) peak 0.900 hops 0.900 0.900 0.900"), r.lines);
    }

    @Test
    public void ruleOfMapsTheSettingAndAnythingElseIsMedium() {
        assertEquals(0.7f, WakeLoop.Rule.of("low").threshold, 0);
        assertEquals(3, WakeLoop.Rule.of("low").hops);
        assertEquals(0.3f, WakeLoop.Rule.of("high").threshold, 0);
        assertEquals(2, WakeLoop.Rule.of("high").hops);
        assertEquals(WakeLoop.Rule.MEDIUM, WakeLoop.Rule.of("medium"));
        assertEquals(WakeLoop.Rule.MEDIUM, WakeLoop.Rule.of(null));
        assertEquals(WakeLoop.Rule.MEDIUM, WakeLoop.Rule.of("loud"));
    }

    @Test
    public void highWakesOnTwoQuietHopsAndLowIgnoresFiveMiddlingOnes() {
        Rig h = new Rig();
        h.rule = WakeLoop.Rule.HIGH;
        h.warm();
        h.hops(2, 0.35f);
        assertEquals(1, h.wakes);
        Rig l = new Rig();
        l.rule = WakeLoop.Rule.LOW;
        l.warm();
        l.hops(5, 0.6f);
        assertEquals(0, l.wakes);
        assertTrue(l.lines.isEmpty());
    }

    @Test
    public void aDropResetsTheCountSilently() {
        Rig r = new Rig();
        r.warm();
        r.hops(2, 0.9f);
        r.loop.drop();
        r.blocks(39, 0); // refill: two scored hops at 0.9, not three
        assertEquals(0, r.wakes);
        assertTrue(r.lines.isEmpty());
    }

    @Test
    public void theWakeIsStampedWithTheFirstQualifyingHop() {
        Rig r = new Rig();
        r.warm();
        r.level = 0.9f;
        r.blocks(3, 1850);
        r.blocks(3, 1970);
        assertEquals(0, r.wakes);
        r.blocks(3, 2090);
        assertEquals(1, r.wakes);
        assertEquals(1850, r.wakeAt);
        // speech ended at 1000: the phrase began inside the overlap window, so it is a barge-in, not a request
        assertTrue(WakeWord.overlapped(false, 1000, r.wakeAt + 100));
        assertFalse(WakeWord.overlapped(false, 1000, 2090));
    }

    @Test
    public void aSensitivityChangeResetsAPartialRun() {
        Rig r = new Rig();
        r.rule = WakeLoop.Rule.LOW;
        r.warm();
        r.hops(2, 0.9f);
        r.rule = WakeLoop.Rule.MEDIUM;
        r.hops(2, 0.9f);
        assertEquals(0, r.wakes);
        r.hops(1, 0.9f);
        assertEquals(1, r.wakes);
    }

    @Test
    public void theRuleIsReadForEachHop() {
        Rig r = new Rig();
        r.rule = WakeLoop.Rule.LOW;
        r.warm();
        r.hops(5, 0.6f);
        assertEquals(0, r.wakes);
        r.rule = WakeLoop.Rule.MEDIUM; // the setting changed: the count starts over, three more hops wake
        r.hops(3, 0.6f);
        assertEquals(1, r.wakes);
    }
}
