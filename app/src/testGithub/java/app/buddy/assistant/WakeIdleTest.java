package app.buddy.assistant;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Random;

/** The real models in {@link WakeLoop}: idle audio never reaches MicServer; after a wake the pre-roll comes first. */
public class WakeIdleTest {
    private static final Path ASSETS = Paths.get("src/wakeword/assets/wakeword");
    private static OnnxWakeEngine engine;
    private int wakes;
    private WakeLoop.Rule rule = WakeLoop.Rule.MEDIUM;

    @BeforeClass
    public static void load() throws Exception {
        engine = new OnnxWakeEngine(Files.readAllBytes(ASSETS.resolve("hey-buddy.onnx")),
                Files.readAllBytes(ASSETS.resolve("mel-spectrogram.onnx")), Files.readAllBytes(ASSETS.resolve("speech-embedding.onnx")));
    }

    @AfterClass
    public static void close() throws Exception {
        engine.close();
    }

    @After
    public void clean() {
        MicServer.endCapture();
    }

    private WakeLoop loop() {
        return new WakeLoop(new WakeFeatures(engine), MicServer::feed, new WakeLoop.Host() {
            @Override
            public WakeLoop.Rule rule() {
                return rule;
            }

            @Override
            public void log(String line) {
                System.out.println("HeyBuddy " + line);
            }

            @Override
            public void onWake(int seq, long detectedAt) {
                wakes++;
            }

            @Override
            public void onHoldExpired(int seq) {
            }

            @Override
            public void onFailed(RuntimeException e) {
                throw e; // a model that can't run fails the test
            }
        });
    }

    /** Feeds 40 ms blocks; returns the sample index after the block that caused the first wake, or -1. */
    private int run(WakeLoop loop, short[] pcm) {
        int wakeEnd = -1;
        for (int i = 0; i + 640 <= pcm.length; i += 640) {
            loop.block(Arrays.copyOfRange(pcm, i, i + 640), 640, i);
            if (wakes > 0 && wakeEnd < 0) wakeEnd = i + 640;
        }
        return wakeEnd;
    }

    @Test
    public void soundAlikeNoiseAndSilenceNeverReachMicServer() throws Exception {
        short[] noise = new short[16000 * 10];
        Random r = new Random(2);
        for (int i = 0; i < noise.length; i++) noise[i] = (short) (r.nextGaussian() * 3000);
        for (short[] pcm : new short[][]{OnnxWakeEngineTest.clip("hey_buddy_adv.wav"), noise, new short[16000 * 10]}) {
            WakeLoop loop = loop();
            run(loop, pcm);
            assertEquals(0, wakes);
            assertEquals(WakeLoop.Mode.DETECT, loop.mode());
            assertFalse(MicServer.adopted());
            assertEquals(0, MicServer.pull(100000).length);
        }
    }

    @Test
    public void afterAWakeThePreRollComesFirstThenTheRestInOrder() throws Exception {
        short[] pcm = OnnxWakeEngineTest.clip("hey_buddy.wav");
        WakeLoop loop = loop();
        int wakeEnd = run(loop, pcm); // the main thread is slow: the whole clip passes while held
        assertTrue("no wake", wakeEnd > 0);
        assertEquals(1, wakes);
        assertEquals(0, MicServer.pull(100000).length); // nothing before adopt
        int cap = MicServer.adopt();
        assertTrue(loop.feedTo(cap));
        int end = pcm.length - pcm.length % 640;
        int pre = Math.min(wakeEnd, 24000); // at most 1.5 s = 48000 bytes
        byte[] want = new byte[(end - (wakeEnd - pre)) * 2];
        for (int i = 0; i < want.length / 2; i++) {
            short v = pcm[wakeEnd - pre + i];
            want[2 * i] = (byte) v;
            want[2 * i + 1] = (byte) (v >> 8);
        }
        assertTrue(pre * 2 <= 48000);
        assertArrayEquals(want, MicServer.pull(cap, want.length + 100));
    }

    /** G.711 mu-law, 8 bit, 16 kHz mono (the LibriSpeech clip). */
    private static short[] ulaw(String name) throws Exception {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        try (InputStream in = WakeIdleTest.class.getResourceAsStream("/wakeword/" + name)) {
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

    /** White noise of this sd on top of the audio, clipped. */
    private static short[] mix(short[] pcm, int sd, long seed) {
        Random r = new Random(seed);
        short[] out = new short[pcm.length];
        for (int i = 0; i < pcm.length; i++) out[i] = (short) Math.max(-32768, Math.min(32767, pcm[i] + r.nextGaussian() * sd));
        return out;
    }

    private int wakesOn(short[] pcm, WakeLoop.Rule r) {
        wakes = 0;
        rule = r;
        run(loop(), pcm);
        return wakes;
    }

    /** 034: 3 min of speech in noise: the old rule (one hop at 0.12) wakes, medium and low never do. */
    @Test
    public void speechInNoiseWakesTheOldRuleButNotMediumOrLow() throws Exception {
        short[] pcm = mix(ulaw("librispeech-3min.ulaw"), 800, 33);
        int old = wakesOn(pcm, new WakeLoop.Rule("old", 0.12f, 1));
        assertTrue("the clip must be a real challenge: old rule wakes " + old, old >= 1);
        assertEquals(0, wakesOn(pcm, WakeLoop.Rule.MEDIUM));
        assertEquals(0, wakesOn(pcm, WakeLoop.Rule.LOW));
    }

    @Test
    public void heyBuddyWakesOnceAtEveryLevel() throws Exception {
        short[] pcm = OnnxWakeEngineTest.clip("hey_buddy.wav");
        for (WakeLoop.Rule r : new WakeLoop.Rule[]{WakeLoop.Rule.LOW, WakeLoop.Rule.MEDIUM, WakeLoop.Rule.HIGH}) {
            assertEquals(r.name, 1, wakesOn(pcm, r));
            MicServer.endCapture();
        }
    }
}
