package app.buddy.assistant;

import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** [DEBUG-hb34] Diagnostic only: per-hop scores of the real models on speech without the wake phrase. Never merged. */
public class FalseWakeDiagTest {
    static short[] ulaw(String name) throws Exception {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        try (InputStream in = FalseWakeDiagTest.class.getResourceAsStream("/wakeword/" + name)) {
            byte[] b = new byte[8192];
            for (int n; (n = in.read(b)) > 0; ) all.write(b, 0, n);
        }
        byte[] u = all.toByteArray();
        short[] s = new short[u.length];
        for (int i = 0; i < u.length; i++) {
            int x = ~u[i] & 0xff;
            int t = ((x & 0x0f) << 3) + 0x84;
            t <<= (x & 0x70) >> 4;
            s[i] = (short) ((x & 0x80) != 0 ? 0x84 - t : t - 0x84);
        }
        return s;
    }

    static float[] hops(short[] pcm) {
        WakeFeatures f = new WakeFeatures(OnnxWakeEngineTest.engine);
        List<Float> out = new ArrayList<>();
        for (int i = 0; i + 640 <= pcm.length; i += 640) {
            float v = f.feed(java.util.Arrays.copyOfRange(pcm, i, i + 640), 640);
            if (v >= 0) out.add(v);
        }
        float[] a = new float[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    /** Wakes under "n consecutive hops >= t"; after a wake, 12 hops (1.4 s) are skipped, as the loop refills after a drop. */
    static int wakes(float[] h, float t, int n) {
        int w = 0, run = 0;
        for (int i = 0; i < h.length; i++) {
            run = h[i] >= t ? run + 1 : 0;
            if (run >= n) {
                w++;
                run = 0;
                i += 12;
            }
        }
        return w;
    }

    static int maxRun(float[] h, float t) {
        int best = 0, run = 0;
        for (float v : h) {
            run = v >= t ? run + 1 : 0;
            best = Math.max(best, run);
        }
        return best;
    }

    static String stats(String name, float[] h) {
        float max = 0;
        for (float v : h) max = Math.max(max, v);
        StringBuilder sb = new StringBuilder(String.format("%n%s: hops %d (%.1f min), max %.3f;", name, h.length, h.length * 0.12 / 60, max));
        float[] ts = {0.05f, 0.08f, 0.12f, 0.2f, 0.3f, 0.4f, 0.5f, 0.7f};
        for (float t : ts) {
            sb.append(String.format(" t%.2f:", t));
            for (int n = 1; n <= 4; n++) sb.append(n == 1 ? "" : "/").append(wakes(h, t, n));
            sb.append(" run").append(maxRun(h, t));
        }
        return sb.toString();
    }

    static String around(String name, float[] h) {
        int p = 0;
        for (int i = 0; i < h.length; i++) if (h[i] > h[p]) p = i;
        StringBuilder sb = new StringBuilder(String.format("%n%s peak hops:", name));
        for (int i = Math.max(0, p - 5); i < Math.min(h.length, p + 6); i++) sb.append(String.format(" %.3f", h[i]));
        return sb.toString();
    }

    @Test
    public void report() throws Exception {
        OnnxWakeEngineTest.load();
        short[] speech = ulaw("librispeech.ulaw");
        short[] loud = new short[speech.length], noisy = new short[speech.length], quiet = new short[speech.length];
        Random r = new Random(7);
        for (int i = 0; i < speech.length; i++) {
            loud[i] = (short) Math.max(-32768, Math.min(32767, speech[i] * 3));
            noisy[i] = (short) Math.max(-32768, Math.min(32767, speech[i] + r.nextGaussian() * 800));
            quiet[i] = (short) (speech[i] / 4);
        }
        StringBuilder sb = new StringBuilder("[DEBUG-hb34] wakes per rule t:n=1/2/3/4, run = longest run of hops >= t");
        sb.append(stats("speech", hops(speech)));
        sb.append(stats("speech x3 clipped", hops(loud)));
        sb.append(stats("speech + noise", hops(noisy)));
        sb.append(stats("speech /4", hops(quiet)));
        for (String c : new String[]{"hey_buddy.wav", "hey_buddy_augmented.wav", "hey_buddy_adv.wav"}) {
            float[] h = hops(OnnxWakeEngineTest.clip(c));
            sb.append(stats(c, h)).append(around(c, h));
        }
        OnnxWakeEngineTest.close();
        fail(sb.toString());
    }
}
