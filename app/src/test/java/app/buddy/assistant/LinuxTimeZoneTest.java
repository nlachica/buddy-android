package app.buddy.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.TimeZone;
import org.junit.Test;

public class LinuxTimeZoneTest {
    private static final long NOV = 1795140000000L; // 2026-11-20 02:00Z
    private static final long JUL = 1783000000000L; // 2026-07-02

    @Test public void envHasOneTzAndKeepsTheRest() {
        List<String> e = Linux.env("Etc/GMT+7");
        assertEquals(1, e.stream().filter(x -> x.startsWith("TZ=")).count());
        assertTrue(e.contains("TZ=Etc/GMT+7"));
        assertTrue(e.contains("HOME=/root"));
        assertTrue(e.contains("LANG=C.UTF-8"));
        assertTrue(e.stream().anyMatch(x -> x.startsWith("PATH=/root/.local/bin:")));
    }

    @Test public void fixedZoneGivesInvertedEtcId() {
        assertEquals("Etc/GMT+7", Linux.zoneId(TimeZone.getTimeZone("America/Dawson_Creek"), NOV));
        assertEquals("Etc/GMT-1", Linux.zoneId(TimeZone.getTimeZone("Europe/Berlin"), NOV));
        assertEquals("Etc/GMT-14", Linux.zoneId(TimeZone.getTimeZone("Pacific/Kiritimati"), NOV));
        assertEquals("Etc/GMT+12", Linux.zoneId(TimeZone.getTimeZone("Etc/GMT+12"), NOV));
        assertEquals("Etc/GMT", Linux.zoneId(TimeZone.getTimeZone("UTC"), NOV));
    }

    @Test public void dstZoneFollowsTheOffsetOfTheMoment() {
        TimeZone la = TimeZone.getTimeZone("America/Los_Angeles");
        assertEquals("Etc/GMT+8", Linux.zoneId(la, NOV));
        assertEquals("Etc/GMT+7", Linux.zoneId(la, JUL));
    }

    @Test public void halfHourZoneKeepsItsRegionId() {
        assertEquals("Asia/Kolkata", Linux.zoneId(TimeZone.getTimeZone("Asia/Kolkata"), NOV));
    }
}
