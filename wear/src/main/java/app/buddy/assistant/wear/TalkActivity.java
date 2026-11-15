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
import android.speech.RecognizerIntent;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Speak to one chat and read what Buddy answers. The words come from the watch's own speech recognizer (the system's
 * voice screen, so this app needs no microphone permission). The conversation itself lives in {@link Talk}; this only
 * draws it.
 */
public class TalkActivity extends Activity {
    private static final int LISTEN = 1, CALL = 2;
    /** With nothing from the phone for this long, the screen may go to sleep; Talk carries on without it. */
    private static final long SCREEN_ON_MS = 120_000;

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
    private ScrollView scroll;
    private LinearLayout body;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        chat = getIntent().getStringExtra("chat");
        title = getIntent().getStringExtra("title");
        accent = getIntent().getIntExtra("accent", Look.ACCENT);
        boolean round = getResources().getConfiguration().isScreenRound();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Look.BG);
        scroll = new ScrollView(this);
        scroll.setFocusableInTouchMode(true); // the Watch4's bezel scrolls what has focus
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        int side = Look.sidePad(this);
        body.setPadding(side, Look.dp(this, round ? 28 : 12), side, Look.dp(this, 8));
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
        ImageView call = new ImageView(this); // a call: the live voice, with the watch's microphone and speaker
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

        // a fresh open means "I want to say something"; not when Talk has news for this chat, or Android is recreating us
        boolean news = chat.equals(Talk.chat) && (Talk.waiting || Talk.unseen);
        if (b == null && !news) listen();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Talk.onChange = this::changed;
        changed();
        Talk.unseen = false;
    }

    @Override
    protected void onStop() {
        super.onStop();
        Talk.onChange = null;
        main.removeCallbacks(dim);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing() && chat.equals(Talk.chat)) Talk.left(); // swiped away: no more waiting for this
    }

    /** The call button: the microphone permission once, then Talk gives way to the call (the last started owns the speaker). */
    private void call() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, CALL);
            return;
        }
        problem = "";
        Talk.left();
        Talk.silence();
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
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.talk_prompt));
        try {
            startActivityForResult(i, LISTEN);
        } catch (ActivityNotFoundException e) {
            problem = getString(R.string.talk_no_recognizer);
            draw();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        ArrayList<String> heard = data == null ? null : data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (request != LISTEN || result != RESULT_OK || heard == null || heard.isEmpty()) return; // cancelled: the mic button is there
        final String text = heard.get(0);
        Talk.begin(this, chat, title, accent, (valid, done) -> {
            try {
                Phone.send(this, Phone.SEND, new JSONObject().put("chat", chat).put("text", text), valid, done);
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
        });
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

    private void draw() {
        body.removeAllViews();
        boolean mine = chat.equals(Talk.chat);
        boolean working = mine && Talk.waiting;
        ImageView face = new ImageView(this);
        face.setImageResource(working ? R.drawable.bubble_face_thinking : R.drawable.bubble_face);
        body.addView(face);
        TextView name = Look.text(this, title, 14, Look.MUTED);
        name.setGravity(Gravity.CENTER);
        body.addView(name);
        if (mine) {
            for (Talk.Line l : Talk.lines) {
                TextView t = Look.text(this, l.kind == Talk.YOU ? getString(R.string.talk_you, l.text) : l.text, 15,
                        l.kind == Talk.ERROR ? Look.ERR : l.kind == Talk.YOU ? Look.TEXT2 : Look.TEXT);
                t.setPadding(0, Look.dp(this, 8), 0, 0);
                body.addView(t);
            }
            String status = !working ? "" : !Talk.heard ? getString(R.string.talk_sending)
                    : sleepy ? getString(R.string.talk_sleepy) : Talk.status;
            if (!status.isEmpty()) {
                TextView t = Look.text(this, status, 13, Look.MUTED);
                t.setPadding(0, Look.dp(this, 8), 0, 0);
                body.addView(t);
            }
        }
        if (!problem.isEmpty()) body.addView(Look.text(this, problem, 14, Look.ERR));
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }
}
