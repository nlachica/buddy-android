package app.buddy.assistant.wear;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import java.util.List;
import java.util.function.Consumer;

/**
 * The off-body sensor (docs/features/033-watch-worn-audio/plan.md): hands each event on as worn / not worn. Used by
 * ReadAloud, for the phone's ask and while a reading plays. No permission is requested; a sensor that is missing or
 * refused is reported by {@link #start()} as the reason the phone logs.
 */
final class Worn implements SensorEventListener {
    private final SensorManager sm;
    private final Consumer<Boolean> out;

    Worn(Context c, Consumer<Boolean> out) {
        sm = c.getSystemService(SensorManager.class);
        this.out = out;
    }

    /** The sensor's value: 1.0 on the body, 0.0 off it (floats: compare, don't equal). */
    static boolean worn(float v) {
        return v >= 0.5f;
    }

    /** Why there is no answer: "no sensor", "refused" (registration failed), or null. */
    static String why(boolean hasSensor, boolean registered) {
        return !hasSensor ? "no sensor" : !registered ? "refused" : null;
    }

    /** Starts listening (events come on the main thread); null when it does, else the reason it can't. */
    String start() {
        List<Sensor> l = sm.getSensorList(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT);
        boolean ok = false;
        try {
            ok = !l.isEmpty() && sm.registerListener(this, l.get(0), SensorManager.SENSOR_DELAY_NORMAL);
        } catch (SecurityException e) {
            // ok stays false: "refused"
        }
        return why(!l.isEmpty(), ok);
    }

    void stop() {
        sm.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        out.accept(worn(e.values[0]));
    }

    @Override
    public void onAccuracyChanged(Sensor s, int accuracy) {
    }
}
