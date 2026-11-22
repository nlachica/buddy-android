package app.buddy.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.List;

/**
 * "Hey buddy": the foreground service that owns the detector's microphone, its capture thread and the notification.
 * Declared in the github-only manifest (app/src/watchlink); WakeWord.ensure is the one way it is started. Every 500 ms
 * (100 ms while Buddy speaks) and on every recording change the main thread asks {@link WakeWord#state} what the
 * microphone should be and tells the capture thread; the capture thread alone opens, reads and closes it.
 */
public final class WakeService extends Service implements WakeLoop.Host, WakeLoop.Sink {
    static final String ACTION_OFF = "app.buddy.assistant.WAKE_OFF";
    private static final String TAG = "BuddyWake", CHANNEL = "wake";
    private static final int FG_ID = 13, TAP_ID = 14;
    /** MediaRecorder.AudioSource.HOTWORD (a system API): the phone's own always-on listeners are not "someone recording". */
    private static final int HOTWORD = 1999;
    private static final String[] MODELS = {"hey-buddy", "mel-spectrogram", "speech-embedding"};

    private static volatile WakeService instance;
    /** The models couldn't be loaded: a broken build, not worth trying again in this process. */
    static volatile boolean broken;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::tick;
    private final AudioManager.AudioRecordingCallback recordings = new AudioManager.AudioRecordingCallback() {
        @Override
        public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
            main.post(tick);
        }
    };
    private final WakeMic mic = new WakeMic();
    private volatile WakeLoop loop;
    private volatile boolean running;
    /** What the capture thread should have: 0 the microphone closed, 1 idle, 2 while Buddy speaks. */
    private volatile int want;
    /** The capture thread has, or is about to open, the microphone; guarded with {@code want} by {@code held}'s lock. */
    private final Object held = new Object();
    private volatile boolean heldOpen;
    /** The detector's own audio session (0 while its recorder is closed or opening). */
    private volatile int session;
    private long lastCause, speechEndedAt; // elapsedRealtime ms; 0: never
    private boolean wasSpeaking;
    private int shown;

    static boolean running() {
        return instance != null;
    }

    static WakeService get() {
        return instance;
    }

    /** Main thread: look at the state again now (a hook of Buddy's own voice paths). */
    static void poke() {
        WakeService s = instance;
        if (s != null) s.main.post(s.tick);
    }

    WakeLoop loop() {
        return loop;
    }

    /**
     * Main thread: the detector's microphone closes now (the loop forgets what it heard); the next tick may reopen it.
     * Returns true once the capture thread has released the recorder (one 40 ms read at most), false after 300 ms
     * without it: the caller's own recorder must not open against it then.
     */
    boolean closeNow() {
        synchronized (held) {
            want = 0;
        }
        WakeLoop l = loop;
        if (l != null) l.drop();
        long end = SystemClock.elapsedRealtime() + 300;
        while (heldOpen && SystemClock.elapsedRealtime() < end) SystemClock.sleep(5);
        return !heldOpen;
    }

    // ---- the service

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(FG_ID, notification(R.string.wake_listening), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } catch (IllegalStateException | SecurityException e) { // started from the background, or the permission is gone
            Log.i(TAG, "foreground start refused: " + e);
            stopSelf();
            tapToStart(this);
            return START_NOT_STICKY;
        }
        getSystemService(NotificationManager.class).cancel(TAP_ID);
        if (!running) {
            running = true;
            instance = this;
            shown = R.string.wake_listening;
            getSystemService(AudioManager.class).registerAudioRecordingCallback(recordings, main);
            Thread t = new Thread(this::capture, "wake-capture");
            t.start();
            Log.i(TAG, "service started");
        }
        main.post(tick);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        main.removeCallbacksAndMessages(null);
        getSystemService(AudioManager.class).unregisterAudioRecordingCallback(recordings);
        if (WakeWord.turnOn()) WakeWord.cancelTurn();
        super.onDestroy();
    }

    private void shutdown() {
        running = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    /** The microphone can't be had (any way): stop, say why in the log and offer one tap to try again. */
    private void fail(String why) {
        Log.i(TAG, "listening stopped: " + why);
        main.post(() -> {
            if (!running) return;
            shutdown();
            tapToStart(this);
        });
    }

    // ---- the capture thread

    private void capture() {
        WakeFeatures.Engine engine;
        WakeLoop l;
        try {
            engine = engine();
            l = new WakeLoop(new WakeFeatures(engine), this, this);
        } catch (Exception e) {
            Log.i(TAG, "the models didn't load: " + e);
            broken = true;
            main.post(this::shutdown);
            return;
        }
        loop = l;
        main.post(tick);
        short[] buf = new short[640]; // 40 ms
        int have = 0;
        while (running) {
            int w;
            synchronized (held) { // closeNow either sees the microphone held, or this sees want = 0
                w = want;
                if (w != 0) heldOpen = true;
            }
            if (w != have) {
                session = 0;
                mic.close();
                boolean switching = have != 0; // idle <-> speaking keeps what was heard: "hey" may come before the switch
                have = 0;
                if (w == 0) heldOpen = false;
                else {
                    if (!switching) l.drop(); // after a real pause or stop, nothing heard before can trigger
                    if (!mic.open(w == 2)) {
                        fail("the microphone didn't open");
                        break;
                    }
                    session = mic.session();
                    have = w;
                }
                main.post(tick);
            }
            if (have == 0) {
                SystemClock.sleep(50);
                continue;
            }
            int n = mic.read(buf, buf.length);
            if (n < 0) {
                fail("read error " + n);
                break;
            }
            if (n > 0) l.block(buf, n, SystemClock.elapsedRealtime());
        }
        session = 0;
        mic.close();
        heldOpen = false;
        loop = null;
        try {
            ((AutoCloseable) engine).close();
        } catch (Exception ignored) {
        }
    }

    /** The ONNX engine, loaded by name (it is in the github build only). */
    private WakeFeatures.Engine engine() throws Exception {
        byte[][] m = new byte[3][];
        for (int i = 0; i < 3; i++) {
            try (java.io.InputStream in = getAssets().open("wakeword/" + MODELS[i] + ".onnx")) {
                m[i] = in.readAllBytes();
            }
        }
        return (WakeFeatures.Engine) Class.forName("app.buddy.assistant.OnnxWakeEngine")
                .getConstructor(byte[].class, byte[].class, byte[].class).newInstance(m[0], m[1], m[2]);
    }

    @Override
    public boolean feed(int capture, byte[] pcm) {
        return MicServer.feed(capture, pcm);
    }

    @Override
    public void onWake(int seq, long detectedAt) {
        main.post(() -> WakeWord.onWake(seq, detectedAt));
    }

    @Override
    public void onFailed(RuntimeException e) {
        Log.i(TAG, "the models failed: " + e); // the loop stops scoring; the capture loop ends with the service and releases the microphone
        broken = true;
        main.post(this::shutdown);
    }

    @Override
    public void onHoldExpired(int seq) {
        main.post(WakeWord::cancelTurn);
    }

    // ---- the main thread: what should the microphone be

    private WakeWord.Inputs inputs(long now) {
        WakeWord.Inputs in = new WakeWord.Inputs();
        BuddyService bs = BuddyService.get();
        in.enabled = WakeWord.wanted(this);
        in.call = bs != null && bs.autoPauser() != null && bs.autoPauser().callNow();
        int own = session;
        for (AudioRecordingConfiguration c : getSystemService(AudioManager.class).getActiveRecordingConfigurations()) {
            int s = c.getClientAudioSessionId();
            if (s != own && c.getClientAudioSource() != HOTWORD && !MicServer.mine(s)) in.foreignRecordings++;
        }
        in.turn = WakeWord.turnOn();
        in.turnFed = WakeWord.turnFed();
        in.ownVoice = Voice.listening() || CodexLive.on() || MicServer.serving();
        in.speaking = Voice.phoneSpeaking();
        if (WakeWord.cause(in)) lastCause = now;
        in.msSinceCause = lastCause == 0 ? Long.MAX_VALUE : now - lastCause;
        return in;
    }

    private void tick() {
        main.removeCallbacks(tick);
        if (!running) return;
        if (loop == null || (want != 0 && session == 0)) { // models loading, or the recorder is opening: its own recording isn't known yet
            main.postDelayed(tick, 100);
            return;
        }
        long now = SystemClock.elapsedRealtime(); // the clock the capture thread stamps detections with
        boolean speaking = Voice.isSpeaking();
        if (wasSpeaking && !speaking) speechEndedAt = now;
        wasSpeaking = speaking;
        WakeWord.Inputs in = inputs(now);
        WakeWord.State st = WakeWord.state(in);
        if ((st == WakeWord.State.OFF || st == WakeWord.State.PAUSED) && WakeWord.turnOn()) {
            if (in.foreignRecordings > 0 && !in.call) Voice.fedCancelled(); // 2 of these in a row: Phone input is no longer fed
            WakeWord.cancelTurn();
        }
        switch (st) {
            case OFF:
                Log.i(TAG, "off");
                shutdown();
                return;
            case PAUSED:
                if (want != 0) {
                    closeNow();
                    Log.i(TAG, "paused: " + (in.call ? "call" : "microphone in use"));
                }
                show(in.call ? R.string.wake_paused_call : R.string.wake_paused_mic);
                break;
            case TURN:
                if (!in.turnFed) want = 0; // the recognizer records by itself; a fed turn keeps the microphone as it is
                break;
            case SPEAKING:
                want = 2;
                show(R.string.wake_listening);
                break;
            default:
                want = 1;
                show(R.string.wake_listening);
        }
        main.postDelayed(tick, speaking ? 100 : 500);
    }

    /** What the microphone should be right now. */
    WakeWord.State stateNow() {
        return WakeWord.state(inputs(SystemClock.elapsedRealtime()));
    }

    /** When Buddy's speech last ended (elapsedRealtime ms; 0: never spoke). */
    long speechEndedAt() {
        return speechEndedAt;
    }

    private void show(int text) {
        if (shown == text) return;
        shown = text;
        try {
            getSystemService(NotificationManager.class).notify(FG_ID, notification(text));
        } catch (SecurityException ignored) { // notifications off: it still listens
        }
    }

    // ---- notifications

    private Notification notification(int text) {
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, getString(R.string.wake_channel), NotificationManager.IMPORTANCE_LOW));
        PendingIntent off = PendingIntent.getBroadcast(this, 20, new Intent(this, HiddenNotice.class).setAction(ACTION_OFF),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent open = PendingIntent.getActivity(this, 21,
                new Intent(this, SetupActivity.class).putExtra("page", "voice").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(getString(text))
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, getString(R.string.wake_turn_off), off).build())
                .setOngoing(true)
                .build();
    }

    /** One quiet notification whose tap starts the service (a notification tap is an exemption from the background rule). */
    static void tapToStart(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, c.getString(R.string.wake_channel), NotificationManager.IMPORTANCE_LOW));
        PendingIntent start = PendingIntent.getForegroundService(c, 22, new Intent(c, WakeService.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            nm.notify(TAP_ID, new Notification.Builder(c, CHANNEL)
                    .setSmallIcon(R.drawable.ic_launcher_monochrome)
                    .setContentTitle(c.getString(R.string.wake_tap))
                    .setContentIntent(start)
                    .setAutoCancel(true)
                    .build());
        } catch (SecurityException ignored) { // notifications off: opening Buddy tries again
        }
    }

    static void cancelTap(Context c) {
        c.getSystemService(NotificationManager.class).cancel(TAP_ID);
    }
}
