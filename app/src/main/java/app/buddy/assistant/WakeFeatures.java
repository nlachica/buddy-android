package app.buddy.assistant;

/**
 * Turns 16 kHz mono PCM into the "hey buddy" score, the way the model was trained (hey-buddy's embeddings.py): every
 * 1920 samples (120 ms) the last 1.08 s go through the mel model, four 76-frame windows of that through the embedding
 * model, and the last four such groups (16 x 96) through the classifier. Pure Java; the three models sit behind
 * {@link Engine} so the framing is testable without them.
 */
final class WakeFeatures {
    interface Engine {
        /** [17280] samples (int16 range as floats) to the mel model's raw output, 105 frames x 32 bins. */
        float[] mel(float[] audio);

        /** [4][76][32] mel values to 4 x 96 embeddings. */
        float[] embed(float[] windows);

        /** [16][96] embeddings to one score, 0..1. */
        float score(float[] embeddings);
    }

    /**
     * A score at or above this is a detection. 0.12, not the library's 0.5: CI peaks on the real clips were 0.991 (clean),
     * 0.172 (augmented: clipped and 3x louder) and 0.057 (sound-alike); 0.12 sits between the last two. Silence and
     * noise are asserted below it in OnnxWakeEngineTest. False accepts in daily use are a post-release device check (P-checks).
     */
    static final float THRESHOLD = 0.12f;
    static final int HOP = 1920, WINDOW = 17280, FRAMES = 105, BINS = 32, FRAME_LEN = 76, FRAME_STEP = 8;
    /** Samples go to the models in int16 range, as in training (embeddings.py); OnnxWakeEngineTest on real clips is the arbiter. */
    static final float SCALE = 1f;
    static final int EMB = 96, PER_HOP = 4, GROUPS = 4;
    /** The pre-roll: 1.5 s, which also holds the 1.08 s window. */
    static final int RING = 24000;

    private final Engine engine;
    private final short[] ring = new short[RING];
    private int end, filled, sinceHop; // end: next write index
    private final float[] audio = new float[WINDOW];
    private final float[] history = new float[GROUPS * PER_HOP * EMB]; // newest group last
    private int groups;

    WakeFeatures(Engine engine) {
        this.engine = engine;
    }

    /**
     * Adds samples; returns the highest score of the hops that completed in this call, or -1 when none did. A hop
     * completes every 1920 samples once a full window has been heard and four groups are in; before that its score is 0.
     */
    float feed(short[] pcm, int n) {
        float best = -1;
        for (int i = 0; i < n; i++) {
            ring[end] = pcm[i];
            end = (end + 1) % RING;
            if (filled < RING) filled++;
            if (++sinceHop >= HOP && filled >= WINDOW) {
                sinceHop = 0;
                best = Math.max(best, hop());
            }
        }
        return best;
    }

    private float hop() {
        for (int i = 0; i < WINDOW; i++) audio[i] = ring[(end - WINDOW + i + RING) % RING] * SCALE;
        float[] mel = engine.mel(audio);
        for (int i = 0; i < mel.length; i++) mel[i] = mel[i] / 10f + 2f;
        int win = FRAME_LEN * BINS;
        float[] windows = new float[PER_HOP * win];
        for (int w = 0; w < PER_HOP; w++) System.arraycopy(mel, w * FRAME_STEP * BINS, windows, w * win, win);
        float[] emb = engine.embed(windows);
        int group = PER_HOP * EMB;
        System.arraycopy(history, group, history, 0, history.length - group);
        System.arraycopy(emb, 0, history, history.length - group, group);
        if (groups < GROUPS) groups++;
        return groups < GROUPS ? 0f : engine.score(history);
    }

    /** The last up to 1.5 s heard, oldest first: it starts before the wake phrase and includes it. */
    short[] preRoll() {
        short[] out = new short[filled];
        for (int i = 0; i < filled; i++) out[i] = ring[(end - filled + i + RING) % RING];
        return out;
    }

    /** Forget everything heard: nothing before this can trigger a detection. */
    void reset() {
        end = filled = sinceHop = groups = 0;
        java.util.Arrays.fill(history, 0f);
    }
}
