package app.buddy.assistant.wear;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;

/**
 * Plays a stream of PCM 16-bit mono 16 kHz (what the phone sends for a call or a reply, docs/features/007-watch-live-voice/
 * plan.md) through an AudioTrack. It starts after about 300 ms are buffered, and again after running dry. {@code live}
 * (a call) drops audio that doesn't fit in a second, to stay a conversation; not live (a reply) never drops: a full
 * track blocks the reading thread, which pushes back through the channel to the phone. It reports the first audible
 * block as it is written (what the voice owns a reply by), whether sound is playing now, and how the stream ended.
 */
final class Speaker {
    interface Listener {
        /** The first audible block was written (reader thread). */
        void audible();

        /** The stream ended: "eof" (the phone's normal end, all played) or "closed" (it broke); not after stop(). */
        void ended(String by);
    }

    private static final int RATE = 16000, BYTES_PER_MS = RATE * 2 / 1000;
    private static final int PREBUFFER = 300 * BYTES_PER_MS, BUFFER = 1000 * BYTES_PER_MS;
    /** The phone page's own "audible" threshold (CodexSpeech.java), as a 16-bit sample. */
    private static final int AUDIBLE = (int) (0.01 * 32768);

    private final boolean live;
    private final String tag;
    private final Listener listener;
    private volatile boolean stopped;
    private volatile String endedBy = "";
    private volatile long audibleUntil;
    private volatile InputStream in;
    private volatile AudioTrack track;
    private long received, played, dropped, audibleAt = -1;

    Speaker(boolean live, String tag, Listener listener) {
        this.live = live;
        this.tag = tag;
        this.listener = listener;
    }

    /** Reads and plays this stream on a thread of its own. */
    void play(InputStream stream) {
        in = stream;
        if (stopped) { // stopped before the stream came
            try {
                stream.close();
            } catch (IOException ignored) {
            }
            return;
        }
        new Thread(this::run, "speaker").start();
    }

    /** Sound is coming out of the speaker now (the last audible block written is still playing). */
    boolean audibleNow() {
        return SystemClock.elapsedRealtime() < audibleUntil;
    }

    /** Silence now and let go ("say", "timeout" or "hush", for the log line); the stream is closed. */
    void stop(String by) {
        if (stopped) return;
        endedBy = by;
        stopped = true;
        try {
            if (in != null) in.close();
        } catch (IOException ignored) {
        }
        AudioTrack t = track;
        if (t != null) try {
            t.pause();
            t.flush(); // frees a write blocked on a full track
        } catch (IllegalStateException ignored) {
        }
    }

    private void run() {
        final long t0 = SystemClock.elapsedRealtime();
        AudioTrack t = null;
        String by = "closed";
        try {
            t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(BUFFER).setTransferMode(AudioTrack.MODE_STREAM).build();
            track = t;
            if (stopped) return;
            long frames = 0; // written to the track
            boolean playing = false;
            byte[] buf = new byte[1280]; // 40 ms
            int have = 0;
            while (!stopped) {
                int n = in.read(buf, have, buf.length - have);
                if (n < 0) {
                    by = "eof";
                    break;
                }
                received += n;
                n += have;
                have = n & 1; // an odd byte waits for its other half
                n -= have;
                if (n > 0) {
                    if (playing && t.getPlaybackHeadPosition() >= frames) { // ran dry: buffer again before going on
                        t.pause();
                        playing = false;
                    }
                    int len = write(t, buf, n);
                    frames += len / 2;
                    if (!playing && (frames - t.getPlaybackHeadPosition()) * 2 >= PREBUFFER) {
                        t.play();
                        playing = true;
                    }
                    if (len > 0 && peak(buf, len) > AUDIBLE) {
                        long lag = (frames - t.getPlaybackHeadPosition()) * 1000 / RATE;
                        audibleUntil = SystemClock.elapsedRealtime() + lag;
                        if (audibleAt < 0) {
                            audibleAt = SystemClock.elapsedRealtime() - t0;
                            listener.audible();
                        }
                    }
                }
                if (have == 1) buf[0] = buf[n];
            }
            if ("eof".equals(by) && !stopped) { // the end of the stream: let what is buffered play out
                if (!playing) t.play();
                long end = SystemClock.elapsedRealtime() + 2000 + BUFFER / BYTES_PER_MS;
                while (!stopped && t.getPlaybackHeadPosition() < frames && SystemClock.elapsedRealtime() < end) {
                    Thread.sleep(20);
                }
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            by = "closed";
        } finally {
            if (t != null) t.release();
            audibleUntil = 0;
            if (stopped) by = endedBy;
            Log.i("BuddyTalk", tag + ": received " + received + ", played " + played + ", dropped " + dropped
                    + ", audible " + (audibleAt < 0 ? "never" : "at " + audibleAt + " ms") + ", ended by " + by);
            if (!stopped) listener.ended(by);
        }
    }

    /** Writes what fits; a call drops the rest, a reply waits for room. Returns the bytes taken. */
    private int write(AudioTrack t, byte[] b, int n) {
        int w = t.write(b, 0, n, live ? AudioTrack.WRITE_NON_BLOCKING : AudioTrack.WRITE_BLOCKING);
        if (w < 0) throw new IllegalStateException("AudioTrack " + w);
        played += w;
        dropped += n - w;
        return w;
    }

    private static int peak(byte[] b, int n) {
        int m = 0;
        for (int i = 0; i + 1 < n; i += 2) m = Math.max(m, Math.abs((short) ((b[i] & 0xff) | (b[i + 1] << 8))));
        return m;
    }
}
