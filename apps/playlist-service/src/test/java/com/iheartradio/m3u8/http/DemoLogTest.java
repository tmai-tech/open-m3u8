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
}
