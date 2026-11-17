package app.buddy.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WatchLinkTest {
    @Test
    public void aWatchMadeIdHasThePhonesFormat() {
        assertTrue(WatchLink.newChatId("0123456789ab"));
        for (String bad : new String[] {"", "0123456789a", "0123456789abc", "0123456789AB", "../x", "a/b", "0123456789a/"})
            assertFalse(bad, WatchLink.newChatId(bad));
    }

    private static Sessions.Msg msg(Bubble.Kind k, String t) {
        return new Sessions.Msg(k, t);
    }

    private static JSONArray history(List<Sessions.Msg> all) {
        return WatchLink.history(all, m -> m.text);
    }

    @Test
    public void historyKeepsTheConversationOldestFirst() throws Exception {
        List<Sessions.Msg> all = new ArrayList<>();
        all.add(msg(Bubble.Kind.USER, "hi"));
        all.add(msg(Bubble.Kind.STEP, "tapping"));
        all.add(msg(Bubble.Kind.NOTE, "a note"));
        all.add(msg(Bubble.Kind.BUDDY, ""));
        all.add(msg(Bubble.Kind.BUDDY, "hello"));
        all.add(msg(Bubble.Kind.ERROR, "oops"));
        JSONArray h = history(all);
        assertEquals(3, h.length());
        assertEquals("you", h.getJSONObject(0).getString("kind"));
        assertEquals("hello", h.getJSONObject(1).getString("text"));
        assertEquals("error", h.getJSONObject(2).getString("kind"));
        assertEquals(0, history(new ArrayList<>()).length());
    }

    @Test
    public void historyKeepsTheLatest30() throws Exception {
        List<Sessions.Msg> all = new ArrayList<>();
        for (int i = 0; i < 50; i++) all.add(msg(Bubble.Kind.USER, "m" + i));
        JSONArray h = history(all);
        assertEquals(WatchLink.HISTORY_MAX, h.length());
        assertEquals("m20", h.getJSONObject(0).getString("text"));
        assertEquals("m49", h.getJSONObject(29).getString("text"));
    }

    @Test
    public void aLongMessageIsCutWithoutSplittingAPair() throws Exception {
        StringBuilder b = new StringBuilder("x");
        for (int i = 0; i < 2000; i++) b.append("😀"); // the cut at 3000 falls inside a pair
        List<Sessions.Msg> all = new ArrayList<>();
        all.add(msg(Bubble.Kind.BUDDY, b.toString()));
        String t = history(all).getJSONObject(0).getString("text");
        assertTrue(t.endsWith("…"));
        assertFalse(Character.isHighSurrogate(t.charAt(t.length() - 2)));
        assertTrue(t.length() <= 3001);
    }

    @Test
    public void historyStaysUnderTheByteBudgetAndKeepsTheNewest() throws Exception {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 3000; i++) b.append('語'); // 3 bytes each
        List<Sessions.Msg> all = new ArrayList<>();
        for (int i = 0; i < 30; i++) all.add(msg(Bubble.Kind.BUDDY, i == 29 ? "last" : b.toString()));
        JSONArray h = history(all);
        assertTrue(h.length() < 30);
        assertTrue(h.toString().getBytes(StandardCharsets.UTF_8).length <= WatchLink.HISTORY_MAX_BYTES + 100);
        assertEquals("last", h.getJSONObject(h.length() - 1).getString("text"));
    }

    private static String shown(Map<String, WatchLink.Viewer> m) {
        WatchLink.Viewer v = m.get("w");
        return v == null || v.chat == null ? null : v.chat + v.view;
    }

    @Test
    public void aDelayedStartOrStopDoesNotUndoANewerView() {
        Map<String, WatchLink.Viewer> m = new HashMap<>();
        assertTrue(WatchLink.register(m, "w", "a", 2, false, 0));
        assertFalse(WatchLink.register(m, "w", "a", 1, false, 1)); // start 1 comes after start 2
        assertFalse(WatchLink.register(m, "w", "a", 1, true, 2)); // and so does stop 1
        assertEquals("a2", shown(m));
    }

    @Test
    public void aDelayedStartCannotReviveAStoppedView() {
        Map<String, WatchLink.Viewer> m = new HashMap<>();
        assertTrue(WatchLink.register(m, "w", "a", 2, false, 0));
        assertTrue(WatchLink.register(m, "w", "a", 2, true, 1));
        assertFalse(WatchLink.register(m, "w", "a", 2, false, 2));
        assertEquals(null, shown(m));
        assertTrue(WatchLink.register(m, "w", "b", 3, false, 3)); // a newer start after a stop works
        assertEquals("b3", shown(m));
    }

    @Test
    public void aStopBeforeItsStartKeepsTheViewStopped() {
        Map<String, WatchLink.Viewer> m = new HashMap<>();
        assertTrue(WatchLink.register(m, "w", "a", 1, false, 0));
        assertTrue(WatchLink.register(m, "w", "a", 2, true, 1)); // stop 2 first
        assertFalse(WatchLink.register(m, "w", "a", 2, false, 2)); // then its start
        assertEquals(null, shown(m));
    }

    @Test
    public void aRegistrationLapsesSoAWatchWithAnOlderClockCanStartAgain() {
        Map<String, WatchLink.Viewer> m = new HashMap<>();
        assertTrue(WatchLink.register(m, "w", "a", 100, false, 0));
        assertFalse(WatchLink.register(m, "w", "a", 5, false, 1));
        assertTrue(WatchLink.register(m, "w", "a", 5, false, 31 * 60_000L));
        assertEquals("a5", shown(m));
    }
}
