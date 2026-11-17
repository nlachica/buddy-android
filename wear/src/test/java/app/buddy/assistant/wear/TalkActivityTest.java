package app.buddy.assistant.wear;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TalkActivityTest {
    private static Talk.Line row(int kind, String text) {
        return new Talk.Line(kind, text);
    }

    private static Talk.Line mirror(int kind, String text) {
        Talk.Line l = row(kind, text);
        l.mirror = true;
        return l;
    }

    /** What the screen draws: the snapshot's rows, then below(). Rows are the snapshot's texts, lines the Talk lines. */
    private static List<String> shown(List<String> rows, List<Talk.Line> lines, boolean snapshot, int mark) {
        List<String> out = new ArrayList<>(rows);
        for (Talk.Line l : TalkActivity.below(lines, snapshot, mark)) out.add(l.text);
        return out;
    }

    @Test
    public void withoutASnapshotAllTheTalkLinesShow() {
        List<Talk.Line> lines = Arrays.asList(mirror(Talk.YOU, "hi"), mirror(Talk.BUDDY, "B"), row(Talk.ERROR, "lost"));
        assertEquals(Arrays.asList("hi", "B", "lost"), shown(new ArrayList<>(), lines, false, 0));
    }

    @Test
    public void aReplyShowsOnceWhateverComesFirst() {
        List<String> rows = Arrays.asList("A", "B");
        // snapshot [A,B] used, then the line B arrives
        int mark = Talk.Line.count;
        assertEquals(rows, shown(rows, Arrays.asList(mirror(Talk.BUDDY, "B")), true, mark));
        // line B first, then the snapshot [A,B]: the line is older than the mark and a mirror either way
        List<Talk.Line> lines = Arrays.asList(mirror(Talk.BUDDY, "B"));
        assertEquals(rows, shown(rows, lines, true, Talk.Line.count));
    }

    @Test
    public void anOlderSnapshotLeavesTheReplyOutUntilItsOwnComes() {
        List<Talk.Line> lines = Arrays.asList(mirror(Talk.BUDDY, "B"));
        assertEquals(Arrays.asList("A"), shown(Arrays.asList("A"), lines, true, Talk.Line.count));
        assertEquals(Arrays.asList("A", "B"), shown(Arrays.asList("A", "B"), lines, true, Talk.Line.count));
    }

    @Test
    public void aSnapshotIsTakenOnlyIfOwnAndNewer() {
        assertTrue(TalkActivity.takes("c", 7, 2, "c", 7, 1));
        assertFalse(TalkActivity.takes("c", 7, 1, "c", 7, 2)); // a late older one after [A,B] (n=2)
        assertFalse(TalkActivity.takes("c", 7, 2, "c", 7, 2));
        assertFalse(TalkActivity.takes("c", 6, 9, "c", 7, 1)); // another view
        assertFalse(TalkActivity.takes("d", 7, 9, "c", 7, 1)); // another chat
    }

    @Test
    public void theWatchsOwnErrorShowsOnlyIfItCameAfterTheSnapshot() {
        Talk.Line before = row(Talk.ERROR, "old trouble");
        int mark = Talk.Line.count;
        Talk.Line after = row(Talk.ERROR, "new trouble");
        assertEquals(Arrays.asList("A", "new trouble"), shown(Arrays.asList("A"), Arrays.asList(before, after), true, mark));
    }

    private static List<Talk.Line> rows(String... t) {
        List<Talk.Line> out = new ArrayList<>();
        for (String s : t) out.add(row(Talk.BUDDY, s));
        return out;
    }

    @Test
    public void anAnchorStaysWhenTheWindowIsNotFull() {
        assertEquals(1, TalkActivity.anchor(rows("A", "B", "C"), 1, rows("A", "B", "C", "D")));
    }

    @Test
    public void anAnchorMovesUpWhenTheWindowRolls() {
        assertEquals(1, TalkActivity.anchor(rows("A", "B", "C", "D"), 2, rows("B", "C", "D", "E")));
        assertEquals(0, TalkActivity.anchor(rows("A", "B", "C", "D"), 3, rows("D", "E", "F", "G")));
    }

    @Test
    public void aDuplicateAnchorsToTheNearestAtOrBeforeTheOldIndex() {
        assertEquals(2, TalkActivity.anchor(rows("x", "y", "x", "z"), 2, rows("x", "y", "x", "z", "w")));
        assertEquals(0, TalkActivity.anchor(rows("x", "y", "z"), 0, rows("x", "y", "x")));
    }

    @Test
    public void anAnchorThatLeftIsMinusOne() {
        assertEquals(-1, TalkActivity.anchor(rows("A", "B", "C"), 0, rows("B", "C", "D")));
    }

    /** F1: a burst keeps the first snapshot's rows and index, and finds them in the newest (smaller) rows: in range, or -1. */
    @Test
    public void aRetainedAnchorStaysInRangeWhenTheRowsShrink() {
        String[] big = new String[30];
        for (int k = 0; k < big.length; k++) big[k] = "m" + k;
        List<Talk.Line> old = rows(big);
        List<Talk.Line> small = rows("m23", "m24", "m25", "m26", "m27", "m28", "m29");
        assertEquals(-1, TalkActivity.anchor(old, 20, small)); // left: the top
        assertEquals(1, TalkActivity.anchor(old, 24, small)); // still there, at its new index
    }
}
