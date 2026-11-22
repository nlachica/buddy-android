package app.buddy.assistant.wear;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WornTest {
    @Test
    public void theSensorValueIsWornFromHalf() {
        assertFalse(Worn.worn(0.0f));
        assertFalse(Worn.worn(0.49f));
        assertTrue(Worn.worn(0.5f));
        assertTrue(Worn.worn(1.0f));
    }

    @Test
    public void eachFailureHasItsReason() {
        assertEquals("no sensor", Worn.why(false, false));
        assertEquals("refused", Worn.why(true, false));
        assertNull(Worn.why(true, true));
    }
}
