package app.buddy.assistant.wear;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Plays one of the phone's readings (docs/features/012-watch-audio-device/plan.md): the phone opens the channel
 * /buddy/read/<id>, sends PCM 16-bit mono 16 kHz, and this plays it through {@link Speaker} while a mediaPlayback
 * foreground service keeps the watch awake. The channel is closed with a code the phone relies on: {@link #PLAYED}
 * (it played to the end of the stream), {@link #NOT_PLAYED} (nothing of it sounded: busy, the service was refused, an
 * empty or silent stream, a failed player; the phone then reads it itself) or 0 (broke or was hushed after sound:
 * nobody reads it again). It also answers the phone's "are you on the wrist?" (033) and, while a reading plays, sends
 * the off-body sensor's events to the phone. State is static and main-thread only; the system's callbacks are posted to main.
 */
public class ReadAloud extends WearableListenerService {
    static final int NOT_PLAYED = 1, PLAYED = 2;
    private static final String PATH = "/buddy/read/", CHANNEL = "read";
    private static final long WAKE_MS = 11 * 60_000L;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Context app;
    private static ReadAloud instance;
    private static ChannelClient.Channel channel; // the reading
    private static Speaker speaker;
    private static PowerManager.WakeLock wake;
    /** 033: while a reading plays, the off-body sensor's events go to the node that opened its channel. */
    private static Worn push;

    /** Another voice of the watch has the speaker or the microphone. */
    private static boolean busy() {
        return Talk.busy() || Call.on || TalkActivity.dictating();
    }

    /** 033: the phone asks whether the watch is worn: register the sensor, wait for its first event (Android allows 5 s), answer. */
    @Override
    public void onMessageReceived(MessageEvent e) {
        if (!Phone.WORN.equals(e.getPath())) return;
        final long id;
        try {
            id = new JSONObject(new String(e.getData(), StandardCharsets.UTF_8)).getLong("id");
        } catch (JSONException x) {
            return;
        }
        PowerManager.WakeLock lock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "buddy:worn");
        lock.acquire(8000);
        final long t0 = SystemClock.elapsedRealtime();
        final CountDownLatch got = new CountDownLatch(1);
        final Boolean[] worn = new Boolean[1];
        final long[] ms = new long[1];
        Worn sensor = new Worn(this, w -> {
            if (got.getCount() == 0) return;
            worn[0] = w;
            ms[0] = SystemClock.elapsedRealtime() - t0;
            got.countDown();
        });
        String why = sensor.start();
        try {
            if (why == null && !got.await(5, TimeUnit.SECONDS)) why = "no event";
        } catch (InterruptedException x) {
            Thread.currentThread().interrupt();
            why = "no event";
        } finally {
            sensor.stop();
            lock.release();
        }
        try {
            JSONObject o = new JSONObject().put("id", id);
            if (why == null) o.put("worn", worn[0]).put("ms", ms[0]);
            else o.put("why", why);
            Wearable.getMessageClient(this).sendMessage(e.getSourceNodeId(), Phone.WORN, o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (JSONException x) {
            throw new IllegalStateException(x); // only for a NaN number
        }
    }

    @Override
    public void onChannelOpened(ChannelClient.Channel ch) {
        if (!ch.getPath().startsWith(PATH)) return;
        final Context c = getApplicationContext();
        main.post(() -> opened(c, ch));
    }

    private static void opened(Context c, ChannelClient.Channel ch) {
        app = c;
        if (busy()) {
            close(ch, NOT_PLAYED);
            return;
        }
        if (channel != null) { // the phone sends one reading at a time: it had already given the old one up
            ChannelClient.Channel old = channel;
            Speaker sp = speaker;
            channel = null;
            speaker = null;
            if (sp != null) sp.stop("replaced");
            close(old, 0);
        }
        channel = ch;
        try {
            c.startForegroundService(new Intent(c, ReadAloud.class));
        } catch (RuntimeException e) { // Android 12+ may refuse a foreground start from the background
            channel = null;
            close(ch, NOT_PLAYED);
        }
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        instance = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.read_channel), NotificationManager.IMPORTANCE_LOW));
        try {
            startForeground(3, new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ms_mic)
                    .setContentText(getString(R.string.read_notif)).setOngoing(true).build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } catch (RuntimeException e) { // Android may refuse the promotion after accepting the start: nothing played, the phone reads it
            finish(NOT_PLAYED);
            return START_NOT_STICKY;
        }
        if (wake == null) {
            wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "buddy:read");
            wake.setReferenceCounted(false);
        }
        wake.acquire(WAKE_MS);
        final ChannelClient.Channel ch = channel;
        if (ch == null) { // hushed or replaced before the service started
            down();
        } else if (busy()) { // the state may have changed since the channel came
            finish(NOT_PLAYED);
        } else if (speaker == null) { // a second start command for the same reading finds its player already made
            final Speaker[] me = new Speaker[1];
            final Speaker sp = me[0] = new Speaker(false, "read " + ch.getPath().substring(PATH.length()), new Speaker.Listener() {
                @Override
                public boolean audible() {
                    return true;
                }

                @Override
                public void ended(String by) {
                    main.post(() -> ReadAloud.ended(me[0], by));
                }
            });
            speaker = sp;
            if (push == null) { // 033: the phone hears the wrist state at the start of the reading and each change
                final String phone = ch.getNodeId();
                push = new Worn(this, w -> Wearable.getMessageClient(app).sendMessage(phone, Phone.WORN,
                        ("{\"worn\":" + w + "}").getBytes(StandardCharsets.UTF_8)));
                push.start(); // no sensor or refused: the phone hears nothing and keeps its own answer
            }
            Wearable.getChannelClient(this).getInputStream(ch)
                    .addOnSuccessListener(in -> {
                        if (speaker == sp) sp.play(in);
                        else {
                            sp.stop("replaced");
                            sp.play(in); // stopped: play() only closes the late stream
                        }
                    })
                    .addOnFailureListener(e -> {
                        if (speaker == sp) finish(NOT_PLAYED);
                    });
        }
        return START_NOT_STICKY;
    }

    /** The speaker's stream ended: "eof" is the phone's normal end. */
    private static void ended(Speaker sp, String by) {
        if (sp == null || speaker != sp) return; // replaced or hushed meanwhile
        finish(!sp.sounded() ? NOT_PLAYED : "eof".equals(by) ? PLAYED : 0);
    }

    /** Close the reading's channel with code and let go of the service. */
    private static void finish(int code) {
        ChannelClient.Channel ch = channel;
        channel = null;
        speaker = null;
        if (ch != null) close(ch, code);
        down();
    }

    private static void down() {
        if (push != null) push.stop();
        push = null;
        if (wake != null && wake.isHeld()) wake.release();
        ReadAloud i = instance;
        instance = null;
        if (i != null) {
            i.stopForeground(STOP_FOREGROUND_REMOVE);
            i.stopSelf();
        }
    }

    /** The watch's microphone is about to be used (TalkActivity): a reading going on stops, and the phone is told it broke. */
    static void silence() {
        ChannelClient.Channel ch = channel;
        if (ch == null) return;
        Speaker sp = speaker;
        speaker = null;
        if (sp != null) sp.stop("hush");
        finish(0);
    }

    private static void close(ChannelClient.Channel ch, int code) {
        Wearable.getChannelClient(app).close(ch, code);
    }
}
