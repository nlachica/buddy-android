package app.buddy.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Random;

/** The real models through {@link WakeFeatures} on real clips (hey-buddy dataset examples, CC BY 4.0). */
public class OnnxWakeEngineTest {
    static final Path ASSETS = Paths.get("src/wakeword/assets/wakeword"); // the module directory is the working directory
    static OnnxWakeEngine engine;

    @BeforeClass
    public static void load() throws Exception {
        engine = new OnnxWakeEngine(Files.readAllBytes(ASSETS.resolve("hey-buddy.onnx")),
                Files.readAllBytes(ASSETS.resolve("mel-spectrogram.onnx")), Files.readAllBytes(ASSETS.resolve("speech-embedding.onnx")));
    }

    @AfterClass
    public static void close() throws Exception {
        engine.close();
    }

    static short[] clip(String name) throws IOException {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        try (InputStream in = OnnxWakeEngineTest.class.getResourceAsStream("/wakeword/" + name)) {
            byte[] b = new byte[8192];
            for (int n; (n = in.read(b)) > 0; ) all.write(b, 0, n);
        }
        ByteBuffer w = ByteBuffer.wrap(all.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
        int p = 12; // RIFF header; then chunks until "data"
        while (p + 8 <= w.limit()) {
            String id = new String(w.array(), p, 4, StandardCharsets.US_ASCII);
            int size = w.getInt(p + 4);
            if (id.equals("data")) {
                short[] s = new short[size / 2];
                w.position(p + 8);
                w.asShortBuffer().get(s);
                return s;
            }
            p += 8 + size + (size & 1);
        }
        throw new IOException("no data chunk in " + name);
    }

    /** The highest score over the audio fed in 40 ms blocks, as the service does. */
    static float peak(short[] pcm) {
        WakeFeatures f = new WakeFeatures(engine);
        float best = 0;
        for (int i = 0; i + 640 <= pcm.length; i += 640) best = Math.max(best, f.feed(java.util.Arrays.copyOfRange(pcm, i, i + 640), 640));
        return best;
    }

    @Test
    public void modelFilesMatchTheirSums() throws Exception {
        int seen = 0;
        for (String line : Files.readAllLines(ASSETS.resolve("SHA256SUMS"))) {
            String[] p = line.trim().split("\\s+");
            byte[] d = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(ASSETS.resolve(p[1])));
            StringBuilder hex = new StringBuilder();
            for (byte x : d) hex.append(String.format("%02x", x));
            assertEquals(p[1], p[0], hex.toString());
            seen++;
        }
        assertEquals(3, seen);
    }

    @Test
    public void positiveClipsDetect() throws Exception {
        float a = peak(clip("hey_buddy.wav")), b = peak(clip("hey_buddy_augmented.wav"));
        System.out.println("hey_buddy peak " + a + ", augmented " + b);
        assertTrue("hey_buddy " + a, a >= WakeFeatures.THRESHOLD);
        assertTrue("hey_buddy_augmented " + b, b >= WakeFeatures.THRESHOLD);
    }

    @Test
    public void soundAlikeSilenceAndNoiseDoNot() throws Exception {
        float adv = peak(clip("hey_buddy_adv.wav")), silence = peak(new short[16000 * 10]);
        short[] noise = new short[16000 * 10];
        Random r = new Random(1);
        for (int i = 0; i < noise.length; i++) noise[i] = (short) (r.nextGaussian() * 3000);
        float n = peak(noise);
        System.out.println("adv peak " + adv + ", silence " + silence + ", noise " + n);
        assertTrue("sound-alike " + adv, adv < WakeFeatures.THRESHOLD);
        assertTrue("silence " + silence, silence < WakeFeatures.THRESHOLD);
        assertTrue("noise " + n, n < WakeFeatures.THRESHOLD);
    }
}
