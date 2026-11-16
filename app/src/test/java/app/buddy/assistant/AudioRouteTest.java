package app.buddy.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.media.AudioDeviceInfo;

import org.junit.Test;

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
}
