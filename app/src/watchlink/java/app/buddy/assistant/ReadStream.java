package app.buddy.assistant;

import java.io.OutputStream;
import java.util.concurrent.Executor;

/**
 * One reading's audio to the watch (docs/features/012-watch-audio-device/plan.md, "Watch link, phone side"). Owns the
 * outcome: the watch confirms with the channel's close code (2 played, 1 nothing played), and the stream says so to
 * its {@link ReadQueue.Events} once, or never if it was cancelled. Finishing the audio says nothing about playback.
 * No Google types here, so it is unit-tested.
 */
final class ReadStream implements ReadQueue.Stream {
    static final int PLAYED = 2, NOT_PLAYED = 1;
    /** How long PcmOut's failure waits for the watch's code before the reading counts as broken. */
    static final long CLOSE_GRACE_MS = 3000;

    interface Clock {
        void after(long ms, Runnable r);
    }

    private final ReadQueue.Events events;
    private final Executor exec;
    private final Clock clock;
    private final Runnable ended;
    private final PcmOut out;
    private final Object lock = new Object();
    private PcmOut.Closer real; // the channel's close, once it is open
    private boolean held, settled, cancelled;
    private long written;

    /** exec: where events are delivered (the main thread). ended: runs once when the stream settled or was cancelled. */
    ReadStream(ReadQueue.Events events, Executor exec, Clock clock, int cap, long abandonMs, Runnable ended) {
        this.events = events;
        this.exec = exec;
        this.clock = clock;
        this.ended = ended;
        out = new PcmOut(cap, false, abandonMs, () -> clock.after(CLOSE_GRACE_MS, this::graceExpired));
    }

    /** The channel is open. */
    void attached(OutputStream o, PcmOut.Closer closer) {
        synchronized (lock) {
            real = closer;
        }
        out.attach(o, this::hold);
    }

    /**
     * PcmOut's closer. Once the stream has settled (or was cancelled) it closes the channel at once; before, it keeps the
     * close: PcmOut closes after a failed write or an overflow, and the watch may still answer NOT_PLAYED.
     */
    private void hold() {
        PcmOut.Closer c;
        synchronized (lock) {
            if (!settled) {
                held = true;
                return;
            }
            c = real;
        }
        c.close();
    }

    @Override
    public void pcm(byte[] pcm16k) {
        synchronized (lock) {
            written += pcm16k.length;
        }
        out.write(pcm16k);
    }

    /** The producer has finished. */
    @Override
    public void close() {
        boolean none;
        synchronized (lock) {
            none = written == 0;
        }
        if (none) {
            if (settle(Outcome.REFUSED)) out.cancel();
        } else {
            out.finish();
        }
    }

    /** The channel closed: local says by us (never the watch's answer), code is the app specific error code. */
    void closed(boolean local, int code) {
        if (local) return;
        if (settle(code == PLAYED ? Outcome.PLAYED : code == NOT_PLAYED ? Outcome.REFUSED : Outcome.BROKEN)) out.cancel();
    }

    /** The channel did not open in time. */
    void openTimedOut() {
        synchronized (lock) {
            if (real != null) return;
        }
        if (settle(Outcome.REFUSED)) out.cancel();
    }

    /** No code came in the grace after PcmOut's failure. */
    void graceExpired() {
        if (settle(Outcome.BROKEN)) out.cancel();
    }

    /** Settles silently: no event follows, even one already handed to the executor. */
    @Override
    public void cancel() {
        PcmOut.Closer c = null;
        boolean first;
        synchronized (lock) {
            first = !settled;
            cancelled = settled = true;
            if (held) {
                held = false;
                c = real;
            }
        }
        if (c != null) c.close();
        out.cancel();
        if (first) ended.run();
    }

    private enum Outcome { PLAYED, REFUSED, BROKEN }

    private boolean settle(Outcome o) {
        PcmOut.Closer c = null;
        synchronized (lock) {
            if (settled) return false;
            settled = true;
            if (held) {
                held = false;
                c = real;
            }
        }
        if (c != null) c.close();
        ended.run();
        exec.execute(() -> {
            synchronized (lock) {
                if (cancelled) return;
            }
            if (o == Outcome.PLAYED) events.played();
            else if (o == Outcome.REFUSED) events.refused();
            else events.broken();
        });
        return true;
    }
}
