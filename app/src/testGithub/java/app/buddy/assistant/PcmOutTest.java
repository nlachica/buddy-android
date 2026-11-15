package app.buddy.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class PcmOutTest {
    /** A consumer that never reads: a write blocks until the stream is closed, like a peer that stopped reading. */
    private static final class Stuck extends OutputStream {
        final CountDownLatch closed = new CountDownLatch(1), writing = new CountDownLatch(1);

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            writing.countDown();
            try {
                closed.await();
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
            throw new IOException("closed");
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    /** Collects what is written; write blocks while the gate is shut (a slow consumer). */
    private static final class Slow extends OutputStream {
        final ByteArrayOutputStream got = new ByteArrayOutputStream();
        volatile boolean closedStream;
        final CountDownLatch gate;

        Slow(CountDownLatch gate) {
            this.gate = gate;
        }

        @Override
        public void write(int b) {
            got.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            try {
                gate.await();
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
            synchronized (got) {
                got.write(b, off, len);
            }
        }

        @Override
        public void close() {
            closedStream = true;
        }

        int size() {
            synchronized (got) {
                return got.size();
            }
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier c, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (!c.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(5);
    }

    @Test
    public void neverReadingConsumerKeepsQueueUnderTheCap() throws Exception {
        AtomicInteger broken = new AtomicInteger();
        PcmOut p = new PcmOut(10_000, true, 60_000, broken::incrementAndGet);
        Stuck s = new Stuck();
        p.attach(s, () -> {
        });
        for (int i = 0; i < 100; i++) p.write(new byte[1000]);
        assertTrue(p.queued() <= 10_000);
        assertEquals(0, broken.get()); // a call drops the oldest instead
        p.cancel();
    }

    @Test
    public void replyIsBrokenOnceWhenPastTheCap() throws Exception {
        AtomicInteger broken = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        PcmOut p = new PcmOut(10_000, false, 60_000, broken::incrementAndGet);
        p.attach(new Stuck(), closed::incrementAndGet);
        for (int i = 0; i < 50; i++) p.write(new byte[1000]);
        assertEquals(1, broken.get());
        assertEquals(1, closed.get());
        assertTrue(p.done());
        assertEquals(0, p.queued());
    }

    @Test
    public void cancelFreesAWriteBlockedOnThePeer() throws Exception {
        AtomicInteger closed = new AtomicInteger();
        PcmOut p = new PcmOut(10_000, true, 60_000, () -> {
        });
        Stuck s = new Stuck();
        p.attach(s, closed::incrementAndGet);
        p.write(new byte[100]);
        assertTrue(s.writing.await(1, TimeUnit.SECONDS)); // the writer thread is stuck in write()
        long t = System.currentTimeMillis();
        p.cancel();
        assertEquals(1, closed.get());
        assertTrue(s.closed.await(1, TimeUnit.SECONDS));
        waitFor(() -> !p.writerAlive(), 1000);
        assertFalse(p.writerAlive());
        assertTrue(System.currentTimeMillis() - t < 1500);
    }

    @Test
    public void finishDrainsWhatASlowConsumerReadsThenClosesOnlyTheStream() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        Slow s = new Slow(gate);
        AtomicInteger closed = new AtomicInteger(), broken = new AtomicInteger();
        PcmOut p = new PcmOut(100_000, false, 60_000, broken::incrementAndGet);
        p.attach(s, closed::incrementAndGet);
        for (int i = 0; i < 5; i++) p.write(new byte[1000]);
        p.finish();
        p.write(new byte[1000]); // after finish: ignored
        gate.countDown();
        waitFor(() -> s.closedStream, 2000);
        assertEquals(5000, s.size());
        assertTrue(s.closedStream);
        assertEquals(0, closed.get()); // the channel stays open until the watch closes it
        assertEquals(0, broken.get());
        p.peerClosed(); // the watch read the end and closed: normal
        assertEquals(0, broken.get());
        assertEquals(0, closed.get());
    }

    @Test
    public void abandonedChannelIsClosedAfterTheBound() throws Exception {
        AtomicInteger closed = new AtomicInteger(), broken = new AtomicInteger();
        PcmOut p = new PcmOut(100_000, false, 150, broken::incrementAndGet);
        p.attach(new ByteArrayOutputStream(), closed::incrementAndGet);
        p.write(new byte[10]);
        p.finish();
        assertEquals(0, closed.get());
        waitFor(() -> closed.get() > 0, 2000);
        assertEquals(1, closed.get());
        assertEquals(0, broken.get());
    }

    @Test
    public void peerClosingFirstNeverRunsTheCloser() throws Exception {
        AtomicInteger closed = new AtomicInteger(), broken = new AtomicInteger();
        PcmOut p = new PcmOut(100_000, false, 150, broken::incrementAndGet);
        p.attach(new ByteArrayOutputStream(), closed::incrementAndGet);
        p.finish();
        p.peerClosed();
        Thread.sleep(400); // past the abandoned bound
        assertEquals(0, closed.get());
        assertEquals(0, broken.get());
        assertTrue(p.done());
    }

    @Test
    public void watchClosingBeforeFinishIsBrokenOnceAndNoCloserCall() throws Exception {
        AtomicInteger closed = new AtomicInteger(), broken = new AtomicInteger();
        PcmOut p = new PcmOut(100_000, false, 60_000, broken::incrementAndGet);
        p.write(new byte[10]); // a queued reply the watch already closed
        p.peerClosed();
        p.peerClosed();
        assertEquals(1, broken.get());
        p.attach(new ByteArrayOutputStream(), closed::incrementAndGet); // opened late: closed at once
        assertEquals(1, closed.get());
    }
}
