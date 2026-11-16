package app.buddy.assistant;

import android.media.AudioFormat;

import java.util.Arrays;

/**
 * Turns a TTS engine's PCM (8-bit unsigned, 16-bit LE or float; any rate; 1 or 2 channels) into the watch's format:
 * 16-bit mono 16 kHz little endian. Linear interpolation with the phase kept across chunks, the same method as
 * CodexSpeech.TAP_JS; chunks may split a sample anywhere. One instance per utterance.
 */
final class Pcm16k {
    private final int rate, channels, size;
    private final int encoding;
    private final byte[] frame;
    private int have;
    private double pos, prev; // pos: where the next output sample falls, relative to the next chunk's first sample

    Pcm16k(int rate, int encoding, int channels) {
        if (rate <= 0 || channels < 1 || channels > 2) throw new IllegalArgumentException("pcm " + rate + "/" + channels);
        size = encoding == AudioFormat.ENCODING_PCM_8BIT ? 1 : encoding == AudioFormat.ENCODING_PCM_16BIT ? 2
                : encoding == AudioFormat.ENCODING_PCM_FLOAT ? 4 : 0;
        if (size == 0) throw new IllegalArgumentException("pcm encoding " + encoding);
        this.rate = rate;
        this.encoding = encoding;
        this.channels = channels;
        frame = new byte[size * channels];
    }

    /** The converted audio for b[off, off+len) (may be empty). */
    byte[] feed(byte[] b, int off, int len) {
        double[] in = new double[(have + len) / frame.length + 1];
        int n = 0;
        for (int i = off; i < off + len; i++) {
            frame[have++] = b[i];
            if (have == frame.length) {
                in[n++] = mono();
                have = 0;
            }
        }
        double ratio = rate / 16000.0;
        byte[] out = new byte[2 * (int) (n / ratio + 2)];
        int c = 0;
        if (rate == 16000) { // as is: interpolation would delay it by a sample
            for (int i = 0; i < n; i++) c = put(out, c, in[i]);
        } else if (n > 0) {
            double x = pos;
            for (; x < n - 1; x += ratio) {
                int k = (int) Math.floor(x);
                double t = x - k;
                c = put(out, c, (k < 0 ? prev : in[k]) * (1 - t) + in[k + 1] * t);
            }
            pos = x - n;
            prev = in[n - 1];
        }
        return Arrays.copyOf(out, 2 * c);
    }

    /** Writes a sample at out[2c], returns the next index. */
    private static int put(byte[] out, int c, double v) {
        int s = (int) Math.max(-32768, Math.min(32767, Math.round(v)));
        out[2 * c] = (byte) s;
        out[2 * c + 1] = (byte) (s >> 8);
        return c + 1;
    }

    /** The frame in frame[], as one 16-bit-scale sample (channels averaged). */
    private double mono() {
        double sum = 0;
        for (int ch = 0; ch < channels; ch++) {
            int o = ch * size;
            if (size == 1) sum += ((frame[o] & 0xff) - 128) * 256;
            else if (encoding == AudioFormat.ENCODING_PCM_16BIT) sum += (short) ((frame[o] & 0xff) | (frame[o + 1] << 8));
            else sum += Float.intBitsToFloat((frame[o] & 0xff) | (frame[o + 1] & 0xff) << 8
                    | (frame[o + 2] & 0xff) << 16 | (frame[o + 3] & 0xff) << 24) * 32768.0;
        }
        return sum / channels;
    }
}
