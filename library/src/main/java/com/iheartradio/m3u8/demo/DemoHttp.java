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
        applyCorsHeaders(ex);
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

    private static final java.util.concurrent.atomic.AtomicReference<String> LAST_HTTPS_BASE =
            new java.util.concurrent.atomic.AtomicReference<String>();
    private static final java.util.concurrent.atomic.AtomicBoolean DISCOVERY_STARTED =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final java.util.concurrent.atomic.AtomicInteger CACHED_METRICS_PORT =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private static final Object SCAN_LOCK = new Object();

    public static String envPublicBase() {
        String e = System.getenv("DEMO_PUBLIC_BASE");
        if (e == null) {
            return null;
        }
        e = e.trim();
        if (e.endsWith("/")) {
            e = e.substring(0, e.length() - 1);
        }
        return e.length() == 0 ? null : e;
    }

    /** Cached HTTPS origin. Never scans; discovery runs on a background thread. */
    public static String advertisedPublicBase() {
        String env = envPublicBase();
        return env != null ? env : LAST_HTTPS_BASE.get();
    }

    public static void startPublicBaseDiscovery() {
        if (!DISCOVERY_STARTED.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        String found = scanCloudflareQuickTunnel();
                        if (found != null) {
                            String prev = LAST_HTTPS_BASE.getAndSet(found);
                            if (prev == null || !prev.equals(found)) {
                                System.out.println("  HTTPS:    " + found);
                            }
                        }
                        Thread.sleep(LAST_HTTPS_BASE.get() == null ? 4000L : 30000L);
                    } catch (InterruptedException e) {
                        return;
                    } catch (Exception ignored) {
                        try {
                            Thread.sleep(8000L);
                        } catch (InterruptedException ie) {
                            return;
                        }
                    }
                }
            }
        }, "demo-cf-discover");
        t.setDaemon(true);
        t.start();
    }

    /**
     * cloudflared quick tunnels expose GET /quicktunnel on their local metrics port:
     * {"hostname":"….trycloudflare.com"}
     */
    static String httpsOriginFromQuickTunnelJson(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        String host = jsonStringValue(json, "hostname");
        if (host == null) {
            return null;
        }
        host = host.trim();
        if (host.regionMatches(true, 0, "https://", 0, 8)
                || host.regionMatches(true, 0, "http://", 0, 7)) {
            try {
                URI uri = new URI(host);
                host = uri.getHost();
            } catch (Exception e) {
                return null;
            }
        }
        if (host == null || host.isEmpty()) {
            return null;
        }
        host = host.trim().toLowerCase();
        if (host.indexOf("127.0.0.1") >= 0 || "localhost".equals(host) || host.indexOf(':') >= 0) {
            return null;
        }
        if (host.indexOf('.') < 0 || host.indexOf(' ') >= 0) {
            return null;
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '.' && c != '-') {
                return null;
            }
        }
        return "https://" + host;
    }

    static String scanCloudflareQuickTunnel() {
        synchronized (SCAN_LOCK) {
            java.util.LinkedHashSet<Integer> ports = new java.util.LinkedHashSet<Integer>();
            int cached = CACHED_METRICS_PORT.get();
            if (cached > 0) {
                ports.add(Integer.valueOf(cached));
            }
            ports.add(20241);
            ports.add(20242);
            ports.add(9090);
            if (cached <= 0) {
                ports.addAll(localListenPorts());
            }
            int probed = 0;
            for (Integer port : ports) {
                if (port == null || port.intValue() <= 1024 || port.intValue() > 65535) {
                    continue;
                }
                if (port.intValue() == 8765) {
                    continue;
                }
                if (probed >= 16) {
                    break;
                }
                probed++;
                String found = probeQuickTunnel(port.intValue());
                if (found != null) {
                    CACHED_METRICS_PORT.set(port.intValue());
                    return found;
                }
            }
            return null;
        }
    }

    private static String probeQuickTunnel(int port) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/quicktunnel")
                    .openConnection();
            conn.setConnectTimeout(150);
            conn.setReadTimeout(250);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("Accept", "application/json, */*");
            if (conn.getResponseCode() != 200) {
                return null;
            }
            String body = new String(readAll(conn.getInputStream()), StandardCharsets.UTF_8);
            return httpsOriginFromQuickTunnelJson(body);
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static java.util.List<Integer> localListenPorts() {
        java.util.ArrayList<Integer> out = new java.util.ArrayList<Integer>();
        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("netstat", "-ano", "-p", "tcp");
            pb.redirectErrorStream(true);
            proc = pb.start();
            byte[] raw = readAll(proc.getInputStream());
            proc.waitFor();
            String text = new String(raw, StandardCharsets.US_ASCII);
            String[] lines = text.split("\n");
            for (String line : lines) {
                String u = line.toUpperCase();
                if (u.indexOf("LISTENING") < 0 && u.indexOf("LISTEN") < 0) {
                    continue;
                }
                int idx = line.indexOf("127.0.0.1:");
                if (idx < 0) {
                    idx = line.indexOf("[::1]:");
                    if (idx < 0) {
                        continue;
                    }
                    idx += 6;
                } else {
                    idx += 10;
                }
                int end = idx;
                while (end < line.length() && line.charAt(end) >= '0' && line.charAt(end) <= '9') {
                    end++;
                }
                if (end == idx) {
                    continue;
                }
                try {
                    out.add(Integer.valueOf(line.substring(idx, end)));
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
        } catch (Exception ignored) {
            // Windows netstat is best-effort; hardcoded ports still run.
        } finally {
            if (proc != null) {
                proc.destroy();
            }
        }
        return out;
    }

    public static String publicBase(HttpExchange ex, int port) {
        String proto = firstHeader(ex.getRequestHeaders(), "X-Forwarded-Proto");
        if (proto == null || proto.trim().isEmpty()) {
            proto = "http";
        } else {
            proto = proto.trim().split(",")[0].trim().toLowerCase();
            if (!"https".equals(proto)) {
                proto = "http";
            }
        }
        String host = firstHeader(ex.getRequestHeaders(), "X-Forwarded-Host");
        if (host == null || host.trim().isEmpty()) {
            host = firstHeader(ex.getRequestHeaders(), "Host");
        }
        if (host == null || host.trim().isEmpty()) {
            host = "127.0.0.1:" + port;
        } else {
            host = host.trim().split(",")[0].trim();
        }
        String base = proto + "://" + host;
        if ("https".equals(proto) && host.indexOf("127.0.0.1") < 0 && host.indexOf("localhost") < 0) {
            LAST_HTTPS_BASE.set(base);
        }
        return base;
    }

    public static void applyCorsHeaders(HttpExchange ex) {
        if (ex == null) {
            return;
        }
        applyCorsHeaders(ex.getResponseHeaders(), originOf(ex));
    }

    public static void applyCorsHeaders(Headers h) {
        applyCorsHeaders(h, null);
    }

    /**
     * Echo a specific Origin when present. Chrome Private Network Access / local-network
     * preflights (public https page → http://127.0.0.1) reject Allow-Origin: *.
     */
    public static void applyCorsHeaders(Headers h, String origin) {
        if (h == null) {
            return;
        }
        if (isSafeHttpOrigin(origin)) {
            h.set("Access-Control-Allow-Origin", origin);
            h.set("Vary", "Origin");
        } else {
            h.set("Access-Control-Allow-Origin", "*");
        }
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

    static String originOf(HttpExchange ex) {
        if (ex == null || ex.getRequestHeaders() == null) {
            return null;
        }
        return ex.getRequestHeaders().getFirst("Origin");
    }

    static boolean isSafeHttpOrigin(String origin) {
        if (origin == null) {
            return false;
        }
        String o = origin.trim();
        if (o.length() == 0 || o.indexOf('\r') >= 0 || o.indexOf('\n') >= 0) {
            return false;
        }
        return o.startsWith("http://") || o.startsWith("https://");
    }

    public static void send(HttpExchange ex, int code, String contentType, String body)
            throws IOException {
        sendCors(ex, code, body.getBytes(StandardCharsets.UTF_8), contentType);
    }

    public static void sendCors(HttpExchange ex, int code, byte[] body, String contentType)
            throws IOException {
        Headers h = ex.getResponseHeaders();
        applyCorsHeaders(ex);
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
        applyCorsHeaders(ex);
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
        applyCorsHeaders(ex);
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
