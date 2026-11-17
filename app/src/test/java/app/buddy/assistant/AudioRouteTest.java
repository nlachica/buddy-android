package app.buddy.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.media.AudioDeviceInfo;

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
        return AudioRoute.liveDevice(comm, cn, out, on, new HashSet<>(java.util.Arrays.asList(refused)));
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
        assertEquals(AudioRoute.NONE, AudioRoute.liveAttempt(comm, cn, out, on, refused, never));
        assertEquals(java.util.Arrays.asList(2, 1), calls); // the buds, then the speaker
        assertEquals(2, refused.size());
        calls.clear();
        assertEquals(AudioRoute.NONE, AudioRoute.liveAttempt(comm, cn, out, on, refused, never)); // kept: nothing retried
        assertTrue(calls.isEmpty());
        calls.clear();
        refused.clear();
        assertEquals(AudioRoute.NONE, AudioRoute.liveAttempt(new int[]{EARPIECE, SPEAKER}, new String[]{"Earpiece", "Speaker"},
                new int[]{SPEAKER}, new String[]{"Speaker"}, refused, never));
        assertEquals(java.util.Arrays.asList(1), calls); // the earpiece's index is never applied
        refused.clear();
        assertEquals(1, AudioRoute.liveAttempt(comm, cn, out, on, refused, j -> j == 1));
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
        assertFalse(AudioRoute.slipped("2:Speaker", "1:Earpiece", 1999));
        assertFalse(AudioRoute.slipped("2:Speaker", "2:Speaker", 5000));
        assertTrue(AudioRoute.slipped("2:Speaker", "1:Earpiece", 2000));
        assertTrue(AudioRoute.slipped("2:Speaker", null, 2000));
        assertFalse(AudioRoute.slipped(null, "1:Earpiece", 5000));
    }
}
