package app.buddy.assistant;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 028: dictation reads only Claude Code's input box, never the /voice hold echo above it. */
public class ClaudeDictationTest {
    private static final String RULE = "────────────────────────────────";
    private static final String BANNER = "  Claude Code v2.1.328\n\n";
    private static final String ECHO = "❯ /voice hold\n  ⎿  Turned on voice mode (hold). Hold space to record.\n\n";

    private static String box(String inside) {
        return RULE + "\n" + inside + "\n" + RULE + "\n  ? for shortcuts\n";
    }

    @Test
    public void echoWhileRecordingIsNotText() { // T1
        assertEquals("", ClaudeDictation.promptText(BANNER + ECHO + RULE + "\n  ● Recording…\n" + RULE + "\n"));
    }

    @Test
    public void boxTextWins() { // T2
        assertEquals("hello from the phone", ClaudeDictation.promptText(BANNER + ECHO + box("❯ hello from the phone")));
    }

    @Test
    public void emptyBox() { // T3
        assertEquals("", ClaudeDictation.promptText(BANNER + ECHO + box("❯ ")));
    }

    @Test
    public void wrappedBoxText() { // T4
        assertEquals("one two three", ClaudeDictation.promptText(BANNER + ECHO + box("❯ one two\n  three")));
    }

    @Test
    public void typedVoiceCommandIsNotText() { // T5
        assertEquals("", ClaudeDictation.promptText(BANNER + box("❯ /voice hold")));
    }

    @Test
    public void ruleAboveTheEchoWithNoBox() { // T6
        assertEquals("", ClaudeDictation.promptText(BANNER + RULE + "\n" + ECHO));
    }

    @Test
    public void statusLinesAreNotText() { // T7
        assertEquals("", ClaudeDictation.promptText(box("❯ ALSA lib pcm.c: unable to open")));
        assertEquals("", ClaudeDictation.promptText(box("❯ keep holding")));
    }

    @Test
    public void emptyScreens() { // T8
        assertEquals("", ClaudeDictation.promptText(""));
        assertEquals("", ClaudeDictation.promptText("❯ hi\n" + RULE + "\n"));
    }

    @Test
    public void oldWordingEchoIsNotText() { // T9
        String old = "❯ /voice hold\n  ⎿  Voice mode is on (hold). Hold space to record.\n\n";
        assertEquals("", ClaudeDictation.promptText(BANNER + old + RULE + "\n  ● Recording…\n" + RULE + "\n"));
    }

    @Test
    public void dictatedWordsLikeTheEchoPass() { // T10
        assertEquals("Hold space to record a memo",
                ClaudeDictation.promptText(BANNER + ECHO + box("❯ Hold space to record a memo")));
    }

    @Test
    public void otherSlashWordsPass() { // T11
        assertEquals("/voicemail", ClaudeDictation.promptText(BANNER + ECHO + box("❯ /voicemail")));
        assertEquals("/voice holder", ClaudeDictation.promptText(BANNER + ECHO + box("❯ /voice holder")));
    }
}
