package app.buddy.assistant;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.nio.FloatBuffer;
import java.util.Collections;

/**
 * The three ONNX models of "hey buddy" (mel spectrogram, speech embedding, classifier) on ONNX Runtime, one thread
 * each. GitHub flavor only; {@code src/main} loads it by name. No android.* here, so JVM tests run the real models.
 */
public final class OnnxWakeEngine implements WakeFeatures.Engine, AutoCloseable {
    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final OrtSession.SessionOptions options = new OrtSession.SessionOptions();
    private final OrtSession mel, embed, classify;

    public OnnxWakeEngine(byte[] classifier, byte[] melModel, byte[] embeddingModel) throws OrtException {
        OrtSession m = null, e = null, c = null;
        try {
            options.setIntraOpNumThreads(1);
            m = env.createSession(melModel, options);
            e = env.createSession(embeddingModel, options);
            c = env.createSession(classifier, options);
        } catch (OrtException | RuntimeException x) { // sessions made so far, then the options, have no other owner
            if (m != null) m.close();
            if (e != null) e.close();
            options.close();
            throw x;
        }
        mel = m;
        embed = e;
        classify = c;
    }

    @Override
    public float[] mel(float[] audio) {
        return run(mel, audio, 1, audio.length);
    }

    @Override
    public float[] embed(float[] windows) {
        return run(embed, windows, WakeFeatures.PER_HOP, WakeFeatures.FRAME_LEN, WakeFeatures.BINS, 1);
    }

    @Override
    public float score(float[] embeddings) {
        return run(classify, embeddings, 1, WakeFeatures.GROUPS * WakeFeatures.PER_HOP, WakeFeatures.EMB)[0];
    }

    private float[] run(OrtSession s, float[] in, long... shape) {
        try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(in), shape);
             OrtSession.Result r = s.run(Collections.singletonMap(s.getInputNames().iterator().next(), t))) {
            FloatBuffer b = ((OnnxTensor) r.get(0)).getFloatBuffer();
            float[] out = new float[b.remaining()];
            b.get(out);
            return out;
        } catch (OrtException e) {
            throw new IllegalStateException(e); // a model that can't run is a broken build, not a state to recover from
        }
    }

    @Override
    public void close() throws OrtException {
        mel.close();
        embed.close();
        classify.close();
        options.close();
    }
}
