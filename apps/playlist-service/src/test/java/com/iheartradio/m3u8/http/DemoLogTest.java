package com.iheartradio.m3u8.http;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DemoLogTest {

    @Test
    public void summarizePlaylistExtractsCueAndPdt() {
        String text = "#EXTM3U\n"
                + "#EXT-X-MEDIA-SEQUENCE:99\n"
                + "#EXT-X-PROGRAM-DATE-TIME:2026-08-23T10:20:17.920000Z\n"
                + "#EXT-X-DATERANGE:ID=\"user-ad-1\",START-DATE=2026-08-23T10:20:27.920Z\n"
                + "#EXTINF:0.32,\nseg.ts\n"
                + "#EXT-X-CUE-OUT:8.0\n"
                + "#EXT-X-CUE-OUT-CONT:4.0/8.0\n"
                + "#EXT-X-ENDLIST\n";
        DemoLog.Event ev = DemoLog.event("rewrite").sid("s-test");
        DemoLog.summarizePlaylist(ev, text.getBytes(StandardCharsets.UTF_8));
        ev.write();
        List<String> dump = DemoLog.dump("s-test", 10);
        assertFalse(dump.isEmpty());
        String line = dump.get(dump.size() - 1);
        assertTrue(line.contains("\"ev\":\"rewrite\""));
        assertTrue(line.contains("\"sid\":\"s-test\""));
        assertTrue(line.contains("\"endlist\":true"));
        assertTrue(line.contains("\"cues\":1"));
        assertTrue(line.contains("\"dateranges\":1"));
        assertTrue(line.contains("2026-08-23T10:20:17.920000Z"));
        assertTrue(line.contains("2026-08-23T10:20:27.920Z"));
        assertTrue(line.contains("\"mediaSequence\":\"99\""));
    }

    @Test
    public void dumpFiltersBySessionAndWritesFile() throws Exception {
        File tmp = Files.createTempFile("open-m3u8-log", ".jsonl").toFile();
        tmp.deleteOnExit();
        DemoLog.setLogFileForTest(tmp);
        DemoLog.event("session").sid("keep-me").put("strategy", "sgai").write();
        DemoLog.event("session").sid("other").put("strategy", "ssai").write();
        List<String> mine = DemoLog.dump("keep-me", 50);
        assertEquals(1, mine.size());
        assertTrue(mine.get(0).contains("keep-me"));
        String file = new String(Files.readAllBytes(tmp.toPath()), StandardCharsets.UTF_8);
        assertTrue(file.contains("keep-me"));
        assertTrue(file.contains("other"));
        String json = DemoLog.dumpJson("keep-me", 20);
        assertTrue(json.contains("\"ok\":true"));
        assertTrue(json.contains("\"events\":["));
        assertTrue(json.contains("keep-me"));
        assertFalse(json.contains("\"sid\":\"other\""));
    }

    @Test
    public void pageReadsNewestLinesAndWalksOlder() throws Exception {
        File prev = DemoLog.logFile();
        File tmp = Files.createTempFile("open-m3u8-page", ".jsonl").toFile();
        tmp.deleteOnExit();
        DemoLog.setLogFileForTest(tmp);
        try {
            DemoLog.event("session").sid("s1").put("strategy", "ssai").write();
            DemoLog.event("warn").sid("s1").put("msg",
                    "Break ssai-1 at 12s discarded\n  http://ads/bad.m3u8\n  reason: HTTP 404").write();
            DemoLog.event("session").sid("s2").put("strategy", "sgai").write();
            DemoLog.Page first = DemoLog.page(null, 2, -1L);
            assertEquals(2, first.events.size());
            assertTrue(first.events.get(0).contains("\"sid\":\"s2\""));
            assertTrue(first.events.get(1).contains("reason: HTTP 404"));
            assertTrue(first.hasOlder);
            DemoLog.Page older = DemoLog.page(null, 2, first.olderBefore);
            assertEquals(1, older.events.size());
            assertTrue(older.events.get(0).contains("\"sid\":\"s1\""));
            assertFalse(older.hasOlder);
            DemoLog.Page only = DemoLog.page("s2", 10, -1L);
            assertEquals(1, only.events.size());
            assertTrue(only.events.get(0).contains("sgai"));
            String json = DemoLog.pageJson(null, 1, -1L);
            assertTrue(json.contains("\"hasOlder\":true"));
            assertTrue(json.contains("\"olderBefore\":"));
        } finally {
            DemoLog.setLogFileForTest(prev);
        }
    }

    @Test
    public void pageFiltersByEventType() throws Exception {
        File prev = DemoLog.logFile();
        File tmp = Files.createTempFile("open-m3u8-ev", ".jsonl").toFile();
        tmp.deleteOnExit();
        DemoLog.setLogFileForTest(tmp);
        try {
            DemoLog.event("warn").sid("s1").put("msg", "one").write();
            DemoLog.event("session").sid("s1").put("strategy", "ssai").write();
            DemoLog.event("warn").sid("s1").put("msg", "two").write();
            DemoLog.event("rewrite").sid("s2").put("kind", "media").write();
            DemoLog.Page first = DemoLog.page(null, "warn", 1, -1L);
            assertEquals(1, first.events.size());
            assertTrue(first.events.get(0).contains("\"ev\":\"warn\""));
            assertTrue(first.events.get(0).contains("two"));
            assertTrue(first.hasOlder);
            DemoLog.Page older = DemoLog.page(null, "warn", 1, first.olderBefore);
            assertEquals(1, older.events.size());
            assertTrue(older.events.get(0).contains("one"));
            assertFalse(older.hasOlder);
            DemoLog.Page both = DemoLog.page("s1", "session", 10, -1L);
            assertEquals(1, both.events.size());
            assertTrue(both.events.get(0).contains("ssai"));
            assertFalse(both.events.get(0).contains("\"ev\":\"warn\""));
            String json = DemoLog.pageJson(null, "origin", 10, -1L);
            assertTrue(json.contains("\"count\":0"));
            assertTrue(json.contains("\"event\":\"origin\""));
            assertFalse(json.contains("\"ev\":\"warn\""));
        } finally {
            DemoLog.setLogFileForTest(prev);
        }
    }

    @Test
    public void pageWalksAcrossChunkBoundaries() throws Exception {
        File prev = DemoLog.logFile();
        File tmp = Files.createTempFile("open-m3u8-chunks", ".jsonl").toFile();
        tmp.deleteOnExit();
        DemoLog.setLogFileForTest(tmp);
        try {
            StringBuilder big = new StringBuilder(70000);
            big.append("span-marker-");
            while (big.length() < 70000) {
                big.append('x');
            }
            DemoLog.event("ingest").put("id", big.toString()).write();
            DemoLog.event("session").sid("late").put("strategy", "ssai").write();
            DemoLog.event("session").sid("newest").put("strategy", "sgai").write();
            DemoLog.Page first = DemoLog.page(null, 2, -1L);
            assertEquals(2, first.events.size());
            assertTrue(first.events.get(0).contains("\"sid\":\"newest\""));
            assertTrue(first.events.get(1).contains("\"sid\":\"late\""));
            assertTrue(first.hasOlder);
            DemoLog.Page older = DemoLog.page(null, 2, first.olderBefore);
            assertEquals(1, older.events.size());
            assertTrue(older.events.get(0).contains("span-marker-"));
            assertFalse(older.hasOlder);

            File many = Files.createTempFile("open-m3u8-many", ".jsonl").toFile();
            many.deleteOnExit();
            DemoLog.setLogFileForTest(many);
            int n = 4000;
            StringBuilder body = new StringBuilder(n * 48);
            for (int i = 0; i < n; i++) {
                body.append("{\"ev\":\"session\",\"sid\":\"m\",\"n\":").append(i).append("}\n");
            }
            Files.write(many.toPath(), body.toString().getBytes(StandardCharsets.UTF_8));
            int seen = 0;
            long before = -1L;
            boolean more = true;
            String newest = null;
            String oldest = null;
            for (int guard = 0; more && guard < 80; guard++) {
                DemoLog.Page p = DemoLog.page(null, 100, before);
                assertFalse(p.events.isEmpty());
                if (newest == null) {
                    newest = p.events.get(0);
                }
                oldest = p.events.get(p.events.size() - 1);
                seen += p.events.size();
                if (p.hasOlder) {
                    assertTrue(p.olderBefore >= 0L);
                    assertTrue(before < 0L || p.olderBefore < before);
                }
                more = p.hasOlder;
                before = p.olderBefore;
            }
            assertEquals(n, seen);
            assertFalse(more);
            assertTrue(newest.contains("\"n\":3999"));
            assertTrue(oldest.contains("\"n\":0"));
            DemoLog.Page onlyNewest = DemoLog.page("nope", 10, -1L);
            assertEquals(0, onlyNewest.events.size());
            assertFalse(onlyNewest.hasOlder);
        } finally {
            DemoLog.setLogFileForTest(prev);
        }
    }

    @Test
    public void loadTailRestoresTheRingFromTheFile() throws Exception {
        File prev = DemoLog.logFile();
        File tmp = Files.createTempFile("open-m3u8-tail", ".jsonl").toFile();
        tmp.deleteOnExit();
        DemoLog.setLogFileForTest(tmp);
        try {
            DemoLog.event("ingest").put("id", "tail-title").put("status", "ready").write();
            DemoLog.loadTail();
            List<String> dump = DemoLog.dump(null, 20);
            assertFalse(dump.isEmpty());
            assertTrue(dump.get(dump.size() - 1).contains("tail-title"));
        } finally {
            DemoLog.setLogFileForTest(prev);
        }
    }

    @Test
    public void playTagsModeAndFormat() {
        DemoSession live = DemoSession.fromJson("live",
                "{\"strategy\":\"sgai\",\"contentUrl\":\"http://127.0.0.1/a.mpd?x=1\",\"liveCadenceSec\":60}");
        assertEquals("live", live.modeName());
        assertEquals("dash", live.formatName());
        DemoSession snap = DemoSession.fromJson("snap",
                "{\"strategy\":\"ssai\",\"contentUrl\":\"http://127.0.0.1/a.m3u8\","
                        + "\"forceVod\":true,\"liveCadenceSec\":60}");
        assertEquals("snapshot", snap.modeName());
        assertEquals("hls", snap.formatName());
        DemoSession vod = DemoSession.fromJson("vod",
                "{\"strategy\":\"ssai\",\"contentUrl\":\"http://127.0.0.1/a.m3u8\"}");
        assertEquals("vod", vod.modeName());
        assertEquals("hls", vod.formatName());
    }
}
