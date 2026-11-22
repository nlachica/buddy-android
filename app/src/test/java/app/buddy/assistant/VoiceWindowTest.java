package app.buddy.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.ContextWrapper;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Test;

/** 032: a listening window owns its callbacks, and "nothing heard" is told apart from an error. */
public class VoiceWindowTest {
    /** Records what a listener heard, in order: "final:x", "nothing", "end:msg". */
    private static final class Rec implements Voice.Listener {
        final List<String> got = new ArrayList<>();

        @Override public void onPartial(String text) { got.add("partial:" + text); }
        @Override public void onFinal(String text) { got.add("final:" + text); }
        @Override public void onEnd(String message) { got.add("end:" + message); }
        @Override public void onNothing(String message) { got.add("nothing"); }
    }

    private final ContextWrapper ctx = new ContextWrapper(null); // getString answers null in JVM tests

    @After
    public void closeWindow() {
        Voice.stopListening();
    }

    @Test
    public void lateCallbacksOfACancelledWindowChangeNothing() { // T5
        Rec first = new Rec(), second = new Rec();
        Voice.Window a = Voice.openWindow(ctx, first);
        Voice.stopListening(); // cancelled: the first listener hears onEnd(null) once
        Voice.Window b = Voice.openWindow(ctx, second);

        a.onError(SpeechRecognizer.ERROR_CLIENT); // what a cancelled recognizer sends late
        a.onError(SpeechRecognizer.ERROR_NO_MATCH);
        a.onResults(new android.os.Bundle());
        a.onPartialResults(new android.os.Bundle());

        assertEquals(List.of("end:null"), first.got);
        assertTrue(second.got.isEmpty());
        assertTrue("the second window is still the open one", Voice.listening());

        b.onError(SpeechRecognizer.ERROR_NO_MATCH);
        assertEquals(List.of("nothing"), second.got);
        b.onError(SpeechRecognizer.ERROR_NO_MATCH); // and only once
        assertEquals(List.of("nothing"), second.got);
    }

    @Test
    public void nothingHeardIsNotAnError() { // T2
        Rec l = new Rec();
        Voice.openWindow(ctx, l).onResults(new android.os.Bundle()); // no words
        assertEquals(List.of("nothing"), l.got);

        Rec e = new Rec();
        Voice.openWindow(ctx, e).onError(SpeechRecognizer.ERROR_NETWORK);
        assertEquals(List.of("end:null"), e.got); // getString is null here; what matters is onEnd, not onNothing
    }

    @Test
    public void endpointPassesNothingOn() { // T2
        Rec l = new Rec();
        Voice.Listener wrapped = Voice.endpoint(1000, 0, l);
        wrapped.onNothing("x");
        assertEquals(List.of("nothing"), l.got);
        wrapped = Voice.endpoint(1000, 0, l = new Rec());
        wrapped.onEnd("boom");
        assertEquals(List.of("end:boom"), l.got);
    }

    @Test
    public void phoneFeedStopsAfterTwoEarlyFailuresInARow() { // P6 run-time fallback
        Voice.PhoneFeed f = new Voice.PhoneFeed();
        f.started(1000);
        assertEquals(false, f.failed(2000)); // 1 s after starting: one failure
        assertTrue(f.on);
        f.started(10_000);
        assertTrue(f.failed(11_000)); // the second in a row turns it off
        assertEquals(false, f.on);

        f = new Voice.PhoneFeed();
        f.started(1000);
        f.failed(2000);
        f.started(10_000);
        f.ok(); // a window that went fine in between
        f.started(20_000);
        f.failed(21_000);
        assertTrue("not in a row", f.on);

        f.started(30_000);
        f.failed(34_000); // 4 s after starting: not an early failure
        f.started(40_000);
        f.failed(44_000);
        assertTrue(f.on);
    }

    @Test
    public void aLateOrCancelledWindowBreaksTheStreak() {
        Voice.PhoneFeed f = new Voice.PhoneFeed();
        f.started(1000);
        f.failed(2000); // early
        f.started(10_000);
        f.failed(15_000); // late: resets
        f.started(20_000);
        f.failed(21_000); // early again: only one in a row
        assertTrue(f.on);

        f = new Voice.PhoneFeed();
        f.started(1000);
        f.failed(2000); // early
        f.started(10_000); // this window is cancelled: no failure, no ok
        f.started(20_000);
        f.failed(21_000);
        assertTrue("a cancelled window in between", f.on);
    }
}
