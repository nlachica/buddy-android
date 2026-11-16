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
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * One conversation with Buddy on the phone, from the moment the user's words are sent until the answer is said. It is
 * a foreground service, not the screen, that listens and speaks: the wait outlives the screen (the watch face comes
 * back when the wrist goes down, and Android may reclaim a stopped activity's process). TalkActivity only draws
 * {@link #lines}. The wait ENDS on done, an error, lost contact, the 15-minute ceiling or the screen being closed while
 * it shows (Back or a swipe; a relaunch of the app doesn't count). A failure of later words doesn't end a run the phone
 * has accepted. The service STOPS once the last reply has been said (so a done that comes mid-reply doesn't cut it off).
 * Replies are said in order: by the watch's TTS, or, when the phone says a reply comes in ChatGPT's voice
 * (docs/features/007-watch-live-voice/plan.md), by playing its channel; the watch alone decides which, once.
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
        void send(BooleanSupplier valid, Phone.Sent done);
    }

    // the conversation, process-wide and main-thread only; one at a time. New words for the same chat join it while it
    // is live (waiting, or still saying its replies), sent one at a time; anything else replaces it
    static String chat = "", title = "";
    static int accent = Look.ACCENT;
    static final List<Line> lines = new ArrayList<>();
    /** heard: nothing in flight (the latest words were heard, refused or given up). */
    static boolean waiting, heard;
    /** The phone took words of this conversation and hasn't sent done since: a run of ours is still going. */
    static boolean accepted;
    /** Goes up on a new conversation and an ended wait: their callbacks (sends, status checks) must not touch what follows. */
    private static int run;
    /** Goes up with each words sent and words given up: their callbacks must not send them late or settle the next words. */
    private static int sends;
    /** The latest step of the run ("Opening Calendar"), shown while it works. */
    static String status = "";
    /** Lines that came while no screen showed them: reopening the chat just shows them, it doesn't listen again. */
    static boolean unseen;
    /** The open screen's redraw, or null. */
    static Runnable onChange;
    /** Words still to send, oldest first. */
    private static final List<Sender> queue = new ArrayList<>();
    private static Talk instance;

    private static final String PATHS = "/buddy";
    private static final String CHANNEL = "talk";
    private static final long NO_HEARD_MS = 10_000, QUIET_MS = 30_000, STATUS_MS = 10_000;
    /** A reply in the phone's voice that isn't audible after this long at the head of the queue is said by the watch. */
    private static final long HEAD_MS = 35_000;
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
    /** What is still to be said, in order: TTS texts (also those that arrived before the engine was ready) and voice replies. */
    private final List<Say> toSay = new ArrayList<>();
    /** Ids of the TTS utterances still to be said, and when their reply arrived (for the latency log). */
    private final Set<String> pending = new HashSet<>();
    private final Map<String, Long> ttsArrived = new HashMap<>();
    private int utterances;
    /** Voice channels the phone opened, by reply id, kept until read to the end, converted, hushed or Talk stops. */
    private final Map<Long, ChannelClient.Channel> channels = new HashMap<>();
    /** Reply ids the phone said the watch must say itself (its message may come before the reply's). */
    private final Set<Long> saidByWatch = new HashSet<>();

    /** One thing to say. voice: the reply id the phone streams in ChatGPT's voice, or -1 for the watch's TTS. */
    private static final class Say {
        final String text;
        final long arrived = SystemClock.elapsedRealtime();
        long voice;
        /** The voice's first audible block was written: the voice owns this reply from then on (reader thread sets). */
        volatile boolean committed; // the voice owns the reply; with fellBack, set only under synchronized (this)
        boolean fellBack; // the TTS owns it
        Speaker speaker;
        Runnable deadline;

        Say(String text, long voice) {
            this.text = text;
            this.voice = voice;
        }
    }

    private final MessageClient.OnMessageReceivedListener listener = e -> h.post(() -> onPhone(e));
    private final ChannelClient.ChannelCallback channelCallback = new ChannelClient.ChannelCallback() {
        @Override
        public void onChannelOpened(ChannelClient.Channel ch) {
            h.post(() -> voiceOpened(ch));
        }

        @Override
        public void onChannelClosed(ChannelClient.Channel ch, int reason, int code) {
            h.post(() -> voiceClosed(ch));
        }
    };
    private Task<Void> registration;
    private final Runnable noHeard = () -> settle(getString(R.string.talk_no_answer));
    private final Runnable quiet = this::askStatus;
    private final Runnable noStatus = () -> fail(R.string.talk_lost);
    private final Runnable ceiling = () -> fail(R.string.talk_ceiling);
    private final Runnable forceStop = this::stop;

    /**
     * Starts the conversation: the listener first, then {@code send}, so a fast answer can't come before anyone listens.
     * If this chat's conversation is still live the words join it (lines and speech stay) instead of replacing it.
     */
    static void begin(Context c, String chat, String title, int accent, Sender send) {
        Call.hangUp(); // the last one started owns the speaker
        boolean join = instance != null && chat.equals(Talk.chat);
        boolean wasWaiting = waiting;
        if (!join) {
            Talk.chat = chat;
            Talk.title = title;
            Talk.accent = accent;
            lines.clear();
            queue.clear();
            heard = false;
            accepted = false;
            run++;
        }
        waiting = true;
        status = "";
        unseen = false;
        queue.add(send);
        if (join) instance.join(wasWaiting);
        else c.startForegroundService(new Intent(c, Talk.class)); // the screen is showing, so this is allowed
        changed();
    }

    /** The screen that shows this chat: from the chat list, the ongoing notification or a new talk. */
    static Intent intent(Context c, String chat, String title, int accent) {
        return new Intent(c, TalkActivity.class)
                .putExtra("chat", chat).putExtra("title", title).putExtra("accent", accent);
    }

    /** The user closed the talk screen (Back or a swipe): stop waiting (what is being said is finished). */
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
        Wearable.getChannelClient(this).unregisterChannelCallback(channelCallback);
        hush();
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
        Wearable.getChannelClient(this).registerChannelCallback(channelCallback); // from start to stop: voice channels
        register();
        next();
        return START_NOT_STICKY;
    }

    private void register() {
        final Task<Void> t = Wearable.getMessageClient(this).addListener(listener);
        registration = t;
        t.addOnFailureListener(e -> {
            if (waiting && registration == t) fail(R.string.talk_connect);
        });
    }

    /**
     * Sends the oldest queued words once the listener is registered. Words are sent one at a time: the next ones go
     * only after these are settled (heard, refused, failed or given up), so their answers can't be mixed up.
     */
    private void next() {
        if (queue.isEmpty()) return;
        final Sender send = queue.remove(0);
        heard = false;
        h.removeCallbacks(noHeard);
        h.postDelayed(noHeard, NO_HEARD_MS); // armed before anything is sent
        final int mine = run, w = ++sends;
        registration.addOnSuccessListener(v -> {
            if (active(mine) && w == sends) send.send(() -> active(mine) && w == sends, error -> {
                if (error != null && active(mine) && w == sends) settle(error);
            });
        });
    }

    /** New words for the conversation that is live: waiting, or over but its replies are still being said. */
    private void join(boolean wasWaiting) {
        if (!wasWaiting) {
            h.removeCallbacks(forceStop);
            getSystemService(NotificationManager.class).notify(1, notification(R.string.talk_notif_waiting));
            // the listener stays registered after the end (end() keeps it); only a dead registration is retried
            if (registration.isComplete() && !registration.isSuccessful()) register();
            next();
        } else if (heard) {
            next(); // else the words wait in the queue for the ones in flight
        }
        h.removeCallbacks(ceiling);
        h.postDelayed(ceiling, CEILING_MS);
        wake.acquire(WAKE_MS);
    }

    /**
     * The words in flight failed (send, refusal or no answer): say why, then go on with the next queued words. The wait
     * itself goes on while the phone has accepted a run (it will send replies and a done), else it ends.
     */
    private void settle(String error) {
        sends++; // callbacks of the words given up must not act any more
        heard = true;
        h.removeCallbacks(noHeard);
        say(ERROR, error);
        if (!queue.isEmpty()) {
            next();
        } else if (accepted) {
            h.removeCallbacks(quiet); // nothing re-arms it while words are in flight
            h.postDelayed(quiet, QUIET_MS);
        } else {
            end();
            return;
        }
        changed();
    }

    /** Whether conversation {@code r} is still the one being waited for. */
    private static boolean active(int r) {
        return waiting && r == run;
    }

    private Notification notification(int text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, intent(this, chat, title, accent),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ms_mic)
                .setContentText(getString(text, title)).setContentIntent(open).setOngoing(true).build();
    }

    // ---------------------------------------------------------------- from the phone

    private void onPhone(MessageEvent e) {
        if (e.getPath().startsWith(Phone.LIVE)) return; // a call's (Call listens): not an answer, nothing to reset
        if (!e.getPath().startsWith(PATHS) || (!waiting && !Phone.SAY.equals(e.getPath()))) return; // after the end only /buddy/say
        JSONObject in;
        try {
            in = new JSONObject(new String(e.getData(), StandardCharsets.UTF_8));
        } catch (JSONException x) {
            return;
        }
        if (!chat.equals(in.optString("chat"))) return;
        if (Phone.SAY.equals(e.getPath())) { // the phone's voice couldn't say reply id: the watch does
            sayItself(in.optLong("id"), "say");
            return;
        }
        String text = in.optString("text");
        switch (e.getPath()) {
            case Phone.HEARD:
                heard = accepted = true;
                h.removeCallbacks(noHeard);
                lines.add(new Line(YOU, text));
                if (!queue.isEmpty()) next();
                break;
            case Phone.STEP:
                status = text;
                break;
            case Phone.REPLY:
                status = "";
                ChatsActivity.remember(this, chat, title, accent); // the default chat: the one that last answered
                say(in.optBoolean("error") ? ERROR : BUDDY, text, in.optLong("voice", -1));
                break;
            case Phone.DONE:
                if ("stopped".equals(in.optString("outcome"))) say(ERROR, getString(R.string.talk_stopped));
                accepted = false;
                // words in flight: this done belongs to the run before them; they decide whether the wait goes on
                if (heard) end();
                else changed();
                return;
            case Phone.ERROR:
                settle(text);
                return;
            case Phone.STATUS:
                h.removeCallbacks(noStatus);
                if (!in.optBoolean("running")) {
                    if (heard) fail(R.string.talk_stopped_working); // running is false and no done came: the answer is lost
                    return; // words in flight: a status asked before them, they start their own run
                }
                // only a sign of life: not news for the screen (it would never be released), but keep asking
                h.removeCallbacks(quiet);
                h.postDelayed(quiet, QUIET_MS);
                return;
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
        final int mine = run;
        try {
            Phone.send(this, Phone.STATUS, new JSONObject().put("chat", chat), () -> active(mine), error -> {
                if (error != null && active(mine)) noStatus.run();
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
        for (Say s : new ArrayList<>(toSay)) toTts(s, "fail"); // no more waiting for a voice that may never come
        say(ERROR, text);
        end();
    }

    /** The wait is over: no more deadlines or listening. The screen shows it; the service stops when all is said. */
    private void end() {
        h.removeCallbacks(noHeard);
        h.removeCallbacks(quiet);
        h.removeCallbacks(noStatus);
        h.removeCallbacks(ceiling);
        queue.clear(); // words not sent yet go with the wait
        accepted = false;
        run++;
        waiting = false; // the listener stays until stop: after the end it only takes /buddy/say
        status = "";
        getSystemService(NotificationManager.class).notify(1, notification(R.string.talk_notif_speaking));
        h.postDelayed(forceStop, SPEAK_MS); // a hung engine must not keep the watch awake
        changed();
        maybeStop();
    }

    private void maybeStop() {
        if (!waiting && pending.isEmpty() && toSay.isEmpty()) stop();
    }

    private void stop() {
        if (instance == this) instance = null; // a service that is going away must not be joined
        h.removeCallbacksAndMessages(null);
        release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        Wearable.getMessageClient(this).removeListener(listener);
        Wearable.getChannelClient(this).unregisterChannelCallback(channelCallback);
        release();
        if (tts != null) tts.shutdown();
        if (wake != null && wake.isHeld()) wake.release();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    // ---------------------------------------------------------------- speech

    /** Shows a line and says it (in order, once). End messages are said too: the user may not be looking. */
    private void say(int kind, String text) {
        say(kind, text, -1);
    }

    /** voice: a reply id the phone streams in ChatGPT's voice (-1: the watch's TTS says it). */
    private void say(int kind, String text, long voice) {
        lines.add(new Line(kind, text));
        if (voice >= 0 && saidByWatch.remove(voice)) voice = -1; // the phone already gave up on its voice
        Log.i("BuddyTalk", "reply arrived" + (voice >= 0 ? " (voice " + voice + ")" : " (tts)"));
        if (voice < 0 && ttsFailed) return;
        toSay.add(new Say(text, voice));
        pump();
    }

    /** Moves the head of the queue on: TTS goes to the engine at once; a voice reply waits for the TTS before it. */
    private void pump() {
        while (!toSay.isEmpty()) {
            Say s = toSay.get(0);
            if (s.voice < 0) {
                if (!ttsReady && !ttsFailed) break; // ttsInit() calls pump again
                toSay.remove(0);
                if (!ttsFailed) speak(s);
            } else {
                if (pending.isEmpty()) startVoice(s);
                break; // what comes after waits for this
            }
        }
        maybeStop();
    }

    private void speak(Say s) {
        String id = "u" + utterances++;
        pending.add(id);
        ttsArrived.put(id, s.arrived);
        if (tts.speak(s.text, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.ERROR) { // no callback will come for it
            pending.remove(id);
            ttsArrived.remove(id);
            lines.add(new Line(ERROR, getString(R.string.talk_no_voice)));
            changed();
        }
    }

    // ---------------------------------------------------------------- ChatGPT's voice for a reply

    /** The head voice reply: its 35 s start now; its channel is played when it is here. */
    private void startVoice(Say s) {
        if (s.deadline == null) {
            s.deadline = () -> toTts(s, "timeout");
            h.postDelayed(s.deadline, HEAD_MS);
        }
        ChannelClient.Channel ch = channels.get(s.voice);
        if (ch == null || s.speaker != null) return;
        final Speaker sp = new Speaker(false, "voice " + s.voice, new Speaker.Listener() {
            @Override
            public boolean audible() {
                synchronized (s) { // one owner: the first sound or the fallback, whichever claims first
                    if (s.fellBack) return false;
                    s.committed = true; // here, not posted: a /buddy/say that comes next must find it already the voice's
                }
                h.post(() -> {
                    Log.i("BuddyTalk", "first sound (voice) " + (SystemClock.elapsedRealtime() - s.arrived) + " ms after the reply");
                    if (s.deadline != null) h.removeCallbacks(s.deadline);
                });
                return true;
            }

            @Override
            public void ended(String by) {
                h.post(() -> streamEnded(s, by));
            }
        });
        s.speaker = sp;
        Wearable.getChannelClient(this).getInputStream(ch)
                .addOnSuccessListener(sp::play)
                .addOnFailureListener(e -> toTts(s, "closed"));
    }

    /** The head's stream ended or broke (main thread). Audible before: the voice said it, never again. Else the watch does. */
    private void streamEnded(Say s, String by) {
        if (!toSay.contains(s) || s.speaker == null) return; // converted or hushed meanwhile
        if (s.committed && !s.speaker.sounded()) { // claimed, but the first write failed: nobody heard it. The speaker is done, so no race
            synchronized (s) {
                s.committed = false;
            }
        }
        if (!s.committed) {
            toTts(s, by);
            return;
        }
        toSay.remove(s);
        if (s.deadline != null) h.removeCallbacks(s.deadline);
        s.speaker = null;
        closeChannel(s.voice);
        pump();
    }

    /** The phone gave up on its voice for reply id, or the watch did: TTS says it (unless the voice already started it). */
    private void sayItself(long id, String why) {
        for (Say s : toSay) {
            if (s.voice == id) {
                toTts(s, why);
                return;
            }
        }
        saidByWatch.add(id); // the reply itself is still to come
        closeChannel(id);
    }

    private void toTts(Say s, String why) {
        if (s.voice < 0 || !toSay.contains(s)) return;
        synchronized (s) {
            if (s.committed) return;
            s.fellBack = true;
        }
        Log.i("BuddyTalk", "voice " + s.voice + " becomes tts (" + why + ")");
        if (s.deadline != null) h.removeCallbacks(s.deadline);
        if (s.speaker != null) s.speaker.stop(why);
        s.speaker = null;
        closeChannel(s.voice); // unread: the phone retires that reply
        s.voice = -1;
        pump();
    }

    private void voiceOpened(ChannelClient.Channel ch) {
        String path = ch.getPath();
        if (!path.startsWith(Phone.VOICE)) return;
        long id;
        try {
            id = Long.parseLong(path.substring(Phone.VOICE.length()));
        } catch (NumberFormatException e) {
            return;
        }
        if (saidByWatch.contains(id)) { // the phone already said the watch says it
            Wearable.getChannelClient(this).close(ch);
            return;
        }
        channels.put(id, ch);
        pump();
    }

    /** A channel closed: by the phone (cancelled, link gone) or by us. Nothing audible from it yet means the watch says it. */
    private void voiceClosed(ChannelClient.Channel ch) {
        String path = ch.getPath();
        if (!path.startsWith(Phone.VOICE)) return;
        long id;
        try {
            id = Long.parseLong(path.substring(Phone.VOICE.length()));
        } catch (NumberFormatException e) {
            return;
        }
        if (!channels.containsKey(id)) return; // closed by us (read, converted, hushed)
        channels.remove(id);
        for (Say s : toSay) {
            if (s.voice == id) {
                toTts(s, "closed"); // changes toSay
                return;
            }
        }
    }

    private void closeChannel(long id) {
        ChannelClient.Channel ch = channels.remove(id);
        if (ch != null) Wearable.getChannelClient(this).close(ch);
    }

    /** Silences the voice and lets go of every channel (hush, stop). */
    private void release() {
        for (Say s : toSay) {
            if (s.deadline != null) h.removeCallbacks(s.deadline);
            if (s.speaker != null) s.speaker.stop("hush");
            s.speaker = null;
        }
        for (ChannelClient.Channel ch : channels.values()) Wearable.getChannelClient(this).close(ch);
        channels.clear();
    }

    private void ttsInit(int status) {
        if (status != TextToSpeech.SUCCESS) {
            ttsFailed = true;
            lines.add(new Line(ERROR, getString(R.string.talk_no_voice))); // shown, not said
            changed();
            pump(); // drops the TTS items; voice replies can still be played
            return;
        }
        tts.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String id) {
                h.post(() -> {
                    Long at = ttsArrived.remove(id);
                    if (at != null) Log.i("BuddyTalk", "first sound (tts) " + (SystemClock.elapsedRealtime() - at) + " ms after the reply");
                });
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
        pump(); // every speak() may have been refused
    }

    private void finished(String id) {
        h.post(() -> {
            pending.remove(id);
            ttsArrived.remove(id);
            pump(); // a voice reply waiting behind this one is next
        });
    }

    private void hush() {
        pending.clear();
        ttsArrived.clear();
        release(); // the voice and its channels; the phone retires those replies
        toSay.clear(); // replies buffered before the engine was ready are silenced too
        saidByWatch.clear();
        if (tts != null && ttsReady) tts.stop();
        maybeStop();
    }
}
