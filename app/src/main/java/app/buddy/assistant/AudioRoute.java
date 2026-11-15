package app.buddy.assistant;

import android.annotation.TargetApi;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;

/**
 * Which audio device Buddy's voice uses: Auto (Android decides) or one of the devices Android offers for calls
 * (speaker, earpiece, a Bluetooth watch or headset). While Buddy's voice is on and a device is chosen, Buddy runs as a
 * call: that device becomes Android's communication device and the phone is in communication mode, which also moves
 * media (read aloud) and the recognizers' input along. A voice session is that time plus a quiet tail; a poll ends it
 * and puts everything back, so no end of voice needs a hook. Android 12+ (setCommunicationDevice); below, only Auto.
 * All state lives on the main thread.
 */
final class AudioRoute {
    private static final long POLL_MS = 300, QUIET_MS = 1500, NEVER_STARTED_MS = 10_000;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private static Context app;
    private static boolean on, other, ownMode, dirty, sawLive, heard;
    private static String applied, refused;
    private static long began, quietSince;

    private AudioRoute() {
    }

    /** {key, label} pairs: Auto first (key ""), then the devices Android offers for calls now. */
    static String[][] choices(Context c) {
        ArrayList<String[]> l = new ArrayList<>();
        l.add(new String[]{"", c.getString(R.string.audio_auto)});
        if (Build.VERSION.SDK_INT >= 31) {
            for (AudioDeviceInfo d : c.getSystemService(AudioManager.class).getAvailableCommunicationDevices()) {
                l.add(new String[]{key(d), label(c, d)});
            }
        }
        return l.toArray(new String[0][]);
    }

    /** Index of the key in choices(), or -1 when that device isn't there. */
    static int indexOf(String[][] choices, String key) {
        for (int i = 0; i < choices.length; i++) if (choices[i][0].equals(key)) return i;
        return -1;
    }

    // the id changes on reconnect and a Bluetooth address can be hidden from apps: type and name are stable
    private static String key(AudioDeviceInfo d) {
        return d.getType() + ":" + d.getProductName();
    }

    private static String label(Context c, AudioDeviceInfo d) {
        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                return c.getString(R.string.audio_speaker);
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:
                return c.getString(R.string.audio_phone);
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                return c.getString(R.string.audio_headset);
            default:
                String n = String.valueOf(d.getProductName());
                return n.isEmpty() ? c.getString(R.string.audio_headset) : n;
        }
    }

    /**
     * Buddy's voice starts now, or the choice changed: apply the choice now. The audio mode is read here, before
     * anything of Buddy's own (CodexLive.start calls this before its call exists), so a phone or app call that is on
     * already is seen and left alone for the whole session.
     */
    static void use(Context c) {
        if (Build.VERSION.SDK_INT < 31) return;
        final Context a = c.getApplicationContext();
        final int mode = a.getSystemService(AudioManager.class).getMode();
        main.post(() -> {
            app = a;
            if (!on) {
                on = true;
                other = mode != AudioManager.MODE_NORMAL;
            }
            began = System.currentTimeMillis();
            heard = false;
            quietSince = 0;
            applied = refused = null;
            try {
                reconcile();
            } catch (Exception ignored) {
            }
            main.removeCallbacks(poll);
            main.postDelayed(poll, POLL_MS);
        });
    }

    /** Buddy itself holds communication mode (AutoPause doesn't count that as a call). */
    static boolean ownsMode() {
        return ownMode;
    }

    @TargetApi(31)
    private static void reconcile() {
        AudioManager am = app.getSystemService(AudioManager.class);
        int mode = am.getMode();
        boolean live = CodexLive.on();
        afterLive(mode);
        sawLive |= live;
        String want = Prefs.audioDevice(app);
        if (want.isEmpty()) {
            drop(am);
            return;
        }
        // ringing or in a phone call: not ours to touch, whatever was applied before
        if (mode != AudioManager.MODE_NORMAL && mode != AudioManager.MODE_IN_COMMUNICATION) other = true;
        AudioDeviceInfo d = null;
        if (!other) {
            for (AudioDeviceInfo i : am.getAvailableCommunicationDevices()) if (key(i).equals(want)) d = i;
        }
        if (d == null) { // also when Android ended the selection because the device left: back on reconnect
            drop(am);
            return;
        }
        if (want.equals(applied) || want.equals(refused)) return;
        // in live voice the WebView sets and resets the mode itself
        if (mode == AudioManager.MODE_NORMAL && !live) {
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            ownMode = true;
        }
        if (am.setCommunicationDevice(d)) {
            applied = want;
            dirty = true;
        } else { // don't retry every tick: on reconnect or a new choice
            refused = want;
            dropMode(am);
        }
    }

    // The WebView's teardown (after a live call ends) clears the device, then sets MODE_NORMAL: once Buddy sees
    // NORMAL, it has nothing left to undo and the choice is applied again for dictation or read aloud already going
    private static void afterLive(int mode) {
        if (sawLive && !CodexLive.on() && mode == AudioManager.MODE_NORMAL) {
            sawLive = false;
            applied = refused = null;
        }
    }

    @TargetApi(31)
    private static void drop(AudioManager am) {
        if (applied != null) am.clearCommunicationDevice();
        applied = refused = null;
        dropMode(am);
    }

    private static void dropMode(AudioManager am) {
        // not while ringing or in a call, and the WebView owns the mode in live voice
        if (ownMode && !CodexLive.on() && am.getMode() == AudioManager.MODE_IN_COMMUNICATION) {
            am.setMode(AudioManager.MODE_NORMAL);
        }
        ownMode = false;
    }

    private static final Runnable poll = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            if (Voice.isSpeaking() || Voice.listening() || CodexLive.on()) {
                heard = true;
                quietSince = 0;
                try {
                    reconcile();
                } catch (Exception ignored) {
                }
            } else {
                if (quietSince == 0) quietSince = now;
                if (heard ? now - quietSince > QUIET_MS : now - began > NEVER_STARTED_MS) {
                    finish();
                    return;
                }
                if (sawLive) afterLive(app.getSystemService(AudioManager.class).getMode());
            }
            main.postDelayed(this, POLL_MS);
        }
    };

    // Also clears what Chromium may have selected again at hang-up (it restores the state it saved at call start).
    @TargetApi(31)
    private static void finish() {
        try {
            AudioManager am = app.getSystemService(AudioManager.class);
            if (dirty) am.clearCommunicationDevice();
            dropMode(am);
        } catch (Exception ignored) {
        }
        on = other = ownMode = dirty = sawLive = false;
        applied = refused = null;
    }
}
