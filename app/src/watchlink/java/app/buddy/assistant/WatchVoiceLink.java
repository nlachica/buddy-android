package app.buddy.assistant;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.Wearable;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The github build's end of the Watch audio device (docs/features/012-watch-audio-device/plan.md): finds a watch that
 * can play readings and opens one channel per reading, /buddy/read/<id>. WatchVoice loads this class by reflection.
 * Main thread, except the Google callbacks, which are posted to it.
 */
public class WatchVoiceLink implements WatchVoice.Link {
    private static final String CAPABILITY = "buddy_watch_read", PATH = "/buddy/read/";
    /** 10 minutes of PCM 16 kHz mono: phone TTS is faster than real time and the watch pulls at playback speed. */
    private static final int READ_CAP = 10 * 60 * 32_000;
    private static final long FIND_MS = 3000, OPEN_MS = 10_000, ABANDON_MS = 11 * 60_000L, WAKE_MS = 11 * 60_000L;

    private static Handler main;
    private static PowerManager.WakeLock wake;
    private static boolean watching;
    private static long seq = System.currentTimeMillis(); // unique across phone restarts
    /** Streams not yet settled: channel path → stream. */
    private static final Map<String, ReadStream> streams = new HashMap<>();

    public WatchVoiceLink() {
    }

    private static Handler main() { // not a static initializer: JVM tests load this class
        if (main == null) main = new Handler(Looper.getMainLooper());
        return main;
    }

    @Override
    public void find(Context c, Consumer<String> node) {
        final Context app = c.getApplicationContext();
        final AtomicBoolean answered = new AtomicBoolean();
        final Consumer<String> answer = n -> {
            if (answered.compareAndSet(false, true)) node.accept(n);
        };
        main().postDelayed(() -> answer.accept(null), FIND_MS);
        Wearable.getCapabilityClient(app).getCapability(CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .addOnSuccessListener(info -> answer.accept(
                        info.getNodes().isEmpty() ? null : info.getNodes().iterator().next().getId()))
                .addOnFailureListener(e -> answer.accept(null));
    }

    @Override
    public ReadQueue.Stream open(Context c, String node, ReadQueue.Events e, Runnable attached) {
        final Context app = c.getApplicationContext();
        final String path = PATH + ++seq;
        final ReadStream s = new ReadStream(e, main()::post, (ms, r) -> main().postDelayed(r, ms), READ_CAP, ABANDON_MS,
                () -> main().post(() -> {
                    streams.remove(path);
                    if (streams.isEmpty() && wake != null && wake.isHeld()) wake.release();
                }));
        streams.put(path, s);
        watchChannels(app);
        // with the screen off nothing else keeps the CPU up while the watch pulls a long reading
        if (wake == null) {
            wake = app.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "buddy:watch-read");
            wake.setReferenceCounted(false);
        }
        wake.acquire(WAKE_MS);
        final Runnable late = s::openTimedOut;
        main().postDelayed(late, OPEN_MS);
        final ChannelClient cc = Wearable.getChannelClient(app);
        cc.openChannel(node, path).addOnSuccessListener(ch -> cc.getOutputStream(ch).addOnSuccessListener(o -> {
            main().removeCallbacks(late);
            s.attached(o, () -> cc.close(ch));
            if (attached != null) attached.run();
        }).addOnFailureListener(x -> {
            cc.close(ch);
            s.openTimedOut();
        })).addOnFailureListener(x -> s.openTimedOut());
        return s;
    }

    /** One callback for all readings: a channel's close goes to its stream. */
    private static void watchChannels(Context app) {
        if (watching) return;
        watching = true;
        Wearable.getChannelClient(app).registerChannelCallback(new ChannelClient.ChannelCallback() {
            @Override
            public void onChannelClosed(ChannelClient.Channel ch, int reason, int code) {
                final boolean local = reason == ChannelClient.ChannelCallback.CLOSE_REASON_LOCAL_CLOSE;
                main().post(() -> {
                    ReadStream s = streams.get(ch.getPath());
                    if (s != null) s.closed(local, code);
                });
            }
        });
    }
}
