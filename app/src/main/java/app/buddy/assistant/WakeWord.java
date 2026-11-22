package app.buddy.assistant;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Hey buddy": the rules, as pure functions so they are unit-tested without a phone: what to cut from a transcript,
 * what counts as a stop command, whether the wake phrase overlapped Buddy's speech, what the detector's microphone
 * should be doing ({@link #state}) and how a wake turn goes ({@link Turn}). The service, the main-thread glue and the
 * microphone are elsewhere.
 */
final class WakeWord {
    /** The phrase plus the detector's delay: playback that ended less than this before the detection overlapped it. */
    static final long WAKE_SPAN_MS = 1000;
    /** A pause cause must have been gone this long before the detector listens again, so a restarting recorder doesn't flap. */
    static final long RESUME_MS = 1000;
    private static final String ENGINE = "app.buddy.assistant.OnnxWakeEngine";

    private WakeWord() {
    }

    /** Offered on Android 14+ and where the engine is in the build (github flavor); not a promise the models load. */
    static boolean available() {
        if (Build.VERSION.SDK_INT < 34) return false;
        try {
            Class.forName(ENGINE, false, WakeWord.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    // ---- the service's entry points (main thread)

    /** What the settings and the phone allow: the switch is on, the microphone is granted, Buddy's service is connected. */
    static boolean wanted(Context c) {
        return available() && Prefs.wakeWord(c) && Voice.micAllowed(c) && BuddyService.get() != null;
    }

    /**
     * The one way the service starts or stops; idempotent and cheap, called wherever something it depends on changes
     * (Buddy's service connecting or going, the switch, the permission, every Buddy screen resuming). Android may
     * refuse a microphone service started from the background: then one quiet notification offers a tap to start it.
     */
    static void ensure(Context c) {
        if (!available()) return;
        c = c.getApplicationContext();
        if (!wanted(c)) {
            stop(c);
            return;
        }
        if (WakeService.running() || WakeService.broken) return;
        try {
            c.startForegroundService(new Intent(c, WakeService.class));
            Log.i("BuddyWake", "start requested");
        } catch (IllegalStateException | SecurityException e) { // ForegroundServiceStartNotAllowedException, or the permission is gone
            Log.i("BuddyWake", "start refused: " + e);
            WakeService.tapToStart(c);
        }
    }

    /** No service, no notification, no tap prompt. */
    static void stop(Context c) {
        if (!available()) return;
        if (WakeService.running()) c.stopService(new Intent(c, WakeService.class));
        WakeService.cancelTap(c);
    }

    /**
     * Buddy's own voice path is starting (Voice.listen): the detector's microphone closes at once, unless a wake
     * window has just adopted MicServer for it. Android's recognizer runs in another app and must not start against an
     * open microphone.
     */
    static void release() {
        WakeService s = WakeService.get();
        if (s != null && !MicServer.adopted()) s.closeNow();
    }

    // ---- the turn on now (main thread)

    private static Turn turn;
    private static boolean turnFed;
    private static int window, capture;

    static boolean turnOn() {
        return turn != null;
    }

    static boolean turnFed() {
        return turnFed;
    }

    /**
     * Ends the turn without sending anything (a call, another app's recording, the switch going off, a hold that ran out):
     * its window is cleared so a late transcript is ignored, the recognizer is cancelled, the adopted audio dropped and
     * the loop forgets what it holds.
     */
    static void cancelTurn() {
        if (turn == null) return;
        int c = capture;
        turn = null;
        turnFed = false;
        capture = 0;
        window++;
        if (c != 0) MicServer.release(c);
        WakeService s = WakeService.get();
        if (s != null && s.loop() != null) s.loop().drop();
        Voice.stopListening();
    }

    /** A detection (the capture thread posted it here). Nothing is sent yet: it is logged and the loop detects again. */
    static void onWake(int seq, long detectedAt) {
        Log.i("BuddyWake", "wake " + seq);
        WakeService s = WakeService.get();
        if (s != null && s.loop() != null) s.loop().drop();
    }

    // ---- the transcript

    private static final Pattern PHRASE = Pattern.compile("(?i)\\b(?:hey|hay|hi|a|eh|okay|ok)\\W+(?:buddy|buddie|body)\\b");
    private static final Pattern BARE = Pattern.compile("(?i)^\\W*buddy\\b");
    private static final Pattern LEAD = Pattern.compile("^[\\s\\p{Punct}]+");

    /**
     * The request without the wake phrase: everything up to and including the first "hey buddy" (or a mishearing of
     * it) when that starts within the first 8 words, or a bare leading "buddy". No phrase: the text unchanged (the
     * detector fired and the recognizer dropped it). Cutting "up to" also drops words of Buddy's own speech that
     * leaked into the pre-roll.
     */
    static String strip(String text) {
        if (text == null) return "";
        Matcher m = PHRASE.matcher(text);
        if (m.find() && words(text.substring(0, m.start())) < 8) return LEAD.matcher(text.substring(m.end())).replaceFirst("").trim();
        m = BARE.matcher(text);
        if (m.find()) return LEAD.matcher(text.substring(m.end())).replaceFirst("").trim();
        return text;
    }

    private static int words(String s) {
        s = s.trim();
        return s.isEmpty() ? 0 : s.split("\\s+").length;
    }

    private static final Set<String> STOPS = new HashSet<>(Arrays.asList(
            "stop", "stop talking", "be quiet", "quiet", "shut up", "thats enough", "never mind", "cancel"));

    /** The whole request is a stop command (case, punctuation and a leading ok/okay/please don't matter). */
    static boolean isStop(String req) {
        if (req == null) return false;
        String s = req.toLowerCase(Locale.ROOT).replaceAll("['’]", "").replaceAll("[^a-z ]", " ").trim().replaceAll("\\s+", " ");
        s = s.replaceFirst("^(?:(?:ok|okay|please) )+", "");
        return STOPS.contains(s);
    }

    /**
     * Playback overlapped the wake phrase: Buddy is speaking now, or its speech ended less than {@link #WAKE_SPAN_MS}
     * before the detection. Times are one clock's milliseconds; 0 means it never spoke.
     */
    static boolean overlapped(boolean speakingNow, long speechEndedAt, long detectedAt) {
        return speakingNow || (speechEndedAt > 0 && detectedAt - speechEndedAt < WAKE_SPAN_MS);
    }

    // ---- states

    enum State { OFF, PAUSED, TURN, SPEAKING, IDLE }

    /** What {@link #state} looks at; the service gathers it on the main thread. */
    static final class Inputs {
        /** The setting is on, the microphone is granted and {@link #available()}. */
        boolean enabled;
        /** A call (AutoPause's rule). */
        boolean call;
        /** Active recordings that aren't Buddy's: not the detector's own session, not HOTWORD clients, not ones MicServer runs. */
        int foreignRecordings;
        /** A wake turn is on; fed = the detector's recorder stays open and its audio goes on to the engine. */
        boolean turn, turnFed;
        /** Buddy's own voice path has the microphone: Voice.listening(), CodexLive, MicServer serving the Terminal. */
        boolean ownVoice;
        /** Buddy's voice is playing on the phone (Voice.phoneSpeaking()). */
        boolean speaking;
        /** Milliseconds since a pause cause was last seen (a large number when never). */
        long msSinceCause = Long.MAX_VALUE;
    }

    /** Something that pauses the detector, apart from the setting: a call, someone else's recording, Buddy's own voice path. */
    static boolean cause(Inputs in) {
        if (in.call) return true;
        // an unfed turn lets the recognizer record by itself: that one recording is expected, a second is not
        if (in.foreignRecordings >= (in.turn && !in.turnFed ? 2 : 1)) return true;
        return !in.turn && in.ownVoice;
    }

    /**
     * What the detector should be. The first row that matches wins: OFF; PAUSED for a cause (it beats a turn, which
     * is then cancelled); TURN; PAUSED until the cause has been gone for {@link #RESUME_MS}; SPEAKING; IDLE.
     */
    static State state(Inputs in) {
        if (!in.enabled) return State.OFF;
        if (cause(in)) return State.PAUSED;
        if (in.turn) return State.TURN;
        if (in.msSinceCause < RESUME_MS) return State.PAUSED;
        return in.speaking ? State.SPEAKING : State.IDLE;
    }

    // ---- a wake turn

    /** One wake turn: at most two listening windows ("hey buddy", a pause, then the request). */
    static final class Turn {
        enum Act { SEND, END, AGAIN }

        static final class Step {
            final Act act;
            /** For SEND: the request without the wake phrase. */
            final String request;

            Step(Act act, String request) {
                this.act = act;
                this.request = request;
            }
        }

        private static final Step AGAIN = new Step(Act.AGAIN, null), END = new Step(Act.END, null);
        /** The wake cut Buddy's speech: a stop command is then done, not sent. */
        private final boolean cutSpeech;
        private boolean again;

        Turn(boolean cutSpeech) {
            this.cutSpeech = cutSpeech;
        }

        /** A window ended with this transcript. */
        Step heard(String text) {
            String req = strip(text);
            if (req.isEmpty()) return nothing(); // only the wake phrase
            if (cutSpeech && isStop(req)) return END;
            return new Step(Act.SEND, req);
        }

        /** A window ended with nothing heard: one more window, then give up. */
        Step nothing() {
            if (again) return END;
            again = true;
            return AGAIN;
        }
    }
}
