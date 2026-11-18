package app.buddy.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.media.AudioDeviceInfo;
import android.media.AudioManager;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class LiveWatchTest {
    private static final long TAIL = LiveWatch.TAIL_MS, FAIL = LiveWatch.FAIL_TAIL_MS, RETRY = LiveWatch.RETRY_MS,
            QUIET_MS = LiveWatch.QUIET_MS, POLL = LiveWatch.POLL_MS;

    private static final class Reading implements ReadQueue.Stream {
        final ReadQueue.Events events;
        final Runnable attached;
        final List<byte[]> got = new ArrayList<>();
        boolean closed, cancelled;

        Reading(ReadQueue.Events events, Runnable attached) {
            this.events = events;
            this.attached = attached;
        }

        @Override public void pcm(byte[] b) { got.add(b); }
        @Override public void close() { closed = true; }
        @Override public void cancel() { cancelled = true; }
    }

    private static final class Fake implements LiveWatch.Out {
        String saved = "watch", node = "n1";
        boolean wins = true, holdFind;
        long now;
        int finds, savedReads;
        final List<Reading> opened = new ArrayList<>();
        final List<String> log = new ArrayList<>();
        private final List<long[]> due = new ArrayList<>(); // {at, id}
        private final List<Runnable> timers = new ArrayList<>();
        private Consumer<String> waiting;

        @Override public String saved() { savedReads++; return saved; }
        @Override public boolean watchWins() { return wins; }

        @Override
        public void find(Consumer<String> n) {
            finds++;
            if (holdFind) waiting = n;
            else n.accept(node);
        }

        void answerFind() { waiting.accept(node); }

        @Override
        public ReadQueue.Stream open(String n, ReadQueue.Events e, Runnable attached) {
            Reading r = new Reading(e, attached);
            opened.add(r);
            return r;
        }

        @Override
        public void after(long ms, Runnable r) {
            due.add(new long[]{now + ms, timers.size()});
            timers.add(r);
        }

        @Override public long now() { return now; }
        @Override public void log(String line) { log.add(line); }

        /** Moves the clock, running the timers that fall due in order (also those they add). */
        void advance(long ms) {
            long end = now + ms;
            while (true) {
                long[] next = null;
                for (long[] d : due) if (d[0] <= end && (next == null || d[0] < next[0])) next = d;
                if (next == null) break;
                due.remove(next);
                now = Math.max(now, next[0]);
                timers.get((int) next[1]).run();
            }
            now = end;
        }

        Reading last() { return opened.get(opened.size() - 1); }
    }

    private static byte[] block(int sample) {
        byte[] b = new byte[3200]; // 100 ms
        for (int i = 0; i < b.length; i += 2) {
            b[i] = (byte) sample;
            b[i + 1] = (byte) (sample >> 8);
        }
        return b;
    }

    private static final byte[] LOUD = block(5000), QUIET = block(0);

    private final Fake f = new Fake();
    private final LiveWatch.Call c = new LiveWatch.Call(f);

    /** A call whose first reading is attached: the voice is on the watch, the mic open. */
    private Reading onWatch() {
        c.begin();
        Reading r = f.last();
        r.attached.run();
        assertEquals(1, c.toWatch());
        return r;
    }

    private void quiet(int blocks) {
        for (int i = 0; i < blocks; i++) c.pcm(QUIET);
    }

    /** One utterance: a loud block, then 1.1 s of silence (the gate closes). */
    private void utterance() {
        c.pcm(LOUD);
        quiet(11);
    }

    @Test
    public void foundAndAttachedTheVoiceIsOnTheWatch() {
        c.begin();
        Reading r = f.last();
        assertEquals(0, c.toWatch()); // not attached: the phone still plays
        assertFalse(c.noEarpiece(""));
        r.attached.run();
        assertEquals(1, c.toWatch());
        assertTrue(c.noEarpiece(""));
        quiet(3);
        assertTrue(r.got.isEmpty()); // the silence before the voice is not sent
        c.pcm(LOUD);
        assertEquals(1, r.got.size());
        assertEquals(3, c.toWatch());
    }

    @Test
    public void noWatchFoundStaysOnThePhone() {
        f.node = null;
        c.begin();
        assertTrue(f.opened.isEmpty());
        assertEquals(0, c.toWatch());
        assertTrue(c.noEarpiece("watch"));
        assertFalse(c.noEarpiece(""));
    }

    @Test
    public void refusedAfterAttachFallsBackAndNothingIsFoundAgainBeforeTheRetry() {
        Reading r = onWatch();
        r.events.refused();
        assertEquals(0, c.toWatch());
        assertTrue(c.noEarpiece("")); // took stays: the phone route keeps the live rule
        f.advance(RETRY - 1);
        assertEquals(1, f.finds);
        assertEquals(1, f.opened.size());
    }

    @Test
    public void brokenAfterAttachFallsBackToo() {
        Reading r = onWatch();
        r.events.broken();
        assertEquals(0, c.toWatch());
        f.advance(RETRY - 1);
        assertEquals(1, f.opened.size());
    }

    @Test
    public void staleAnswersAreIgnored() {
        f.holdFind = true;
        c.begin();
        c.end();
        f.answerFind();
        assertTrue(f.opened.isEmpty()); // a find that came after end

        f.holdFind = false;
        c.begin();
        Reading r = f.last();
        r.events.refused();
        r.attached.run(); // attached after a refusal
        assertEquals(0, c.toWatch());

        // an old attempt's reading after a new attempt
        f.saved = "2:Speaker";
        f.wins = false;
        f.advance(500);
        f.saved = "watch";
        f.wins = true;
        f.advance(500);
        Reading second = f.last();
        assertEquals(2, f.opened.size());
        r.events.refused();
        r.attached.run();
        assertEquals(0, c.toWatch());
        second.attached.run();
        assertEquals(1, c.toWatch());
    }

    @Test
    public void endCancelsTheOpenAndTheWaitingReadingAndStopsThePoll() {
        Reading r = onWatch();
        utterance();
        assertTrue(r.closed);
        c.end();
        assertTrue(r.cancelled); // the waiting one
        c.begin();
        Reading n = f.last();
        c.end();
        assertTrue(n.cancelled); // the open one
        int reads = f.savedReads;
        f.advance(5000);
        assertEquals(reads, f.savedReads);
        assertEquals(0, c.toWatch());
        assertFalse(c.noEarpiece("watch"));
    }

    @Test
    public void aChangeOfTheSettingMovesTheVoice() {
        f.saved = "2:Speaker";
        f.wins = false;
        c.begin();
        assertEquals(0, f.finds);
        f.saved = "watch";
        f.wins = true;
        f.advance(500);
        assertEquals(1, f.finds);
        Reading r = f.last();
        r.attached.run();
        f.saved = "2:Speaker";
        f.wins = false;
        f.advance(500);
        assertTrue(r.cancelled); // no utterance going on: cancelled
        assertEquals(0, c.toWatch());

        f.saved = "watch";
        f.wins = true;
        f.advance(500);
        Reading r2 = f.last();
        r2.attached.run();
        f.saved = "";
        f.advance(500); // Auto and the watch still first: nothing changes
        assertFalse(r2.cancelled);
        assertEquals(1, c.toWatch());
        f.saved = "2:Speaker";
        f.wins = false; // Auto with buds, or the speaker
        f.advance(500);
        assertTrue(r2.cancelled);
        assertEquals(0, c.toWatch());
        f.advance(500); // no change: no line
        assertTrue(f.log.contains("setting 2:Speaker -> watch"));
        assertTrue(f.log.contains("setting watch -> 2:Speaker"));
        assertTrue(f.log.contains("setting watch -> auto"));
        assertTrue(f.log.contains("setting auto -> 2:Speaker"));
        assertEquals(5, f.log.stream().filter(l -> l.startsWith("setting ")).count()); // one per change
    }

    @Test
    public void theMicStaysClosedUntilTheWatchSaysItIsQuiet() {
        Reading r = onWatch();
        c.pcm(LOUD);
        assertEquals(3, c.toWatch());
        quiet(10); // inside the 1 s hangover: kept
        assertFalse(r.closed);
        quiet(1);
        assertTrue(r.closed); // the gate closed: the reading is finished
        assertEquals(3, c.toWatch());
        assertEquals(1, f.opened.size()); // the next waits for the answer
        for (int s = 0; s < 3; s++) {
            f.advance(1000);
            assertEquals(3, c.toWatch()); // writes taken at once, playback late: still closed
        }
        r.events.played();
        assertEquals(2, f.opened.size()); // opened ahead
        assertEquals(3, c.toWatch());
        f.advance(TAIL - 1);
        assertEquals(3, c.toWatch());
        f.advance(1);
        assertEquals(1, c.toWatch());
        assertTrue(f.log.get(f.log.size() - 1).startsWith("played 1 (+3000 ms after its end)"));
    }

    @Test
    public void voiceThatComesWhileTheWatchFinishesIsHeldAndGivenToTheNextReading() {
        Reading r = onWatch();
        utterance();
        c.pcm(block(4000));
        c.pcm(block(6000));
        assertEquals(11, r.got.size()); // the loud block and its 1 s hangover; the held blocks go to no reading yet
        assertEquals(1, f.opened.size());
        r.events.played();
        Reading n = f.last();
        assertEquals(2, n.got.size());
        assertEquals(4000, (n.got.get(0)[0] & 0xff) | (n.got.get(0)[1] << 8));
        assertEquals(6000, (n.got.get(1)[0] & 0xff) | (n.got.get(1)[1] << 8));
        assertFalse(n.closed);
        assertEquals(3, c.toWatch());
    }

    @Test
    public void voiceThatCameAndEndedWhileWaitingIsFinishedAtOnce() {
        Reading r = onWatch();
        utterance();
        c.pcm(LOUD);
        quiet(11); // its gate closed too
        r.events.played();
        Reading n = f.last();
        assertEquals(11, n.got.size()); // the loud block and its hangover
        assertTrue(n.closed);
        assertEquals(3, c.toWatch());
    }

    @Test
    public void speechThatResumesAfterAHeldEndIsNotClosedByTheOldEnd() {
        Reading r = onWatch();
        utterance(); // A, still draining
        utterance(); // B: held, and it ended
        c.pcm(LOUD); // C starts before A answers
        r.events.played();
        Reading n = f.last();
        assertEquals(12, n.got.size()); // B's loud block and hangover, then C's first block
        assertFalse(n.closed); // B's end marker must not close the reading in the middle of C
        c.pcm(LOUD);
        assertEquals(13, n.got.size()); // C goes into the open reading, not into held
    }

    @Test
    public void oneThresholdDecidesWhatStartsAnUtterance() {
        Reading r = onWatch();
        c.pcm(block(500)); // peak 0.015: between the watch's 0.01 and the page's 0.02
        assertEquals(1, r.got.size());
        assertEquals(3, c.toWatch());

        Fake g = new Fake();
        LiveWatch.Call d = new LiveWatch.Call(g);
        d.begin();
        g.last().attached.run();
        d.pcm(block(160)); // 0.005: not audible
        assertTrue(g.last().got.isEmpty());
        assertEquals(1, d.toWatch());
    }

    @Test
    public void aRefusalOfTheOpenReadingMeansPhoneAndNoMicWait() {
        Reading r = onWatch();
        r.events.refused();
        assertEquals(0, c.toWatch());
    }

    @Test
    public void theWatchsOwnStopOpensTheMicAfterTheTail() {
        Reading r = onWatch();
        utterance();
        r.events.broken(true); // its hush, on a live link
        assertEquals(2, c.toWatch()); // the phone is back
        f.advance(TAIL);
        assertEquals(0, c.toWatch());
    }

    @Test
    public void aLocalBrokenWithNoAnswerKeepsTheMicClosedForTheFailTail() {
        Reading r = onWatch();
        utterance();
        r.events.broken(); // a write failure or a disconnect: nobody said the player is silent
        assertEquals(2, c.toWatch());
        f.advance(FAIL - 1);
        assertEquals(2, c.toWatch());
        f.advance(1);
        assertEquals(0, c.toWatch());
        assertEquals(1, f.opened.size()); // no retry yet

        // the same while the reading is still open and has audio
        Fake g = new Fake();
        LiveWatch.Call d = new LiveWatch.Call(g);
        d.begin();
        Reading o = g.last();
        o.attached.run();
        d.pcm(LOUD);
        o.events.broken();
        assertEquals(2, d.toWatch());
        g.advance(FAIL - 1);
        assertEquals(2, d.toWatch());
        g.advance(1);
        assertEquals(0, d.toWatch());
    }

    @Test
    public void noAnswerForThirtySecondsIsGivenUpAndTheMicWaitsTheFailTail() {
        Reading r = onWatch();
        utterance();
        f.advance(LiveWatch.ANSWER_MS - 1000);
        assertEquals(3, c.toWatch());
        assertFalse(r.cancelled);
        f.advance(1000);
        assertTrue(r.cancelled);
        assertEquals(2, c.toWatch());
        f.advance(FAIL - 1);
        assertEquals(2, c.toWatch());
        f.advance(1);
        assertEquals(0, c.toWatch());
    }

    @Test
    public void movingAwayMidUtteranceFinishesTheReadingAndTheMicWaitsForItsAnswer() {
        Reading r = onWatch();
        c.pcm(LOUD);
        f.saved = "2:Speaker";
        f.wins = false;
        f.advance(500);
        assertTrue(r.closed);
        assertFalse(r.cancelled);
        assertEquals(2, c.toWatch()); // the voice is on the phone, the watch still has some
        f.advance(5000);
        assertEquals(2, c.toWatch());
        r.events.played();
        assertEquals(2, c.toWatch());
        f.advance(TAIL);
        assertEquals(0, c.toWatch());
        assertEquals(1, f.opened.size());
    }

    @Test
    public void movingBackBeforeTheAnswerOpensNothingUntilTheAnswer() {
        Reading r = onWatch();
        c.pcm(LOUD);
        f.saved = "2:Speaker";
        f.wins = false;
        f.advance(500);
        f.saved = "watch";
        f.wins = true;
        f.advance(500);
        assertEquals(1, f.opened.size());
        r.events.played(); // a waiting reading's answer counts whatever attempt it came from
        assertEquals(2, f.opened.size());
        f.last().attached.run();
        assertEquals(3, c.toWatch()); // the mic is still in its tail
        f.advance(TAIL);
        assertEquals(1, c.toWatch());
    }

    @Test
    public void aDisconnectDuringTheFinalDrainAfterAMidUtteranceMoveKeepsTheMicClosedForTheFailTail() {
        Reading r = onWatch();
        c.pcm(LOUD);
        f.saved = "2:Speaker";
        f.wins = false;
        f.advance(500);
        assertTrue(r.closed);
        r.events.broken(false); // the link dropped while the watch's last buffer played
        assertEquals(2, c.toWatch());
        f.advance(FAIL - 1);
        assertEquals(2, c.toWatch());
        f.advance(1);
        assertEquals(0, c.toWatch());
    }

    private boolean logged(String prefix) {
        return logCount(prefix) > 0;
    }

    private int logCount(String prefix) {
        int n = 0;
        for (String l : f.log) if (l.startsWith(prefix)) n++;
        return n;
    }

    /** The number in the last log line that starts with prefix, e.g. "retry 1 (quiet 1500 ms)" -> 1500. */
    private long quietIn(String prefix) {
        String line = null;
        for (String l : f.log) if (l.startsWith(prefix)) line = l;
        return Long.parseLong(line.replaceAll(".*quiet (\\d+) ms.*", "$1"));
    }

    /** The W2 drop: an utterance went to the watch, and the watch stopped that reading. */
    private void drop(Reading r) {
        utterance();
        r.events.broken(true);
    }

    private void soft(long ms) {
        for (long t = 0; t < ms; t += 70) {
            c.voicePeak(0.015f); // between the gate's 0.01 and the page's 0.02
            f.advance(70);
        }
    }

    private void silent(long ms) {
        for (long t = 0; t < ms; t += 70) {
            c.voicePeak(0.005f);
            f.advance(70);
        }
    }

    @Test
    public void aDropIsRetriedFiveSecondsLaterInAPauseAndTheVoiceGoesBackToTheWatch() {
        drop(onWatch());
        f.advance(RETRY - 1);
        assertEquals(1, f.finds);
        assertEquals(1, f.opened.size());
        f.advance(1);
        assertEquals(2, f.finds);
        assertTrue(logged("retry 1 (quiet"));
        assertEquals(2, f.opened.size());
        assertEquals(0, c.toWatch() & 1);
        Reading n = f.last();
        n.attached.run();
        assertEquals(1, c.toWatch());
        assertTrue(logged("playing there (quiet"));
        c.pcm(LOUD);
        assertEquals(1, n.got.size());
    }

    @Test
    public void aRetryWaitsForAPauseAndMovesTheVoiceOnlyInOne() {
        drop(onWatch());
        soft(10_000);
        assertEquals(1, f.finds);
        assertFalse(logged("retry"));
        silent(QUIET_MS + POLL);
        assertTrue(logged("retry 1"));
        assertTrue(quietIn("retry 1") >= QUIET_MS);
        Reading n = f.last();
        soft(70);
        n.attached.run();
        assertEquals(0, c.toWatch());
        assertTrue(logged("waiting for a pause"));
        c.pcm(LOUD);
        assertTrue(n.got.isEmpty());
        soft(2000);
        assertEquals(0, c.toWatch());
        f.advance(QUIET_MS + POLL);
        assertEquals(1, c.toWatch());
        assertTrue(quietIn("playing there (quiet") >= QUIET_MS);
    }

    @Test
    public void aWatchThatComesBackLaterIsFoundByTheNextRetry() {
        Reading r = onWatch();
        f.node = null;
        drop(r);
        f.advance(RETRY);
        assertTrue(logged("retry 1"));
        assertEquals("none found: phone", f.log.get(f.log.size() - 1));
        f.advance(1000);
        f.node = "n1";
        f.advance(RETRY - 1001);
        assertEquals(2, f.finds);
        f.advance(1);
        assertEquals(3, f.finds);
        assertTrue(logged("retry 2"));
        f.last().attached.run();
        assertEquals(1, c.toWatch());
    }

    @Test
    public void atMostThreeRetriesPerCallWhateverTheirOutcome() {
        drop(onWatch());
        f.node = null;
        f.advance(RETRY - 1);
        assertEquals(1, f.finds);
        f.advance(1); // retry 1: none found
        assertEquals(2, f.finds);
        f.node = "n1";
        f.advance(RETRY - 1);
        assertEquals(2, f.finds);
        f.advance(1); // retry 2: opens
        assertEquals(3, f.finds);
        f.last().events.refused(); // before it attached
        f.advance(RETRY - 1);
        assertEquals(3, f.finds);
        f.advance(1); // retry 3: attaches, then the watch stops it
        assertEquals(4, f.finds);
        Reading third = f.last();
        third.attached.run();
        assertEquals(1, c.toWatch() & 1);
        third.events.broken(true);
        f.advance(60_000);
        assertEquals(4, f.finds);
        assertFalse(logged("retry 4"));
        assertEquals(0, c.toWatch());

        // the count is per call
        c.end();
        c.begin();
        Reading r = f.last();
        r.attached.run();
        drop(r);
        f.advance(RETRY);
        assertEquals(2, logCount("retry 1"));
    }

    @Test
    public void anUnreachableWatchNeverSilencesThePhone() {
        Reading r = onWatch();
        f.node = null;
        drop(r);
        f.advance(TAIL);
        for (int i = 0; i < 60; i++) {
            f.advance(POLL);
            assertEquals(0, c.toWatch());
        }
        assertEquals(3, logCount("retry "));

        // a retry whose channel never attaches
        f.node = "n1";
        c.end();
        c.begin();
        Reading o = f.last();
        o.attached.run();
        drop(o);
        f.advance(RETRY);
        Reading n = f.last();
        assertTrue(n != o);
        assertEquals(0, c.toWatch());
        c.pcm(LOUD);
        assertTrue(n.got.isEmpty());
        n.events.refused(); // the open timeout
        assertEquals(0, c.toWatch());
    }

    @Test
    public void aRetryWaitsWhileTheWatchDoesNotWinAndASettingChangeCancelsIt() {
        f.saved = ""; // Auto
        drop(onWatch());
        f.wins = false; // a headset came
        f.advance(10_000);
        assertEquals(1, f.finds);
        assertFalse(logged("retry"));
        f.wins = true;
        f.advance(POLL);
        assertTrue(logged("retry 1"));

        f.last().attached.run();
        Reading n = f.last();
        drop(n);
        f.saved = "2:Speaker";
        f.wins = false;
        f.advance(POLL);
        f.saved = "";
        f.wins = true;
        c.voicePeak(0.5f);
        int finds = f.finds;
        f.advance(POLL);
        assertEquals(finds + 1, f.finds);
        assertFalse(logged("retry 2"));
        f.last().attached.run(); // a setting change's attach isn't gated
        assertEquals(1, c.toWatch());
        f.advance(10_000);
        assertFalse(logged("retry 2"));
    }

    @Test
    public void anAutoCallThatLosesTheWatchUsesTheSpeakerNotTheEarpiece() {
        final int earpiece = AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, speaker = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                sco = AudioDeviceInfo.TYPE_BLUETOOTH_SCO, a2dp = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP;
        f.saved = "";
        Reading r = onWatch();
        utterance();
        r.events.broken(true);
        assertEquals(0, c.toWatch() & 1);
        assertTrue(AudioRoute.liveRule(AudioManager.MODE_IN_COMMUNICATION, c.noEarpiece("")));
        // no headset: the speaker (the watch's SCO isn't one); buds connected: their call device
        assertEquals(1, AudioRoute.liveDevice(new int[]{earpiece, speaker, sco}, new String[]{"Earpiece", "Speaker", "Watch4"},
                new int[]{speaker}, new String[]{"Speaker"}, new java.util.HashSet<>()));
        assertEquals(3, AudioRoute.liveDevice(new int[]{earpiece, speaker, sco, sco},
                new String[]{"Earpiece", "Speaker", "Watch4", "Buds2"}, new int[]{speaker, a2dp},
                new String[]{"Speaker", "Buds2"}, new java.util.HashSet<>()));
    }

    @Test
    public void gate() {
        LiveWatch.Gate g = new LiveWatch.Gate();
        assertFalse(g.keep(QUIET)); // leading silence
        assertTrue(g.keep(LOUD));
        for (int i = 0; i < 10; i++) assertTrue(g.keep(QUIET)); // 1 s after it
        assertFalse(g.keep(QUIET));
        assertTrue(g.keep(LOUD));
    }

    @Test
    public void noEarpiece() {
        assertFalse(c.noEarpiece("watch")); // no call yet
        f.node = null;
        c.begin();
        assertTrue(c.noEarpiece("watch"));
        assertFalse(c.noEarpiece("")); // Auto, the watch hasn't taken the voice
        assertFalse(c.noEarpiece("2:Speaker"));
        c.end();
        assertFalse(c.noEarpiece("watch"));

        f.node = "n1";
        c.begin();
        f.last().attached.run();
        assertTrue(c.noEarpiece(""));
        assertFalse(c.noEarpiece("2:Speaker"));
        c.end();
        assertFalse(c.noEarpiece(""));
    }
}
