package app.buddy.assistant.wear;

import android.content.Context;

import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

/** The link to Buddy on the phone: finds it, sends it JSON, and holds the message paths (see the plan's table). */
final class Phone {
    static final String CHATS = "/buddy/chats", SEND = "/buddy/send", HEARD = "/buddy/heard", STEP = "/buddy/step",
            REPLY = "/buddy/reply", DONE = "/buddy/done", ERROR = "/buddy/error", STATUS = "/buddy/status", HISTORY = "/buddy/history",
            // 007: the call (a channel), its status and end, a reply's voice (a channel per reply: VOICE + id) and the
            // phone's "the voice couldn't say reply id"
            LIVE = "/buddy/live", LIVE_STATUS = "/buddy/live/status", LIVE_END = "/buddy/live/end",
            VOICE = "/buddy/voice/", SAY = "/buddy/say";
    /** The capability the phone's Buddy (github build) declares in app/src/watchlink/res/values/wear.xml. */
    private static final String CAPABILITY = "buddy_phone";

    /** error is null when sent, else a sentence for the user. */
    interface Sent {
        void done(String error);
    }

    private Phone() {
    }

    /** The phone's node id, or a sentence for the user when there is none. */
    interface Found {
        void done(String node, String error);
    }

    /** Looks the phone up. {@code valid} is asked again once it is found: an obsolete request gets no answer. */
    static void find(Context c, BooleanSupplier valid, Found found) {
        final Context app = c.getApplicationContext();
        Wearable.getCapabilityClient(app).getCapability(CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .addOnSuccessListener(info -> {
                    if (info.getNodes().isEmpty()) {
                        why(app, found);
                        return;
                    }
                    if (!valid.getAsBoolean()) return;
                    found.done(info.getNodes().iterator().next().getId(), null);
                })
                .addOnFailureListener(e -> found.done(null, app.getString(R.string.phone_unreachable)));
    }

    /** {@code valid} is asked again once the phone is found, right before sending: an obsolete request sends nothing. */
    static void send(Context c, String path, JSONObject body, BooleanSupplier valid, Sent sent) {
        final Context app = c.getApplicationContext();
        find(c, valid, (node, error) -> {
            if (error != null) {
                sent.done(error);
                return;
            }
            Wearable.getMessageClient(app).sendMessage(node, path, body.toString().getBytes(StandardCharsets.UTF_8))
                    .addOnSuccessListener(id -> sent.done(null))
                    .addOnFailureListener(e -> sent.done(app.getString(R.string.phone_send_failed)));
        });
    }

    /** No phone with the link: either the phone isn't connected at all, or it has no (matching) Buddy. */
    private static void why(Context app, Found found) {
        Wearable.getNodeClient(app).getConnectedNodes()
                .addOnSuccessListener(nodes -> found.done(null, app.getString(nodes.isEmpty() ? R.string.phone_unreachable : R.string.phone_no_link)))
                .addOnFailureListener(e -> found.done(null, app.getString(R.string.phone_unreachable)));
    }
}
