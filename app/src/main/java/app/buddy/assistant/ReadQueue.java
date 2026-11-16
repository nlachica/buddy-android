package app.buddy.assistant;

import java.util.ArrayDeque;
import java.util.function.Consumer;

/**
 * The rules for reading replies aloud when the audio device is Watch (docs/features/012-watch-audio-device/plan.md).
 * One reading is in flight at a time; the next starts when it has settled. A reading goes to the watch when one can
 * play it, and otherwise (nothing found, or the watch refused it) the phone reads it, after a spoken notice once per
 * stretch of fallbacks. Android is behind {@link Out}, so this is unit-tested. Main thread only; every callback is
 * dropped unless its reading (and stream) are still the current ones.
 */
final class ReadQueue {
    static final long READ_MAX_MS = 11 * 60_000L;

    /** One reading's audio stream to the watch. Its events never run from inside one of these calls, nor after cancel(). */
    interface Stream extends CodexSpeech.Sink {
        /** Drop the stream and free the channel; no event follows. */
        void cancel();
    }

    /** At most one fires per stream, on the main thread. */
    interface Events {
        /** The watch played it to the end. */
        void played();

        /** Nothing was played: the phone must read it. */
        void refused();

        /** Stopped partway, or the outcome is unknown: nobody reads the rest. */
        void broken();
    }

    interface Out {
        /** The node of a watch that can play a reading, or null. */
        void find(Consumer<String> node);

        Stream open(String node, Events e);

        /** ChatGPT's voice into the stream; failed: it failed before starting. */
        void chatgpt(String text, Stream s, Runnable failed);

        /** The phone's voice: into the stream, or (null) out loud on the phone. done: spoken, or failed. */
        void tts(String text, Stream orNull, Runnable done);

        /** Stop whatever is producing audio for this stream (null: the phone's own utterance). */
        void stopProducers(Stream orNull);

        void after(long ms, Runnable r);

        /** The sentence said before the first reading read on the phone instead. */
        String notice();
    }

    private static final class Item {
        final String text;
        final boolean chatgpt;

        Item(String text, boolean chatgpt) {
            this.text = text;
            this.chatgpt = chatgpt;
        }
    }

    /** The current reading; its Ev is the token of the stream it has now. */
    private static final class Reading {
        final Item item;
        Stream s;
        Ev ev;

        Reading(Item item) {
            this.item = item;
        }
    }

    private final class Ev implements Events {
        final Reading r;

        Ev(Reading r) {
            this.r = r;
        }

        boolean live() {
            return cur == r && r.ev == this;
        }

        @Override
        public void played() {
            if (!live()) return;
            noticeDue = true;
            end(r);
        }

        @Override
        public void refused() {
            if (!live()) return;
            Stream s = r.s;
            r.s = null;
            r.ev = null;
            out.stopProducers(s);
            s.cancel();
            phone(r);
        }

        @Override
        public void broken() {
            if (!live()) return;
            out.stopProducers(r.s);
            end(r);
        }
    }

    private final Out out;
    private final ArrayDeque<Item> fifo = new ArrayDeque<>();
    private Reading cur;
    private boolean noticeDue = true;

    ReadQueue(Out out) {
        this.out = out;
    }

    void speak(String text, boolean chatgpt) {
        fifo.add(new Item(text, chatgpt));
        pump();
    }

    boolean active() {
        return cur != null || !fifo.isEmpty();
    }

    void stop() {
        fifo.clear();
        Reading r = cur;
        cur = null;
        if (r == null) return;
        if (r.s != null) r.s.cancel();
        out.stopProducers(r.s);
    }

    private void pump() {
        if (cur != null || fifo.isEmpty()) return;
        final Reading r = cur = new Reading(fifo.poll());
        out.after(READ_MAX_MS, () -> {
            if (cur != r) return;
            if (r.s != null) r.s.cancel();
            out.stopProducers(r.s);
            end(r);
        });
        out.find(node -> {
            if (cur != r) return;
            if (node == null) phone(r);
            else watch(r, node, r.item.chatgpt);
        });
    }

    private void end(Reading r) {
        if (cur != r) return;
        cur = null;
        pump();
    }

    private void watch(Reading r, String node, boolean chatgpt) {
        final Ev ev = new Ev(r);
        final Stream s = out.open(node, ev);
        r.s = s;
        r.ev = ev;
        if (chatgpt) {
            out.chatgpt(r.item.text, s, () -> {
                if (!ev.live()) return;
                s.cancel(); // the voice never started: the phone's voice goes to the same watch, once
                watch(r, node, false);
            });
        } else {
            out.tts(r.item.text, s, () -> {
                if (ev.live()) s.close();
            });
        }
    }

    private void phone(Reading r) {
        if (!noticeDue) {
            readHere(r);
            return;
        }
        noticeDue = false;
        out.tts(out.notice(), null, () -> {
            if (cur == r) readHere(r);
        });
    }

    private void readHere(Reading r) {
        out.tts(r.item.text, null, () -> end(r));
    }
}
