package app.buddy.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class ReadQueueTest {
    private static final class FakeStream implements ReadQueue.Stream {
        final String node;
        final ReadQueue.Events ev;
        boolean closed, cancelled;

        FakeStream(String node, ReadQueue.Events ev) {
            this.node = node;
            this.ev = ev;
        }

        @Override public void pcm(byte[] b) {}
        @Override public void close() { closed = true; }
        @Override public void cancel() { cancelled = true; }
    }

    /** What the queue asked for, in order, as short strings; the test fires the callbacks. */
    private static final class Fake implements ReadQueue.Out {
        final List<String> log = new ArrayList<>();
        final List<Consumer<String>> finds = new ArrayList<>();
        final List<FakeStream> streams = new ArrayList<>();
        final List<Runnable> chatgptFailed = new ArrayList<>(), ttsDone = new ArrayList<>(), timers = new ArrayList<>();

        @Override public void find(Consumer<String> node) { log.add("find"); finds.add(node); }

        @Override
        public ReadQueue.Stream open(String node, ReadQueue.Events e) {
            FakeStream s = new FakeStream(node, e);
            streams.add(s);
            log.add("open " + node);
            return s;
        }

        @Override
        public void chatgpt(String text, ReadQueue.Stream s, Runnable failed) {
            log.add("chatgpt " + text);
            chatgptFailed.add(failed);
        }

        @Override
        public void tts(String text, ReadQueue.Stream s, Runnable done) {
            log.add((s == null ? "phone " : "tts ") + text);
            ttsDone.add(done);
        }

        @Override public void stopProducers(ReadQueue.Stream s) { log.add("stopProducers"); }
        @Override public void after(long ms, Runnable r) { timers.add(r); }
        @Override public String notice() { return "NOTICE"; }
        @Override public boolean watchWins() { return watchWins; }
        @Override public boolean phoneBusy() { return phoneBusy; }
        @Override public void here(String text) { log.add("here " + text); }

        boolean watchWins = true, phoneBusy = false;
        void runWait() { timers.get(timers.size() - 1).run(); }

        Consumer<String> lastFind() { return finds.get(finds.size() - 1); }
        FakeStream lastStream() { return streams.get(streams.size() - 1); }
        Runnable lastDone() { return ttsDone.get(ttsDone.size() - 1); }
    }

    private final Fake f = new Fake();
    private final ReadQueue q = new ReadQueue(f);

    @Test
    public void textsAreReadInOrderOneAtATime() {
        q.speak("a", false);
        q.speak("b", false);
        assertEquals("[find]", f.log.toString());
        f.lastFind().accept("w");
        f.lastDone().run();
        assertTrue(f.lastStream().closed);
        assertEquals(1, f.finds.size()); // b waits until a has settled
        f.lastStream().ev.played();
        assertEquals("[find, open w, tts a, find]", f.log.toString());
        f.lastFind().accept("w");
        assertEquals("tts b", f.log.get(f.log.size() - 1));
    }

    @Test
    public void aSecondTextWaitsForAColdEngine() {
        q.speak("a", false);
        q.speak("b", false);
        f.lastFind().accept("w"); // tts a is asked, and does not answer yet
        assertEquals(1, f.ttsDone.size());
        assertEquals(1, f.finds.size());
        f.lastDone().run();
        f.lastStream().ev.played();
        f.lastFind().accept("w");
        assertEquals("tts b", f.log.get(f.log.size() - 1));
    }

    @Test
    public void noWatchMeansTheNoticeThenTheReplyOnThePhone() {
        q.speak("a", false);
        f.lastFind().accept(null);
        assertEquals("[find, phone NOTICE]", f.log.toString());
        f.lastDone().run();
        assertEquals("phone a", f.log.get(f.log.size() - 1));
        f.lastDone().run();
        q.speak("b", false); // a second one in a row: no notice
        f.lastFind().accept(null);
        assertEquals("phone b", f.log.get(f.log.size() - 1));
        f.lastDone().run();
        // a reading the watch plays makes the notice due again
        q.speak("c", false);
        f.lastFind().accept("w");
        f.lastDone().run();
        f.lastStream().ev.played();
        q.speak("d", false);
        f.lastFind().accept(null);
        assertEquals("phone NOTICE", f.log.get(f.log.size() - 1));
    }

    @Test
    public void refusedGoesToThePhoneAfterStoppingTheProducers() {
        q.speak("a", false);
        f.lastFind().accept("w");
        f.lastStream().ev.refused();
        assertTrue(f.lastStream().cancelled);
        assertEquals("[find, open w, tts a, stopProducers, phone NOTICE]", f.log.toString());
    }

    @Test
    public void brokenAndPlayedDoNotReadOnThePhone() {
        q.speak("a", false);
        f.lastFind().accept("w");
        f.lastStream().ev.broken();
        assertEquals("[find, open w, tts a, stopProducers]", f.log.toString());
        assertFalse(q.active());
        q.speak("b", false);
        f.lastFind().accept("w");
        f.lastStream().ev.played();
        assertFalse(f.log.toString().contains("phone"));
        assertFalse(q.active());
    }

    @Test
    public void chatgptFailingBeforeItStartsFallsBackToThePhoneVoiceOnTheSameWatchOnce() {
        q.speak("a", true);
        f.lastFind().accept("w");
        FakeStream first = f.lastStream();
        assertEquals("chatgpt a", f.log.get(f.log.size() - 1));
        f.chatgptFailed.get(0).run();
        assertTrue(first.cancelled);
        assertEquals("[find, open w, chatgpt a, open w, tts a]", f.log.toString());
        first.ev.refused(); // the old stream's late event
        first.ev.played();
        assertEquals(5, f.log.size());
        f.lastStream().ev.refused(); // the second stream is refused: the phone, once, no loop
        assertEquals("[find, open w, chatgpt a, open w, tts a, stopProducers, phone NOTICE]", f.log.toString());
        assertEquals(2, f.streams.size());
    }

    @Test
    public void ttsDoneClosesTheStream() {
        q.speak("a", false);
        f.lastFind().accept("w");
        assertFalse(f.lastStream().closed);
        f.lastDone().run();
        assertTrue(f.lastStream().closed);
    }

    @Test
    public void theCapEndsAReadingAndLetsTheNextOneStart() {
        q.speak("a", false);
        q.speak("b", false);
        f.lastFind().accept("w");
        FakeStream s = f.lastStream();
        f.timers.get(0).run();
        assertTrue(s.cancelled);
        assertEquals("[find, open w, tts a, stopProducers, find]", f.log.toString()); // and b started
        assertEquals(2, f.finds.size());
        f.timers.get(0).run(); // the cap of a again, after it ended: nothing
        assertEquals(2, f.finds.size());
        assertTrue(q.active());
        s.ev.played(); // and a's late event does nothing
        assertEquals(2, f.finds.size());
    }

    @Test
    public void theCapAlsoCutsAReadingOnThePhone() {
        q.speak("a", false);
        f.lastFind().accept(null);
        f.timers.get(0).run();
        assertEquals("stopProducers", f.log.get(f.log.size() - 1));
        f.lastDone().run(); // the notice's late done does nothing
        assertEquals(1, f.ttsDone.size());
        assertFalse(q.active());
    }

    @Test
    public void stopDuringFindIgnoresTheLateNode() {
        q.speak("a", false);
        q.stop();
        assertFalse(q.active());
        f.lastFind().accept("w");
        assertEquals("[find, stopProducers]", f.log.toString()); // nothing opened
    }

    @Test
    public void stopDuringATtsCancelsAndIgnoresItsLateDone() {
        q.speak("a", false);
        f.lastFind().accept("w");
        FakeStream s = f.lastStream();
        q.stop();
        assertTrue(s.cancelled);
        assertEquals("stopProducers", f.log.get(f.log.size() - 1));
        f.lastDone().run();
        s.ev.refused();
        assertFalse(s.closed);
        assertEquals("[find, open w, tts a, stopProducers]", f.log.toString());
        assertFalse(q.active());
    }

    @Test
    public void stopThenANewTextStartsFreshAndOldCallbacksDoNothing() {
        q.speak("a", false);
        Consumer<String> oldFind = f.lastFind();
        q.stop();
        q.speak("b", false);
        assertEquals(2, f.finds.size());
        oldFind.accept("w"); // a's late node
        assertEquals("[find, stopProducers, find]", f.log.toString());
        f.timers.get(0).run(); // a's cap
        assertTrue(q.active());
        f.lastFind().accept("w");
        FakeStream s = f.lastStream();
        Runnable oldDone = f.lastDone();
        q.stop();
        q.speak("c", false);
        oldDone.run();
        s.ev.played();
        assertFalse(s.closed);
        assertEquals(3, f.finds.size()); // c's find, nothing else started
    }

    @Test
    public void activeFromSpeakUntilTheLastTextSettles() {
        assertFalse(q.active());
        q.speak("a", false);
        assertTrue(q.active());
        f.lastFind().accept("w");
        f.lastStream().ev.played();
        assertFalse(q.active());
    }

    // ---- 016: quiet readings (Watch not picked: Auto's step)

    @Test
    public void quietNoWatchReadsOnThePhoneWithNoNotice() {
        q.speak("a", false, true);
        q.speak("b", false, true);
        f.lastFind().accept(null);
        assertEquals("[find, phone a]", f.log.toString());
        assertEquals(1, f.finds.size()); // b's find waits for a's done
        f.lastDone().run();
        assertEquals("find", f.log.get(f.log.size() - 1));
    }

    @Test
    public void quietChatgptFallbackEndsAtOnce() {
        q.speak("a", true, true);
        q.speak("b", true, true);
        f.lastFind().accept(null);
        assertEquals("[find, here a, find]", f.log.toString());
    }

    @Test
    public void quietRefusedGoesToThePhoneQuietly() {
        q.speak("a", false, true);
        f.lastFind().accept("w");
        f.lastStream().ev.refused();
        assertEquals("[find, open w, tts a, stopProducers, phone a]", f.log.toString());
    }

    @Test
    public void quietWithAWatchPlaysOnIt() {
        q.speak("a", false, true);
        f.lastFind().accept("w");
        assertEquals("[find, open w, tts a]", f.log.toString());
    }

    @Test
    public void aQuietFallbackDoesNotUseUpTheNotice() {
        q.speak("a", false, true);
        f.lastFind().accept(null);
        f.lastDone().run();
        q.speak("b", false); // picked Watch, no node
        f.lastFind().accept(null);
        assertEquals("phone NOTICE", f.log.get(f.log.size() - 1));
    }

    @Test
    public void theRouteIsDecidedWhenTheReadingStarts() {
        q.speak("a", false, true);
        q.speak("b", false, true);
        f.lastFind().accept("w");
        f.lastDone().run();
        f.watchWins = false; // the buds came on while a played
        f.lastStream().ev.played();
        assertEquals("[find, open w, tts a, phone b]", f.log.toString()); // no find for b
    }

    @Test
    public void theRouteIsDecidedAgainWhenTheFindReturns() {
        q.speak("a", false, true);
        f.watchWins = false;
        f.lastFind().accept("w");
        assertEquals("[find, phone a]", f.log.toString());
    }

    @Test
    public void aQuietReadingWaitsWhileThePhoneSpeaks() {
        f.watchWins = false; // the buds are in
        q.speak("a", true, true);
        assertEquals("[here a]", f.log.toString());
        f.phoneBusy = true; // a's voice is still going
        f.watchWins = true; // the buds left
        q.speak("b", true, true);
        assertEquals("[here a]", f.log.toString());
        f.runWait();
        assertEquals("[here a]", f.log.toString());
        f.phoneBusy = false;
        f.runWait();
        assertEquals("[here a, find]", f.log.toString());
        f.lastFind().accept("w");
        assertEquals("[here a, find, open w, chatgpt b]", f.log.toString());
    }

    @Test
    public void aQuietReadingWaitingForThePhoneFollowsARouteThatChangesDuringTheWait() {
        f.phoneBusy = true;
        f.watchWins = false;
        q.speak("b", true, true);
        f.watchWins = true;
        f.phoneBusy = false;
        f.runWait();
        assertEquals("[find]", f.log.toString());
        f.lastFind().accept("w");
        assertEquals("[find, open w, chatgpt b]", f.log.toString());
    }

    @Test
    public void aQuietReadingThatStaysWithThePhoneReadsThereAfterTheWait() {
        f.phoneBusy = true;
        f.watchWins = false;
        q.speak("b", true, true);
        f.phoneBusy = false;
        f.runWait();
        assertEquals("[here b]", f.log.toString());
    }

    @Test
    public void thePhoneGettingBusyDuringTheFindWaitsAndAsksAgain() {
        q.speak("a", false, true);
        f.phoneBusy = true;
        f.lastFind().accept("w");
        assertEquals("[find]", f.log.toString());
        f.phoneBusy = false;
        f.runWait();
        assertEquals("[find, find]", f.log.toString());
        f.lastFind().accept("w");
        assertEquals("[find, find, open w, tts a]", f.log.toString());
    }

    @Test
    public void aPickedWatchIgnoresAPhoneThatIsBusy() {
        f.phoneBusy = true;
        q.speak("a", false);
        assertEquals("[find]", f.log.toString());
    }
}
