package com.iheartradio.m3u8.http;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
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

    /** One page of log lines, newest first. {@code olderBefore} is the file offset of the oldest line. */
    public static final class Page {
        public final List<String> events;
        public final long olderBefore;
        public final boolean hasOlder;

        Page(List<String> events, long olderBefore, boolean hasOlder) {
            this.events = events;
            this.olderBefore = olderBefore;
            this.hasOlder = hasOlder;
        }
    }

    /**
     * Put the newest {@link #RING_SIZE} lines from the log file back into the ring.
     * Called once at server start so a restart does not show an empty Logs page.
     */
    public static void loadTail() {
        synchronized (FILE_LOCK) {
            Page page = readPage(logFile, null, null, RING_SIZE, -1L);
            RING.clear();
            for (int i = page.events.size() - 1; i >= 0; i--) {
                RING.add(page.events.get(i));
            }
        }
    }

    /** Newest {@code limit} lines at or before {@code before} (a file offset). Negative reads from the end. */
    public static Page page(String sessionId, int limit, long before) {
        return page(sessionId, null, limit, before);
    }

    /** Same as {@link #page(String, int, long)} plus an exact {@code ev} match. Blank event means all types. */
    public static Page page(String sessionId, String event, int limit, long before) {
        int cap = limit > 0 ? Math.min(limit, RING_SIZE) : DEFAULT_DUMP_LIMIT;
        synchronized (FILE_LOCK) {
            return readPage(logFile, sessionId, event, cap, before);
        }
    }

    public static String pageJson(String sessionId, int limit, long before) {
        return pageJson(sessionId, null, limit, before);
    }

    public static String pageJson(String sessionId, String event, int limit, long before) {
        Page page = page(sessionId, event, limit, before);
        StringBuilder sb = new StringBuilder(256 + page.events.size() * 80);
        sb.append("{\"ok\":true,\"file\":").append(DemoHttp.jsonString(logFile.getPath()));
        sb.append(",\"count\":").append(page.events.size());
        sb.append(",\"hasOlder\":").append(page.hasOlder);
        sb.append(",\"olderBefore\":").append(page.olderBefore);
        if (sessionId != null && sessionId.length() > 0) {
            sb.append(",\"session\":").append(DemoHttp.jsonString(sessionId));
        }
        if (event != null && event.length() > 0) {
            sb.append(",\"event\":").append(DemoHttp.jsonString(event));
        }
        sb.append(",\"events\":[");
        for (int i = 0; i < page.events.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(page.events.get(i));
        }
        sb.append("]}");
        return sb.toString();
    }

    private static Page readPage(File file, String sessionId, String event, int cap, long before) {
        List<String> events = new ArrayList<String>();
        if (file == null || !file.isFile() || cap <= 0) {
            return new Page(events, 0L, false);
        }
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "r");
            long len = raf.length();
            long end = (before < 0L || before > len) ? len : before;
            if (end <= 0L) {
                return new Page(events, 0L, false);
            }
            long pos = end;
            byte[] tail = new byte[0];
            long oldestStart = end;
            boolean any = false;
            // True when the page filled while unread bytes remained in the current chunk.
            // pos can already be 0 in that case; that is not the same as scanning to EOF.
            boolean stoppedForCap = false;
            while (pos > 0L && events.size() < cap) {
                int n = (int) Math.min(64 * 1024L, pos);
                pos -= n;
                byte[] chunk = new byte[n];
                raf.seek(pos);
                raf.readFully(chunk);
                int i = chunk.length;
                while (i > 0 && events.size() < cap) {
                    int nl = -1;
                    for (int j = i - 1; j >= 0; j--) {
                        if (chunk[j] == '\n') {
                            nl = j;
                            break;
                        }
                    }
                    if (nl < 0) {
                        tail = concat(chunk, 0, i, tail);
                        break;
                    }
                    byte[] content = concat(chunk, nl + 1, i, tail);
                    long start = pos + nl + 1L;
                    tail = new byte[0];
                    i = nl;
                    String text = lineText(content);
                    if (acceptLine(text, sessionId, event)) {
                        events.add(text);
                        oldestStart = start;
                        any = true;
                    }
                }
                if (events.size() >= cap && i > 0) {
                    stoppedForCap = true;
                }
            }
            if (pos == 0L && tail.length > 0 && events.size() < cap) {
                String text = lineText(tail);
                tail = new byte[0];
                if (acceptLine(text, sessionId, event)) {
                    events.add(text);
                    oldestStart = 0L;
                    any = true;
                }
            }
            if (!any) {
                return new Page(events, 0L, false);
            }
            boolean scannedAll = pos == 0L && tail.length == 0 && !stoppedForCap;
            if (scannedAll || oldestStart == 0L) {
                return new Page(events, oldestStart, false);
            }
            return new Page(events, oldestStart, true);
        } catch (IOException e) {
            return new Page(events, 0L, false);
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException ignored) {
                    // closed enough
                }
            }
        }
    }

    private static boolean acceptLine(String text, String sessionId, String event) {
        if (text == null || text.length() == 0 || text.charAt(0) != '{') {
            return false;
        }
        if (sessionId != null && sessionId.length() > 0
                && text.indexOf("\"sid\":" + DemoHttp.jsonString(sessionId)) < 0) {
            return false;
        }
        if (event != null && event.length() > 0
                && text.indexOf("\"ev\":" + DemoHttp.jsonString(event)) < 0) {
            return false;
        }
        return true;
    }

    private static String lineText(byte[] content) {
        int n = content.length;
        if (n > 0 && content[n - 1] == '\r') {
            n--;
        }
        if (n <= 0) {
            return "";
        }
        return new String(content, 0, n, StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[] left, int off, int end, byte[] right) {
        int n = end - off;
        if (n < 0) {
            n = 0;
            off = end;
        }
        byte[] out = new byte[n + right.length];
        if (n > 0) {
            System.arraycopy(left, off, out, 0, n);
        }
        if (right.length > 0) {
            System.arraycopy(right, 0, out, n, right.length);
        }
        return out;
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

        /** Mode and format of the play this line belongs to. */
        public Event play(DemoSession session) {
            if (session == null) {
                return this;
            }
            put("mode", session.modeName());
            put("format", session.formatName());
            return this;
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
