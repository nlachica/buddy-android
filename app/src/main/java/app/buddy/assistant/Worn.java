package app.buddy.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Is the Galaxy Watch on the wrist (docs/features/033-watch-worn-audio/plan.md)? For Auto, "a watch answers" means "a
 * watch answers and says it is worn": Auto asks the watch each time it is about to use it. Unknown, stale or no
 * answer is not worn. Google-free so the rules are unit-tested; the link only sends (WatchVoice.Link.askWorn). Main thread.
 */
final class Worn {
    /** A sample older than this on arrival is stale: Android's bound for the sensor to notice a removal. */
    static final long FRESH_MS = 3000;
    /** The watch waits up to 5 s for the sensor's first event; the phone adds delivery both ways. */
    static final long ASK_MS = 8000;

    interface Port {
        /** Sends the ask; failed runs (main thread) when it can't be sent. */
        void send(String node, long id, Runnable failed);

        void after(long ms, Runnable r);

        long now();

        void log(String line);
    }

    private final Port port;
    private long seq;
    /** The last answer: its node, worn (null = can't tell) and the earliest moment its sample can have been taken. */
    private String node;
    private Boolean worn;
    private long at;
    /** The ask in flight: its node, id, send time and the callers waiting for it. */
    private String askNode;
    private long askId, sentAt;
    private final List<Consumer<Boolean>> waiting = new ArrayList<>();

    Worn(Port port) {
        this.port = port;
    }

    /** True only when this node said "worn" and the sample is at most FRESH_MS old. */
    boolean on(String n) {
        return n != null && n.equals(node) && Boolean.TRUE.equals(worn) && port.now() - at <= FRESH_MS;
    }

    /** Asks the node (callers during one ask join it) and gives out.accept(on(node)). Every placement gets its own sample. */
    void check(String n, Consumer<Boolean> out) {
        if (askNode != null) {
            if (askNode.equals(n)) waiting.add(out);
            else out.accept(false); // shortcut: one watch at a time, a second one is not worn
            return;
        }
        final long id = ++seq;
        askNode = n;
        askId = id;
        sentAt = port.now();
        waiting.add(out);
        port.after(ASK_MS, () -> {
            if (askNode != null && askId == id) answer(null, 0, "no reply");
        });
        port.send(n, id, () -> {
            if (askNode != null && askId == id) answer(null, 0, "send failed");
        });
    }

    /** The watch's answer to ask id: worn (null = can't tell, why says so), ms from its receiving the ask to the sample. */
    void reply(String n, long id, Boolean w, long ms, String why) {
        if (askNode == null || askId != id || !askNode.equals(n)) return; // a late reply to an older ask, or another watch
        answer(w, ms, w == null ? "can't tell: " + why : null);
    }

    private void answer(Boolean w, long ms, String line) {
        long now = port.now();
        node = askNode;
        worn = w;
        at = sentAt + Math.max(0, Math.min(ms, now - sentAt));
        if (line == null) line = w ? on(node) ? "worn" : "stale (age " + (now - at) + " ms)" : "not worn";
        port.log(line);
        boolean on = on(node);
        List<Consumer<Boolean>> l = new ArrayList<>(waiting);
        waiting.clear();
        askNode = null;
        for (Consumer<Boolean> c : l) c.accept(on);
    }

    /**
     * Auto's "a watch answers": the finder's node, kept only while worn. Whether Watch is the user's pick is read when
     * each answer arrives: a hand-picked Watch is used as it is, worn or not.
     */
    void find(Consumer<Consumer<String>> finder, BooleanSupplier picked, Consumer<String> out) {
        finder.accept(n -> {
            if (n == null || picked.getAsBoolean()) out.accept(n);
            else check(n, w -> out.accept(w || picked.getAsBoolean() ? n : null));
        });
    }
}
