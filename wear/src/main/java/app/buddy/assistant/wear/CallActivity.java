package app.buddy.assistant.wear;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** The screen of a call: the chat, what the phone says it is doing, and End. The call itself is {@link Call}'s. */
public class CallActivity extends Activity {
    private LinearLayout body;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Look.BG);
        scroll.setFocusableInTouchMode(true);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        int side = Look.sidePad(this);
        body.setPadding(side, Look.dp(this, getResources().getConfiguration().isScreenRound() ? 28 : 12), side, Look.dp(this, 14));
        scroll.addView(body);
        setContentView(scroll);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Call.onChange = this::draw;
        draw();
    }

    @Override
    protected void onStop() {
        super.onStop();
        Call.onChange = null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing()) Call.left(); // swiped away: hang up
    }

    private void draw() {
        if (!Call.on && Call.ended.isEmpty()) { // opened from an old notification: nothing to show
            finish();
            return;
        }
        body.removeAllViews();
        ImageView face = new ImageView(this);
        face.setImageResource(Call.on ? R.drawable.bubble_face_thinking : R.drawable.bubble_face);
        body.addView(face);
        TextView name = Look.text(this, Call.title, 14, Look.MUTED);
        name.setGravity(Gravity.CENTER);
        body.addView(name);
        TextView line = Look.text(this, Call.on ? Call.status : Call.ended, 15, Call.on ? Look.TEXT : Look.TEXT2);
        line.setGravity(Gravity.CENTER);
        line.setPadding(0, Look.dp(this, 8), 0, Look.dp(this, 12));
        body.addView(line);
        TextView end = Look.text(this, getString(Call.on ? R.string.call_end : R.string.call_close), 16, 0xFF1A1714);
        end.setGravity(Gravity.CENTER);
        end.setBackground(Look.pill(this, Call.on ? 0xFFE5604F : Look.TEXT2, 24));
        end.setPadding(Look.dp(this, 28), Look.dp(this, 10), Look.dp(this, 28), Look.dp(this, 10));
        end.setOnClickListener(v -> {
            Call.hangUp();
            finish();
        });
        body.addView(end, new LinearLayout.LayoutParams(-2, -2));
    }
}
