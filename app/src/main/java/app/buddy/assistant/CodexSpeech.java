package app.buddy.assistant;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Buddy's replies read aloud in ChatGPT's voice (the user's ChatGPT plan), through a Codex voice call like
 * {@link CodexDictation}'s. The call only receives: Buddy sends silence, so the microphone isn't used. Each text
 * is given to the voice to read word for word. If anything goes wrong, the phone's own voice reads it instead.
 * A text can also have a {@link Sink} (a reply for the watch, docs/features/007-watch-live-voice/plan.md): then the
 * page's voice is muted on the phone and tapped into the Sink as PCM 16-bit mono 16 kHz.
 */
final class CodexSpeech {
    static final String[][] VOICES = {{"juniper", "Juniper"}, {"maple", "Maple"}, {"ember", "Ember"}, {"cove", "Cove"}};
    private static final String PROMPT = "You are the voice of Buddy, a phone assistant. You only read text aloud. "
            + "Each time you're given text, read it exactly as written, word for word, in the language it is in, "
            + "naturally and warmly. Never add, answer, comment, ask, or hand anything off. Say nothing else.";

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final ArrayDeque<Item> queue = new ArrayDeque<>();
    private static volatile WebView web;
    private static Context app;
    private static volatile String threadId;
    private static Process threadServer;
    private static volatile String sessionThread; // the thread with a live call
    /** Threads of calls cancel() hung up: their late events are claimed here and dropped, not given to dictation. */
    private static final Set<String> retired = ConcurrentHashMap.newKeySet();
    private static boolean connected, reading, playing;
    /** The item the voice was given, until it has been read and has gone quiet; read: its text was read. */
    private static volatile Item current;
    private static boolean read;
    private static long lastSound, sentAt;

    /** Fallback for what couldn't be spoken (the phone's voice; for a watch reply, the watch's). */
    interface Fallback {
        void say(String text);
    }

    /** Where a watch reply's voice goes: PCM 16-bit mono 16 kHz blocks (any thread), then close() when it is read. */
    interface Sink {
        void pcm(byte[] pcm16k);

        void close();
    }

    private static final class Item {
        final String text;
        final Fallback fallback;
        final Sink sink;
        volatile boolean closed;

        Item(String text, Fallback fallback, Sink sink) {
            this.text = text;
            this.fallback = fallback;
            this.sink = sink;
        }
    }

    private CodexSpeech() {
    }

    static boolean active() {
        return web != null;
    }

    static boolean owns(String thread) {
        return thread != null && (thread.equals(sessionThread) || retired.contains(thread));
    }

    static void speak(Context c, String text, Fallback f) {
        speak(c, text, f, null);
    }

    /** sink: a watch reply (main thread); null: the phone's speaker. */
    static void speak(Context c, String text, Fallback f, Sink sink) {
        app = c.getApplicationContext();
        queue.add(new Item(text, f, sink));
        if (web == null) open();
        else if (connected) next();
    }

    static void stop() {
        Item cur = current;
        List<Item> rest = new ArrayList<>(queue);
        queue.clear();
        close();
        closeSink(cur);
        for (Item i : rest) closeSink(i);
    }

    /**
     * Retires one watch reply and nothing else (main thread): a queued one is dropped; the one being read is hung up
     * on and, when others wait, the call is made again with the queue intact (on a fresh thread, so the old call's late
     * events can't fail it). A Sink matching no item (already read or dropped) is ignored.
     */
    static void cancel(Sink sink) {
        if (sink == null) return;
        for (Iterator<Item> it = queue.iterator(); it.hasNext(); ) {
            Item i = it.next();
            if (i.sink == sink) {
                it.remove();
                closeSink(i);
                if (current == null && queue.isEmpty() && web != null) hangUp(); // nothing left for the call to do
                return;
            }
        }
        Item cur = current;
        if (cur == null || cur.sink != sink) return;
        hangUp();
        closeSink(cur);
        if (!queue.isEmpty()) open();
    }

    /** Ends the call so its late events can't reach a later one. */
    private static synchronized void hangUp() { // same lock as startCall's publish: it publishes before this (and is retired) or finds its page gone
        String old = sessionThread;
        if (old != null) retired.add(old);
        threadId = null;
        close();
    }

    private static void closeSink(Item i) {
        if (i != null && i.sink != null && !i.closed) {
            i.closed = true;
            i.sink.close();
        }
    }

    private static void stale(String what) {
        Log.i("BuddySpeech", "stale " + what + " ignored");
    }

    // ------------------------------------------------------------------ the call

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private static void open() {
        connected = reading = playing = read = false;
        current = null;
        opened = System.currentTimeMillis();
        web = new WebView(app);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setMediaPlaybackRequiresUserGesture(false);
        final WebView mine = web;
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void offer(String sdp) {
                new Thread(() -> startCall(mine, sdp), "codex-speech-call").start();
            }

            @JavascriptInterface
            public void connected() {
                main.post(() -> {
                    if (web != mine) return;
                    connected = true;
                    next();
                });
            }

            /** Whether the voice is audible right now (the page measures what it plays). */
            @JavascriptInterface
            public void sound(boolean on) {
                main.post(() -> {
                    if (web != mine) return;
                    playing = on;
                    lastSound = System.currentTimeMillis();
                });
            }

            /** The voice, tapped for a watch reply (see TAP_JS): 16-bit mono 16 kHz as base64. */
            @JavascriptInterface
            public void pcm(String b64) {
                Item it = current;
                if (web == mine && it != null && it.sink != null && !it.closed) {
                    it.sink.pcm(android.util.Base64.decode(b64, android.util.Base64.NO_WRAP));
                }
            }

            @JavascriptInterface
            public void failed(String why) {
                main.post(() -> {
                    if (web == mine) fail();
                    else stale("page failure");
                });
            }
        }, "Buddy");
        web.loadDataWithBaseURL("https://buddy.local/", PAGE, "text/html", "utf-8", null);
        main.postDelayed(watchdog, 1000);
    }

    private static void startCall(WebView page, String sdp) {
        try {
            CodexDictation.ensureServer(app);
            String t = threadId;
            Process srv = threadServer;
            if (t == null || srv != CodexDictation.server()) {
                t = CodexDictation.newThread();
                srv = CodexDictation.server();
            }
            synchronized (CodexSpeech.class) { // close() clears web and sessionThread under the same lock
                if (web != page) {
                    stale("call start");
                    return;
                }
                if (srv != threadServer) retired.clear(); // a new server knows none of the old threads
                threadId = t;
                threadServer = srv;
                sessionThread = t;
            }
            JSONObject r = CodexDictation.request("thread/realtime/start", new JSONObject()
                    .put("threadId", t)
                    .put("transport", new JSONObject().put("type", "webrtc").put("sdp", sdp))
                    .put("version", "v3")
                    .put("outputModality", "audio")
                    .put("voice", Prefs.speechVoice(app))
                    .put("delegationAckFiller", false)
                    .put("prompt", PROMPT)
                    .put("includeStartupContext", false), 30_000);
            if (r.has("error")) throw new Exception(r.getJSONObject("error").optString("message"));
        } catch (Exception e) {
            main.post(() -> {
                if (web == page) fail();
                else stale("call failure");
            });
        }
    }

    /** Give the voice the next text (one at a time: it reads, then the next). */
    private static void next() {
        if (reading || queue.isEmpty() || !connected) return;
        final Item item = queue.poll();
        current = item;
        read = false;
        reading = true;
        sentAt = System.currentTimeMillis();
        final WebView mine = web;
        // the page's voice goes to the phone's speaker, or only to the watch's Sink
        mine.evaluateJavascript("route(" + (item.sink != null) + ")", null);
        final String t = sessionThread, text = item.text;
        new Thread(() -> {
            try {
                JSONObject r = CodexDictation.request("thread/realtime/appendText", new JSONObject()
                        .put("threadId", t).put("role", "user")
                        .put("text", "Read this aloud exactly, word for word, nothing else:\n\n" + text), 15_000);
                if (r.has("error")) throw new Exception(r.getJSONObject("error").optString("message"));
            } catch (Exception e) {
                main.post(() -> {
                    if (web == mine) fail();
                    else stale("text failure");
                });
            }
        }, "codex-speech-text").start();
    }

    static void notification(String method, JSONObject p) {
        final String t = p.optString("threadId");
        if (!t.equals(sessionThread)) { // a call that was hung up (cancel() retires its thread)
            stale(method);
            return;
        }
        switch (method) {
            case "thread/realtime/sdp": {
                final String sdp = p.optString("sdp");
                main.post(() -> {
                    if (web != null && t.equals(sessionThread)) web.evaluateJavascript("answer(" + JSONObject.quote(sdp) + ")", null);
                });
                break;
            }
            case "thread/realtime/transcript/done":
                if ("assistant".equals(p.optString("role"))) main.post(() -> {
                    if (t.equals(sessionThread)) read = true; // read; the audio may still be playing (the watchdog waits for quiet)
                });
                break;
            case "thread/realtime/error":
            case "thread/realtime/closed":
                main.post(() -> {
                    if (t.equals(sessionThread)) fail();
                    else stale(method);
                });
                break;
        }
    }

    /** Moves on once a text has been read and the voice went quiet; hangs up when there's nothing left. */
    private static final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (web == null) return;
            long now = System.currentTimeMillis();
            if (reading && read && !playing && now - lastSound > 700) {
                reading = false;
                Item done = current;
                current = null;
                closeSink(done); // a watch reply: the end of its stream
                if (queue.isEmpty()) {
                    close();
                    return;
                }
                next();
            } else if (reading && !read && lastSound < sentAt && now - sentAt > 30_000) {
                fail(); // the voice never started on it
                return;
            } else if (reading && !read && lastSound >= sentAt && !playing && now - lastSound > 8_000) {
                // it spoke, then went quiet without saying it was done: count it as read (a long text can take
                // minutes to say, so the time since it was sent says nothing)
                read = true;
            } else if (!connected && now - opened > 20_000) {
                fail(); // never connected
                return;
            }
            main.postDelayed(this, 200);
        }
    };

    private static long opened;

    /**
     * Hand whatever wasn't spoken on: the phone's texts to the phone's voice, joined into one call (its TTS keeps only
     * the last text while it starts), each watch reply to its own Fallback. A text the voice already started isn't read
     * again. Every Sink ends.
     */
    private static void fail() {
        if (web == null) return;
        List<Item> all = new ArrayList<>();
        Item cur = current;
        if (cur != null) all.add(cur); // handed on only if the voice never started it
        List<Item> handOn = new ArrayList<>();
        if (cur != null && !read && lastSound < sentAt) handOn.add(cur);
        handOn.addAll(queue);
        all.addAll(queue);
        queue.clear();
        close();
        for (Item i : all) closeSink(i);
        StringBuilder phone = new StringBuilder();
        Fallback phoneFallback = null;
        for (Item i : handOn) {
            if (i.sink != null) {
                if (i.fallback != null) i.fallback.say(i.text);
            } else {
                phone.append(phone.length() > 0 ? "\n" : "").append(i.text);
                if (phoneFallback == null) phoneFallback = i.fallback;
            }
        }
        if (phone.length() > 0 && phoneFallback != null) phoneFallback.say(phone.toString());
    }

    private static void close() {
        main.removeCallbacks(watchdog);
        opened = 0;
        sentAt = 0;
        reading = false;
        current = null;
        final String t;
        synchronized (CodexSpeech.class) { // startCall publishes under the same lock, only for the page that is still web
            if (web != null) {
                web.destroy();
                web = null;
            }
            t = sessionThread;
            sessionThread = null;
        }
        connected = false;
        if (t != null) new Thread(() -> {
            try {
                CodexDictation.request("thread/realtime/stop", new JSONObject().put("threadId", t), 5000);
            } catch (Exception ignored) {
            }
        }, "codex-speech-stop").start();
    }

    /**
     * For both pages: tap(ctx, stream, on) sends the remote track to Buddy.pcm(base64) as PCM 16-bit mono 16 kHz while
     * on() is true, and the phone stays silent (the processor's output is zeroed). The page's {@code <audio>} keeps the
     * stream attached, muted: Chromium only feeds a remote WebRTC track into Web Audio through a media element.
     */
    static final String TAP_JS = "function tap(ctx, stream, on) {\n"
            + "  const src = ctx.createMediaStreamSource(stream), proc = ctx.createScriptProcessor(2048, 1, 1);\n"
            + "  const ratio = ctx.sampleRate / 16000; let pos = 0, prev = 0;\n" // pos: where the next output sample falls, relative to the next block
            + "  proc.onaudioprocess = e => {\n"
            + "    e.outputBuffer.getChannelData(0).fill(0);\n"
            + "    const inp = e.inputBuffer.getChannelData(0), n = inp.length;\n"
            + "    if (!on()) { pos = 0; prev = 0; return; }\n"
            + "    const o = new Int16Array(Math.ceil((n - pos) / ratio)); let c = 0, x = pos;\n"
            + "    for (; x < n - 1 && c < o.length; c++, x += ratio) {\n"
            + "      const k = Math.floor(x), t = x - k, v = (k < 0 ? prev : inp[k]) * (1 - t) + inp[k + 1] * t;\n"
            + "      o[c] = Math.max(-1, Math.min(1, v)) * 32767;\n"
            + "    }\n"
            + "    pos = x - n; prev = inp[n - 1];\n"
            + "    if (c > 0) Buddy.pcm(btoa(String.fromCharCode.apply(null, new Uint8Array(o.buffer, 0, c * 2))));\n"
            + "  };\n"
            + "  src.connect(proc); proc.connect(ctx.destination);\n"
            + "}\n";

    /** Sends silence (the call needs audio flowing for the voice to speak) and plays what comes back. */
    private static final String PAGE = "<!doctype html><html><body><audio id=out autoplay></audio><script>\n"
            + "let pc, fwd = false;\n" // fwd: this item's voice goes to the watch (muted here), not to the phone's speaker
            + TAP_JS
            + "function route(on) { fwd = on; const o = document.getElementById('out'); if (o) o.muted = on; }\n"
            + "async function start() {\n"
            + "  try {\n"
            + "    const ctx = new AudioContext(); ctx.resume();\n"
            + "    const osc = ctx.createConstantSource(); osc.offset.value = 0;\n"
            + "    const dest = ctx.createMediaStreamDestination(); osc.connect(dest); osc.start();\n"
            + "    pc = new RTCPeerConnection();\n"
            + "    pc.addTrack(dest.stream.getAudioTracks()[0], dest.stream);\n"
            + "    pc.createDataChannel('oai-events');\n"
            + "    pc.ontrack = e => {\n"
            + "      const s = e.streams[0] || new MediaStream([e.track]);\n"
            + "      const out = document.getElementById('out'); out.muted = fwd; out.srcObject = s; out.play().catch(() => {});\n"
            + "      tap(ctx, s, () => fwd);\n"
            + "      const an = ctx.createAnalyser(); an.fftSize = 1024; ctx.createMediaStreamSource(s).connect(an);\n"
            + "      const buf = new Float32Array(an.fftSize); let was = false;\n"
            + "      setInterval(() => { an.getFloatTimeDomainData(buf); let m = 0; for (const v of buf) m = Math.max(m, Math.abs(v));\n"
            + "        const on = m > 0.01; if (on || was) Buddy.sound(on); was = on; }, 100);\n"
            + "    };\n"
            + "    pc.onconnectionstatechange = () => {\n"
            + "      if (pc.connectionState === 'connected') Buddy.connected();\n"
            + "      else if (pc.connectionState === 'failed') Buddy.failed('the connection failed');\n"
            + "    };\n"
            + "    await pc.setLocalDescription(await pc.createOffer());\n"
            + "    await new Promise(r => { if (pc.iceGatheringState === 'complete') r(); else { pc.onicegatheringstatechange = () => { if (pc.iceGatheringState === 'complete') r(); }; setTimeout(r, 1500); } });\n"
            + "    Buddy.offer(pc.localDescription.sdp);\n"
            + "  } catch (e) { Buddy.failed(String(e && e.message || e)); }\n"
            + "}\n"
            + "function answer(sdp) { pc.setRemoteDescription({type: 'answer', sdp}).catch(e => Buddy.failed(String(e.message || e))); }\n"
            + "start();\n"
            + "</script></body></html>";
}
