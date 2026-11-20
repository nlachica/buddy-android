package app.buddy.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.media.AudioDeviceInfo;
import android.media.AudioManager;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.function.IntPredicate;

public class AudioRouteTest {
    private static final int SPEAKER = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, SCO = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            A2DP = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP;

    @Test
    public void headsetTypes() {
        for (int t : new int[]{AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET, A2DP, AudioDeviceInfo.TYPE_HEARING_AID, 26, 27}) {
            assertTrue("type " + t, AudioRoute.headset(t));
        }
        for (int t : new int[]{SCO, SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, 24 /* SPEAKER_SAFE */,
                AudioDeviceInfo.TYPE_TELEPHONY, AudioDeviceInfo.TYPE_REMOTE_SUBMIX}) {
            assertFalse("type " + t, AudioRoute.headset(t));
        }
    }

    @Test
    public void headsetIn() {
        assertEquals(AudioRoute.NONE, AudioRoute.headsetIn(new int[]{SPEAKER}, new int[]{SPEAKER, SCO, A2DP}));
        assertEquals(0, AudioRoute.headsetIn(new int[]{A2DP}, new int[]{A2DP, A2DP}));
        assertEquals(1, AudioRoute.headsetIn(new int[]{SPEAKER, A2DP}, new int[]{SPEAKER}));
        assertEquals(AudioRoute.NONE, AudioRoute.headsetIn(null, new int[]{SPEAKER, SCO}));
        assertEquals(AudioRoute.UNNAMED, AudioRoute.headsetIn(new int[0], new int[]{SPEAKER, A2DP}));
        assertEquals(AudioRoute.UNNAMED, AudioRoute.headsetIn(null, new int[]{A2DP}));
    }

    @Test
    public void autoOrder() {
        assertEquals("", AudioRoute.pick("", false, true, true));       // headset beats watch
        assertEquals("watch", AudioRoute.pick("", false, false, true));  // no headset: watch
        assertEquals("", AudioRoute.pick("", false, false, false));      // speaker, or F-Droid
    }

    @Test
    public void pickedDevice() {
        String ls2 = "7:LS2 FreeCom";
        assertEquals("", AudioRoute.pick(ls2, false, true, true));        // away: Android's headset
        assertEquals("watch", AudioRoute.pick(ls2, false, false, true));  // away, no headset: watch
        assertEquals(ls2, AudioRoute.pick(ls2, true, true, true));        // here beats a headset
        assertEquals("watch", AudioRoute.pick("watch", true, true, true));
        assertEquals("", AudioRoute.pick("watch", false, false, false));  // stale "watch", build without the link
    }

    @Test
    public void settingsReadSurvivesAPickThatVanishedBetweenQueries() {
        String[][] gone = {{"", "Auto"}};
        String[][] here = {{"", "Auto"}, {"7:FreeCom", "FreeCom"}};
        assertEquals("FreeCom", SettingsTool.audioNow("7:FreeCom", "7:FreeCom", here, "speaker"));
        assertEquals("auto \u2192 speaker (FreeCom isn't connected)", SettingsTool.audioNow("7:FreeCom", "7:FreeCom", gone, "speaker"));
        assertEquals("auto \u2192 speaker", SettingsTool.audioNow("", "", gone, "speaker"));
    }

    private static final int EARPIECE = AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, WIRED = AudioDeviceInfo.TYPE_WIRED_HEADSET;

    private static int live(int[] comm, String[] cn, int[] out, String[] on, String... refused) {
        return AudioRoute.liveDevice("", comm, cn, out, on, new HashSet<>(java.util.Arrays.asList(refused)));
    }

    @Test
    public void liveDevice() {
        int[] comm = {EARPIECE, SPEAKER, SCO, SCO};
        String[] cn = {"Earpiece", "Speaker", "Watch4", "Buds2"};
        // the watch's SCO alone isn't a headset
        assertEquals(1, live(new int[]{EARPIECE, SPEAKER, SCO}, new String[]{"Earpiece", "Speaker", "Watch4"},
                new int[]{SPEAKER}, new String[]{"Speaker"}));
        // buds: a SCO named like a connected media headset
        int[] out = {SPEAKER, A2DP};
        String[] on = {"Speaker", "Buds2"};
        assertEquals(3, live(comm, cn, out, on));
        assertEquals(1, live(comm, cn, out, on, SCO + ":Buds2"));
        // no call profile: no buds SCO in comm
        assertEquals(1, live(new int[]{EARPIECE, SPEAKER}, new String[]{"Earpiece", "Speaker"}, new int[]{A2DP}, new String[]{"Buds2"}));
        assertEquals(2, live(new int[]{EARPIECE, SPEAKER, WIRED}, new String[]{"Earpiece", "Speaker", "Headset"},
                new int[]{SPEAKER, WIRED}, new String[]{"Speaker", "Headset"}));
        // the earpiece is never chosen
        assertEquals(AudioRoute.NONE, live(new int[]{EARPIECE}, new String[]{"Earpiece"}, new int[]{SPEAKER}, new String[]{"Speaker"}));
    }

    private static final String LS2 = "7:LS2 Freecom";

    @Test
    public void aSavedDeviceThatIsConnectedIsChosenFirst() {
        Set<String> none = new HashSet<>();
        int[] out = {SPEAKER};
        String[] on = {"Speaker"};
        // the watch-like SCO has no media output: only the pick makes it a device
        int[] comm = {EARPIECE, SPEAKER, SCO};
        String[] cn = {"Earpiece", "Speaker", "LS2 Freecom"};
        assertEquals(2, AudioRoute.liveDevice(LS2, comm, cn, out, on, none));
        assertEquals(1, AudioRoute.liveDevice("", comm, cn, out, on, none));
        // away: the speaker, or a headset's call device
        assertEquals(1, AudioRoute.liveDevice(LS2, new int[]{EARPIECE, SPEAKER}, new String[]{"Earpiece", "Speaker"}, out, on, none));
        assertEquals(2, AudioRoute.liveDevice(LS2, new int[]{EARPIECE, SPEAKER, WIRED}, new String[]{"Earpiece", "Speaker", "Headset"},
                new int[]{SPEAKER, WIRED}, new String[]{"Speaker", "Headset"}, none));
        // the pick beats a headset; a pick the speaker is the speaker
        int[] comm2 = {EARPIECE, SPEAKER, SCO, SCO};
        String[] cn2 = {"Earpiece", "Speaker", "Buds2", "LS2 Freecom"};
        int[] out2 = {SPEAKER, A2DP};
        String[] on2 = {"Speaker", "Buds2"};
        assertEquals(3, AudioRoute.liveDevice(LS2, comm2, cn2, out2, on2, none));
        assertEquals(1, AudioRoute.liveDevice("2:Speaker", comm2, cn2, out2, on2, none));
        // a refused pick falls to the headset, then the speaker
        assertEquals(2, AudioRoute.liveDevice(LS2, comm2, cn2, out2, on2, new HashSet<>(java.util.Arrays.asList(LS2))));
        // the earpiece is never chosen, even when it is the pick
        assertEquals(1, AudioRoute.liveDevice("1:Earpiece", new int[]{EARPIECE, SPEAKER}, new String[]{"Earpiece", "Speaker"}, out, on, none));
        assertEquals(AudioRoute.NONE, AudioRoute.liveDevice("1:Earpiece", new int[]{EARPIECE}, new String[]{"Earpiece"}, out, on, none));
    }

    @Test
    public void aPickThatRefusesIsTriedOnceThenAPickThatLeavesIsTriedAgainWhenBack() {
        int[] comm = {EARPIECE, SPEAKER, SCO};
        String[] cn = {"Earpiece", "Speaker", "LS2 Freecom"};
        int[] out = {SPEAKER};
        String[] on = {"Speaker"};
        java.util.List<Integer> calls = new java.util.ArrayList<>();
        Set<String> refused = new HashSet<>();
        // refusal: LS2, then the speaker; the earpiece's index (0) never
        assertEquals(1, AudioRoute.liveAttempt(LS2, comm, cn, out, on, refused, j -> {
            calls.add(j);
            return j == 1;
        }));
        assertEquals(java.util.Arrays.asList(2, 1), calls);
        assertTrue(refused.contains(LS2));
        calls.clear();
        // still listed: LS2 isn't offered to apply again
        assertEquals(1, AudioRoute.liveAttempt(LS2, comm, cn, out, on, refused, j -> {
            calls.add(j);
            return true;
        }));
        assertEquals(java.util.Arrays.asList(1), calls);
        // it leaves (a slip refused it as it went), and the refusal goes with it; back, it is tried again
        refused.clear();
        refused.add(LS2);
        assertEquals(1, AudioRoute.liveAttempt(LS2, new int[]{EARPIECE, SPEAKER}, new String[]{"Earpiece", "Speaker"}, out, on, refused, j -> true));
        assertTrue(refused.isEmpty());
        assertEquals(2, AudioRoute.liveAttempt(LS2, comm, cn, out, on, refused, j -> true));
    }

    @Test
    public void liveRule() {
        assertTrue(AudioRoute.liveRule(AudioManager.MODE_NORMAL, true));
        assertTrue(AudioRoute.liveRule(AudioManager.MODE_IN_COMMUNICATION, true));
        assertFalse(AudioRoute.liveRule(AudioManager.MODE_IN_CALL, true));
        assertFalse(AudioRoute.liveRule(AudioManager.MODE_RINGTONE, true));
        assertFalse(AudioRoute.liveRule(AudioManager.MODE_IN_COMMUNICATION, false));
    }

    @Test
    public void liveAttempt() {
        int[] comm = {EARPIECE, SPEAKER, SCO};
        String[] cn = {"Earpiece", "Speaker", "Buds2"};
        int[] out = {SPEAKER, A2DP};
        String[] on = {"Speaker", "Buds2"};
        java.util.List<Integer> calls = new java.util.ArrayList<>();
        IntPredicate never = j -> {
            calls.add(j);
            return false;
        };
        Set<String> refused = new HashSet<>();
        assertEquals(AudioRoute.NONE, AudioRoute.liveAttempt("", comm, cn, out, on, refused, never));
        assertEquals(java.util.Arrays.asList(2, 1), calls); // the buds, then the speaker
        assertEquals(2, refused.size());
        calls.clear();
        assertEquals(AudioRoute.NONE, AudioRoute.liveAttempt("", comm, cn, out, on, refused, never)); // kept: nothing retried
        assertTrue(calls.isEmpty());
        calls.clear();
        refused.clear();
        assertEquals(AudioRoute.NONE, AudioRoute.liveAttempt("", new int[]{EARPIECE, SPEAKER}, new String[]{"Earpiece", "Speaker"},
                new int[]{SPEAKER}, new String[]{"Speaker"}, refused, never));
        assertEquals(java.util.Arrays.asList(1), calls); // the earpiece's index is never applied
        refused.clear();
        assertEquals(1, AudioRoute.liveAttempt("", comm, cn, out, on, refused, j -> j == 1));
        assertEquals(1, refused.size());
        assertTrue(refused.contains(SCO + ":Buds2"));
    }

    @Test
    public void livePick() {
        // the connected outputs only: a routed {SPEAKER} answer that hides buds in a call isn't an input here
        assertEquals("", AudioRoute.livePick("", false, new int[]{SPEAKER, A2DP}, true));
        assertEquals("watch", AudioRoute.livePick("", false, new int[]{SPEAKER, SCO}, true));
        assertEquals("watch", AudioRoute.livePick("watch", true, new int[]{SPEAKER, A2DP}, true));
        assertEquals("2:Speaker", AudioRoute.livePick("2:Speaker", true, new int[]{SPEAKER}, true));
    }

    @Test
    public void slipped() {
        assertFalse(AudioRoute.slipped("2:Speaker", "1:Earpiece", 1999, 2000));
        assertFalse(AudioRoute.slipped("2:Speaker", "2:Speaker", 5000, 2000));
        assertTrue(AudioRoute.slipped("2:Speaker", "1:Earpiece", 2000, 2000));
        assertTrue(AudioRoute.slipped("2:Speaker", null, 2000, 2000));
        assertFalse(AudioRoute.slipped(null, "1:Earpiece", 5000, 2000));
    }

    @Test
    public void aHeadsetNotSeenYetGetsTimeToConnect() { // round 1 F3
        String ls2 = "7:LS2 Freecom";
        String speaker = "2:Speaker", earpiece = "1:Earpiece";
        assertTrue(AudioRoute.grace(ls2, false, speaker) > 3000);
        assertEquals(2000, AudioRoute.grace(ls2, true, earpiece)); // once seen, a loss is a slip after 2 s
        assertEquals(2000, AudioRoute.grace("2:Speaker", false, earpiece)); // the speaker keeps its 2 s
        // LS2 reports itself after 3 s with the speaker active meanwhile: not a slip
        assertFalse(AudioRoute.slipped(ls2, speaker, 3000, AudioRoute.grace(ls2, false, speaker)));
        assertTrue(AudioRoute.slipped(ls2, earpiece, 3000, AudioRoute.grace(ls2, true, earpiece)));
        // round 2 F2: still connecting at 10 s is not a slip; the full 30 s window with no link is
        assertFalse(AudioRoute.slipped(ls2, "2:Speaker", 10_000, AudioRoute.grace(ls2, false, "2:Speaker")));
        assertFalse(AudioRoute.slipped(ls2, "2:Speaker", 29_999, AudioRoute.grace(ls2, false, "2:Speaker")));
        assertTrue(AudioRoute.slipped(ls2, "2:Speaker", 30_000, AudioRoute.grace(ls2, false, "2:Speaker"))); // a link that never comes
    }

    @Test
    public void aHeadsetPendingWhileTheEarpieceIsReportedFallsBackToTheSpeaker() { // round 3 F1
        String ls2 = "7:LS2 Freecom", earpiece = "1:Earpiece";
        long g = AudioRoute.grace(ls2, false, earpiece);
        assertEquals(0, g);
        assertTrue(AudioRoute.slipped(ls2, earpiece, 300, g)); // round 4 F1: the first poll after the apply falls back
        assertTrue(AudioRoute.refuseSlip(ls2, false)); // LS2 refused, so liveAttempt takes the speaker
        Set<String> refused = new HashSet<>();
        refused.add(ls2);
        int[] comm = {EARPIECE, SPEAKER, 7};
        String[] cn = {"Earpiece", "SM-G990W", "LS2 Freecom"};
        assertEquals(1, AudioRoute.liveAttempt("7:LS2 Freecom", comm, cn, new int[]{SPEAKER}, new String[]{"SM-G990W"},
                refused, j -> true));
    }

    @Test
    public void aSpeakerThatNeverTakesTheRouteEndsTheCall() { // round 1 F2
        assertTrue(AudioRoute.refuseSlip("7:LS2 Freecom", false));
        assertFalse(AudioRoute.refuseSlip("2:SM-G990W", false)); // the first slip: applied again
        assertTrue(AudioRoute.refuseSlip("2:SM-G990W", true)); // the second with no sighting between
        Set<String> refused = new HashSet<>();
        refused.add("2:SM-G990W");
        int[] comm = {EARPIECE, SPEAKER};
        String[] cn = {"Earpiece", "SM-G990W"};
        int[] out = {SPEAKER};
        String[] on = {"SM-G990W"};
        assertEquals(AudioRoute.NONE, AudioRoute.liveAttempt("", comm, cn, out, on, refused, j -> {
            assertNotEquals(0, j); // never the earpiece
            return true;
        }));
    }

    @Test
    public void aPickIsOnOnlyWhenAndroidReportsIt() { // round 1 F1
        assertFalse(AudioRoute.pickOn("7:LS2 Freecom", "7:LS2 Freecom", "2:Speaker")); // accepted, speaker still active
        assertFalse(AudioRoute.pickOn("7:LS2 Freecom", "7:LS2 Freecom", null));
        assertFalse(AudioRoute.pickOn("7:LS2 Freecom", null, "7:LS2 Freecom"));
        assertTrue(AudioRoute.pickOn("7:LS2 Freecom", "7:LS2 Freecom", "7:LS2 Freecom"));
    }
}
