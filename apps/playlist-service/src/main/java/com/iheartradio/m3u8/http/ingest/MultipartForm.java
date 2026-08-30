package com.iheartradio.m3u8.http.ingest;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

/**
 * Multipart/form-data reader for a single MP4 upload plus optional title field.
 */
public final class MultipartForm {

    public static final long MAX_BYTES = 200L * 1024L * 1024L;

    private MultipartForm() {
    }

    public static final class ParsedUpload {
        public String filename;
        public String title;
        public File tempFile;
    }

    public static ParsedUpload parseRequest(InputStream in, String contentType, File inbox)
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

    public static String multipartBoundary(String contentType) {
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

    public static ParsedUpload parseMultipart(InputStream in, String boundary, File fileDest)
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

    public static String headerAttr(String headers, String attr) {
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
