package app.buddy.assistant;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The phone's end of the Galaxy Watch app (the wear/ module): the watch lists the chats, sends what the user said to
 * one of them, and gets Buddy's answer back to show and speak. Messages are JSON over the Wear Data Layer, paths under
 * /buddy (the table is in docs/features/003-wear-companion/plan.md). Github build only (src/watchlink).
 */
public class WatchLink extends WearableListenerService {
    private static final String CHATS = "/buddy/chats", SEND = "/buddy/send", STATUS = "/buddy/status";
    private static final int TITLE_MAX = 40;
    /** MessageClient carries 100 KB; stop adding chats before the list itself can fail (about 900 chats). */
    private static final int LIST_MAX_BYTES = 90_000;
    private static final int SPOKEN_MAX = 3000;
    private static final long WAKE_MS = 15 * 60_000L;

    private static final Handler main = new Handler(Looper.getMainLooper());
    /** Chats whose run answers to a watch: chat id → the watch's node id. Main thread. */
    private static final Map<String, String> tracked = new HashMap<>();
    private static Context app;
    private static PowerManager.WakeLock wake;
    private static boolean listening;

    @Override
    public void onMessageReceived(MessageEvent e) {
        final Context c = getApplicationContext();
        final String path = e.getPath(), node = e.getSourceNodeId();
        final JSONObject in;
        try {
            in = new JSONObject(new String(e.getData(), StandardCharsets.UTF_8));
        } catch (JSONException x) {
            return;
        }
        // this thread is not the main one: keep the phone awake until the message has been handed over
        if (SEND.equals(path)) holdWake(c);
        main.post(() -> {
            app = c;
            switch (path) {
                case CHATS:
                    chats(node);
                    break;
                case SEND:
                    deliver(node, in.optString("chat"), in.optString("text").trim());
                    break;
                case STATUS:
                    Sessions.Session ses = ChatHub.get(c).sessions.find(in.optString("chat"));
                    tell(node, STATUS, json("chat", in.optString("chat"), "running", ses != null && ses.running));
                    break;
                default:
                    break;
            }
        });
    }

    /** Top-level chats that aren't archived, pinned first, then the chat list's own order (most recent first). */
    private static void chats(String node) {
        List<Sessions.Session> list = new ArrayList<>();
        List<Sessions.Session> all = ChatHub.get(app).sessions.all;
        for (Sessions.Session s : all) if (s.parent.isEmpty() && !s.archived && s.pinned) list.add(s);
        for (Sessions.Session s : all) if (s.parent.isEmpty() && !s.archived && !s.pinned) list.add(s);
        JSONArray rows = new JSONArray();
        int bytes = 0;
        for (Sessions.Session s : list) {
            String title = s.label();
            if (title.length() > TITLE_MAX) title = title.substring(0, TITLE_MAX) + "…";
            JSONObject row = json("id", s.id, "title", title, "running", s.running);
            bytes += row.toString().getBytes(StandardCharsets.UTF_8).length + 1;
            if (bytes > LIST_MAX_BYTES) break;
            rows.put(row);
        }
        tell(node, CHATS, json("accent", Prefs.accent(app), "chats", rows));
    }

    /** What the user said on the watch goes to the chat exactly like a typed message. */
    private static void deliver(String node, String chat, String text) {
        ChatHub hub = ChatHub.get(app);
        Sessions.Session ses = hub.sessions.find(chat);
        if (text.isEmpty()) {
            refuse(node, chat, app.getString(R.string.hub_something_wrong));
        } else if (ses == null) {
            refuse(node, chat, app.getString(R.string.watch_chat_gone));
        } else if (BuddyService.get() == null) {
            // agent events reach ChatHub only through the accessibility service's MCP server: a run could never answer
            refuse(node, chat, app.getString(R.string.watch_buddy_off));
        } else {
            String err = hub.send(ses, text); // a chat that is running takes it as a note
            if (err != null) {
                refuse(node, chat, err);
                return;
            }
            tell(node, "/buddy/heard", json("chat", chat, "text", text));
            tracked.put(chat, node);
            ses.watch = true;
            if (!listening) {
                listening = true;
                hub.addListener(relay);
            }
        }
    }

    private static void refuse(String node, String chat, String text) {
        tell(node, "/buddy/error", json("chat", chat, "text", text));
        if (tracked.isEmpty()) releaseWake();
    }

    /** Sends a run's messages on to the watch it was asked from (main thread, like ChatHub). */
    private static final ChatHub.Listener relay = new ChatHub.Listener() {
        @Override
        public void onMessage(Sessions.Session ses, Sessions.Msg msg) {
            String node = tracked.get(ses.id);
            if (node == null) return;
            if (msg.kind == Bubble.Kind.STEP) {
                tell(node, "/buddy/step", json("chat", ses.id, "text", msg.text));
            } else if (msg.kind == Bubble.Kind.BUDDY || msg.kind == Bubble.Kind.ERROR) {
                String text = Voice.spoken(app, msg.text); // one text to show and to speak: no markdown, no URLs
                if (text.length() > SPOKEN_MAX) text = text.substring(0, SPOKEN_MAX) + "…";
                if (!text.isEmpty())
                    tell(node, "/buddy/reply", json("chat", ses.id, "text", text, "error", msg.kind == Bubble.Kind.ERROR));
            }
        }

        @Override
        public void onChatsChanged() {
            for (Map.Entry<String, String> t : tracked.entrySet()) {
                Sessions.Session ses = ChatHub.get(app).sessions.find(t.getKey());
                // Not decided here but a turn later: a run that ends with notes the agent never got starts a second
                // run from a post of ChatHub's own (buddy_done), which comes before this call
                if (ses == null || !ses.running) {
                    main.post(WatchLink::endCheck);
                    return;
                }
            }
        }
    };

    /** A tracked chat that is not running by now has ended for good: tell the watch. Safe to run more than once. */
    private static void endCheck() {
        for (Map.Entry<String, String> t : new ArrayList<>(tracked.entrySet())) {
            Sessions.Session ses = ChatHub.get(app).sessions.find(t.getKey());
            if (ses != null && ses.running) continue; // a second run took over: it is still the watch's
            tell(t.getValue(), "/buddy/done", json("chat", t.getKey(), "outcome", ses == null || ses.outcome == null ? "" : ses.outcome));
            tracked.remove(t.getKey());
            if (ses != null) ses.watch = false;
        }
        if (tracked.isEmpty()) releaseWake();
    }

    /** Keeps the phone's CPU on while a watch run works with the screen off, as notification rules do. */
    private static void holdWake(Context c) {
        synchronized (WatchLink.class) {
            if (wake == null) {
                wake = c.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "buddy:watch");
                wake.setReferenceCounted(false);
            }
            wake.acquire(WAKE_MS);
        }
    }

    private static void releaseWake() {
        synchronized (WatchLink.class) {
            if (wake != null && wake.isHeld()) wake.release();
        }
    }

    /** Fire and forget: the watch notices a lost message with its own status check. */
    private static void tell(String node, String path, JSONObject body) {
        Wearable.getMessageClient(app).sendMessage(node, path, body.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static JSONObject json(Object... pairs) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i < pairs.length; i += 2) o.put((String) pairs[i], pairs[i + 1]);
        } catch (JSONException e) {
            throw new IllegalStateException(e); // only for a NaN number
        }
        return o;
    }
}
