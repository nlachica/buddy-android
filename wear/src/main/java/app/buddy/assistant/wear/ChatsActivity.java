package app.buddy.assistant.wear;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The watch's home screen: Buddy's chats on the phone, to pick one and talk to. */
public class ChatsActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.BLACK);
        ImageView face = new ImageView(this);
        face.setImageResource(R.drawable.bubble_face);
        root.addView(face);
        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextColor(0xFFF3EFE8);
        title.setTextSize(18);
        root.addView(title);
        setContentView(root);
    }
}
