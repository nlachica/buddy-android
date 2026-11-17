package app.buddy.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ReadStreamTest {
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final List<Runnable> queued = new ArrayList<>();
    private final AtomicInteger closes = new AtomicInteger(), ends = new AtomicInteger();
    private final CountDownLatch graceScheduled = new CountDownLatch(1);
    private final List<Runnable> grace = new CopyOnWriteArrayList<>();
    private final List<Boolean> stops = new CopyOnWriteArrayList<>();

    private final ReadQueue.Events recorder = new ReadQueue.Events() {
        @Override public void played() { events.add("played"); }
        @Override public void refused() { events.add("refused"); }
        @Override public void broken() { events.add("broken"); }
        @Override public void broken(boolean stopped) { stops.add(stopped); broken(); }
    };

    private ReadStream stream(int cap) {
        return new ReadStream(recorder, Runnable::run, (ms, r) -> {
            assertEquals(ReadStream.CLOSE_GRACE_MS, ms);
            grace.add(r);
            graceScheduled.countDown();
        }, cap, 60_000, ends::incrementAndGet);
    }

    private ReadStream stream() {
        return stream(1 << 20);
    }

    private void attach(ReadStream s, OutputStream o) {
        s.attached(o, closes::incrementAndGet);
    }

    /** A write fails once something is written. */
    private static final class Throwing extends OutputStream {
        @Override public void write(int b) throws IOException { throw new IOException("peer gone"); }
        @Override public void write(byte[] b, int off, int len) throws IOException { throw new IOException("peer gone"); }
    }

    private void awaitGrace() throws InterruptedException {
        assertTrue("PcmOut's failure never reached onBroken", graceScheduled.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void notOpenInTimeIsRefusedOnceAndALateAttachClosesTheChannel() {
        ReadStream s = stream();
        s.openTimedOut();
        s.openTimedOut();
        assertEquals("[refused]", events.toString());
        attach(s, new ByteArrayOutputStream());
        assertEquals(1, closes.get());
    }

    @Test
    public void timedOutAfterItOpenedDoesNothing() {
        ReadStream s = stream();
        attach(s, new ByteArrayOutputStream());
        s.openTimedOut();
        assertEquals("[]", events.toString());
    }

    @Test
    public void closeWithNothingWrittenIsRefused() {
        ReadStream s = stream();
        attach(s, new ByteArrayOutputStream());
        s.close();
        assertEquals("[refused]", events.toString());
        assertEquals(1, closes.get());
    }

    @Test
    public void closeAfterAudioSaysNothingUntilTheWatchAnswers() {
        ReadStream s = stream();
        attach(s, new ByteArrayOutputStream());
        s.pcm(new byte[100]);
        s.close();
        assertEquals("[]", events.toString());
        s.closed(false, 2);
        assertEquals("[played]", events.toString());
    }

    @Test
    public void codeOneIsRefusedBeforeAndAfterClose() {
        ReadStream a = stream();
        attach(a, new ByteArrayOutputStream());
        a.pcm(new byte[10]);
        a.closed(false, 1);
        assertEquals("[refused]", events.toString());
        events.clear();
        ReadStream b = stream();
        attach(b, new ByteArrayOutputStream());
        b.pcm(new byte[10]);
        b.close();
        b.closed(false, 1);
        assertEquals("[refused]", events.toString());
    }

    @Test
    public void otherRemoteClosesAreBrokenAndOurOwnCloseIsNothing() {
        ReadStream a = stream();
        attach(a, new ByteArrayOutputStream());
        a.pcm(new byte[10]);
        a.close();
        a.closed(false, 0); // a disconnect or a hush after finish
        assertEquals("[broken]", events.toString());
        events.clear();
        ReadStream b = stream();
        attach(b, new ByteArrayOutputStream());
        b.pcm(new byte[10]);
        b.closed(false, 0); // before finish
        assertEquals("[broken]", events.toString());
        events.clear();
        ReadStream c = stream();
        attach(c, new ByteArrayOutputStream());
        c.pcm(new byte[10]);
        c.close();
        c.closed(true, 0);
        assertEquals("[]", events.toString());
    }

    @Test
    public void aFailedWriteHoldsTheCloseSoTheWatchCanStillRefuse() throws Exception {
        ReadStream s = stream();
        attach(s, new Throwing());
        s.pcm(new byte[100]);
        awaitGrace();
        assertEquals(0, closes.get()); // PcmOut's close is kept
        assertEquals("[]", events.toString());
        s.closed(true, 0); // our own close notification: not an answer
        s.closed(false, 1);
        assertEquals("[refused]", events.toString());
        assertEquals(1, closes.get()); // the held close ran
        grace.get(0).run(); // too late: nothing more
        assertEquals("[refused]", events.toString());
    }

    @Test
    public void codeOneThenTheWriteFailureIsOneRefusal() throws Exception {
        final CountDownLatch writing = new CountDownLatch(1), go = new CountDownLatch(1);
        ReadStream s = stream();
        attach(s, new OutputStream() {
            @Override public void write(int b) throws IOException { write(new byte[]{(byte) b}, 0, 1); }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                writing.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    // cancelled by the refusal: fails like a closed channel
                }
                throw new IOException("closed");
            }
        });
        s.pcm(new byte[100]);
        assertTrue(writing.await(5, TimeUnit.SECONDS));
        s.closed(false, 1);
        go.countDown();
        Thread.sleep(100);
        assertEquals("[refused]", events.toString());
    }

    @Test
    public void onlyTheWatchsOwnHushCountsAsItsPlayerBeingStopped() throws Exception {
        ReadStream a = stream();
        attach(a, new ByteArrayOutputStream());
        a.pcm(new byte[10]);
        a.closed(false, 0, false); // the watch closed the channel itself
        ReadStream b = stream();
        attach(b, new ByteArrayOutputStream());
        b.pcm(new byte[10]);
        b.closed(false, 0, true); // a disconnect carries the same code
        ReadStream c = stream();
        attach(c, new Throwing());
        c.pcm(new byte[100]);
        awaitGrace();
        grace.get(0).run(); // no code at all
        assertEquals("[true, false, false]", stops.toString());
    }

    @Test
    public void aFailedWriteWithNoCodeIsBrokenAfterTheGraceAndTheHeldCloseRuns() throws Exception {
        ReadStream s = stream();
        attach(s, new Throwing());
        s.pcm(new byte[100]);
        awaitGrace();
        assertEquals("[]", events.toString());
        grace.get(0).run();
        assertEquals("[broken]", events.toString());
        assertEquals(1, closes.get());
    }

    @Test
    public void anOverflowIsBrokenAfterTheGrace() throws Exception {
        ReadStream s = stream(10);
        attach(s, new ByteArrayOutputStream());
        s.pcm(new byte[100]);
        awaitGrace();
        assertEquals(0, closes.get());
        grace.get(0).run();
        assertEquals("[broken]", events.toString());
        assertEquals(1, closes.get());
    }

    @Test
    public void cancelClosesAtOnceAndNothingFollowsIt() {
        ReadStream s = new ReadStream(recorder, queued::add, (ms, r) -> grace.add(r), 1 << 20, 60_000, ends::incrementAndGet);
        attach(s, new ByteArrayOutputStream());
        s.pcm(new byte[10]);
        s.closed(false, 2); // an outcome already handed to the executor...
        s.cancel(); // ...is dropped by cancel
        assertEquals(1, closes.get());
        for (Runnable r : queued) r.run();
        s.closed(false, 1);
        s.openTimedOut();
        s.close();
        assertEquals("[]", events.toString());
        assertEquals(1, ends.get());
    }

    @Test
    public void cancelWithoutAnythingElseEndsOnceAndNeverSpeaks() {
        ReadStream s = stream();
        attach(s, new ByteArrayOutputStream());
        s.cancel();
        s.cancel();
        s.closed(false, 1);
        assertEquals("[]", events.toString());
        assertEquals(1, ends.get());
        assertEquals(1, closes.get());
    }
}
