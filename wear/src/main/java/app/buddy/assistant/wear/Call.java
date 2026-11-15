package app.buddy.assistant.wear;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * A live voice call with Buddy on the phone (docs/features/007-watch-live-voice/plan.md): the watch's microphone goes to
 * the phone's call over the channel /buddy/live and the voice comes back on it to the speaker. A foreground service of
 * type microphone, so it works with the screen off. Half-duplex: while the voice is audible (and 300 ms after) the
 * microphone is read but not sent. The newest call replaces one that is on; {@link CallActivity} only draws the statics.
 */
public class Call extends Service {
    private static final String TAG = "BuddyCall", CHANNEL = "call";
    private static final long SETUP_MS = 10_000, CAP_MS = 30 * 60_000L, AFTER_MS = 300;
    private static final Handler h = new Handler(Looper.getMainLooper());

    // what the call screen shows, main thread only
    static String chat = "", title = "", status = "", ended = "";
    static int accent = Look.ACCENT;
    static boolean on;
    static Runnable onChange;

    private static long seq = System.currentTimeMillis(); // unique across restarts
    private static Run current;
    private static Call instance;

    private PowerManager.WakeLock wake;

    /** Starts a call in this chat (main thread, from a visible screen), replacing one that is on. */
    static void start(Context c, String chat, String title, int accent) {
        if (current != null) current.close();
        Call.chat = chat;
        Call.title = title;
        Call.accent = accent;
        status = c.getString(R.string.call_connecting);
        ended = "";
        on = true;
        current = new Run(c.getApplicationContext(), ++seq, chat);
        c.startForegroundService(new Intent(c, Call.class));
        changed();
    }

    /** End (the button, a new talk): the phone hears the channel close. */
    static void hangUp() {
        if (current != null) finish(current, null);
    }

    /** The call screen was swiped away: no call without a screen to end it. */
    static void left() {
        hangUp();
    }

    private static void changed() {
        if (onChange != null) onChange.run();
    }

    /** The call is over (main thread): text is what the screen shows, or null. */
    private static void finish(Run r, String text) {
        if (current != r) return;
        current = null;
        on = false;
        status = "";
        ended = text == null ? "" : text;
        r.close();
        if (instance != null) {
            instance.stopForeground(STOP_FOREGROUND_REMOVE);
            instance.stopSelf();
        }
        changed();
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        instance = this;
        getSystemService(NotificationManager.class)
                .createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.call_channel), NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, CallActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        startForeground(2, new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ms_graphic_eq)
                .setContentText(getString(R.string.call_notif, title)).setContentIntent(open).setOngoing(true).build());
        Run r = current;
        if (r == null || r.begun) { // ended before this start ran, or a second start for the same call
            if (r == null) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        if (wake == null) {
            wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "buddy:call");
            wake.setReferenceCounted(false);
        }
        wake.acquire(CAP_MS + 60_000);
        r.begin();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (wake != null && wake.isHeld()) wake.release();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    /** One call: the channel, the microphone, the speaker. Its id is what the phone's status and end carry back. */
    private static final class Run {
        final Context app;
        final long id;
        final String chat;
        boolean begun;
        private volatile boolean dead, ready;
        private volatile ChannelClient.Channel channel;
        private volatile Speaker speaker;
        private volatile AudioRecord rec;
        private final MessageClient.OnMessageReceivedListener listener = e -> h.post(() -> onPhone(e));
        private final Runnable setup = this::setupLate, cap = this::capReached;

        Run(Context app, long id, String chat) {
            this.app = app;
            this.id = id;
            this.chat = chat;
        }

        private void setupLate() {
            finish(this, app.getString(ready ? R.string.call_lost : R.string.talk_connect));
        }

        private void capReached() {
            finish(this, app.getString(R.string.call_cap));
        }

        void begin() {
            begun = true;
            Log.i(TAG, "call " + id + " starts in " + chat);
            h.postDelayed(setup, SETUP_MS);
            h.postDelayed(cap, CAP_MS);
            Wearable.getMessageClient(app).addListener(listener);
            Phone.find(app, () -> !dead, (node, error) -> {
                if (error != null) finish(this, error);
                else new Thread(() -> talk(node), "call-" + id).start();
            });
        }

        /** The setup, then the microphone loop, on this thread. */
        private void talk(String node) {
            try {
                ChannelClient cc = Wearable.getChannelClient(app);
                ChannelClient.Channel ch = Tasks.await(cc.openChannel(node, Phone.LIVE));
                channel = ch;
                if (dead) { // ended or replaced while it opened
                    cc.close(ch);
                    return;
                }
                InputStream in = Tasks.await(cc.getInputStream(ch));
                OutputStream out = Tasks.await(cc.getOutputStream(ch));
                out.write((new JSONObject().put("chat", chat).put("call", id) + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                Speaker sp = new Speaker(true, "call " + id, new Speaker.Listener() {
                    @Override
                    public boolean audible() {
                        return true;
                    }

                    @Override
                    public void ended(String by) {
                        h.post(() -> finish(Run.this, app.getString(R.string.call_lost)));
                    }
                });
                speaker = sp;
                sp.play(in);
                h.post(() -> {
                    ready = true;
                    h.removeCallbacks(setup);
                });
                mic(out, sp);
            } catch (Exception e) {
                Log.i(TAG, "call " + id + ": " + e);
                h.post(() -> finish(this, app.getString(ready ? R.string.call_lost : R.string.talk_connect)));
            }
        }

        private void mic(OutputStream out, Speaker sp) throws IOException {
            int rate = 16000, min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioRecord r = null;
            for (int src : new int[]{MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC}) {
                try {
                    r = new AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, rate));
                    if (r.getState() == AudioRecord.STATE_INITIALIZED) break;
                } catch (RuntimeException ignored) {
                }
                if (r != null) r.release();
                r = null;
            }
            if (r == null) {
                h.post(() -> finish(this, app.getString(R.string.call_no_mic)));
                return;
            }
            rec = r;
            if (dead) { // close() ran before rec was set
                r.release();
                return;
            }
            r.startRecording();
            byte[] buf = new byte[1280]; // 40 ms
            long quietUntil = 0;
            while (!dead) {
                int n = r.read(buf, 0, buf.length);
                if (n < 0) {
                    if (dead) break; // close() released the recorder
                    throw new IOException("microphone read " + n); // talk() ends the call
                }
                long now = SystemClock.elapsedRealtime();
                if (sp.audibleNow()) quietUntil = now + AFTER_MS;
                if (n == 0 || now < quietUntil) continue; // half-duplex: the speaker is 3 cm from the microphone
                out.write(buf, 0, n);
                out.flush();
            }
        }

        private void onPhone(MessageEvent e) {
            String path = e.getPath();
            if (dead || !(Phone.LIVE_STATUS.equals(path) || Phone.LIVE_END.equals(path))) return;
            try {
                JSONObject in = new JSONObject(new String(e.getData(), StandardCharsets.UTF_8));
                if (in.optLong("call") != id) {
                    Log.i(TAG, "ignored " + path + " for call " + in.optLong("call") + " (this is " + id + ")");
                    return;
                }
                if (Phone.LIVE_STATUS.equals(path)) {
                    status = in.optString("text");
                    changed();
                } else {
                    finish(this, in.optString("text"));
                }
            } catch (JSONException x) {
                // not ours
            }
        }

        /** Lets everything go: mic, speaker, channel, listeners (main thread). */
        void close() {
            if (dead) return;
            dead = true;
            Log.i(TAG, "call " + id + " ends");
            h.removeCallbacks(setup);
            h.removeCallbacks(cap);
            Wearable.getMessageClient(app).removeListener(listener);
            Speaker sp = speaker;
            if (sp != null) sp.stop("hush");
            AudioRecord r = rec;
            if (r != null) try {
                r.stop();
                r.release();
            } catch (RuntimeException ignored) {
            }
            ChannelClient.Channel ch = channel;
            if (ch != null) Wearable.getChannelClient(app).close(ch);
        }
    }
}
