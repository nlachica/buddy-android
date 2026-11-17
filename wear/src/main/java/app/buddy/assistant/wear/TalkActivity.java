package app.buddy.assistant.wear;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognizerIntent;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Speak to one chat and read what Buddy answers. The words come from the watch's own speech recognizer (the system's
 * voice screen, so this app needs no microphone permission). The conversation itself lives in {@link Talk}; this only
 * draws it, above it the chat's latest messages from the phone (/buddy/history, docs/features/022-watch-chat-view/plan.md).
 */
public class TalkActivity extends Activity {
    private static final int LISTEN = 1, CALL = 2;
    /** With nothing from the phone for this long, the screen may go to sleep; Talk carries on without it. */
    private static final long SCREEN_ON_MS = 120_000;
    /** The phone's answer to a history request, and the wait for a snapshot that a line of Talk says is coming. */
    private static final long NO_ANSWER_MS = 8000;
    /** A request's number: only goes up, also across restarts of the app (the phone ignores older ones). */
    private static long viewSeq = System.currentTimeMillis();

    /** When the watch's recognizer was opened, or 0: ReadAloud keeps off the speaker meanwhile (a recognizer left with Home counts for a minute). */
    private static long listenedAt;
    private static final long LISTEN_MS = 60_000;

    static boolean dictating() {
        return listenedAt != 0 && SystemClock.elapsedRealtime() - listenedAt < LISTEN_MS;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable dim = () -> {
        sleepy = true;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        draw();
    };
    private boolean sleepy;
    private String chat, title;
    /** Why listening can't start (no recognizer on the watch), shown under the conversation. */
    private String problem = "";
    private int accent;
    /** A New chat the phone hasn't heard yet (Talk.fresh): no call button; cleared when a YOU line shows. */
    private boolean fresh;
    private ImageView call, face;
    /** ChatsActivity.relaunches when this screen was created: a change means a relaunch cleared it. */
    private int opened;
    private final Runnable redraw = this::changed;
    private ScrollView scroll;
    private LinearLayout body, history, live;
    private boolean started, listening, snapshot, caughtUp, followNext;
    /** This screen's current request, the last snapshot's n, and the newest Talk line when it was used. */
    private long view, lastN;
    private int mark;
    /** The used snapshot's messages. */
    private final List<Talk.Line> rows = new ArrayList<>();
    /**
     * The reading place before the newest unfinished restoration, kept across a burst of snapshots: the views of a
     * rebuilt, not yet laid out list say nothing. {@code restore} counts the restorations; only the last one runs.
     */
    private List<Talk.Line> placeRows;
    private int placeTop, placeOffset, restore;
    private boolean placeFollow, placePending;
    /** Why the phone's messages aren't here (no answer, can't reach it), shown under the conversation. */
    private String note = "";
    private final Runnable noAnswer = () -> {
        note = getString(R.string.chats_no_answer);
        draw();
    };
    private final Runnable catchUp = this::request;
    private final MessageClient.OnMessageReceivedListener listener = e -> {
        if (!Phone.HISTORY.equals(e.getPath())) return;
        try {
            JSONObject in = new JSONObject(new String(e.getData(), StandardCharsets.UTF_8));
            main.post(() -> onSnapshot(in));
        } catch (JSONException x) {
            // not for us
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        opened = ChatsActivity.relaunches;
        chat = getIntent().getStringExtra("chat");
        title = getIntent().getStringExtra("title");
        accent = getIntent().getIntExtra("accent", Look.ACCENT);
        fresh = getIntent().getBooleanExtra("new", false);
        boolean round = getResources().getConfiguration().isScreenRound();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Look.BG);
        // the chat's name, fixed at the top: tap it for the chat list
        TextView pill = Look.text(this, title + "  \u203A", 14, Look.TEXT);
        pill.setSingleLine();
        pill.setEllipsize(TextUtils.TruncateAt.END);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(Look.dp(this, 14), Look.dp(this, 6), Look.dp(this, 14), Look.dp(this, 6));
        pill.setBackground(Look.pill(this, Look.CARD, 16));
        pill.setContentDescription(getString(R.string.talk_chats));
        pill.setOnClickListener(v -> startActivity(new Intent(this, ChatsActivity.class).putExtra("list", true)));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, -2);
        pp.gravity = Gravity.CENTER_HORIZONTAL;
        pp.topMargin = Look.dp(this, round ? 22 : 6);
        pp.setMarginStart(Look.sidePad(this));
        pp.setMarginEnd(Look.sidePad(this));
        root.addView(pill, pp);
        scroll = new ScrollView(this);
        scroll.setFocusableInTouchMode(true); // the Watch4's bezel scrolls what has focus
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        int side = Look.sidePad(this);
        body.setPadding(side, Look.dp(this, 4), side, Look.dp(this, 8));
        face = new ImageView(this);
        face.setImageResource(R.drawable.bubble_face);
        body.addView(face);
        history = new LinearLayout(this); // the phone's messages: rebuilt only when a snapshot comes
        history.setOrientation(LinearLayout.VERTICAL);
        body.addView(history, new LinearLayout.LayoutParams(-1, -2));
        live = new LinearLayout(this); // what Talk has: rebuilt on every change
        live.setOrientation(LinearLayout.VERTICAL);
        body.addView(live, new LinearLayout.LayoutParams(-1, -2));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        ImageView mic = new ImageView(this);
        mic.setImageResource(R.drawable.ms_mic);
        mic.setColorFilter(0xFF1A1714);
        mic.setContentDescription(getString(R.string.talk_speak));
        int p = Look.dp(this, 10);
        mic.setPadding(p, p, p, p);
        mic.setBackground(Look.pill(this, accent, 24));
        mic.setOnClickListener(v -> {
            Talk.silence();
            listen();
        });
        call = new ImageView(this); // a call: the live voice, with the watch's microphone and speaker
        call.setImageResource(R.drawable.ms_graphic_eq);
        call.setColorFilter(0xFF1A1714);
        call.setContentDescription(getString(R.string.call_button));
        call.setPadding(p, p, p, p);
        call.setBackground(Look.pill(this, accent, 24));
        call.setOnClickListener(v -> call());
        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(Look.dp(this, 48), Look.dp(this, 48));
        bp.setMarginStart(Look.dp(this, 4));
        bp.setMarginEnd(Look.dp(this, 4));
        buttons.addView(mic, bp);
        buttons.addView(call, bp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = Look.dp(this, round ? 14 : 6);
        root.addView(buttons, lp);
        setContentView(root);
        scroll.requestFocus(); // the bezel turns what has focus; the talk screen never asked before

        // only quick talk (the Home double-press, "speak") listens by itself: the app list and a pick leave the mic and
        // call buttons to the user. Not when Talk has news for this chat, or Android is recreating us.
        // Quick talk listens even while a reply is awaited; an unseen reply is still shown first.
        boolean speak = getIntent().getBooleanExtra("speak", false);
        boolean news = chat.equals(Talk.chat) && (Talk.waiting ? !speak : Talk.unseen);
        if (b == null && speak && !news) listen();
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        Talk.onChange = redraw;
        if (!fresh) request(); // a New chat the phone hasn't made has no messages to ask for; draw() asks when it has
        changed();
        Talk.unseen = false;
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        main.removeCallbacks(noAnswer);
        main.removeCallbacks(catchUp);
        if (listening) {
            listening = false;
            Wearable.getMessageClient(this).removeListener(listener);
            tell(view, true); // fire and forget: the phone also stops by itself after a while
        }
        if (Talk.onChange == redraw) Talk.onChange = null; // a newer screen may have taken it already
        main.removeCallbacks(dim);
        // Back or a swipe while showing: no more waiting. Not a relaunch from the launcher (it clears this screen too):
        // a screen that is showing is stopped only after ChatsActivity got the new intent, and one that is out of sight
        // is destroyed without a finishing onStop.
        if (isFinishing() && opened == ChatsActivity.relaunches && chat.equals(Talk.chat)) Talk.left();
    }

    /** The call button: the microphone permission once, then Talk gives way to the call (the last started owns the speaker). */
    private void call() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, CALL);
            return;
        }
        problem = "";
        ReadAloud.silence();
        Talk.left();
        Talk.silence();
        ChatsActivity.remember(this, chat, title, accent);
        Call.start(this, chat, title, accent);
        startActivity(new Intent(this, CallActivity.class));
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        if (request != CALL) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            call();
        } else {
            problem = getString(R.string.call_needs_mic);
            draw();
        }
    }

    private void listen() {
        problem = "";
        ReadAloud.silence(); // the microphone must not hear a reading
        listenedAt = SystemClock.elapsedRealtime();
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.talk_prompt));
        try {
            startActivityForResult(i, LISTEN);
        } catch (ActivityNotFoundException e) {
            listenedAt = 0;
            problem = getString(R.string.talk_no_recognizer);
            draw();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == LISTEN) listenedAt = 0;
        ArrayList<String> heard = data == null ? null : data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (request != LISTEN || result != RESULT_OK || heard == null || heard.isEmpty()) return; // cancelled: the mic button is there
        final String text = heard.get(0);
        followNext = true; // the user's own words and the answer to them are what to see
        final boolean isNew = fresh; // the chat may never exist: Talk remembers it when the phone has heard
        if (!isNew) ChatsActivity.remember(this, chat, title, accent);
        Talk.begin(this, chat, title, accent, isNew, (valid, done) -> {
            try {
                JSONObject msg = new JSONObject().put("chat", chat).put("text", text);
                if (isNew) msg.put("new", true);
                Phone.send(this, Phone.SEND, msg, valid, done);
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /** Whether a snapshot of the phone's chat is this screen's own and newer than the last it used (they may arrive in any order). */
    static boolean takes(String chat, long view, long n, String myChat, long myView, long lastN) {
        return chat.equals(myChat) && view == myView && n > lastN;
    }

    /**
     * The Talk lines drawn under the snapshot's rows. With no snapshot (yet, or an old phone): all, as before. With
     * one: only the watch's own lines made after it was used; the phone's messages (mirror) are in the snapshot.
     */
    static List<Talk.Line> below(List<Talk.Line> lines, boolean snapshot, int mark) {
        List<Talk.Line> out = new ArrayList<>();
        for (Talk.Line l : lines) if (!snapshot || (!l.mirror && l.seq > mark)) out.add(l);
        return out;
    }

    /**
     * Where row {@code old.get(i)} is in the {@code now} rows: the nearest one at or before i with the same kind and
     * text (rows only leave from the top), or -1 if it left. Two equal messages may anchor to the wrong one.
     */
    static int anchor(List<Talk.Line> old, int i, List<Talk.Line> now) {
        Talk.Line a = old.get(i);
        for (int j = Math.min(i, now.size() - 1); j >= 0; j--) {
            Talk.Line b = now.get(j);
            if (b.kind == a.kind && b.text.equals(a.text)) return j;
        }
        return -1;
    }

    /** Talk has news: the screen stays on while it waits, for at most two minutes of silence from the phone. */
    private void changed() {
        main.removeCallbacks(dim);
        sleepy = false;
        if (Talk.waiting && chat.equals(Talk.chat)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            main.postDelayed(dim, SCREEN_ON_MS);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        draw();
    }

    /**
     * Asks the phone for this chat's latest messages and, while the screen shows, for each new one: listening first, so
     * that a quick answer can't come before anyone is there. A new `view` each time (the phone ignores older ones).
     */
    private void request() {
        main.removeCallbacks(noAnswer);
        main.removeCallbacks(catchUp);
        final long mine = view = ++viewSeq;
        note = "";
        main.postDelayed(noAnswer, NO_ANSWER_MS); // armed before sending; a snapshot or an error cancels it
        if (listening) {
            tell(mine, false);
            return;
        }
        listening = true;
        Wearable.getMessageClient(this).addListener(listener)
                .addOnSuccessListener(v -> {
                    if (!listening) Wearable.getMessageClient(this).removeListener(listener); // left meanwhile
                    else if (view == mine) tell(mine, false);
                })
                .addOnFailureListener(e -> {
                    if (view == mine) failed(getString(R.string.phone_unreachable));
                });
    }

    private void tell(long mine, boolean stop) {
        try {
            JSONObject msg = new JSONObject().put("chat", chat).put("view", mine);
            if (stop) msg.put("stop", true);
            Phone.send(this, Phone.HISTORY, msg, () -> stop || (started && view == mine), error -> {
                if (error != null && !stop && started && view == mine) failed(error);
            });
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private void failed(String why) {
        main.removeCallbacks(noAnswer);
        note = why;
        draw();
    }

    /** The phone's messages of this chat: they replace the rows, and the reading place is kept (see anchor). */
    private void onSnapshot(JSONObject in) {
        if (!started || !takes(in.optString("chat"), in.optLong("view"), in.optLong("n"), chat, view, lastN)) return;
        lastN = in.optLong("n");
        main.removeCallbacks(noAnswer);
        main.removeCallbacks(catchUp);
        caughtUp = false;
        note = "";
        // reading place: the end if the user is at it; otherwise the top-most row in view, found again in the new rows
        if (!placePending) { // else the place of the first snapshot of the burst stays: the views are not laid out yet
            placeRows = new ArrayList<>(rows);
            placeFollow = !snapshot || atEnd();
            placeTop = -1;
            for (int k = 0; !placeFollow && k < history.getChildCount(); k++) {
                View r = history.getChildAt(k);
                if (history.getTop() + r.getBottom() > scroll.getScrollY()) {
                    placeTop = k;
                    placeOffset = history.getTop() + r.getTop() - scroll.getScrollY();
                    break;
                }
            }
        }
        rows.clear();
        JSONArray ms = in.optJSONArray("messages");
        for (int k = 0; ms != null && k < ms.length(); k++) {
            JSONObject m = ms.optJSONObject(k);
            if (m == null) continue;
            String kind = m.optString("kind");
            rows.add(new Talk.Line("you".equals(kind) ? Talk.YOU : "error".equals(kind) ? Talk.ERROR : Talk.BUDDY, m.optString("text")));
        }
        mark = Talk.Line.count; // the watch's own lines made after this one are not in it
        snapshot = true;
        history.removeAllViews();
        for (Talk.Line l : rows) history.addView(line(l.kind, l.text));
        drawLive();
        placePending = true;
        final int mine = ++restore;
        scroll.post(() -> {
            if (mine != restore) return; // a newer snapshot rebuilt the rows: its restoration runs
            placePending = false;
            if (placeFollow) scroll.fullScroll(View.FOCUS_DOWN);
            else if (placeTop >= 0) {
                int at = placeTop < placeRows.size() ? anchor(placeRows, placeTop, rows) : -1;
                scroll.scrollTo(0, history.getTop() + (at < 0 ? 0 : history.getChildAt(at).getTop() - placeOffset));
            }
        });
    }

    /** The user is at the end (within 48dp of it): new things scroll into view (012's rule). */
    private boolean atEnd() {
        return body.getHeight() - (scroll.getScrollY() + scroll.getHeight()) <= Look.dp(this, 48);
    }

    private TextView line(int kind, String text) {
        TextView t = Look.text(this, kind == Talk.YOU ? getString(R.string.talk_you, text) : text, 15,
                kind == Talk.ERROR ? Look.ERR : kind == Talk.YOU ? Look.TEXT2 : Look.TEXT);
        t.setPadding(0, Look.dp(this, 8), 0, 0);
        return t;
    }

    private void draw() {
        final boolean follow = followNext || atEnd();
        followNext = false;
        drawLive();
        if (follow) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    /** Everything under the phone's messages: Talk's lines the snapshot doesn't hold, the status, what went wrong. */
    private void drawLive() {
        live.removeAllViews();
        boolean mine = chat.equals(Talk.chat);
        boolean working = mine && Talk.waiting;
        face.setImageResource(working ? R.drawable.bubble_face_thinking : R.drawable.bubble_face);
        if (mine) {
            boolean ahead = false; // a message of the phone's own that its snapshot hasn't come with yet
            for (Talk.Line l : Talk.lines) {
                if (l.kind == Talk.YOU && fresh) { // only /buddy/heard adds one: the chat exists
                    fresh = false;
                    if (started) request(); // so it has messages to show, and new ones from the phone's side
                }
                ahead |= snapshot && l.mirror && l.seq > mark;
            }
            for (Talk.Line l : below(Talk.lines, snapshot, mark)) live.addView(line(l.kind, l.text));
            if (ahead && !caughtUp) { // the snapshot with it should be right behind: if not in 8 s, ask again, once
                caughtUp = true;
                main.postDelayed(catchUp, NO_ANSWER_MS);
            }
            String status = !working ? "" : !Talk.heard ? getString(R.string.talk_sending)
                    : sleepy ? getString(R.string.talk_sleepy) : Talk.status;
            if (!status.isEmpty()) {
                TextView t = Look.text(this, status, 13, Look.MUTED);
                t.setPadding(0, Look.dp(this, 8), 0, 0);
                live.addView(t);
            }
        }
        if (!note.isEmpty()) {
            TextView t = Look.text(this, note, 13, Look.MUTED);
            t.setPadding(0, Look.dp(this, 8), 0, 0);
            live.addView(t);
        }
        if (!problem.isEmpty()) live.addView(Look.text(this, problem, 14, Look.ERR));
        call.setVisibility(fresh ? View.GONE : View.VISIBLE);
    }
}
