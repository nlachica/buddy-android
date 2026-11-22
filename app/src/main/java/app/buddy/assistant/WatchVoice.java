package app.buddy.assistant;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.function.Consumer;

/**
 * The Watch entry of the audio device list (docs/features/012-watch-audio-device/plan.md): read aloud on the Galaxy
 * Watch. The rules are ReadQueue's; this is the Android side: the watch link (github build only, loaded by reflection
 * because src/main can't see src/watchlink, and F-Droid and build.sh don't have it), the voices and the clock.
 * Main thread only.
 */
final class WatchVoice {
    /** The audio device key of Watch. Device keys are type:name and always hold a ':', so there is no clash. */
    static final String KEY = "watch";

    /** The github build's end of the watch link (WatchVoiceLink). */
    interface Link {
        /** The node id of a reachable watch whose app can play readings, or null (main thread, within a few seconds). */
        void find(Context c, Consumer<String> node);

        /** One reading's audio stream to that watch. attached (or null) runs on the main thread when its channel is open. */
        ReadQueue.Stream open(Context c, String node, ReadQueue.Events e, Runnable attached);

        default ReadQueue.Stream open(Context c, String node, ReadQueue.Events e) {
            return open(c, node, e, null);
        }

        /** Asks the watch whether it is worn (033): its reply comes to wornReply. failed runs when the ask can't be sent. */
        default void askWorn(Context c, String node, long id, Runnable failed) {
            failed.run();
        }
    }

    private static Link link;
    private static boolean loaded;
    private static ReadQueue queue;
    private static Worn worn;

    private WatchVoice() {
    }

    /** The link, or null when this build has none. */
    static synchronized Link link() {
        if (!loaded) {
            loaded = true;
            link = load("app.buddy.assistant.WatchVoiceLink");
        }
        return link;
    }

    static Link load(String className) {
        try {
            return (Link) Class.forName(className).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    /** Auto's "a watch answers" (033): a reachable watch that is worn. A hand-picked Watch is used worn or not. */
    static void find(Context c, Consumer<String> node) {
        worn(c).find(n -> link().find(c, n), () -> KEY.equals(Prefs.audioDevice(c)), node);
    }

    private static Worn worn(Context c) {
        if (worn == null) worn = new Worn(new WornPort(c.getApplicationContext()));
        return worn;
    }

    /** The watch's push of its wrist state (main thread): kept for the next Auto decision, then given to a live call. */
    static void wornPush(Context c, String node, boolean w) {
        worn(c).push(node, w);
        LiveWatch.worn(node, w);
    }

    /** The watch's answer to an ask (main thread). */
    static void wornReply(String node, long id, Boolean w, long ms, String why) {
        if (worn != null) worn.reply(node, id, w, ms, why);
    }

    /** A fresh "worn" from this node (a live call that is switched from Watch to Auto, 033). */
    static boolean worn(String node) {
        return worn != null && worn.on(node);
    }

    /** quiet: Watch isn't the user's pick, so it is Auto's step (016): no notice when the watch can't play it. */
    static void speak(Context c, String clean, boolean quiet) {
        if (queue == null) queue = new ReadQueue(new Phone(c.getApplicationContext()));
        queue.speak(clean, Voice.chatgptVoice(c), quiet);
    }

    static boolean active() {
        return queue != null && queue.active();
    }

    static void stop() {
        if (queue != null) queue.stop();
    }

    private static final class Phone implements ReadQueue.Out {
        private final Context app;
        private final Handler main = new Handler(Looper.getMainLooper());

        Phone(Context app) {
            this.app = app;
        }

        @Override
        public void find(Consumer<String> node) {
            WatchVoice.find(app, node);
        }

        @Override
        public ReadQueue.Stream open(String node, ReadQueue.Events e) {
            return link().open(app, node, e);
        }

        @Override
        public void chatgpt(String text, ReadQueue.Stream s, Runnable failed) {
            CodexSpeech.speak(app, text, t -> failed.run(), s);
        }

        @Override
        public void tts(String text, ReadQueue.Stream s, Runnable done) {
            if (s == null) SpeechPause.begin(app);
            Voice.routed(app, text, s, done);
        }

        @Override
        public boolean watchWins() {
            return KEY.equals(AudioRoute.target(app));
        }

        @Override
        public boolean phoneBusy() {
            return Voice.phoneSpeaking();
        }

        @Override
        public void here(String text) {
            SpeechPause.begin(app);
            Voice.here(app, text);
        }

        @Override
        public void stopProducers(ReadQueue.Stream s) {
            CodexSpeech.cancel(s);
            Voice.dropRouted();
        }

        @Override
        public void after(long ms, Runnable r) {
            main.postDelayed(r, ms);
        }

        @Override
        public String notice() {
            return app.getString(R.string.voice_watch_unavailable);
        }
    }

    private static final class WornPort implements Worn.Port {
        private final Context app;
        private final Handler main = new Handler(Looper.getMainLooper());

        WornPort(Context app) {
            this.app = app;
        }

        @Override
        public void send(String node, long id, Runnable failed) {
            link().askWorn(app, node, id, failed);
        }

        @Override
        public void after(long ms, Runnable r) {
            main.postDelayed(r, ms);
        }

        @Override
        public long now() {
            return SystemClock.elapsedRealtime();
        }

        @Override
        public void log(String line) {
            Log.d("Worn", line);
        }
    }
}
