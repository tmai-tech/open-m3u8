package com.iheartradio.m3u8.http;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Demo decision log: JSON lines on disk plus a small in-memory ring for {@code GET /api/logs}.
 * Playlist / session events only — not every proxied segment.
 */
public final class DemoLog {

    public static final int RING_SIZE = 2000;
    public static final int DEFAULT_DUMP_LIMIT = 200;

    private static final Object FILE_LOCK = new Object();
    private static final CopyOnWriteArrayList<String> RING = new CopyOnWriteArrayList<String>();
    private static volatile File logFile = new File("logs", "open-m3u8.jsonl");

    private DemoLog() {
    }

    public static File logFile() {
        return logFile;
    }

    static void setLogFileForTest(File file) {
        logFile = file;
    }

    public static Event event(String ev) {
        return new Event(ev);
    }

    public static void write(String line) {
        if (line == null || line.length() == 0) {
            return;
        }
        RING.add(line);
        int extra = RING.size() - RING_SIZE;
        for (int i = 0; i < extra; i++) {
            if (!RING.isEmpty()) {
                RING.remove(0);
            }
        }
        File dest = logFile;
        if (dest == null) {
            return;
        }
        synchronized (FILE_LOCK) {
            try {
                File parent = dest.getParentFile();
                if (parent != null && !parent.isDirectory()) {
                    parent.mkdirs();
                }
                FileOutputStream fos = new FileOutputStream(dest, true);
                try {
                    OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
                    w.write(line);
                    w.write('\n');
                    w.flush();
                } finally {
                    fos.close();
                }
            } catch (Exception ignored) {
                // ring still has the event
            }
        }
    }

    public static List<String> dump(String sessionId, int limit) {
        int cap = limit > 0 ? limit : DEFAULT_DUMP_LIMIT;
        List<String> all = new ArrayList<String>(RING);
        List<String> out = new ArrayList<String>();
        for (int i = all.size() - 1; i >= 0 && out.size() < cap; i--) {
            String line = all.get(i);
            if (sessionId != null && sessionId.length() > 0
                    && line.indexOf("\"sid\":" + DemoHttp.jsonString(sessionId)) < 0) {
                continue;
            }
            out.add(line);
        }
        java.util.Collections.reverse(out);
        return out;
    }

    public static String dumpJson(String sessionId, int limit) {
        List<String> lines = dump(sessionId, limit);
        StringBuilder sb = new StringBuilder(256 + lines.size() * 80);
        sb.append("{\"ok\":true,\"file\":").append(DemoHttp.jsonString(logFile.getPath()));
        sb.append(",\"count\":").append(lines.size());
        if (sessionId != null && sessionId.length() > 0) {
            sb.append(",\"session\":").append(DemoHttp.jsonString(sessionId));
        }
        sb.append(",\"events\":[");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(lines.get(i));
        }
        sb.append("]}");
        return sb.toString();
    }

    public static void summarizePlaylist(Event ev, byte[] body) {
        if (ev == null || body == null || body.length == 0) {
            return;
        }
        String text = new String(body, StandardCharsets.UTF_8);
        ev.put("bytes", body.length);
        ev.put("endlist", text.indexOf("#EXT-X-ENDLIST") >= 0);
        ev.put("cues", countTag(text, "#EXT-X-CUE-OUT:"));
        ev.put("dateranges", countTag(text, "#EXT-X-DATERANGE:"));
        ev.put("discontinuity", countTag(text, "#EXT-X-DISCONTINUITY"));
        String pdt = firstTagValue(text, "#EXT-X-PROGRAM-DATE-TIME:");
        if (pdt != null) {
            ev.put("pdt", pdt);
        }
        String start = firstAttr(text, "START-DATE=");
        if (start != null) {
            ev.put("startDate", start);
        }
        String msn = firstTagValue(text, "#EXT-X-MEDIA-SEQUENCE:");
        if (msn != null) {
            ev.put("mediaSequence", msn);
        }
        ev.put("durationSec", Math.round(extinfSum(text) * 10.0) / 10.0);
    }

    static int countTag(String text, String tag) {
        int n = 0;
        int from = 0;
        while (from < text.length()) {
            int i = text.indexOf(tag, from);
            if (i < 0) {
                break;
            }
            n++;
            from = i + tag.length();
        }
        return n;
    }

    static String firstTagValue(String text, String tag) {
        int i = text.indexOf(tag);
        if (i < 0) {
            return null;
        }
        int start = i + tag.length();
        int end = text.indexOf('\n', start);
        if (end < 0) {
            end = text.length();
        }
        String v = text.substring(start, end).trim();
        if (v.endsWith("\r")) {
            v = v.substring(0, v.length() - 1).trim();
        }
        return v.length() == 0 ? null : v;
    }

    static String firstAttr(String text, String key) {
        int i = text.indexOf(key);
        if (i < 0) {
            return null;
        }
        int start = i + key.length();
        if (start < text.length() && text.charAt(start) == '"') {
            int end = text.indexOf('"', start + 1);
            return end > start ? text.substring(start + 1, end) : null;
        }
        int end = start;
        while (end < text.length()) {
            char c = text.charAt(end);
            if (c == ',' || c == '\n' || c == '\r') {
                break;
            }
            end++;
        }
        String v = text.substring(start, end).trim();
        return v.length() == 0 ? null : v;
    }

    static float extinfSum(String text) {
        float sum = 0f;
        int from = 0;
        while (from < text.length()) {
            int i = text.indexOf("#EXTINF:", from);
            if (i < 0) {
                break;
            }
            int start = i + 8;
            int end = start;
            while (end < text.length()) {
                char c = text.charAt(end);
                if (c == ',' || c == '\n' || c == '\r') {
                    break;
                }
                end++;
            }
            try {
                sum += Float.parseFloat(text.substring(start, end).trim());
            } catch (NumberFormatException ignored) {
                // skip
            }
            from = end;
        }
        return sum;
    }

    private static String nowIso() {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(new Date());
    }

    public static final class Event {
        private final StringBuilder json = new StringBuilder(256);
        private boolean comma;

        Event(String ev) {
            json.append("{\"ts\":").append(DemoHttp.jsonString(nowIso()));
            comma = true;
            putRaw("ev", DemoHttp.jsonString(ev == null ? "event" : ev));
        }

        public Event sid(String sid) {
            return put("sid", sid);
        }

        public Event put(String key, String value) {
            if (value == null) {
                return this;
            }
            return putRaw(key, DemoHttp.jsonString(value));
        }

        public Event put(String key, boolean value) {
            return putRaw(key, value ? "true" : "false");
        }

        public Event put(String key, long value) {
            return putRaw(key, Long.toString(value));
        }

        public Event put(String key, double value) {
            return putRaw(key, Double.toString(value));
        }

        public Event putRaw(String key, String jsonValue) {
            if (key == null || jsonValue == null) {
                return this;
            }
            if (comma) {
                json.append(',');
            }
            json.append(DemoHttp.jsonString(key)).append(':').append(jsonValue);
            comma = true;
            return this;
        }

        public void write() {
            DemoLog.write(json.append('}').toString());
        }
    }
}
