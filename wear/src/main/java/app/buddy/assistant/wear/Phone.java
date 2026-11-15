package app.buddy.assistant.wear;

import android.content.Context;

import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/** The link to Buddy on the phone: finds it, sends it JSON, and holds the message paths (see the plan's table). */
final class Phone {
    static final String CHATS = "/buddy/chats", SEND = "/buddy/send", HEARD = "/buddy/heard", STEP = "/buddy/step",
            REPLY = "/buddy/reply", DONE = "/buddy/done", ERROR = "/buddy/error", STATUS = "/buddy/status";
    /** The capability the phone's Buddy (github build) declares in app/src/watchlink/res/values/wear.xml. */
    private static final String CAPABILITY = "buddy_phone";

    /** error is null when sent, else a sentence for the user. */
    interface Sent {
        void done(String error);
    }

    private Phone() {
    }

    static void send(Context c, String path, JSONObject body, Sent sent) {
        final Context app = c.getApplicationContext();
        Wearable.getCapabilityClient(app).getCapability(CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .addOnSuccessListener(info -> {
                    if (info.getNodes().isEmpty()) {
                        why(app, sent);
                        return;
                    }
                    String node = info.getNodes().iterator().next().getId();
                    Wearable.getMessageClient(app).sendMessage(node, path, body.toString().getBytes(StandardCharsets.UTF_8))
                            .addOnSuccessListener(id -> sent.done(null))
                            .addOnFailureListener(e -> sent.done(app.getString(R.string.phone_send_failed)));
                })
                .addOnFailureListener(e -> sent.done(app.getString(R.string.phone_unreachable)));
    }

    /** No phone with the link: either the phone isn't connected at all, or it has no (matching) Buddy. */
    private static void why(Context app, Sent sent) {
        Wearable.getNodeClient(app).getConnectedNodes()
                .addOnSuccessListener(nodes -> sent.done(app.getString(nodes.isEmpty() ? R.string.phone_unreachable : R.string.phone_no_link)))
                .addOnFailureListener(e -> sent.done(app.getString(R.string.phone_unreachable)));
    }
}
