package app.buddy.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.List;
import java.util.TimeZone;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

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

    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private static final byte[] A = "AAAA-first".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] B = "BB-second-payload".getBytes(StandardCharsets.US_ASCII);

    /** A file in bionic's tzdata layout: 24-byte header, 52-byte index entries, then the payloads. */
    private File db(byte[] magic) throws IOException {
        int index = 24, data = index + 2 * 52;
        ByteBuffer b = ByteBuffer.allocate(data + A.length + B.length);
        b.put(magic);
        b.position(12);
        b.putInt(index).putInt(data).putInt(0);
        entry(b, index, "America/Dawson_Creek", 0, A.length);
        entry(b, index + 52, "Etc/GMT+7", A.length, B.length);
        b.position(data);
        b.put(A).put(B);
        File f = new File(tmp.getRoot(), "tzdata");
        Files.write(f.toPath(), b.array());
        return f;
    }

    private static void entry(ByteBuffer b, int at, String name, int start, int len) {
        byte[] n = name.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < n.length; i++) b.put(at + i, n[i]);
        b.putInt(at + 40, start).putInt(at + 44, len);
    }

    private static final byte[] MAGIC = "tzdata2026e\0".getBytes(StandardCharsets.US_ASCII);

    @Test public void copiesEntriesFromTheIndex() throws IOException {
        File root = tmp.newFolder("root");
        File db = db(MAGIC);
        assertTrue(Linux.copyZone(db, root, "America/Dawson_Creek"));
        assertArrayEquals(A, Files.readAllBytes(new File(root, "usr/share/zoneinfo/America/Dawson_Creek").toPath()));
        assertTrue(Linux.copyZone(db, root, "Etc/GMT+7")); // start is relative to data_offset
        assertArrayEquals(B, Files.readAllBytes(new File(root, "usr/share/zoneinfo/Etc/GMT+7").toPath()));
    }

    @Test public void unknownIdOrBadMagicWritesNothing() throws IOException {
        File root = tmp.newFolder("root");
        assertFalse(Linux.copyZone(db(MAGIC), root, "GMT+05:30"));
        assertFalse(Linux.copyZone(db("notatzdata\0\0".getBytes(StandardCharsets.US_ASCII)), root, "Etc/GMT+7"));
        assertEquals(0, root.list().length);
    }

    @Test public void replacesAnEmptyTarget() throws IOException {
        File root = tmp.newFolder("root");
        File t = new File(root, "usr/share/zoneinfo/Etc/GMT+7");
        assertTrue(t.getParentFile().mkdirs());
        assertTrue(t.createNewFile());
        assertTrue(Linux.copyZone(db(MAGIC), root, "Etc/GMT+7"));
        assertArrayEquals(B, Files.readAllBytes(t.toPath()));
        assertEquals(1, t.getParentFile().list().length); // no .tmp left
    }

    @Test public void linkedDirectoryThrowsAndLeavesTheOutsideAlone() throws IOException {
        File root = tmp.newFolder("root"), out = tmp.newFolder("outside");
        Files.write(new File(out, "x").toPath(), "keep".getBytes());
        File zi = new File(root, "usr/share/zoneinfo");
        assertTrue(zi.mkdirs());
        Files.createSymbolicLink(new File(zi, "America").toPath(), out.toPath());
        try {
            Linux.copyZone(db(MAGIC), root, "America/Dawson_Creek");
            fail("expected IOException");
        } catch (IOException expected) {
        }
        assertArrayEquals(new String[] {"x"}, out.list());
        assertArrayEquals("keep".getBytes(), Files.readAllBytes(new File(out, "x").toPath()));
    }

    @Test public void linkedTargetAndStaleTmpAreReplacedNotFollowed() throws IOException {
        File root = tmp.newFolder("root"), out = tmp.newFolder("outside");
        File x = new File(out, "x");
        Files.write(x.toPath(), "keep".getBytes());
        File dir = new File(root, "usr/share/zoneinfo/America");
        assertTrue(dir.mkdirs());
        Files.createSymbolicLink(new File(dir, "Dawson_Creek").toPath(), x.toPath());
        Files.createSymbolicLink(new File(dir, "Dawson_Creek.tmp").toPath(), x.toPath());
        assertTrue(Linux.copyZone(db(MAGIC), root, "America/Dawson_Creek"));
        assertArrayEquals("keep".getBytes(), Files.readAllBytes(x.toPath()));
        File t = new File(dir, "Dawson_Creek");
        assertTrue(Files.isRegularFile(t.toPath(), LinkOption.NOFOLLOW_LINKS));
        assertArrayEquals(A, Files.readAllBytes(t.toPath()));
        assertArrayEquals(new String[] {"Dawson_Creek"}, dir.list());
    }
}
