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

    @Test
    public void theOffsetIsTheSamplesOwnAgeNotTheCallbacks() {
        assertEquals(100, Worn.offset(10_100, 10_000)); // delivered 4 s late, still 100 ms after the ask
        assertEquals(0, Worn.offset(9_500, 10_000)); // clock slack
        assertEquals(-1, Worn.offset(5_000, 10_000)); // taken long before the ask
    }
}
