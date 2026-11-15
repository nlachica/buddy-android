package app.buddy.assistant.wear;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.widget.TextView;

/** Buddy's dark palette (Theme.java on the phone), on pure black for the OLED, and the bits both screens share. */
final class Look {
    static final int BG = 0xFF000000, CARD = 0xFF1D1B19;
    static final int TEXT = 0xFFF3EFE8, TEXT2 = 0xFFC9C3B9, MUTED = 0xFFA39D93, ERR = 0xFFF0A090;
    /** Buddy's default accent (coral); the phone sends the user's own with the chat list. */
    static final int ACCENT = 0xFFD97757;

    private Look() {
    }

    static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** Side padding that keeps text off the curved edge of a round watch. */
    static int sidePad(Context c) {
        return dp(c, c.getResources().getConfiguration().isScreenRound() ? 26 : 12);
    }

    static TextView text(Context c, CharSequence s, int sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    /** A rounded filled background (a row, a button). */
    static GradientDrawable pill(Context c, int fill, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }
}
