package app.buddy.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WakeWordTest {
    @Test
    public void stripCutsThePhrase() {
        assertEquals("what's the weather like", WakeWord.strip("Hey buddy, what's the weather like"));
        assertEquals("what's the weather", WakeWord.strip("hey body what's the weather"));
        assertEquals("what's the weather", WakeWord.strip("Okay, buddy: what's the weather"));
        assertEquals("what's the weather", WakeWord.strip("anyway hey buddy what's the weather")); // leaked speech before it goes too
        assertEquals("what's the weather", WakeWord.strip("buddy, what's the weather"));
        assertEquals("", WakeWord.strip("hey buddy"));
        assertEquals("", WakeWord.strip("Hey, buddy."));
        assertEquals("what is the time", WakeWord.strip("hey buddy... what is the time"));
        assertEquals("", WakeWord.strip(null));
    }

    @Test
    public void stripLeavesTextWithoutThePhrase() {
        assertEquals("what's the weather", WakeWord.strip("what's the weather"));
        assertEquals("text my buddy Tom", WakeWord.strip("text my buddy Tom"));
        // the phrase only counts within the first 8 words
        String late = "one two three four five six seven eight hey buddy what";
        assertEquals(late, WakeWord.strip(late));
    }

    @Test
    public void stopCommands() {
        for (String s : new String[]{"stop", "Stop.", "ok stop talking", "Okay, stop", "be quiet", "quiet", "shut up", "That's enough", "never mind",
                "please cancel", "cancel!"})
            assertTrue(s, WakeWord.isStop(s));
        for (String s : new String[]{"stop the timer", "what's the weather", "", "cancel my meeting", "quiet mode on"})
            assertFalse(s, WakeWord.isStop(s));
        assertFalse(WakeWord.isStop(null));
    }

    @Test
    public void overlapOfSpeechAndWakePhrase() {
        assertTrue(WakeWord.overlapped(true, 0, 5000)); // still speaking
        assertTrue(WakeWord.overlapped(false, 4600, 5000)); // ended 400 ms before: the phrase straddles the end
        assertFalse(WakeWord.overlapped(false, 3900, 5000)); // ended 1100 ms before: the phrase began after
        assertFalse(WakeWord.overlapped(false, 0, 5000)); // never spoke
        assertTrue(WakeWord.overlapped(false, 5200, 5000)); // ended after the detection (the thread's clock was earlier)
    }

    @Test
    public void turnSendsTheRequest() {
        WakeWord.Turn.Step s = new WakeWord.Turn(false).heard("hey buddy what's the weather");
        assertEquals(WakeWord.Turn.Act.SEND, s.act);
        assertEquals("what's the weather", s.request);
        assertEquals("no phrase heard: sent as is", "turn it up", new WakeWord.Turn(false).heard("turn it up").request);
    }

    @Test
    public void turnAloneListensOnceMore() {
        WakeWord.Turn t = new WakeWord.Turn(false);
        assertEquals(WakeWord.Turn.Act.AGAIN, t.heard("hey buddy").act);
        WakeWord.Turn.Step s = t.heard("what's the weather");
        assertEquals(WakeWord.Turn.Act.SEND, s.act);
        assertEquals("what's the weather", s.request);
    }

    @Test
    public void turnGivesUpAfterTwoEmptyWindows() {
        WakeWord.Turn t = new WakeWord.Turn(false);
        assertEquals(WakeWord.Turn.Act.AGAIN, t.nothing().act);
        assertEquals(WakeWord.Turn.Act.END, t.nothing().act);
        WakeWord.Turn u = new WakeWord.Turn(false);
        assertEquals(WakeWord.Turn.Act.AGAIN, u.heard("hey buddy").act);
        assertEquals(WakeWord.Turn.Act.END, u.heard("hey buddy").act);
        WakeWord.Turn v = new WakeWord.Turn(false);
        assertEquals(WakeWord.Turn.Act.AGAIN, v.nothing().act);
        assertEquals(WakeWord.Turn.Act.END, v.heard("hey buddy").act);
        assertNull(v.nothing().request);
    }

    @Test
    public void stopWordIsSwallowedOnlyWhenTheWakeCutSpeech() {
        assertEquals(WakeWord.Turn.Act.END, new WakeWord.Turn(true).heard("hey buddy stop").act);
        assertEquals(WakeWord.Turn.Act.END, new WakeWord.Turn(true).heard("hey buddy, cancel").act);
        WakeWord.Turn.Step s = new WakeWord.Turn(false).heard("hey buddy, cancel");
        assertEquals(WakeWord.Turn.Act.SEND, s.act);
        assertEquals("cancel", s.request);
        assertEquals(WakeWord.Turn.Act.SEND, new WakeWord.Turn(true).heard("hey buddy stop the timer").act);
        assertEquals(WakeWord.Turn.Act.SEND, new WakeWord.Turn(true).heard("hey buddy what's the weather").act);
    }

    private static WakeWord.Inputs in() {
        WakeWord.Inputs i = new WakeWord.Inputs();
        i.enabled = true;
        i.screenOn = true;
        return i;
    }

    @Test
    public void stateRowsInOrder() {
        WakeWord.Inputs i = in();
        assertEquals(WakeWord.State.IDLE, WakeWord.state(i));
        i.speaking = true;
        assertEquals(WakeWord.State.SPEAKING, WakeWord.state(i));
        i.enabled = false; // row 1 beats everything
        i.call = true;
        i.turn = true;
        assertEquals(WakeWord.State.OFF, WakeWord.state(i));
    }

    @Test
    public void callAndForeignRecordingBeatATurn() {
        WakeWord.Inputs i = in();
        i.turn = i.turnFed = true;
        assertEquals(WakeWord.State.TURN, WakeWord.state(i));
        i.call = true;
        assertEquals(WakeWord.State.PAUSED, WakeWord.state(i));
        i.call = false;
        i.foreignRecordings = 1; // a fed turn expects none
        assertEquals(WakeWord.State.PAUSED, WakeWord.state(i));
        WakeWord.Inputs idle = in();
        idle.foreignRecordings = 1;
        assertEquals(WakeWord.State.PAUSED, WakeWord.state(idle));
        idle.speaking = true;
        assertEquals(WakeWord.State.PAUSED, WakeWord.state(idle));
    }

    @Test
    public void ownVoicePathsPauseButNotDuringATurn() {
        WakeWord.Inputs i = in();
        i.ownVoice = true;
        assertEquals(WakeWord.State.PAUSED, WakeWord.state(i)); // Talk, dictation, Live, the Terminal's microphone
        i.turn = i.turnFed = true; // a turn's own window is Buddy listening: still the turn
        assertEquals(WakeWord.State.TURN, WakeWord.state(i));
    }

    @Test
    public void unfedTurnExpectsTheRecognizersOneRecording() {
        WakeWord.Inputs i = in();
        i.turn = true;
        i.foreignRecordings = 1;
        assertEquals(WakeWord.State.TURN, WakeWord.state(i));
        i.foreignRecordings = 2;
        assertEquals(WakeWord.State.PAUSED, WakeWord.state(i));
    }

    @Test
    public void resumesOnlyAfterTheCauseWasGoneForASecond() {
        WakeWord.Inputs i = in();
        i.msSinceCause = 400;
        assertEquals(WakeWord.State.PAUSED, WakeWord.state(i));
        i.msSinceCause = WakeWord.RESUME_MS;
        assertEquals(WakeWord.State.IDLE, WakeWord.state(i));
        i.msSinceCause = 400;
        i.turn = true; // a turn is not held back by it
        assertEquals(WakeWord.State.TURN, WakeWord.state(i));
    }

    @Test
    public void listensOnlyWithTheScreenOnOrCharging() {
        for (int m = 0; m < 8; m++) {
            WakeWord.Inputs i = new WakeWord.Inputs();
            i.enabled = (m & 1) != 0;
            i.screenOn = (m & 2) != 0;
            i.charging = (m & 4) != 0;
            WakeWord.State want = !i.enabled ? WakeWord.State.OFF
                    : i.screenOn || i.charging ? WakeWord.State.IDLE : WakeWord.State.ASLEEP;
            assertEquals("mask " + m, want, WakeWord.state(i));
        }
    }

    @Test
    public void screenOffOnBatteryBeatsATurnAndSpeakingButNotACause() {
        WakeWord.Inputs i = in();
        i.screenOn = false;
        i.speaking = true;
        assertEquals(WakeWord.State.ASLEEP, WakeWord.state(i));
        i.speaking = false;
        i.turn = i.turnFed = true;
        assertEquals(WakeWord.State.ASLEEP, WakeWord.state(i));
        i.turnFed = false; // an unfed turn with its one expected recording
        i.foreignRecordings = 1;
        assertEquals(WakeWord.State.ASLEEP, WakeWord.state(i));
        i.screenOn = true; // the same with the screen on
        assertEquals(WakeWord.State.TURN, WakeWord.state(i));
        i.screenOn = false;
        i.charging = true;
        assertEquals(WakeWord.State.TURN, WakeWord.state(i));
        i.charging = false;
        i.turn = false;
        i.foreignRecordings = 1; // a foreign recording is a cause: PAUSED, as before
        assertEquals(WakeWord.State.PAUSED, WakeWord.state(i));
        i.foreignRecordings = 0;
        i.call = true;
        assertEquals(WakeWord.State.PAUSED, WakeWord.state(i));
        i.call = false;
        i.msSinceCause = 400; // no resume wait counted against it
        assertEquals(WakeWord.State.ASLEEP, WakeWord.state(i));
        assertFalse(WakeWord.cause(i));
    }
}
