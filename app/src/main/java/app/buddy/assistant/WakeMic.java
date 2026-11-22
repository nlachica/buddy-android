package app.buddy.assistant;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.util.Log;

/**
 * The detector's microphone, 16 kHz mono PCM16, in its two setups. Idle: VOICE_RECOGNITION (as MicServer, not privacy
 * sensitive). While Buddy speaks on the phone: VOICE_COMMUNICATION with the echo canceller (as CodexLive does), so its
 * own voice isn't heard as "hey buddy" and the phrase still is. If that recorder won't open, VOICE_RECOGNITION with a
 * canceller attempt, so the detector isn't deaf. Plain Android, no Buddy class; one BuddyWake log line per open.
 */
final class WakeMic {
    private static final int RATE = 16000;
    private AudioRecord rec;
    private AcousticEchoCanceler aec;

    /** Opens and starts recording. False (everything released, cause logged) if the microphone can't be had. */
    boolean open(boolean speaking) {
        close();
        if (speaking && open(MediaRecorder.AudioSource.VOICE_COMMUNICATION, true)) return true;
        return open(MediaRecorder.AudioSource.VOICE_RECOGNITION, speaking);
    }

    private boolean open(int source, boolean echoCancel) {
        AudioRecord r = null;
        AcousticEchoCanceler e = null;
        boolean ok = false;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            r = new AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(Math.max(min, RATE * 2)) // about 1 s
                    .setPrivacySensitive(false)
                    .build();
            if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.i("BuddyWake", "mic source " + source + " did not initialise");
                return false;
            }
            if (echoCancel && AcousticEchoCanceler.isAvailable()) {
                e = AcousticEchoCanceler.create(r.getAudioSessionId());
                if (e != null) e.setEnabled(true);
            }
            r.startRecording();
            if (r.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                Log.i("BuddyWake", "mic source " + source + " did not start recording");
                return false;
            }
            rec = r;
            aec = e;
            ok = true;
            Log.i("BuddyWake", "mic open: source " + (source == MediaRecorder.AudioSource.VOICE_COMMUNICATION ? "VOICE_COMMUNICATION" : "VOICE_RECOGNITION")
                    + ", echo canceller " + (e != null && e.getEnabled() ? "on" : echoCancel ? "unavailable" : "off"));
            return true;
        } catch (Exception x) { // SecurityException (permission gone), IllegalArgumentException, UnsupportedOperationException
            Log.i("BuddyWake", "mic source " + source + " failed: " + x);
            return false;
        } finally {
            if (!ok) { // ownership moves to rec/aec only on success
                if (e != null) e.release();
                if (r != null) r.release();
            }
        }
    }

    /** Blocks until n samples are read (or fewer); a negative number is an AudioRecord error. */
    int read(short[] buf, int n) {
        AudioRecord r = rec;
        return r == null ? -1 : r.read(buf, 0, n);
    }

    /** The recording's audio session, to tell it from other apps' recordings. */
    int session() {
        AudioRecord r = rec;
        return r == null ? 0 : r.getAudioSessionId();
    }

    boolean isOpen() {
        return rec != null;
    }

    void close() {
        AudioRecord r = rec;
        AcousticEchoCanceler e = aec;
        rec = null;
        aec = null;
        if (e != null) e.release();
        if (r != null) {
            try {
                r.stop();
            } catch (Exception ignored) {
            }
            r.release();
        }
    }
}
