package app.buddy.assistant;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.io.File;
import java.util.ArrayList;

/** Speech in (Android's recognizer) and speech out (text-to-speech) for chats. Main thread only. */
final class Voice {
    interface Listener {
        void onPartial(String text);

        void onFinal(String text);

        /** Stopped without a result (silence, error, cancelled). message may be null. */
        void onEnd(String message);

        /** Stopped because nothing was heard (an error is onEnd): a listener that tells the two apart overrides this. */
        default void onNothing(String message) {
            onEnd(message);
        }

        /** Microphone loudness, 0..1, many times a second while recording. */
        default void onLevel(float level) {
        }

        /** What's happening when not simply recording ("Starting Claude…", "Transcribing…"); null: recording. */
        default void onStatus(String status) {
        }
    }

    /**
     * Notices that the user stopped talking, from the microphone's loudness (Claude's and ChatGPT's dictation have no
     * end-of-speech of their own; Android's recognizer does). Follows the room's noise so a car or a street works.
     */
    static final class EndOfSpeech {
        private static final long PAUSE_MS = 1800, NOTHING_SAID_MS = 8000;
        private final long start = System.currentTimeMillis();
        private float floor = -1;
        private boolean spoke;
        private long quietSince;

        /** A level (0..1); true once the user has spoken and then paused. */
        boolean feed(float v) {
            long now = System.currentTimeMillis();
            // the noise floor: follows quiet levels at once, louder ones only slowly
            floor = floor < 0 || v < floor ? v : floor + (v - floor) * 0.003f;
            if (v > floor + 0.18f) {
                spoke = true;
                quietSince = 0;
            } else if (v < floor + 0.10f) {
                if (quietSince == 0) quietSince = now;
            }
            if (spoke) return quietSince != 0 && now - quietSince > PAUSE_MS;
            return now - start > NOTHING_SAID_MS;
        }
    }

    private static SpeechRecognizer recognizer;
    private static Listener current;
    private static boolean inputDone; // the phone recognizer stopped taking input and only awaits its result
    private static TextToSpeech tts;
    private static boolean ttsReady;
    private static String pending;

    private Voice() {
    }

    static boolean micAllowed(Context c) {
        return c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Asks for the microphone, saying first which answer to give: one given "Only this time" ends when Buddy has been
     * out of sight for a minute, which Android does by stopping Buddy, a task that is running included (issue #54).
     */
    static void askMic(android.app.Activity a, int code) {
        android.widget.Toast.makeText(a, R.string.voice_mic_while_using, android.widget.Toast.LENGTH_LONG).show();
        a.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, code);
    }

    static boolean listening() {
        return current != null || ClaudeDictation.active() || CodexDictation.active();
    }

    /** 031: input is being taken now (listening() also holds while an engine waits for its transcript). */
    static boolean capturing() {
        return (current != null && !inputDone) || ClaudeDictation.taking() || CodexDictation.taking();
    }

    /** Languages offered for voice: {BCP-47 tag, name}. */
    static final String[][] LANGUAGES = {
            {"en-US", "English"}, {"fr-FR", "Français"}, {"de-DE", "Deutsch"}, {"ar-TN", "العربية · تونس"},
            {"ar-SA", "العربية"}, {"es-ES", "Español"}, {"it-IT", "Italiano"}, {"pt-PT", "Português"},
            {"nl-NL", "Nederlands"}, {"tr-TR", "Türkçe"}, {"ru-RU", "Русский"}, {"ja-JP", "日本語"},
    };

    static String languageName(String tag) {
        for (String[] l : LANGUAGES) if (l[0].equals(tag)) return l[1];
        return tag;
    }

    /** Warm up the chosen engine (a chat or the bubble opened) so the first tap on the mic is quick. */
    static void prepare(Context c) {
        String engine = Prefs.voiceEngine(c), lang = Prefs.voiceLang(c);
        if ("claude".equals(engine) && ClaudeDictation.supports(lang)) ClaudeDictation.prepare(c, lang);
        else if ("chatgpt".equals(engine)) CodexDictation.prepare(c);
    }

    /**
     * Whether Phone turns of "hey buddy" are fed to Android's recognizer (EXTRA_AUDIO_SOURCE): on until two fed windows
     * in a row fail within 3 s of starting (an error other than "no match", or a foreign recording that cancels the
     * turn). Then Phone turns are unfed ("hey buddy", a pause, the request) for the rest of the process: a recognizer
     * that ignores supplied audio and opens the microphone itself. One unrelated recorder can't do it alone.
     */
    static final class PhoneFeed {
        static final long EARLY_MS = 3000;
        boolean on = true;
        private int fails;
        private long since;

        /** A fed window started listening. */
        void started(long now) {
            if (since != 0) fails = 0; // the window before it ended (cancelled) without failing early: not in a row
            since = now;
        }

        /** The fed window failed. True when this turned feeding off. */
        boolean failed(long now) {
            if (since == 0) return false;
            boolean late = now - since > EARLY_MS;
            since = 0;
            if (late) {
                fails = 0;
                return false;
            }
            if (++fails < 2 || !on) return false;
            on = false;
            return true;
        }

        /** The fed window ended normally. */
        void ok() {
            fails = 0;
            since = 0;
        }
    }

    static final PhoneFeed phoneFeed = new PhoneFeed();

    /** A "hey buddy" turn can hand the detector's audio to the input in use (Claude, ChatGPT, or Phone while it works). */
    static boolean takesFeed(Context c) {
        String engine = Prefs.voiceEngine(c);
        if ("claude".equals(engine) && ClaudeDictation.supports(Prefs.voiceLang(c))) return true;
        return "chatgpt".equals(engine) || (phoneFeed.on && android.os.Build.VERSION.SDK_INT >= 33);
    }

    /** A fed phone window failed (an error, or a foreign recording cancelled its turn). */
    private static void fedFailed() {
        if (phoneFeed.failed(android.os.SystemClock.elapsedRealtime())) {
            android.util.Log.i("BuddyWake", "Phone input is not fed any more: the recognizer failed twice in a row");
        }
    }

    /** A foreign recording cancelled a wake turn: if its window was a fed phone one, that is a failure of feeding. */
    static void fedCancelled() {
        if (phone != null && phone.capture != 0) fedFailed();
    }

    /** Start listening. Returns an error message, or null when started. */
    static String listen(Context c, Listener given) {
        if (!micAllowed(c)) return c.getString(R.string.voice_mic_needed);
        if (CodexLive.on()) return c.getString(R.string.live_mic_busy); // the call has the microphone
        stopListening();
        // the detector's microphone closes before the recognizer (another app) starts, unless a wake window just took MicServer
        if (!WakeWord.release()) return c.getString(R.string.dictation_mic_failed);
        stopSpeaking();
        AudioRoute.use(c);
        final Listener l = endpoint(c, given);
        SpeechPause.listen(c); // before the microphone starts, so it hears the user and not the video (#56)
        String lang = Prefs.voiceLang(c);
        // Claude's dictation (on the user's Claude plan) for the languages it supports; otherwise the phone's
        if ("claude".equals(Prefs.voiceEngine(c)) && ClaudeDictation.supports(lang)) {
            return unwatchOnError(l, ClaudeDictation.listen(c, lang, l));
        }
        if ("chatgpt".equals(Prefs.voiceEngine(c))) return unwatchOnError(l, CodexDictation.listen(c, l));
        if (!SpeechRecognizer.isRecognitionAvailable(c)) return unwatchOnError(l, c.getString(R.string.voice_no_recognizer));
        Window window = openWindow(c, l);
        l.onStatus(c.getString(R.string.voice_starting));
        recognizer = SpeechRecognizer.createSpeechRecognizer(c.getApplicationContext());
        recognizer.setRecognitionListener(window);
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
        ArrayList<String> langs = new ArrayList<>(java.util.Arrays.asList(Prefs.voiceLangs(c).split(",")));
        if (langs.size() > 1 && android.os.Build.VERSION.SDK_INT >= 33) {
            // detect which of the user's languages is being spoken
            i.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, langs);
        }
        if (langs.size() > 1 && android.os.Build.VERSION.SDK_INT >= 34) {
            // and follow a switch of language mid-sentence
            i.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, langs);
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 && phoneFeed.on && MicServer.adopted() && !feed(window, i)) {
            finish();
            return unwatchOnError(l, c.getString(R.string.dictation_mic_failed));
        }
        recognizer.startListening(i);
        return null;
    }

    /**
     * A wake turn's audio (what the detector heard, pre-roll first) goes to the recognizer through a pipe: the
     * documented EXTRA_AUDIO_SOURCE, so the recognizer opens no microphone of its own. A pipe that can't be made makes
     * this one window unfed: the detector's microphone is closed first, and false (the window fails) if it won't close.
     */
    private static boolean feed(final Window window, Intent i) {
        final int capture = MicServer.capture();
        final ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createPipe();
        } catch (java.io.IOException e) {
            MicServer.release(capture);
            WakeWord.unfed();
            return WakeWord.release();
        }
        window.feed(capture, pipe);
        i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT);
        phoneFeed.started(android.os.SystemClock.elapsedRealtime());
        final EndOfSpeech eos = new EndOfSpeech(); // Buddy's own end of speech, as for Claude and ChatGPT
        MicServer.levels = v -> ends.post(() -> {
            if (phone == window && !inputDone && eos.feed(v)) finishListening();
        });
        new Thread(() -> { // the capture thread never blocks on the pipe: this one does
            try (java.io.OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                while (true) {
                    byte[] b = MicServer.pull(capture, 3200);
                    if (b == null) break; // that capture is over
                    if (b.length > 0) out.write(b);
                    else if (!MicServer.recording()) break; // the end of input was marked and all of it is written
                    else Thread.sleep(20);
                }
            } catch (java.io.IOException | InterruptedException ignored) { // the recognizer closed its end, or finish() closed ours
            }
            // all of the audio is in and the pipe is closed: only now may the recognizer be told to stop (finishListening waits for this)
            ends.post(() -> {
                if (phone == window && inputDone && recognizer != null) recognizer.stopListening();
            });
        }, "wake-pipe").start();
        return true;
    }

    /** The phone recognizer's open window. */
    private static Window phone;

    /** Starts a phone-recognizer window for l: it is the only one whose callbacks count until finish(). */
    static Window openWindow(Context c, Listener l) {
        current = l;
        inputDone = false;
        phone = new Window(c, l);
        return phone;
    }

    /**
     * What the phone recognizer says to one listening window. A callback that is already on its way from a cancelled
     * recognizer finds another window (or none) open and changes nothing: it can neither end a newer recognizer nor
     * deliver to an old listener.
     */
    static final class Window implements RecognitionListener {
        private final Context c;
        private final Listener l;
        /** A wake turn's window: the adopted MicServer capture it reads (0: none) and the pipe it is fed through. */
        private int capture;
        private ParcelFileDescriptor[] pipe;

        Window(Context c, Listener l) {
            this.c = c;
            this.l = l;
        }

        void feed(int capture, ParcelFileDescriptor[] pipe) {
            this.capture = capture;
            this.pipe = pipe;
        }

        /** The end of input is marked: the pump drains and closes the pipe, and the detector's loop holds what comes next. */
        void endInput() {
            if (capture != 0) MicServer.stopCapture();
        }

        /** The window is over: its capture is released and its pipe closed (which ends the pump). */
        private void closeFeed() {
            if (capture == 0) return;
            MicServer.levels = null;
            MicServer.release(capture);
            for (ParcelFileDescriptor p : pipe) {
                try {
                    p.close();
                } catch (java.io.IOException ignored) {
                }
            }
        }

        @Override
        public void onPartialResults(Bundle b) {
            String t = best(b);
            if (t != null && phone == this && current == l) l.onPartial(t);
        }

        @Override
        public void onResults(Bundle b) {
            if (phone != this) return;
            String t = best(b);
            if (capture != 0) phoneFeed.ok();
            finish();
            if (t != null && !t.trim().isEmpty()) l.onFinal(t.trim());
            else l.onNothing(c.getString(R.string.voice_didnt_catch));
        }

        @Override
        public void onError(int error) {
            if (phone != this) return;
            boolean nothing = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
            if (capture != 0) {
                if (nothing) phoneFeed.ok();
                else fedFailed(); // also ERROR_CLIENT: a cancelled window's late one never gets here (phone != this)
            }
            finish();
            if (nothing) {
                l.onNothing(c.getString(R.string.voice_didnt_catch));
            } else {
                l.onEnd(error == SpeechRecognizer.ERROR_CLIENT ? null : c.getString(R.string.voice_error, error));
            }
        }

        @Override
        public void onReadyForSpeech(Bundle b) {
            if (phone == this && current == l) l.onStatus(null);
        }

        @Override
        public void onRmsChanged(float rms) { // about -2 (quiet) to 10 dB (loud)
            if (phone == this && current == l) l.onLevel(Math.max(0f, Math.min(1f, (rms + 2f) / 12f)));
        }

        @Override public void onBeginningOfSpeech() {}
        @Override public void onBufferReceived(byte[] buf) {}

        @Override
        public void onEndOfSpeech() {
            if (phone == this && current == l) {
                inputDone = true;
                endInput();
                l.onStatus(c.getString(R.string.voice_finishing));
            }
        }

        @Override public void onEvent(int type, Bundle b) {}
    }

    /** The listening that endpoint() watches now: an earlier one's timer must not end a later one. */
    private static Listener watched;
    private static final android.os.Handler ends = new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * Buddy's own end of listening (issue #60), on top of the engine's: done once the words heard so far haven't
     * changed for the chosen pause after the user started speaking, or at the longest recording. Noise around (music,
     * a car) keeps the engine listening, as it waits for quiet; it doesn't make new words, so this pause still ends.
     */
    private static Listener endpoint(Context c, final Listener l) {
        return endpoint(Prefs.voicePauseMs(c), Prefs.voiceMaxMs(c), l);
    }

    static Listener endpoint(final long pause, final long max, final Listener l) {
        if (pause <= 0 && max <= 0) return l; // Auto: the engine decides, as before
        final long started = android.os.SystemClock.elapsedRealtime();
        final Listener[] me = new Listener[1];
        final String[] last = {""};
        final long[] changed = {0};
        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (watched != me[0]) return;
                long now = android.os.SystemClock.elapsedRealtime();
                boolean pauseDone = pause > 0 && !last[0].trim().isEmpty() && now - changed[0] >= pause;
                if ((max > 0 && now - started >= max) || pauseDone) {
                    watched = null;
                    finishListening(); // what was heard goes out, as a tap would send it
                    return;
                }
                ends.postDelayed(this, 200);
            }
        };
        me[0] = new Listener() {
            @Override
            public void onPartial(String text) {
                String t = text == null ? "" : text;
                if (!t.equals(last[0])) {
                    last[0] = t;
                    changed[0] = android.os.SystemClock.elapsedRealtime();
                }
                l.onPartial(text);
            }

            @Override
            public void onFinal(String text) {
                if (watched == me[0]) watched = null;
                l.onFinal(text);
            }

            @Override
            public void onEnd(String message) {
                if (watched == me[0]) watched = null;
                l.onEnd(message);
            }

            @Override
            public void onNothing(String message) {
                if (watched == me[0]) watched = null;
                l.onNothing(message);
            }

            @Override
            public void onLevel(float level) {
                l.onLevel(level);
            }

            @Override
            public void onStatus(String status) {
                l.onStatus(status);
            }
        };
        watched = me[0];
        ends.postDelayed(tick, 200);
        return me[0];
    }

    /** Listening didn't start (error): endpoint()'s timer has nothing to end. */
    private static String unwatchOnError(Listener l, String error) {
        if (error != null && watched == l) watched = null;
        return error;
    }

    /** Stop listening now; whatever was heard so far is delivered as the final result. */
    static void finishListening() {
        if (ClaudeDictation.active()) {
            ClaudeDictation.finish();
            return;
        }
        if (CodexDictation.active()) {
            CodexDictation.finish();
            return;
        }
        if (recognizer != null) {
            inputDone = true;
            if (phone != null) phone.endInput();
            if (phone == null || phone.capture == 0) recognizer.stopListening(); // a fed window's pump does it once its audio is written
        }
    }

    static void stopListening() {
        if (ClaudeDictation.active()) {
            ClaudeDictation.cancel();
            return;
        }
        if (CodexDictation.active()) {
            CodexDictation.cancel();
            return;
        }
        Listener l = current;
        if (recognizer != null) recognizer.cancel();
        finish();
        if (l != null) l.onEnd(null);
    }

    private static void finish() {
        Window w = phone;
        current = null;
        phone = null;
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
        if (w != null) w.closeFeed();
    }

    private static String best(Bundle b) {
        ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return r == null || r.isEmpty() ? null : r.get(0);
    }

    // ------------------------------------------------------------- speaking

    /** A reply as it is read aloud: code blocks and links become the (localized) words "code" and "link", markup goes. */
    static String spoken(Context c, String text) {
        // the words spoken in place of code and links are plain text, not regex replacement syntax
        String code = " " + java.util.regex.Matcher.quoteReplacement(c.getString(R.string.voice_code_omitted)) + " ";
        String link = java.util.regex.Matcher.quoteReplacement(c.getString(R.string.voice_link));
        return text.replaceAll("```[\\s\\S]*?```", code)
                .replaceAll("[*_`#>|]", "")
                .replaceAll("\\[(.*?)\\]\\(.*?\\)", "$1")
                .replaceAll("https?://\\S+", link)
                .trim();
    }

    static void speak(Context c, String text) {
        String clean = spoken(c, text);
        if (clean.isEmpty()) return;
        SpeechPause.begin(c);
        AudioRoute.use(c);
        // github build: every reading goes through ReadQueue, which decides watch or phone when it starts (016)
        if (WatchVoice.link() != null) WatchVoice.speak(c, clean, !WatchVoice.KEY.equals(Prefs.audioDevice(c)));
        else here(c, clean);
    }

    /** Whether replies are read in ChatGPT's voice (the user's plan) rather than the phone's. */
    static boolean chatgptVoice(Context c) {
        return "chatgpt".equals(Prefs.speechEngine(c)) && SetupState.planSignedIn(c, "codex");
    }

    /** Read on the phone: ChatGPT's voice with the phone's as its fallback, or the phone's. */
    static void here(Context c, String clean) {
        if (chatgptVoice(c)) {
            final Context app = c.getApplicationContext();
            CodexSpeech.speak(app, clean, t -> { // the phone's voice if ChatGPT's fails
                SpeechPause.begin(app); // what was paused stays so while that voice gets ready
                phoneSpeak(app, t);
            });
            return;
        }
        phoneSpeak(c, clean);
    }

    private static void phoneSpeak(Context c, String clean) {
        appCtx = c.getApplicationContext();
        if (tts == null) {
            pending = clean;
            startEngine(c);
        } else if (ttsReady) {
            say(clean);
        } else {
            pending = clean;
        }
    }

    private static void startEngine(Context c) {
        tts = new TextToSpeech(c.getApplicationContext(), status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            ttsFailed = !ttsReady;
            Req r = routedPending;
            routedPending = null;
            if (ttsReady) {
                tts.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                tts.setOnUtteranceProgressListener(TAPS);
                if (pending != null) say(pending);
                if (r != null) submitRouted(r);
            } else if (r != null) {
                post(r.done);
            }
            pending = null;
        });
    }

    /** Speak in the reply's own language: detect it (on a worker thread), then pick a matching voice. */
    private static void say(String text) {
        withVoice(text, () -> {
            AudioRoute.use(appCtx); // the phone voice can start well after speak(): a cold engine, language detection, a fallback
            tts.speak(text, TextToSpeech.QUEUE_ADD, new Bundle(), "buddy-" + System.nanoTime());
        });
    }

    /** Detects the text's language off the main thread, then on main picks a matching voice and runs submit (if the engine is up). */
    private static void withVoice(String text, Runnable submit) {
        final Context c = appCtx;
        new Thread(() -> {
            java.util.Locale loc = c == null ? null : detectLanguage(c, text);
            post(() -> {
                if (tts == null || !ttsReady) return;
                if (loc != null && tts.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE) {
                    tts.setLanguage(loc);
                    android.speech.tts.Voice best = null;
                    for (android.speech.tts.Voice v : tts.getVoices()) {
                        if (!v.getLocale().getLanguage().equals(loc.getLanguage())) continue;
                        if (v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
                        if (best == null || v.getQuality() > best.getQuality()
                                || (v.getQuality() == best.getQuality() && v.getLocale().equals(loc))) best = v;
                    }
                    if (best != null) tts.setVoice(best);
                }
                submit.run();
            });
        }, "tts-language").start();
    }

    // ------------------------------------------------- the phone's voice for the Watch route (WatchVoice)

    private static android.os.Handler mainHandler;

    private static void post(Runnable r) { // not a static initializer: JVM tests load Voice
        if (mainHandler == null) mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        mainHandler.post(r);
    }

    /** One routed request. */
    private static final class Req {
        final String text;
        final CodexSpeech.Sink sink;
        final Runnable done;
        final int gen;

        Req(String text, CodexSpeech.Sink sink, Runnable done, int gen) {
            this.text = text;
            this.sink = sink;
            this.done = done;
            this.gen = gen;
        }
    }

    /** An utterance of a routed request, by id: its sink (null: spoken on the phone), converter and done. */
    private static final class Tap {
        final CodexSpeech.Sink sink;
        final Runnable done;
        volatile Pcm16k conv;

        Tap(CodexSpeech.Sink sink, Runnable done) {
            this.sink = sink;
            this.done = done;
        }
    }

    private static final java.util.Map<String, Tap> taps = new java.util.concurrent.ConcurrentHashMap<>(); // callbacks come on binder threads
    private static Req routedPending;
    private static int routedGen;
    private static boolean ttsFailed;
    private static long routedSeq;

    private static File readFile() {
        return new File(appCtx.getCacheDir(), "read.wav");
    }

    /** The utterance with this id is over (done, failed or stopped): its done runs once, on main. */
    private static void end(String id) {
        Tap t = taps.remove(id);
        if (t == null) return;
        if (t.sink != null) readFile().delete();
        post(t.done);
    }

    private static final UtteranceProgressListener TAPS = new UtteranceProgressListener() {
        @Override public void onStart(String id) {}
        @Override public void onDone(String id) { end(id); }
        @Override public void onError(String id) { end(id); }
        @Override public void onError(String id, int code) { end(id); }
        @Override public void onStop(String id, boolean interrupted) { end(id); }

        @Override
        public void onBeginSynthesis(String id, int rate, int format, int channels) {
            Tap t = taps.get(id);
            if (t == null || t.sink == null) return;
            try {
                t.conv = new Pcm16k(rate, format, channels);
            } catch (IllegalArgumentException e) {
                t.conv = null; // no audio reaches the sink: the reading is refused and the phone reads it
            }
        }

        @Override
        public void onAudioAvailable(String id, byte[] audio) {
            Tap t = taps.get(id);
            Pcm16k conv = t == null ? null : t.conv;
            if (conv == null) return;
            byte[] out = conv.feed(audio, 0, audio.length);
            if (out.length > 0) t.sink.pcm(out);
        }
    };

    /**
     * The phone's voice for a reading on the Watch route (main thread; one request at a time, WatchVoice's queue
     * sees to that). sink: the audio goes to it as PCM 16-bit mono 16 kHz and the phone stays silent; null: spoken on
     * the phone. done runs exactly once, on main, when the text has been spoken or the attempt failed (unless
     * {@link #dropRouted} came first). The sink is not closed here.
     */
    static void routed(Context c, String text, CodexSpeech.Sink sink, Runnable done) {
        appCtx = c.getApplicationContext();
        Req r = new Req(text, sink, done, routedGen);
        if (tts == null) {
            routedPending = r;
            startEngine(c);
        } else if (ttsFailed) {
            post(done);
        } else if (!ttsReady) {
            routedPending = r;
        } else {
            submitRouted(r);
        }
    }

    private static void submitRouted(Req r) {
        if (r.gen != routedGen) return;
        withVoice(r.text, () -> {
            if (r.gen != routedGen) return; // dropped while the language was detected
            String id = "read-" + ++routedSeq;
            taps.put(id, new Tap(r.sink, r.done));
            int status;
            if (r.sink != null) {
                status = tts.synthesizeToFile(r.text, new Bundle(), readFile(), id);
            } else {
                AudioRoute.use(appCtx);
                status = tts.speak(r.text, TextToSpeech.QUEUE_ADD, new Bundle(), id);
            }
            if (status != TextToSpeech.SUCCESS) end(id);
        });
    }

    /** Forget every routed request and stop what the engine is doing for them (main thread). Their done never runs. */
    static void dropRouted() {
        routedGen++;
        routedPending = null;
        boolean tapped = !taps.isEmpty();
        taps.clear();
        if (appCtx != null) readFile().delete();
        if (tapped && tts != null && ttsReady) tts.stop();
    }

    private static Context appCtx;

    private static java.util.Locale detectLanguage(Context c, String text) {
        try {
            android.view.textclassifier.TextClassificationManager m =
                    c.getSystemService(android.view.textclassifier.TextClassificationManager.class);
            android.view.textclassifier.TextLanguage r = m.getTextClassifier().detectLanguage(
                    new android.view.textclassifier.TextLanguage.Request.Builder(text).build());
            if (r.getLocaleHypothesisCount() > 0 && r.getConfidenceScore(r.getLocale(0)) > 0.5f) {
                return r.getLocale(0).toLocale();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Buddy is reading something aloud (phone voice, ChatGPT's, or on the watch). */
    static boolean isSpeaking() {
        return phoneSpeaking() || WatchVoice.active();
    }

    /** The phone itself is speaking (ChatGPT's voice or the phone's), not the watch queue. */
    static boolean phoneSpeaking() {
        return CodexSpeech.active() || (tts != null && ttsReady && tts.isSpeaking());
    }

    static void stopSpeaking() {
        WatchVoice.stop(); // first: its streams are cancelled before CodexSpeech.stop() closes them
        dropRouted();
        CodexSpeech.stop();
        if (tts != null && ttsReady) tts.stop();
    }
}
