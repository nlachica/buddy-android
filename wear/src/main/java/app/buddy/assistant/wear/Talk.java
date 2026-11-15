package app.buddy.assistant.wear;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One conversation with Buddy on the phone, from the moment the user's words are sent until the answer is said. It is
 * a foreground service, not the screen, that listens and speaks: the wait outlives the screen (the watch face comes
 * back when the wrist goes down, and Android may reclaim a stopped activity's process). TalkActivity only draws
 * {@link #lines}. The wait ENDS on done, an error, lost contact, the 15-minute ceiling or the screen being swiped away;
 * the service STOPS once the last reply has been said (so a done that comes mid-reply doesn't cut it off).
 */
public class Talk extends Service {
    static final int YOU = 0, BUDDY = 1, ERROR = 2;

    static final class Line {
        final int kind;
        final String text;

        Line(int kind, String text) {
            this.kind = kind;
            this.text = text;
        }
    }

    /** What begin() hands over to send: call done with the result of sending (null error: the message left). */
    interface Sender {
        void send(Phone.Sent done);
    }

    // the conversation, process-wide and main-thread only; one at a time, a new one replaces it
    static String chat = "", title = "";
    static int accent = Look.ACCENT;
    static final List<Line> lines = new ArrayList<>();
    static boolean waiting, heard;
    /** The latest step of the run ("Opening Calendar"), shown while it works. */
    static String status = "";
    /** Lines that came while no screen showed them: reopening the chat just shows them, it doesn't listen again. */
    static boolean unseen;
    /** The open screen's redraw, or null. */
    static Runnable onChange;
    private static Sender sender;
    private static Talk instance;

    private static final String PATHS = "/buddy";
    private static final String CHANNEL = "talk";
    private static final long NO_HEARD_MS = 10_000, QUIET_MS = 30_000, STATUS_MS = 10_000;
    /** The phone's wake lock lasts 15 minutes (WatchLink); the watch gives up at the same time. */
    private static final long CEILING_MS = 15 * 60_000L;
    /** A 3000-character reply takes about 3½ minutes to say. */
    private static final long SPEAK_MS = 5 * 60_000L;
    /** The ceiling plus the longest reply's speech: no bug can hold the watch awake longer. */
    private static final long WAKE_MS = 20 * 60_000L;

    private final Handler h = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wake;
    private TextToSpeech tts;
    private boolean ttsReady, ttsFailed;
    /** Replies that arrived before the engine was ready, in order (the phone's Voice keeps only the last). */
    private final List<String> early = new ArrayList<>();
    /** Ids of the utterances still to be said. */
    private final Set<String> pending = new HashSet<>();
    private int utterances;

    private final MessageClient.OnMessageReceivedListener listener = e -> h.post(() -> onPhone(e));
    private final Runnable noHeard = () -> fail(R.string.talk_no_answer);
    private final Runnable quiet = this::askStatus;
    private final Runnable noStatus = () -> fail(R.string.talk_lost);
    private final Runnable ceiling = () -> fail(R.string.talk_ceiling);
    private final Runnable forceStop = this::stop;

    /** Starts the conversation: the listener first, then {@code send}, so a fast answer can't come before anyone listens. */
    static void begin(Context c, String chat, String title, int accent, Sender send) {
        Talk.chat = chat;
        Talk.title = title;
        Talk.accent = accent;
        lines.clear();
        waiting = true;
        heard = false;
        status = "";
        unseen = false;
        sender = send;
        c.startForegroundService(new Intent(c, Talk.class)); // the screen is showing, so this is allowed
        changed();
    }

    /** The screen that shows this chat: from the chat list, the ongoing notification or a new talk. */
    static Intent intent(Context c, String chat, String title, int accent) {
        return new Intent().setClassName(c, "app.buddy.assistant.wear.TalkActivity")
                .putExtra("chat", chat).putExtra("title", title).putExtra("accent", accent);
    }

    /** The user swiped the talk screen away: stop waiting (what is being said is finished). */
    static void left() {
        if (waiting && instance != null) instance.end();
    }

    /** The mic button: silence what is being said. */
    static void silence() {
        if (instance != null) instance.hush();
    }

    private static void changed() {
        if (onChange != null) onChange.run();
        else unseen = true;
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        instance = this;
        // a conversation that is still being said gives way to this one
        h.removeCallbacksAndMessages(null);
        Wearable.getMessageClient(this).removeListener(listener);
        hush();
        early.clear();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.talk_channel), NotificationManager.IMPORTANCE_LOW));
        startForeground(1, notification(R.string.talk_notif_waiting));
        if (wake == null) {
            wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "buddy:talk");
            wake.setReferenceCounted(false);
        }
        wake.acquire(WAKE_MS); // the deadlines below must run with the screen off
        h.postDelayed(ceiling, CEILING_MS);
        if (tts == null) tts = new TextToSpeech(this, this::ttsInit);
        final Sender send = sender;
        Wearable.getMessageClient(this).addListener(listener)
                .addOnSuccessListener(v -> send.send(error -> {
                    if (error != null) fail(error);
                    else h.postDelayed(noHeard, NO_HEARD_MS);
                }))
                .addOnFailureListener(e -> fail(R.string.talk_connect));
        return START_NOT_STICKY;
    }

    private Notification notification(int text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, intent(this, chat, title, accent),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ms_mic)
                .setContentText(getString(text, title)).setContentIntent(open).setOngoing(true).build();
    }

    // ---------------------------------------------------------------- from the phone

    private void onPhone(MessageEvent e) {
        if (!waiting || !e.getPath().startsWith(PATHS)) return;
        JSONObject in;
        try {
            in = new JSONObject(new String(e.getData(), StandardCharsets.UTF_8));
        } catch (JSONException x) {
            return;
        }
        if (!chat.equals(in.optString("chat"))) return;
        String text = in.optString("text");
        switch (e.getPath()) {
            case Phone.HEARD:
                h.removeCallbacks(noHeard);
                heard = true;
                lines.add(new Line(YOU, text));
                break;
            case Phone.STEP:
                status = text;
                break;
            case Phone.REPLY:
                status = "";
                say(in.optBoolean("error") ? ERROR : BUDDY, text);
                break;
            case Phone.DONE:
                if ("stopped".equals(in.optString("outcome"))) say(ERROR, getString(R.string.talk_stopped));
                end();
                return;
            case Phone.ERROR:
                say(ERROR, text);
                end();
                return;
            case Phone.STATUS:
                h.removeCallbacks(noStatus);
                if (!in.optBoolean("running")) {
                    fail(R.string.talk_stopped_working); // running is false and no done came: the answer is lost
                    return;
                }
                break;
            default:
                return;
        }
        if (heard) { // 30 seconds of silence from here on: ask the phone whether it is still at it
            h.removeCallbacks(quiet);
            h.postDelayed(quiet, QUIET_MS);
        }
        changed();
    }

    private void askStatus() {
        h.postDelayed(noStatus, STATUS_MS);
        try {
            Phone.send(this, Phone.STATUS, new JSONObject().put("chat", chat), error -> {
                if (error != null) noStatus.run();
            });
        } catch (JSONException x) {
            throw new IllegalStateException(x);
        }
    }

    // ---------------------------------------------------------------- the end

    private void fail(int text) {
        fail(getString(text));
    }

    private void fail(String text) {
        say(ERROR, text);
        end();
    }

    /** The wait is over: no more deadlines or listening. The screen shows it; the service stops when all is said. */
    private void end() {
        h.removeCallbacks(noHeard);
        h.removeCallbacks(quiet);
        h.removeCallbacks(noStatus);
        h.removeCallbacks(ceiling);
        Wearable.getMessageClient(this).removeListener(listener);
        waiting = false;
        status = "";
        getSystemService(NotificationManager.class).notify(1, notification(R.string.talk_notif_speaking));
        h.postDelayed(forceStop, SPEAK_MS); // a hung engine must not keep the watch awake
        changed();
        maybeStop();
    }

    private void maybeStop() {
        if (!waiting && pending.isEmpty() && early.isEmpty()) stop();
    }

    private void stop() {
        h.removeCallbacksAndMessages(null);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        Wearable.getMessageClient(this).removeListener(listener);
        if (tts != null) tts.shutdown();
        if (wake != null && wake.isHeld()) wake.release();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    // ---------------------------------------------------------------- speech

    /** Shows a line and says it (in order, once). End messages are said too: the user may not be looking. */
    private void say(int kind, String text) {
        lines.add(new Line(kind, text));
        if (ttsFailed) return;
        if (ttsReady) speak(text);
        else early.add(text);
    }

    private void speak(String text) {
        String id = "u" + utterances++;
        pending.add(id);
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, id);
    }

    private void ttsInit(int status) {
        if (status != TextToSpeech.SUCCESS) {
            ttsFailed = true;
            early.clear();
            lines.add(new Line(ERROR, getString(R.string.talk_no_voice))); // shown, not said
            changed();
            maybeStop();
            return;
        }
        tts.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String id) {
            }

            @Override
            public void onDone(String id) {
                finished(id);
            }

            @Override
            public void onError(String id) {
                finished(id);
            }

            @Override
            public void onStop(String id, boolean interrupted) {
                finished(id);
            }
        });
        ttsReady = true;
        for (String t : early) speak(t);
        early.clear();
    }

    private void finished(String id) {
        h.post(() -> {
            pending.remove(id);
            maybeStop();
        });
    }

    private void hush() {
        pending.clear();
        if (tts != null && ttsReady) tts.stop();
        maybeStop();
    }
}
