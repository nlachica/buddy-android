package app.buddy.assistant;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.os.Bundle;
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
    private static TextToSpeech tts;
    private static boolean ttsReady;
    private static String pending;

    private Voice() {
    }

    static boolean micAllowed(Context c) {
        return c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean listening() {
        return current != null || ClaudeDictation.active() || CodexDictation.active();
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

    /** Start listening. Returns an error message, or null when started. */
    static String listen(Context c, Listener l) {
        if (!micAllowed(c)) return c.getString(R.string.voice_mic_needed);
        if (CodexLive.on()) return c.getString(R.string.live_mic_busy); // the call has the microphone
        stopListening();
        stopSpeaking();
        AudioRoute.use(c);
        String lang = Prefs.voiceLang(c);
        // Claude's dictation (on the user's Claude plan) for the languages it supports; otherwise the phone's
        if ("claude".equals(Prefs.voiceEngine(c)) && ClaudeDictation.supports(lang)) {
            return ClaudeDictation.listen(c, lang, l);
        }
        if ("chatgpt".equals(Prefs.voiceEngine(c))) return CodexDictation.listen(c, l);
        if (!SpeechRecognizer.isRecognitionAvailable(c)) return c.getString(R.string.voice_no_recognizer);
        current = l;
        l.onStatus(c.getString(R.string.voice_starting));
        recognizer = SpeechRecognizer.createSpeechRecognizer(c.getApplicationContext());
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onPartialResults(Bundle b) {
                String t = best(b);
                if (t != null && current == l) l.onPartial(t);
            }

            @Override
            public void onResults(Bundle b) {
                String t = best(b);
                finish();
                if (t != null && !t.trim().isEmpty()) l.onFinal(t.trim());
                else l.onEnd(c.getString(R.string.voice_didnt_catch));
            }

            @Override
            public void onError(int error) {
                finish();
                l.onEnd(error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        ? c.getString(R.string.voice_didnt_catch) : error == SpeechRecognizer.ERROR_CLIENT ? null
                        : c.getString(R.string.voice_error, error));
            }

            @Override
            public void onReadyForSpeech(Bundle b) {
                if (current == l) l.onStatus(null);
            }

            @Override
            public void onRmsChanged(float rms) { // about -2 (quiet) to 10 dB (loud)
                if (current == l) l.onLevel(Math.max(0f, Math.min(1f, (rms + 2f) / 12f)));
            }

            @Override public void onBeginningOfSpeech() {}
            @Override public void onBufferReceived(byte[] buf) {}
            @Override
            public void onEndOfSpeech() {
                if (current == l) l.onStatus(c.getString(R.string.voice_finishing));
            }
            @Override public void onEvent(int type, Bundle b) {}
        });
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
        recognizer.startListening(i);
        return null;
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
        if (recognizer != null) recognizer.stopListening();
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
        current = null;
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
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
        if (WatchVoice.chosen(c)) WatchVoice.speak(c, clean);
        else here(c, clean);
    }

    /** Whether replies are read in ChatGPT's voice (the user's plan) rather than the phone's. */
    static boolean chatgptVoice(Context c) {
        return "chatgpt".equals(Prefs.speechEngine(c)) && SetupState.planSignedIn(c, "codex");
    }

    /** Read on the phone: ChatGPT's voice with the phone's as its fallback, or the phone's. */
    private static void here(Context c, String clean) {
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
        return CodexSpeech.active() || (tts != null && ttsReady && tts.isSpeaking()) || WatchVoice.active();
    }

    static void stopSpeaking() {
        WatchVoice.stop(); // first: its streams are cancelled before CodexSpeech.stop() closes them
        dropRouted();
        CodexSpeech.stop();
        if (tts != null && ttsReady) tts.stop();
    }
}
