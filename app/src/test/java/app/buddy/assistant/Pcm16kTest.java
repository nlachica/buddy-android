package app.buddy.assistant;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.media.AudioFormat;

import org.junit.Test;

import java.io.ByteArrayOutputStream;

public class Pcm16kTest {
    private static byte[] le16(short... s) {
        byte[] b = new byte[s.length * 2];
        for (int i = 0; i < s.length; i++) {
            b[2 * i] = (byte) s[i];
            b[2 * i + 1] = (byte) (s[i] >> 8);
        }
        return b;
    }

    private static short[] shorts(byte[] b) {
        short[] s = new short[b.length / 2];
        for (int i = 0; i < s.length; i++) s[i] = (short) ((b[2 * i] & 0xff) | (b[2 * i + 1] << 8));
        return s;
    }

    /** A 1 kHz tone of {@code seconds} at {@code rate}, 16-bit mono. */
    private static byte[] tone(int rate, double seconds) {
        short[] s = new short[(int) (rate * seconds)];
        for (int i = 0; i < s.length; i++) s[i] = (short) (12000 * Math.sin(2 * Math.PI * 1000 * i / rate));
        return le16(s);
    }

    private static int crossings(short[] s) {
        int n = 0;
        for (int i = 1; i < s.length; i++) if ((s[i - 1] < 0) != (s[i] < 0)) n++;
        return n;
    }

    @Test
    public void sixteenKilohertzMonoPassesThrough() {
        byte[] in = le16((short) 0, (short) 1000, (short) -32768, (short) 32767, (short) 5);
        assertArrayEquals(in, new Pcm16k(16000, AudioFormat.ENCODING_PCM_16BIT, 1).feed(in, 0, in.length));
    }

    @Test
    public void otherRatesGiveTheRightLengthAndKeepTheTone() {
        for (int rate : new int[]{24000, 22050}) {
            byte[] in = tone(rate, 1);
            short[] out = shorts(new Pcm16k(rate, AudioFormat.ENCODING_PCM_16BIT, 1).feed(in, 0, in.length));
            assertTrue(rate + ": " + out.length, Math.abs(out.length - 16000) <= 1);
            assertTrue(rate + ": " + crossings(out), Math.abs(crossings(out) - 2000) <= 3); // 1 kHz = 2000 crossings/s
        }
    }

    @Test
    public void stereoIsAveraged() {
        byte[] in = le16((short) 1000, (short) 3000, (short) -200, (short) 400);
        assertArrayEquals(le16((short) 2000, (short) 100),
                new Pcm16k(16000, AudioFormat.ENCODING_PCM_16BIT, 2).feed(in, 0, in.length));
    }

    @Test
    public void eightBitAndFloatGiveTheSame16BitValues() {
        byte[] u8 = {(byte) 128, (byte) 192, 64, (byte) 255};
        assertArrayEquals(le16((short) 0, (short) 16384, (short) -16384, (short) 32512),
                new Pcm16k(16000, AudioFormat.ENCODING_PCM_8BIT, 1).feed(u8, 0, u8.length));
        float[] f = {0f, 0.5f, -0.5f, 2f}; // 2f clips
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (float v : f) {
            int bits = Float.floatToIntBits(v);
            o.write(bits);
            o.write(bits >> 8);
            o.write(bits >> 16);
            o.write(bits >> 24);
        }
        byte[] in = o.toByteArray();
        assertArrayEquals(le16((short) 0, (short) 16384, (short) -16384, (short) 32767),
                new Pcm16k(16000, AudioFormat.ENCODING_PCM_FLOAT, 1).feed(in, 0, in.length));
    }

    @Test
    public void feedingInPiecesEqualsFeedingAtOnce() {
        for (int[] cfg : new int[][]{{24000, 1}, {22050, 2}}) {
            byte[] in = tone(cfg[0], 0.3);
            if (cfg[1] == 2) { // the same tone on both channels
                byte[] st = new byte[in.length * 2];
                for (int i = 0; i < in.length; i += 2) {
                    st[2 * i] = st[2 * i + 2] = in[i];
                    st[2 * i + 1] = st[2 * i + 3] = in[i + 1];
                }
                in = st;
            }
            byte[] whole = new Pcm16k(cfg[0], AudioFormat.ENCODING_PCM_16BIT, cfg[1]).feed(in, 0, in.length);
            Pcm16k p = new Pcm16k(cfg[0], AudioFormat.ENCODING_PCM_16BIT, cfg[1]);
            ByteArrayOutputStream pieces = new ByteArrayOutputStream();
            int[] cuts = {1, 3, 7, 1, 100, 33};
            for (int at = 0, i = 0; at < in.length; i++) {
                int n = Math.min(cuts[i % cuts.length], in.length - at);
                byte[] out = p.feed(in, at, n);
                pieces.write(out, 0, out.length);
                at += n;
            }
            // the phase is a double carried across chunks: a sample may differ by one step in the last place
            short[] a = shorts(whole), b = shorts(pieces.toByteArray());
            assertEquals("rate " + cfg[0] + " ch " + cfg[1], a.length, b.length);
            for (int i = 0; i < a.length; i++) assertTrue("sample " + i, Math.abs(a[i] - b[i]) <= 1);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void anUnknownEncodingIsRefused() {
        new Pcm16k(16000, 99, 1);
    }

    @Test
    public void emptyInputGivesEmptyOutput() {
        assertEquals(0, new Pcm16k(24000, AudioFormat.ENCODING_PCM_16BIT, 1).feed(new byte[0], 0, 0).length);
    }
}
