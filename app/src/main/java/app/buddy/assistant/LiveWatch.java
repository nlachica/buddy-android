package app.buddy.assistant;

import android.content.Context;
import android.os.Build;

/**
 * A phone live voice call and the Galaxy Watch (docs/features/017-live-voice-watch/plan.md): the call's voice can play
 * on the watch, and while Watch is the audio device the call never falls to the earpiece (AudioRoute.live). Google-free
 * (the watch link is WatchVoice's), so it is unit-tested through {@link Call}. Calls started from the watch have a
 * Remote (007) and never come here.
 */
final class LiveWatch {
    private static volatile Call call;

    private LiveWatch() {
    }

    /** A phone live call starts (main thread). Android 12+ only: 001's platform rule, below it a call is as before. */
    static void begin(Context c) {
        if (Build.VERSION.SDK_INT < 31) return;
        if (call == null) call = new Call();
        call.begin();
    }

    static void end() {
        Call k = call;
        if (k != null) k.end();
    }

    /** Whether AudioRoute must keep this call off the earpiece, given the saved audio device key. */
    static boolean noEarpiece(String saved) {
        Call k = call;
        return k != null && k.noEarpiece(saved);
    }

    static final class Call {
        private boolean on, took;

        void begin() {
            on = true;
            took = false;
        }

        void end() {
            on = false;
            took = false;
        }

        /** A phone call is on and Watch is picked, or Auto after the watch took the voice in this call. */
        boolean noEarpiece(String saved) {
            return on && (WatchVoice.KEY.equals(saved) || (saved.isEmpty() && took));
        }
    }
}
