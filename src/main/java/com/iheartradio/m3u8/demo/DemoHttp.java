package com.iheartradio.m3u8.demo;

import com.iheartradio.m3u8.PlaylistRewriteUtil;
import com.iheartradio.m3u8.PlaylistRewriteUtil.UriMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared HTTP / JSON / origin-fetch helpers for the demo player.
 * One copy of CORS, Range streaming, and the tiny JSON parser (no extra deps).
 */
public final class DemoHttp {

    public static final String ENGINE = "open-m3u8-demo";
    public static final String USER_AGENT = "open-m3u8-demo/1.0";

    private DemoHttp() {
    }

    public static final class HttpException extends Exception {
        public final int status;

        public HttpException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    public static final class FetchResult {
        public final int status;
        public final String contentType;
        public final byte[] body;
        public final String contentRange;
        public final String acceptRanges;

        public FetchResult(int status, String contentType, byte[] body) {
            this(status, contentType, body, null, null);
        }

        public FetchResult(int status, String contentType, byte[] body,
                           String contentRange, String acceptRanges) {
            this.status = status;
            this.contentType = contentType;
            this.body = body == null ? new byte[0] : body;
            this.contentRange = contentRange;
            this.acceptRanges = acceptRanges;
        }
    }

    public interface OriginFetch {
        FetchResult fetch(String url) throws IOException;
    }

    public static void requireHttpUrl(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            if (scheme == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException("only http/https URLs are allowed: " + url);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid url: " + url);
        }
    }

    public static FetchResult fetchRemote(String target, String rangeHeader) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(target).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(rangeHeader != null && rangeHeader.length() > 0 ? 30000 : 120000);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "*/*");
        if (rangeHeader != null && rangeHeader.trim().length() > 0) {
            conn.setRequestProperty("Range", rangeHeader.trim());
        }
        int status = conn.getResponseCode();
        String ct = conn.getContentType();
        String contentRange = conn.getHeaderField("Content-Range");
        String acceptRanges = conn.getHeaderField("Accept-Ranges");
        InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (in == null) {
            return new FetchResult(status, ct, new byte[0], contentRange, acceptRanges);
        }
        try {
            return new FetchResult(status, ct, readAll(in), contentRange, acceptRanges);
        } finally {
            in.close();
            conn.disconnect();
        }
    }

    public static void streamRemote(HttpExchange ex, String target, String rangeHeader)
            throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(target).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(120000);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "*/*");
        if (rangeHeader != null && rangeHeader.trim().length() > 0) {
            conn.setRequestProperty("Range", rangeHeader.trim());
        }

        int status;
        try {
            status = conn.getResponseCode();
        } catch (IOException e) {
            conn.disconnect();
            throw e;
        }

        String ct = conn.getContentType();
        String contentRange = conn.getHeaderField("Content-Range");
        String acceptRanges = conn.getHeaderField("Accept-Ranges");
        long contentLength = conn.getContentLengthLong();

        Headers outHeaders = ex.getResponseHeaders();
        applyCorsHeaders(outHeaders);
        outHeaders.set("Cache-Control", "no-store");
        outHeaders.set("X-Proxy-Target", target);
        outHeaders.set("X-Rewrite-Engine", ENGINE);
        outHeaders.set("Content-Type", ct != null ? ct : "application/octet-stream");
        if (acceptRanges != null && acceptRanges.length() > 0) {
            outHeaders.set("Accept-Ranges", acceptRanges);
        } else {
            outHeaders.set("Accept-Ranges", "bytes");
        }
        if (contentRange != null && contentRange.length() > 0) {
            outHeaders.set("Content-Range", contentRange);
        }

        InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (status >= 400 && in == null) {
            ex.sendResponseHeaders(status, -1);
            ex.close();
            conn.disconnect();
            return;
        }
        if (in == null) {
            ex.sendResponseHeaders(status > 0 ? status : 200, -1);
            ex.close();
            conn.disconnect();
            return;
        }

        try {
            long len = contentLength >= 0 ? contentLength : 0;
            ex.sendResponseHeaders(status > 0 ? status : 200, len);
            try (OutputStream os = ex.getResponseBody()) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    os.write(buf, 0, n);
                }
            }
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
            conn.disconnect();
        }
    }

    public static boolean urlLooksLikePlaylist(String url) {
        try {
            String path = new URI(url).getPath();
            if (path != null) {
                String lower = path.toLowerCase();
                if (lower.endsWith(".m3u8") || lower.endsWith(".m3u")) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        String lower = url.toLowerCase();
        return lower.contains(".m3u8") || lower.contains("mpegurl") || lower.contains("m3u8");
    }

    public static boolean looksLikePlaylist(String url, String contentType, byte[] body) {
        if (contentType != null) {
            String ct = contentType.toLowerCase();
            if (ct.contains("mpegurl") || ct.contains("m3u")) {
                return true;
            }
        }
        try {
            String path = new URI(url).getPath();
            if (path != null) {
                String lower = path.toLowerCase();
                if (lower.endsWith(".m3u8") || lower.endsWith(".m3u")) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        int n = Math.min(body.length, 64);
        if (n <= 0) {
            return false;
        }
        String head = new String(body, 0, n, StandardCharsets.UTF_8).trim().toLowerCase();
        return head.startsWith("#extm3u") || head.startsWith("#ext");
    }

    public static String firstHeader(Headers headers, String name) {
        if (headers == null) {
            return null;
        }
        List<String> values = headers.get(name);
        if (values == null || values.isEmpty()) {
            values = headers.get(name.toLowerCase());
        }
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(0);
    }

    public static String queryParam(String rawQuery, String name) {
        if (rawQuery == null) {
            return null;
        }
        String[] parts = rawQuery.split("&");
        for (String p : parts) {
            int eq = p.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String k = p.substring(0, eq);
            if (k.equals(name)) {
                return p.substring(eq + 1);
            }
        }
        return null;
    }

    public static String decode(String s) {
        if (s == null) {
            return null;
        }
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    public static String publicBase(HttpExchange ex, int port) {
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host == null || host.trim().isEmpty()) {
            host = "127.0.0.1:" + port;
        }
        return "http://" + host.trim();
    }

    public static void applyCorsHeaders(Headers h) {
        if (h == null) {
            return;
        }
        h.set("Access-Control-Allow-Origin", "*");
        h.set("Access-Control-Allow-Methods", "GET, HEAD, POST, OPTIONS");
        h.set("Access-Control-Allow-Headers",
                "Content-Type, Range, Accept, Origin, X-Requested-With, "
                        + "Access-Control-Request-Method, Access-Control-Request-Headers, "
                        + "Access-Control-Request-Private-Network");
        h.set("Access-Control-Expose-Headers",
                "Content-Length, Content-Range, Accept-Ranges, Content-Type, "
                        + "X-Playlist-Rewritten, X-Proxy-Target, X-Playlist-Kind, "
                        + "X-Rewrite-Engine, X-Rewrite-Strategy, X-Rewrite-Fallback");
        h.set("Access-Control-Allow-Private-Network", "true");
        h.set("Access-Control-Max-Age", "86400");
        h.set("Timing-Allow-Origin", "*");
        h.set("Cross-Origin-Resource-Policy", "cross-origin");
    }

    public static void send(HttpExchange ex, int code, String contentType, String body)
            throws IOException {
        sendCors(ex, code, body.getBytes(StandardCharsets.UTF_8), contentType);
    }

    public static void sendCors(HttpExchange ex, int code, byte[] body, String contentType)
            throws IOException {
        Headers h = ex.getResponseHeaders();
        applyCorsHeaders(h);
        h.set("Content-Type", contentType);
        h.set("Cache-Control", "no-store");
        if (body == null || body.length == 0) {
            ex.sendResponseHeaders(code, -1);
            ex.close();
            return;
        }
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    public static void writePlaylistResponse(HttpExchange ex, byte[] body, String target,
                                            String kind, String strategy) throws IOException {
        Headers outHeaders = ex.getResponseHeaders();
        applyCorsHeaders(outHeaders);
        outHeaders.set("Cache-Control", "no-store");
        outHeaders.set("Content-Type", "application/vnd.apple.mpegurl; charset=utf-8");
        outHeaders.set("X-Proxy-Target", target == null ? "" : target);
        outHeaders.set("X-Rewrite-Engine", ENGINE);
        outHeaders.set("X-Rewrite-Strategy", strategy == null ? "" : strategy);
        outHeaders.set("X-Playlist-Rewritten", "1");
        outHeaders.set("X-Playlist-Kind", kind);
        if ("HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(200, -1);
            ex.close();
            return;
        }
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    public static boolean serveStatic(HttpExchange ex, File staticRoot, String path)
            throws IOException {
        if (path == null || path.equals("/") || path.length() == 0) {
            path = "/index.html";
        }
        String rel = path.startsWith("/") ? path.substring(1) : path;
        if (rel.contains("..")) {
            send(ex, 400, "text/plain", "bad path");
            return true;
        }
        File file = new File(staticRoot, rel);
        if (!file.getCanonicalPath().startsWith(staticRoot.getCanonicalPath())) {
            send(ex, 400, "text/plain", "bad path");
            return true;
        }
        if (!file.isFile()) {
            return false;
        }
        byte[] body = readFile(file);
        Headers h = ex.getResponseHeaders();
        applyCorsHeaders(h);
        h.set("Content-Type", contentTypeFor(file.getName()));
        h.set("Cache-Control", "no-store");
        if ("HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(200, -1);
            ex.close();
            return true;
        }
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
        return true;
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    public static byte[] readFile(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        }
    }

    public static String contentTypeFor(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html; charset=utf-8";
        if (lower.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".m3u8")) return "application/vnd.apple.mpegurl";
        return "application/octet-stream";
    }

    public static String rewritePlaylistUrisText(String text, String playlistUrl, UriMapper mapper) {
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length() + 256);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            String trimmed = line.trim();
            if (trimmed.length() == 0) {
                out.append(line);
            } else if (trimmed.startsWith("#")) {
                out.append(rewriteAttrUrisInTag(line, playlistUrl, mapper));
            } else {
                out.append(PlaylistRewriteUtil.mapRef(playlistUrl, trimmed, mapper));
            }
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static String rewriteAttrUrisInTag(String line, String playlistUrl, UriMapper mapper) {
        String[] keys = new String[] { "URI=", "X-ASSET-URI=", "X-ASSET-LIST=" };
        String result = line;
        for (String key : keys) {
            int from = 0;
            StringBuilder sb = new StringBuilder();
            int idx;
            String upper = result.toUpperCase();
            String keyUpper = key.toUpperCase();
            while ((idx = upper.indexOf(keyUpper, from)) >= 0) {
                sb.append(result, from, idx + key.length());
                int q = idx + key.length();
                if (q < result.length() && result.charAt(q) == '"') {
                    int end = result.indexOf('"', q + 1);
                    if (end > q) {
                        String val = result.substring(q + 1, end);
                        String mapped = PlaylistRewriteUtil.mapRef(playlistUrl, val, mapper);
                        sb.append('"').append(mapped).append('"');
                        from = end + 1;
                        continue;
                    }
                }
                int end = result.indexOf(',', q);
                if (end < 0) {
                    end = result.length();
                }
                String val = result.substring(q, end).trim();
                String mapped = PlaylistRewriteUtil.mapRef(playlistUrl, val, mapper);
                sb.append(mapped);
                from = end;
            }
            sb.append(result.substring(from));
            result = sb.toString();
            upper = result.toUpperCase();
        }
        return result;
    }

    public static String jsonString(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    public static String jsonStringValue(String json, String key) {
        int idx = indexOfKey(json, key);
        if (idx < 0) {
            return null;
        }
        int colon = json.indexOf(':', idx);
        if (colon < 0) {
            return null;
        }
        int i = skipWs(json, colon + 1);
        if (i >= json.length() || json.charAt(i) != '"') {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        boolean esc = false;
        for (int p = i + 1; p < json.length(); p++) {
            char c = json.charAt(p);
            if (esc) {
                switch (c) {
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case '"':
                        sb.append('"');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    default:
                        sb.append(c);
                }
                esc = false;
            } else if (c == '\\') {
                esc = true;
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return null;
    }

    public static String jsonObject(String json, String key) {
        int idx = indexOfKey(json, key);
        if (idx < 0) {
            return null;
        }
        int colon = json.indexOf(':', idx);
        if (colon < 0) {
            return null;
        }
        int i = skipWs(json, colon + 1);
        if (i >= json.length() || json.charAt(i) != '{') {
            return null;
        }
        return extractBalanced(json, i, '{', '}');
    }

    public static String jsonArray(String json, String key) {
        int idx = indexOfKey(json, key);
        if (idx < 0) {
            return null;
        }
        int colon = json.indexOf(':', idx);
        if (colon < 0) {
            return null;
        }
        int i = skipWs(json, colon + 1);
        if (i >= json.length() || json.charAt(i) != '[') {
            return null;
        }
        return extractBalanced(json, i, '[', ']');
    }

    public static boolean jsonBool(String json, String key, boolean def) {
        int idx = indexOfKey(json, key);
        if (idx < 0) {
            return def;
        }
        int colon = json.indexOf(':', idx);
        if (colon < 0) {
            return def;
        }
        int i = skipWs(json, colon + 1);
        if (json.regionMatches(true, i, "true", 0, 4)) {
            return true;
        }
        if (json.regionMatches(true, i, "false", 0, 5)) {
            return false;
        }
        return def;
    }

    public static double jsonNumber(String json, String key, double def) {
        int idx = indexOfKey(json, key);
        if (idx < 0) {
            return def;
        }
        int colon = json.indexOf(':', idx);
        if (colon < 0) {
            return def;
        }
        int i = skipWs(json, colon + 1);
        int j = i;
        if (j < json.length() && (json.charAt(j) == '-' || json.charAt(j) == '+')) {
            j++;
        }
        while (j < json.length()) {
            char c = json.charAt(j);
            if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                j++;
            } else {
                break;
            }
        }
        if (j == i) {
            return def;
        }
        try {
            return Double.parseDouble(json.substring(i, j));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static List<String> splitJsonObjects(String arrayLiteral) {
        List<String> out = new ArrayList<String>();
        if (arrayLiteral == null || arrayLiteral.length() < 2) {
            return out;
        }
        String inner = arrayLiteral.substring(1, arrayLiteral.length() - 1).trim();
        if (inner.length() == 0) {
            return out;
        }
        int i = 0;
        while (i < inner.length()) {
            i = skipWs(inner, i);
            if (i >= inner.length()) {
                break;
            }
            if (inner.charAt(i) == ',') {
                i++;
                continue;
            }
            if (inner.charAt(i) == '{') {
                String obj = extractBalanced(inner, i, '{', '}');
                if (obj == null) {
                    break;
                }
                out.add(obj);
                i += obj.length();
            } else {
                break;
            }
        }
        return out;
    }

    public static int indexOfKey(String json, String key) {
        return json.indexOf("\"" + key + "\"");
    }

    public static int skipWs(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    public static String extractBalanced(String s, int start, char open, char close) {
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (esc) {
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return s.substring(start, i + 1);
                }
            }
        }
        return null;
    }
}
