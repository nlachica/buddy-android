package app.buddy.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UpstreamNoteTest {
    private static final String[][] NOT_AHEAD = {{"2.3.10", "2.3.10-fork.1"}, {"2.3.10", "2.3.10-fork.3"},
            {"2.3.9", "2.3.9"}, {null, "2.3.9-fork.4"}, {"", "2.3.9-fork.4"}};

    private static Updates.Release rel(String v) {
        return new Updates.Release(v, "notes", "https://x/buddy.apk");
    }

    @Test
    public void noteWhenUpstreamIsAhead() {
        assertTrue(Updates.upstreamAhead("2.3.10", "2.3.9-fork.4"));
        assertTrue(Updates.upstreamNote("2.3.10", "2.3.9-fork.4").contains("2.3.10"));
        assertTrue(Updates.upstreamNote("2.4.0", "2.3.10-fork.2").contains("2.4.0"));
    }

    @Test
    public void noNoteOtherwise() {
        for (String[] p : NOT_AHEAD) {
            assertFalse(p[0] + " vs " + p[1], Updates.upstreamAhead(p[0], p[1]));
            assertEquals("", Updates.upstreamNote(p[0], p[1]));
        }
    }

    @Test
    public void withoutNoteTextsAreToday() {
        String head = "Installed: Buddy 2.3.9-fork.4. Latest release: ";
        assertEquals(head + "2.3.9-fork.4. Buddy is up to date.",
                Updates.report("2.3.9-fork.4", rel("2.3.9-fork.4"), "", false));
        assertEquals(head + "2.3.9-fork.5. An update is available. What's new:\nnotes\n\nAsk the user if they want it now; "
                + "if yes, call update_buddy with install=true.", Updates.report("2.3.9-fork.4", rel("2.3.9-fork.5"), "", false));
        assertNull(Updates.report("2.3.9-fork.4", rel("2.3.9-fork.5"), "", true));
    }

    @Test
    public void withNoteUpdateAvailableNeverInstallsFirst() {
        String note = Updates.upstreamNote("2.3.10", "2.3.9-fork.5");
        String no = Updates.report("2.3.9-fork.4", rel("2.3.9-fork.5"), note, false);
        String yes = Updates.report("2.3.9-fork.4", rel("2.3.9-fork.5"), note, true);
        assertNotNull(yes);
        for (String t : new String[] {no, yes}) {
            assertTrue(t.contains(note));
            assertTrue(t.contains("skip_upstream=true"));
            assertFalse(t.contains("Ask the user if they want it now"));
        }
        assertTrue(yes.contains("Not installed yet."));
        assertFalse(no.contains("Not installed yet."));
    }

    @Test
    public void withNoteUpToDateSaysHowToBringItIn() {
        String note = Updates.upstreamNote("2.3.10", "2.3.9-fork.4");
        String t = Updates.report("2.3.9-fork.4", rel("2.3.9-fork.4"), note, false);
        assertTrue(t.startsWith("Installed: Buddy 2.3.9-fork.4. Latest release: 2.3.9-fork.4. Buddy is up to date."));
        assertTrue(t.contains(note));
        assertTrue(t.contains("install=true"));
        assertNotNull(Updates.report("2.3.9-fork.4", rel("2.3.9-fork.4"), note, true));
    }
}
