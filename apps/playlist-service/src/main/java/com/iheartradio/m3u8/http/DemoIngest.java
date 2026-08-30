package com.iheartradio.m3u8.http;

import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Accept a user MP4 into {@code media/inbox/} and queue a catalog row. Does not run FFmpeg.
 */
public final class DemoIngest {

    public static final long MAX_BYTES = 200L * 1024L * 1024L;

    private DemoIngest() {
    }

    public static final class Result {
        public final DemoCatalog.Title title;
        public final File inboxFile;

        public Result(DemoCatalog.Title title, File inboxFile) {
            this.title = title;
            this.inboxFile = inboxFile;
        }
    }

    public static Result accept(HttpExchange ex, File mediaRoot) throws IOException {
        String ct = DemoHttp.firstHeader(ex.getRequestHeaders(), "Content-Type");
        if (ct == null) {
            throw new IllegalArgumentException("Content-Type is required");
        }
        String cl = DemoHttp.firstHeader(ex.getRequestHeaders(), "Content-Length");
        if (cl != null) {
            try {
                long n = Long.parseLong(cl.trim());
                if (n > MAX_BYTES) {
                    throw new IllegalArgumentException("file too large (max 200MB)");
                }
            } catch (NumberFormatException ignored) {
                // parse the stream with a running cap
            }
        }
        File inbox = DemoCatalog.inboxDir(mediaRoot);
        inbox.mkdirs();
        ParsedUpload parsed = parseRequest(ex.getRequestBody(), ct, inbox);
        if (parsed.filename == null || !parsed.filename.toLowerCase(Locale.US).endsWith(".mp4")) {
            if (parsed.tempFile != null) {
                parsed.tempFile.delete();
            }
            throw new IllegalArgumentException("only .mp4 uploads are accepted");
        }
        if (parsed.tempFile == null || !parsed.tempFile.isFile() || parsed.tempFile.length() == 0) {
            throw new IllegalArgumentException("missing file");
        }
        if (parsed.tempFile.length() > MAX_BYTES) {
            parsed.tempFile.delete();
            throw new IllegalArgumentException("file too large (max 200MB)");
        }

        String display = DemoCatalog.displayTitle(parsed.filename, parsed.title);
        String slug = DemoCatalog.slug(parsed.filename);
        String hash = DemoCatalog.sha256(parsed.tempFile);
        final String[] idHolder = new String[1];
        final boolean[] duplicate = new boolean[] { false };
        DemoCatalog.Title queued;
        synchronized (DemoIngest.class) {
            DemoCatalog.update(mediaRoot, titles -> {
                DemoCatalog.Title original = DemoCatalog.findOriginal(titles, hash, slug);
                if (original != null) {
                    DemoCatalog.Title existing = DemoCatalog.findDuplicateOf(titles, original.id);
                    if (existing != null) {
                        idHolder[0] = existing.id;
                        duplicate[0] = true;
                        return;
                    }
                    DemoCatalog.Title dup = new DemoCatalog.Title();
                    dup.id = DemoCatalog.allocateId(titles, slug + "-dup", mediaRoot);
                    dup.title = original.title != null ? original.title : display;
                    dup.sub = "Duplicate of " + (original.title != null ? original.title : original.id);
                    dup.url = original.url;
                    dup.poster = original.poster;
                    dup.status = DemoJobStatus.DUPLICATE;
                    dup.contentHash = hash;
                    dup.duplicateOf = original.id;
                    titles.add(dup);
                    idHolder[0] = dup.id;
                    duplicate[0] = true;
                    return;
                }
                String id = DemoCatalog.allocateId(titles, slug, mediaRoot);
                idHolder[0] = id;
                DemoCatalog.Title t = new DemoCatalog.Title();
                t.id = id;
                t.title = display;
                t.sub = "Queued";
                t.url = "/media/titles/" + id + "/master.m3u8";
                t.poster = "/media/titles/" + id + "/poster.jpg";
                t.status = DemoJobStatus.QUEUED;
                t.contentHash = hash;
                titles.add(t);
            });
            queued = DemoCatalog.find(DemoCatalog.load(mediaRoot), idHolder[0]);
        }
        if (duplicate[0]) {
            parsed.tempFile.delete();
            DemoJobLog.append(mediaRoot, idHolder[0],
                    "duplicate of " + (queued != null ? queued.duplicateOf : "existing job")
                            + " — not queued for packager");
            return new Result(queued, null);
        }
        File dest = new File(inbox, idHolder[0] + ".mp4");
        if (dest.exists()) {
            dest.delete();
        }
        Files.move(parsed.tempFile.toPath(), dest.toPath());
        DemoJobLog.append(mediaRoot, idHolder[0],
                "queued saved inbox/" + dest.getName() + " (" + dest.length() + " bytes)");
        DemoJobLog.append(mediaRoot, idHolder[0],
                "waiting for packager (separate process)");
        return new Result(queued, dest);
    }

    public static final class NotFoundException extends IllegalArgumentException {
        public NotFoundException(String id) {
            super("job not found: " + id);
        }
    }

    public static final class DeleteBlockedException extends IllegalStateException {
        public final List<String> usedBy;

        public DeleteBlockedException(List<String> usedBy) {
            super("in use as house ad by " + usedBy);
            this.usedBy = usedBy;
        }
    }

    /**
     * Cancel (queued/packaging) or delete (ready/failed/duplicate) and purge job files.
     */
    public static DemoCatalog.Title delete(File mediaRoot, String id) throws IOException {
        if (!DemoJobLog.validId(id)) {
            throw new IllegalArgumentException("invalid id");
        }
        final DemoCatalog.Title[] removed = new DemoCatalog.Title[1];
        DemoCatalog.update(mediaRoot, titles -> {
            DemoCatalog.Title t = DemoCatalog.find(titles, id);
            if (t == null) {
                throw new NotFoundException(id);
            }
            List<String> usedBy = new ArrayList<String>();
            for (int i = 0; i < titles.size(); i++) {
                DemoCatalog.Title o = titles.get(i);
                if (o == null || id.equals(o.id)) {
                    continue;
                }
                if (o.status == DemoJobStatus.READY && DemoCatalog.pointsAtTitle(o, id)) {
                    usedBy.add(o.id);
                }
            }
            if (!usedBy.isEmpty()) {
                throw new DeleteBlockedException(usedBy);
            }
            File cancel = DemoCatalog.cancelFile(mediaRoot, id);
            File inbox = DemoCatalog.inboxDir(mediaRoot);
            inbox.mkdirs();
            if (!cancel.isFile()) {
                Files.write(cancel.toPath(), new byte[0]);
            }
            Iterator<DemoCatalog.Title> it = titles.iterator();
            while (it.hasNext()) {
                DemoCatalog.Title o = it.next();
                if (o == null) {
                    continue;
                }
                if (id.equals(o.id) || id.equals(o.duplicateOf)) {
                    it.remove();
                }
            }
            removed[0] = t;
        });
        purgeJobFiles(mediaRoot, id);
        DemoCatalog.Title gone = removed[0];
        boolean inFlight = gone != null && (gone.status == DemoJobStatus.QUEUED
                || gone.status == DemoJobStatus.PACKAGING);
        if (!inFlight) {
            File cancel = DemoCatalog.cancelFile(mediaRoot, id);
            if (cancel.isFile()) {
                cancel.delete();
            }
        }
        return gone;
    }

    static void purgeJobFiles(File mediaRoot, String id) {
        File inbox = DemoCatalog.inboxDir(mediaRoot);
        File[] files = new File[] {
                new File(inbox, id + ".mp4"),
                new File(inbox, id + ".work.mp4"),
                new File(inbox, id + ".failed.mp4"),
                new File(new File(inbox, "done"), id + ".mp4"),
                new File(new File(inbox, "logs"), id + ".log"),
        };
        for (int i = 0; i < files.length; i++) {
            if (files[i].isFile()) {
                files[i].delete();
            }
        }
        deleteTree(new File(DemoCatalog.titlesDir(mediaRoot), id));
        File cancel = DemoCatalog.cancelFile(mediaRoot, id);
        if (cancel.isFile()) {
            // leave the flag until the packager has dropped the job; it deletes the flag
        }
    }

    static void deleteTree(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        File[] kids = dir.listFiles();
        if (kids != null) {
            for (int i = 0; i < kids.length; i++) {
                if (kids[i].isDirectory()) {
                    deleteTree(kids[i]);
                } else {
                    kids[i].delete();
                }
            }
        }
        dir.delete();
    }

    static final class ParsedUpload {
        String filename;
        String title;
        File tempFile;
    }

    static ParsedUpload parseRequest(InputStream in, String contentType, File inbox)
            throws IOException {
        String boundary = multipartBoundary(contentType);
        if (boundary == null) {
            throw new IllegalArgumentException("expected multipart/form-data");
        }
        File tmp = File.createTempFile("upload-", ".part", inbox);
        try {
            return parseMultipart(in, boundary, tmp);
        } catch (IOException e) {
            tmp.delete();
            throw e;
        } catch (RuntimeException e) {
            tmp.delete();
            throw e;
        }
    }

    static String multipartBoundary(String contentType) {
        if (contentType == null) {
            return null;
        }
        String lower = contentType.toLowerCase(Locale.US);
        if (!lower.startsWith("multipart/form-data")) {
            return null;
        }
        int idx = lower.indexOf("boundary=");
        if (idx < 0) {
            return null;
        }
        String raw = contentType.substring(idx + "boundary=".length()).trim();
        if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() >= 2) {
            raw = raw.substring(1, raw.length() - 1);
        }
        int semi = raw.indexOf(';');
        if (semi >= 0) {
            raw = raw.substring(0, semi).trim();
        }
        return raw.length() == 0 ? null : raw;
    }

    static ParsedUpload parseMultipart(InputStream in, String boundary, File fileDest)
            throws IOException {
        byte[] dashBoundary = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        byte[] partEnd = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        ByteWindow win = new ByteWindow(in);
        if (!win.skipUntil(dashBoundary)) {
            throw new IllegalArgumentException("invalid multipart body");
        }
        ParsedUpload out = new ParsedUpload();
        while (true) {
            byte[] marker = win.read(2);
            if (marker == null) {
                break;
            }
            if (marker.length == 2 && marker[0] == '-' && marker[1] == '-') {
                break;
            }
            if (!(marker.length == 2 && marker[0] == '\r' && marker[1] == '\n')) {
                win.unread(marker);
            }
            String headers = win.readHeaders();
            String name = headerAttr(headers, "name");
            String filename = headerAttr(headers, "filename");
            if (filename != null && filename.length() > 0) {
                out.filename = filename;
                win.copyUntil(partEnd, fileDest, MAX_BYTES);
                out.tempFile = fileDest;
            } else if ("title".equals(name)) {
                out.title = win.readStringUntil(partEnd);
            } else {
                win.discardUntil(partEnd);
            }
        }
        return out;
    }

    static String headerAttr(String headers, String attr) {
        if (headers == null) {
            return null;
        }
        String needle = attr + "=";
        String lower = headers.toLowerCase(Locale.US);
        int idx = lower.indexOf(needle);
        if (idx < 0) {
            return null;
        }
        int start = idx + needle.length();
        if (start < headers.length() && headers.charAt(start) == '"') {
            int end = headers.indexOf('"', start + 1);
            if (end < 0) {
                return headers.substring(start + 1).trim();
            }
            return headers.substring(start + 1, end);
        }
        int end = start;
        while (end < headers.length()) {
            char c = headers.charAt(end);
            if (c == ';' || c == '\r' || c == '\n' || c == ' ') {
                break;
            }
            end++;
        }
        return headers.substring(start, end);
    }

    /** Sliding window over an input stream so we can match multipart boundaries. */
    static final class ByteWindow {
        private final InputStream in;
        private final byte[] buf = new byte[8192];
        private int pos;
        private int end;
        private boolean eof;

        ByteWindow(InputStream in) {
            this.in = in;
        }

        private int ensure(int n) throws IOException {
            if (end - pos >= n || eof) {
                return end - pos;
            }
            compact();
            while (end - pos < n && !eof) {
                int r = in.read(buf, end, buf.length - end);
                if (r < 0) {
                    eof = true;
                    break;
                }
                end += r;
            }
            return end - pos;
        }

        private void compact() {
            if (pos == 0) {
                return;
            }
            int keep = end - pos;
            if (keep > 0) {
                System.arraycopy(buf, pos, buf, 0, keep);
            }
            end = keep;
            pos = 0;
        }

        void unread(byte[] bytes) {
            if (bytes == null || bytes.length == 0) {
                return;
            }
            if (pos >= bytes.length) {
                pos -= bytes.length;
                System.arraycopy(bytes, 0, buf, pos, bytes.length);
                return;
            }
            int need = bytes.length - pos;
            if (end + need > buf.length) {
                throw new IllegalStateException("unread overflow");
            }
            System.arraycopy(buf, pos, buf, pos + need, end - pos);
            end += need;
            System.arraycopy(bytes, 0, buf, 0, bytes.length);
            pos = 0;
        }

        byte[] read(int n) throws IOException {
            int have = ensure(n);
            if (have <= 0) {
                return null;
            }
            int take = Math.min(n, have);
            byte[] out = new byte[take];
            System.arraycopy(buf, pos, out, 0, take);
            pos += take;
            return out;
        }

        boolean skipUntil(byte[] needle) throws IOException {
            while (true) {
                int have = ensure(needle.length);
                if (have < needle.length) {
                    return false;
                }
                int idx = indexOf(buf, pos, end, needle);
                if (idx >= 0) {
                    pos = idx + needle.length;
                    return true;
                }
                pos = Math.max(pos, end - needle.length + 1);
            }
        }

        String readHeaders() throws IOException {
            byte[] endHeaders = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            while (true) {
                int have = ensure(endHeaders.length);
                if (have < endHeaders.length) {
                    throw new IOException("truncated multipart headers");
                }
                int idx = indexOf(buf, pos, end, endHeaders);
                if (idx >= 0) {
                    bos.write(buf, pos, idx - pos);
                    pos = idx + endHeaders.length;
                    return bos.toString("ISO-8859-1");
                }
                int emit = end - pos - endHeaders.length + 1;
                bos.write(buf, pos, emit);
                pos += emit;
            }
        }

        void copyUntil(byte[] needle, File dest, long maxBytes) throws IOException {
            try (OutputStream os = new FileOutputStream(dest)) {
                long written = 0;
                while (true) {
                    int have = ensure(needle.length);
                    if (have < needle.length) {
                        if (have > 0) {
                            written += have;
                            if (written > maxBytes) {
                                throw new IllegalArgumentException("file too large (max 200MB)");
                            }
                            os.write(buf, pos, have);
                            pos = end;
                        }
                        return;
                    }
                    int idx = indexOf(buf, pos, end, needle);
                    if (idx >= 0) {
                        int n = idx - pos;
                        written += n;
                        if (written > maxBytes) {
                            throw new IllegalArgumentException("file too large (max 200MB)");
                        }
                        os.write(buf, pos, n);
                        pos = idx + needle.length;
                        return;
                    }
                    int emit = end - pos - needle.length + 1;
                    written += emit;
                    if (written > maxBytes) {
                        throw new IllegalArgumentException("file too large (max 200MB)");
                    }
                    os.write(buf, pos, emit);
                    pos += emit;
                }
            }
        }

        String readStringUntil(byte[] needle) throws IOException {
            File tmp = File.createTempFile("field-", ".txt");
            try {
                copyUntil(needle, tmp, 16 * 1024);
                byte[] raw = Files.readAllBytes(tmp.toPath());
                return new String(raw, StandardCharsets.UTF_8).trim();
            } finally {
                tmp.delete();
            }
        }

        void discardUntil(byte[] needle) throws IOException {
            File tmp = File.createTempFile("skip-", ".bin");
            try {
                copyUntil(needle, tmp, MAX_BYTES);
            } finally {
                tmp.delete();
            }
        }
    }

    static int indexOf(byte[] hay, int from, int to, byte[] needle) {
        outer:
        for (int i = from; i <= to - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
