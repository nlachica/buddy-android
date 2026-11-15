package app.buddy.assistant;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * One live voice call from the watch (docs/features/007-watch-live-voice/plan.md, "(a) Calls from the watch"): the
 * watch opens the channel /buddy/live and writes a header line {"chat","call"}, then its microphone as PCM 16 kHz
 * mono; the voice goes back on the same channel. The call itself is {@link CodexLive}'s, with this as its Remote.
 * Github build only (src/watchlink).
 */
final class WatchCall implements CodexLive.Remote {
    static final String PATH = "/buddy/live", STATUS = "/buddy/live/status", END = "/buddy/live/end";
    private static final int FRAME = 1280; // 40 ms
    private static final long HEADER_MS = 5000, WAKE_EVERY_MS = 60_000, CLOSE_DELAY_MS = 500;
    private static final Handler main = new Handler(Looper.getMainLooper());
    /** The watch call that is on (main thread writes). */
    private static volatile WatchCall live;
    /** Channels are numbered as they open; begun is the newest number that started (main thread): an older one is stale. */
    private static final java.util.concurrent.atomic.AtomicLong opened = new java.util.concurrent.atomic.AtomicLong();
    private static long begun;

    private final Context app;
    private final ChannelClient.Channel channel;
    private final String node;
    private final long seq = opened.incrementAndGet();
    /** The call's out stream: 2 s, dropping the oldest. */
    private final PcmOut out = new PcmOut(64_000, true, 60_000, () -> main.post(this::hangUp));
    private String chat = "";
    private long call;
    /** CodexLive.start accepted this call (main thread). */
    private boolean started, finished;

    private WatchCall(Context app, ChannelClient.Channel channel) {
        this.app = app;
        this.channel = channel;
        this.node = channel.getNodeId();
    }

    /** A watch call is on (any thread). */
    static boolean on() {
        return live != null;
    }

    /** The watch call that is on is in this chat. */
    static boolean in(String chat) {
        WatchCall c = live;
        return c != null && c.chat.equals(chat);
    }

    /** The watch opened /buddy/live (any thread): read its header and start the call. */
    static void open(Context app, ChannelClient.Channel channel) {
        WatchCall c = new WatchCall(app, channel);
        new Thread(c::read, "watch-call").start();
    }

    private void read() {
        final Runnable headerLate = this::closeChannel;
        main.postDelayed(headerLate, HEADER_MS);
        try {
            ChannelClient cc = Wearable.getChannelClient(app);
            InputStream in = Tasks.await(cc.getInputStream(channel));
            out.attach(Tasks.await(cc.getOutputStream(channel)), this::closeChannel);
            JSONObject head = new JSONObject(readLine(in));
            main.removeCallbacks(headerLate);
            chat = head.optString("chat");
            call = head.optLong("call");
            main.post(this::begin);
            DataInputStream d = new DataInputStream(in);
            long woke = SystemClock.elapsedRealtime();
            while (true) {
                byte[] frame = new byte[FRAME];
                d.readFully(frame);
                CodexLive.push(this, frame);
                long now = SystemClock.elapsedRealtime();
                if (live == this && now - woke > WAKE_EVERY_MS) {
                    woke = now;
                    WatchLink.holdWake(app);
                }
            }
        } catch (Exception e) { // the watch hung up, or the link dropped, or the header never came
            // fall through
        }
        main.removeCallbacks(headerLate);
        main.post(this::hangUp);
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        for (int c; (c = in.read()) != '\n'; ) {
            if (c < 0 || b.length() > 500) throw new IOException("no header");
            b.append((char) c);
        }
        return b.toString();
    }

    /** The checks of a talk, in their order, then the call (main thread). */
    private void begin() {
        if (finished) return;
        if (seq < begun) { // its header came late: a newer call is already on
            finished = true;
            out.cancel();
            return;
        }
        begun = seq;
        Sessions.Session ses = ChatHub.get(app).sessions.find(chat);
        String err;
        if (ses == null) err = app.getString(R.string.watch_chat_gone);
        else if (BuddyService.get() == null) err = app.getString(R.string.watch_buddy_off);
        else err = CodexLive.start(app, ses, this);
        if (err != null) {
            refuse(err);
            return;
        }
        if (finished) return; // ended while starting (a newer call took its place)
        started = true;
        live = this;
        // the call owns its chat: its replies reach the watch only through the call's voice
        WatchLink.untrack(chat);
        ses.watch = true; // the phone stays quiet (no run-ended notification); read-aloud is off in any call
        WatchLink.holdWake(app);
    }

    private void refuse(String text) {
        finished = true;
        WatchLink.tell(node, END, WatchLink.json("chat", chat, "call", call, "text", text));
        out.cancel();
    }

    /** The watch hung up or vanished, or the stream broke (main thread): end the call if it is still this one. */
    private void hangUp() {
        if (CodexLive.on(this)) CodexLive.end(null); // comes back through ended()
        else if (!started && !finished) { // never started: nothing to end, only the channel to let go
            finished = true;
            out.cancel();
        }
    }

    // ------------------------------------------------------------------ CodexLive.Remote

    @Override
    public void pcm(byte[] pcm16k) {
        out.write(pcm16k);
    }

    @Override
    public void status(String text) {
        WatchLink.tell(node, STATUS, WatchLink.json("chat", chat, "call", call, "text", text));
    }

    @Override
    public void ended(String text) {
        finished = true;
        WatchLink.tell(node, END, WatchLink.json("chat", chat, "call", call, "text", text));
        // the channel closes a moment later, so the reason usually reaches the watch first
        main.postDelayed(out::cancel, CLOSE_DELAY_MS);
        if (live == this) live = null;
        Sessions.Session ses = ChatHub.get(app).sessions.find(chat);
        // its own chat's flag: kept if the live watch call is in that chat (a replacement) or a talk run tracks it
        if (ses != null && !in(chat) && !WatchLink.tracks(chat)) ses.watch = false;
        // the shared wake lock: released only with no watch call on and no talk run tracked
        WatchLink.releaseIfIdle();
    }

    private void closeChannel() {
        Wearable.getChannelClient(app).close(channel);
    }
}
