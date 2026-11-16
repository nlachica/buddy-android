package app.buddy.assistant.wear;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** The watch's home screen: the chats on the phone, to pick one and talk to. */
public class ChatsActivity extends Activity {
    private static final long NO_ANSWER_MS = 8000;
    /**
     * Counts explicit starts of the live task (main thread: app list, quick talk, the chat button). Each one clears
     * the talk screen above this one, which is not the user leaving it: TalkActivity compares this with its value at creation.
     */
    static int relaunches;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable noAnswer = () -> showError(getString(R.string.chats_no_answer));
    private ScrollView scroll;
    private LinearLayout body;
    private boolean listening;
    /** Waiting for the phone's chat list; {@code loads} counts requests so a late callback of an older one is ignored. */
    private boolean pending;
    private int loads;
    private final MessageClient.OnMessageReceivedListener listener = this::onMessage;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(Look.BG);
        scroll.setFocusableInTouchMode(true); // the Watch4's bezel scrolls what has focus
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        int side = Look.sidePad(this), top = Look.dp(this, getResources().getConfiguration().isScreenRound() ? 28 : 12);
        body.setPadding(side, top, side, top);
        scroll.addView(body);
        setContentView(scroll);
        header();
        if (b == null) route(getIntent()); // not on recreation
    }

    /** The app is started again while its task is alive (singleTask): the same routing as a fresh start. */
    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        relaunches++; // first: the talk screen being cleared reads it
        route(i);
    }

    /** The chat button ("list") shows the list; any other start goes to the default chat, listening only for quick talk (the Home double-press, via the QuickTalk alias). */
    private void route(Intent i) {
        if (i.getBooleanExtra("list", false)) return;
        ComponentName c = i.getComponent();
        talkToLast(c != null && c.getClassName().endsWith(".QuickTalk"));
    }

    /**
     * Opening the app means "I want to say something": straight to the default chat (the last one talked to or whose
     * reply the watch took; a pick alone doesn't change it). The list is one Back away.
     */
    private void talkToLast(boolean speak) {
        SharedPreferences last = getSharedPreferences("last", MODE_PRIVATE);
        if (last.contains("chat")) {
            Intent talk = Talk.intent(this, last.getString("chat", ""), last.getString("title", ""),
                    last.getInt("accent", Look.ACCENT));
            startActivity(speak ? talk.putExtra("speak", true) : talk);
        }
    }

    /** The default chat is the latest write here: words sent, a call, or a reply the watch took. */
    static void remember(Context c, String chat, String title, int accent) {
        c.getSharedPreferences("last", MODE_PRIVATE).edit()
                .putString("chat", chat).putString("title", title).putInt("accent", accent).apply();
    }

    @Override
    protected void onStart() {
        super.onStart();
        load();
    }

    @Override
    protected void onStop() {
        super.onStop();
        main.removeCallbacks(noAnswer);
        if (listening) Wearable.getMessageClient(this).removeListener(listener);
        listening = false;
        pending = false;
    }

    private void header() {
        body.removeAllViews();
        ImageView face = new ImageView(this);
        face.setImageResource(R.drawable.bubble_face);
        body.addView(face);
        TextView title = Look.text(this, getString(R.string.app_name), 18, Look.TEXT);
        title.setGravity(Gravity.CENTER);
        body.addView(title);
        scroll.requestFocus();
    }

    /** Asks the phone for its chats: listening first, so that a quick answer can't come before anyone is there. */
    private void load() {
        header();
        main.removeCallbacks(noAnswer);
        if (listening) Wearable.getMessageClient(this).removeListener(listener);
        listening = true;
        pending = true;
        final int mine = ++loads;
        main.postDelayed(noAnswer, NO_ANSWER_MS); // armed before sending; the list or an error cancels it
        Wearable.getMessageClient(this).addListener(listener)
                .addOnSuccessListener(v -> {
                    if (!listening) Wearable.getMessageClient(this).removeListener(listener); // left meanwhile
                    if (live(mine)) Phone.send(this, Phone.CHATS, new JSONObject(), () -> live(mine), error -> {
                        if (error != null && live(mine)) showError(error);
                    });
                })
                .addOnFailureListener(e -> {
                    if (live(mine)) showError(getString(R.string.phone_unreachable));
                });
    }

    private boolean live(int load) {
        return pending && load == loads;
    }

    private void onMessage(MessageEvent e) {
        if (!Phone.CHATS.equals(e.getPath())) return;
        try {
            JSONObject in = new JSONObject(new String(e.getData(), StandardCharsets.UTF_8));
            runOnUiThread(() -> {
                main.removeCallbacks(noAnswer);
                pending = false;
                showChats(in.optJSONArray("chats"), in.optInt("accent", Look.ACCENT));
            });
        } catch (JSONException x) {
            // not for us
        }
    }

    private void showChats(JSONArray chats, int accent) {
        header();
        body.addView(newChatRow(accent), rowParams());
        if (chats == null || chats.length() == 0) {
            body.addView(note(getString(R.string.chats_empty), Look.TEXT2));
            return;
        }
        for (int i = 0; i < chats.length(); i++) {
            JSONObject chat = chats.optJSONObject(i);
            if (chat == null) continue;
            final String id = chat.optString("id"), title = chat.optString("title");
            TextView row = Look.text(this, title, 15, Look.TEXT);
            if (chat.optBoolean("running")) row.append("\n" + getString(R.string.chats_working));
            row.setPadding(Look.dp(this, 14), Look.dp(this, 10), Look.dp(this, 14), Look.dp(this, 10));
            row.setBackground(Look.pill(this, Look.CARD, 16));
            row.setOnClickListener(v -> open(id, title, accent));
            body.addView(row, rowParams());
        }
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Look.dp(this, 6);
        return lp;
    }

    /** An action, not a chat (like Retry). The id is made here in the phone's format; the phone creates the chat on the first words (docs/features/013-watch-new-chat/plan.md). */
    private TextView newChatRow(int accent) {
        TextView row = Look.text(this, getString(R.string.chats_new), 15, Look.BG);
        row.setGravity(Gravity.CENTER);
        row.setPadding(Look.dp(this, 14), Look.dp(this, 10), Look.dp(this, 14), Look.dp(this, 10));
        row.setBackground(Look.pill(this, accent, 16));
        row.setOnClickListener(v -> {
            String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            startActivity(Talk.intent(this, id, getString(R.string.chats_new), accent)
                    .putExtra("new", true).putExtra("speak", true));
        });
        return row;
    }

    private void open(String id, String title, int accent) {
        startActivity(Talk.intent(this, id, title, accent));
    }

    private void showError(String text) {
        main.removeCallbacks(noAnswer);
        pending = false;
        header();
        body.addView(note(text, Look.ERR));
        TextView retry = Look.text(this, getString(R.string.retry), 15, Look.BG);
        retry.setGravity(Gravity.CENTER);
        retry.setPadding(Look.dp(this, 14), Look.dp(this, 8), Look.dp(this, 14), Look.dp(this, 8));
        retry.setBackground(Look.pill(this, Look.ACCENT, 20));
        retry.setOnClickListener(v -> load());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = Look.dp(this, 10);
        body.addView(retry, lp);
    }

    private TextView note(String text, int color) {
        TextView t = Look.text(this, text, 14, color);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Look.dp(this, 8), 0, 0);
        return t;
    }
}
