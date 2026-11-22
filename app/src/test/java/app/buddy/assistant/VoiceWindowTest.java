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
}
