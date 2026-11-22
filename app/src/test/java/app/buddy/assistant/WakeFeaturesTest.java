package app.buddy.assistant;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class WakeFeaturesTest {
    /** Records what the pipeline hands each model; mel value of frame f is 10*f, so after x/10+2 it is f+2. */
    private static final class Fake implements WakeFeatures.Engine {
        int mels, embeds, scores;
        float[] lastAudio, lastWindows, lastScoreIn;
        float next = 0.9f;

        @Override
        public float[] mel(float[] audio) {
            mels++;
            lastAudio = audio.clone();
            float[] m = new float[WakeFeatures.FRAMES * WakeFeatures.BINS];
            for (int f = 0; f < WakeFeatures.FRAMES; f++) java.util.Arrays.fill(m, f * WakeFeatures.BINS, (f + 1) * WakeFeatures.BINS, f * 10f);
            return m;
        }

        @Override
        public float[] embed(float[] windows) {
            embeds++;
            lastWindows = windows.clone();
            float[] e = new float[WakeFeatures.PER_HOP * WakeFeatures.EMB];
            java.util.Arrays.fill(e, embeds); // this hop's group is filled with its own number
            return e;
        }

        @Override
        public float score(float[] embeddings) {
            scores++;
            lastScoreIn = embeddings.clone();
            return next;
        }
    }

    private static short[] ramp(int from, int n) {
        short[] s = new short[n];
        for (int i = 0; i < n; i++) s[i] = (short) (from + i);
        return s;
    }

    @Test
    public void hopsOnlyAfterAFullWindowThenEvery120ms() {
        WakeFeatures f = new WakeFeatures(new Fake());
        assertEquals(-1f, f.feed(ramp(0, 17279), 17279), 0);
        assertEquals(0f, f.feed(ramp(17279, 1), 1), 0); // first hop; history not full yet
        assertEquals(-1f, f.feed(ramp(0, 1919), 1919), 0);
        assertEquals(0f, f.feed(ramp(0, 1), 1), 0);
    }

    @Test
    public void classifierSeesTheLastFourGroupsOldestFirst() {
        Fake e = new Fake();
        WakeFeatures f = new WakeFeatures(e);
        f.feed(ramp(0, 17280), 17280);
        for (int i = 0; i < 2; i++) assertEquals(0f, f.feed(new short[1920], 1920), 0);
        assertEquals(0, e.scores);
        assertEquals(0.9f, f.feed(new short[1920], 1920), 0); // hop 4: four groups in
        assertEquals(1, e.scores);
        assertEquals(16 * WakeFeatures.EMB, e.lastScoreIn.length);
        for (int g = 0; g < 4; g++) assertEquals(g + 1f, e.lastScoreIn[g * 4 * WakeFeatures.EMB], 0);
        f.feed(new short[1920], 1920); // hop 5: group 1 drops out
        assertEquals(2f, e.lastScoreIn[0], 0);
        assertEquals(5f, e.lastScoreIn[e.lastScoreIn.length - 1], 0);
    }

    @Test
    public void bigBlockReturnsTheBestOfItsHops() {
        Fake e = new Fake();
        WakeFeatures f = new WakeFeatures(e);
        int n = 17280 + 3 * 1920; // 4 hops in one call; only the last one has four groups
        assertEquals(0.9f, f.feed(new short[n], n), 0);
        assertEquals(4, e.mels);
    }

    @Test
    public void melWindowIsTheLastSecondAndMelIsRescaled() {
        Fake e = new Fake();
        WakeFeatures f = new WakeFeatures(e);
        f.feed(ramp(0, 19200), 19200); // hops at 17280 and 19200
        assertEquals(17280, e.lastAudio.length);
        assertEquals(19200 - 17280, e.lastAudio[0], 0);
        assertEquals(19199, e.lastAudio[17279], 0);
        // four 76-frame windows at frame offsets 0, 8, 16, 24; frame f has value f + 2 after x/10 + 2
        int win = WakeFeatures.FRAME_LEN * WakeFeatures.BINS;
        assertEquals(4 * win, e.lastWindows.length);
        for (int w = 0; w < 4; w++) {
            assertEquals(w * 8 + 2, e.lastWindows[w * win], 1e-4);
            assertEquals(w * 8 + 75 + 2, e.lastWindows[(w + 1) * win - 1], 1e-4);
        }
    }

    @Test
    public void preRollIsTheLast1500msAndResetForgetsIt() {
        WakeFeatures f = new WakeFeatures(new Fake());
        f.feed(ramp(0, 100), 100);
        assertEquals(100, f.preRoll().length);
        f.feed(ramp(100, 30000), 30000);
        short[] p = f.preRoll();
        assertEquals(24000, p.length);
        assertEquals((short) (30100 - 24000), p[0]);
        assertEquals((short) 30099, p[23999]);
        f.reset();
        assertEquals(0, f.preRoll().length);
        assertEquals(-1f, f.feed(ramp(0, 17279), 17279), 0); // a window must fill again
    }

    @Test
    public void onlyTheFirstNSamplesOfABlockCount() {
        WakeFeatures f = new WakeFeatures(new Fake());
        f.feed(ramp(0, 50), 10);
        assertArrayEquals(ramp(0, 10), f.preRoll());
    }
}
