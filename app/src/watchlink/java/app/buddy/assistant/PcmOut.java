package app.buddy.assistant;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Audio to the watch over one channel's OutputStream (docs/features/007-watch-live-voice/plan.md, "Audio format and
 * transport"). {@link #write} never blocks (its caller is the WebView's JavaBridge thread): a bounded queue and a writer
 * thread. A call ({@code live}) drops the oldest audio past the cap; a reply is broken past it. A normal end is
 * {@link #finish}: the queue drains and only the OutputStream is closed (closing the channel would make the watch's
 * reads fail); the watch closes the channel after reading the end, or {@code abandonMs} later it is cancelled.
 * {@link #cancel} drops everything and closes the stream and the channel. No Google types here, so it is unit-tested.
 */
final class PcmOut {
    /** Closes the channel. */
    interface Closer {
        void close();
    }

    private static final ScheduledThreadPoolExecutor timers = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "pcm-out-timer");
        t.setDaemon(true);
        return t;
    });

    static {
        timers.setRemoveOnCancelPolicy(true);
    }

    private final int cap;
    private final boolean live;
    private final long abandonMs;
    private final Runnable onBroken;
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private int queued;
    private OutputStream out;
    private Closer closer;
    private Thread writer;
    private ScheduledFuture<?> abandon;
    private boolean finished, done;

    /** onBroken runs once, on overflow, a write failing, or the watch closing the channel before finish(). */
    PcmOut(int cap, boolean live, long abandonMs, Runnable onBroken) {
        this.cap = cap;
        this.live = live;
        this.abandonMs = abandonMs;
        this.onBroken = onBroken;
    }

    /** The channel is open: audio queued until now is written. If this was cancelled meanwhile, the channel closes. */
    void attach(OutputStream o, Closer c) {
        synchronized (this) {
            if (!done) {
                out = o;
                closer = c;
                writer = new Thread(this::run, "pcm-out");
                writer.start();
                return;
            }
        }
        try {
            o.close();
        } catch (IOException ignored) {
        }
        c.close();
    }

    /** Queue audio (any thread, never blocks). */
    void write(byte[] pcm) {
        boolean over = false;
        synchronized (this) {
            if (done || finished) return;
            queue.add(pcm);
            queued += pcm.length;
            if (queued > cap) {
                if (live) {
                    while (queued > cap && !queue.isEmpty()) queued -= queue.poll().length;
                } else {
                    over = true;
                }
            }
            notifyAll();
        }
        if (over && stop(true)) onBroken.run();
    }

    /** No more audio: the queue drains, then the stream ends (the channel stays open until the watch closes it). */
    void finish() {
        synchronized (this) {
            if (done || finished) return;
            finished = true;
            abandon = timers.schedule(() -> stop(true), abandonMs, TimeUnit.MILLISECONDS);
            notifyAll();
        }
    }

    /** Discard the queue and close the stream and the channel at once (this also frees a write stuck on the peer). */
    void cancel() {
        stop(true);
    }

    /** The watch closed the channel: before finish() it is broken; after, it is the normal end. */
    void peerClosed() {
        boolean wasFinished;
        synchronized (this) {
            wasFinished = finished;
        }
        if (stop(false) && !wasFinished) onBroken.run();
    }

    /** The audio can't be delivered (the channel never opened): cancel, and tell the owner once. */
    void broken() {
        if (stop(true)) onBroken.run();
    }

    /** Whether this was cancelled, broken, or has ended. */
    synchronized boolean done() {
        return done;
    }

    /** Whether the writer thread is still running (for tests). */
    synchronized boolean writerAlive() {
        return writer != null && writer.isAlive();
    }

    /** Bytes waiting (for tests). */
    synchronized int queued() {
        return queued;
    }

    private boolean stop(boolean closeChannel) {
        OutputStream o;
        Closer c;
        Thread w;
        synchronized (this) {
            if (done) return false;
            done = true;
            queue.clear();
            queued = 0;
            if (abandon != null) abandon.cancel(false);
            o = out;
            c = closer;
            w = writer;
            notifyAll();
        }
        if (closeChannel && c != null) c.close();
        if (o != null) try {
            o.close();
        } catch (IOException ignored) {
        }
        if (w != null) w.interrupt();
        return true;
    }

    private void run() {
        try {
            while (true) {
                byte[] b;
                synchronized (this) {
                    while (queue.isEmpty() && !finished && !done) wait();
                    if (done) return;
                    b = queue.poll();
                    if (b != null) queued -= b.length;
                }
                if (b == null) { // finished and drained: the end of the stream, the channel stays open
                    out.close();
                    return;
                }
                out.write(b);
                out.flush();
            }
        } catch (InterruptedException ignored) {
        } catch (IOException e) {
            if (stop(true)) onBroken.run();
        }
    }
}
