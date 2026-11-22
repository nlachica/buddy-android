package app.buddy.assistant;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicReference;

public class MicServerTest {
    @After
    public void clean() {
        MicServer.endCapture();
        MicServer.levels = null;
    }

    private static byte[] b(int... v) {
        byte[] r = new byte[v.length];
        for (int i = 0; i < v.length; i++) r[i] = (byte) v[i];
        return r;
    }

    @Test
    public void adoptedCaptureIsFedAndPulledByItsOwner() {
        int a = MicServer.adopt();
        assertTrue(a > 0);
        assertTrue(MicServer.adopted());
        assertTrue(MicServer.recording());
        assertTrue(MicServer.feed(a, b(1, 2)));
        assertTrue(MicServer.feed(a, b(3, 4)));
        assertArrayEquals(b(1, 2, 3), MicServer.pull(a, 3));
        assertArrayEquals(b(4), MicServer.pull(a, 10));
        assertEquals(0, MicServer.pull(a, 10).length);
    }

    @Test
    public void staleCaptureIsRefusedAndTouchesNothing() {
        int a = MicServer.adopt();
        MicServer.feed(a, b(1, 2));
        MicServer.endCapture();
        int c = MicServer.adopt();
        assertNotEquals(a, c);
        MicServer.feed(c, b(9, 8));
        assertFalse(MicServer.feed(a, b(7, 7)));
        assertNull(MicServer.pull(a, 10));
        MicServer.release(a); // the old turn's cleanup must not end the new capture
        assertTrue(MicServer.adopted());
        assertArrayEquals(b(9, 8), MicServer.pull(c, 10)); // whole, cursor not moved by the stale calls
    }

    @Test
    public void releaseEndsOnlyTheCurrentCaptureAndAdoptWorksAgain() {
        int a = MicServer.adopt();
        MicServer.release(a);
        assertFalse(MicServer.adopted());
        assertFalse(MicServer.feed(a, b(1, 1)));
        int c = MicServer.adopt();
        assertTrue(MicServer.feed(c, b(5, 6)));
        assertArrayEquals(b(5, 6), MicServer.pull(c, 10));
    }

    @Test
    public void feedIsRefusedOnceInputEnded() {
        int a = MicServer.adopt();
        assertTrue(MicServer.feed(a, b(1, 2)));
        MicServer.stopCapture(); // the engine marked the end of input
        assertFalse(MicServer.feed(a, b(3, 4)));
        assertArrayEquals(b(1, 2), MicServer.pull(a, 10)); // what was captured is still readable
    }

    @Test
    public void feedReportsLoudnessLikeARecorder() {
        AtomicReference<Float> seen = new AtomicReference<>();
        MicServer.levels = seen::set;
        int a = MicServer.adopt();
        MicServer.feed(a, b(0, 0x40, 0, 0x40));
        assertTrue(seen.get() > 0.5f);
        seen.set(null);
        MicServer.feed(a + 1, b(0, 0x40)); // stale: no level
        assertNull(seen.get());
    }

    @Test
    public void sharedWithTheTerminalOnlyForClaudesDictation() {
        assertTrue(MicServer.shares(true, true, 16000, 1));
        assertFalse(MicServer.shares(true, false, 16000, 1)); // a Phone or ChatGPT turn's buffer stays Buddy's
        assertFalse(MicServer.shares(false, true, 16000, 1));
        assertFalse(MicServer.shares(true, true, 44100, 1));
        assertFalse(MicServer.shares(true, true, 16000, 2));
    }

    private static final class Sink extends OutputStream {
        final ByteArrayOutputStream got = new ByteArrayOutputStream();

        @Override
        public synchronized void write(int v) {
            got.write(v);
        }

        @Override
        public synchronized void write(byte[] x, int off, int len) {
            got.write(x, off, len);
        }

        synchronized int size() {
            return got.size();
        }

        synchronized byte[] bytes() {
            return got.toByteArray();
        }
    }

    private static void until(java.util.function.BooleanSupplier c) throws Exception {
        for (int i = 0; i < 200 && !c.getAsBoolean(); i++) Thread.sleep(10);
        assertTrue(c.getAsBoolean());
    }

    @Test
    public void socketReaderIsBoundToItsCapture() throws Exception {
        int a = MicServer.adopt();
        MicServer.feed(a, b(1, 2));
        Sink out = new Sink();
        AtomicReference<Throwable> err = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try {
                MicServer.streamCapture(out, a);
            } catch (Throwable t) {
                err.set(t);
            }
        });
        reader.start();
        until(() -> out.size() == 2);
        MicServer.release(a);
        int c = MicServer.adopt(); // a new capture while the reader is still attached
        MicServer.feed(c, b(7, 7, 7, 7));
        MicServer.stopCapture();
        reader.join(2000);
        assertFalse(reader.isAlive());
        assertNull(err.get());
        assertArrayEquals(b(1, 2), out.bytes()); // none of c's audio
        assertFalse(MicServer.drained); // and c was not marked as drained
    }

    @Test
    public void readerOfAFinishedCaptureMarksItDrained() throws Exception {
        int a = MicServer.adopt();
        MicServer.feed(a, b(1, 2));
        Sink out = new Sink();
        Thread reader = new Thread(() -> {
            try {
                MicServer.streamCapture(out, a);
            } catch (Exception ignored) {
            }
        });
        reader.start();
        until(() -> out.size() == 2);
        MicServer.stopCapture(); // the user finished: the reader sends silence once everything is out
        until(() -> MicServer.drained);
        MicServer.release(a);
        reader.join(2000);
        assertFalse(reader.isAlive());
    }
}
