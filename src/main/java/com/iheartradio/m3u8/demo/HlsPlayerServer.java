package com.iheartradio.m3u8.demo;

import com.iheartradio.m3u8.Encoding;
import com.iheartradio.m3u8.ParseException;
import com.iheartradio.m3u8.PlaylistException;
import com.iheartradio.m3u8.PlaylistRewriteUtil;
import com.iheartradio.m3u8.PlaylistRewriteUtil.InjectConfig;
import com.iheartradio.m3u8.PlaylistRewriteUtil.InterstitialBreak;
import com.iheartradio.m3u8.PlaylistRewriteUtil.UriMapper;
import com.iheartradio.m3u8.data.Playlist;
import com.iheartradio.m3u8.data.StartData;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Local HLS demo player + rewrite proxy.
 *
 * <p>All playlist parse / inject / write is done via the open-m3u8 library
 * ({@link PlaylistRewriteUtil}), not string builders.
 *
 * <pre>
 *   ./gradlew runHlsPlayer
 *   open http://127.0.0.1:8765/
 * </pre>
 */
public final class HlsPlayerServer {

    private static final int DEFAULT_PORT = 8765;
    private static final String HOST = "127.0.0.1";

    private final int port;
    private final File staticRoot;
    private final AtomicReference<InjectConfig> configRef =
            new AtomicReference<InjectConfig>(InjectConfig.builder().build());

    public HlsPlayerServer(int port, File staticRoot) {
        this.port = port;
        this.staticRoot = staticRoot;
    }

    public static void main(String[] args) throws Exception {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }
        File root = locateStaticRoot();
        HlsPlayerServer server = new HlsPlayerServer(port, root);
        server.start();
    }

    private static File locateStaticRoot() {
        // Prefer project-relative hls-player/ (working dir when using gradle runHlsPlayer)
        File[] candidates = new File[] {
                new File("hls-player"),
                new File(".", "hls-player"),
                new File(System.getProperty("user.dir"), "hls-player")
        };
        for (File f : candidates) {
            if (f.isDirectory() && new File(f, "index.html").isFile()) {
                return f.getAbsoluteFile();
            }
        }
        return new File("hls-player").getAbsoluteFile();
    }

    public void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(HOST, port), 0);
        server.createContext("/", new RootHandler());
        server.createContext("/api/health", new HealthHandler());
        server.createContext("/api/rewrite-config", new ConfigHandler());
        server.createContext("/proxy", new ProxyHandler());
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("HLS player (open-m3u8 rewrite proxy)");
        System.out.println("  UI:     http://" + HOST + ":" + port + "/");
        System.out.println("  Proxy:  http://" + HOST + ":" + port + "/proxy?url=<encoded-m3u8>");
        System.out.println("  Config: POST/GET http://" + HOST + ":" + port + "/api/rewrite-config");
        System.out.println("  Static: " + staticRoot.getAbsolutePath());
        System.out.println("  Engine: open-m3u8 PlaylistParser / PlaylistWriter / PlaylistRewriteUtil");
    }

    // ---------------- handlers ----------------

    private final class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                send(ex, 405, "text/plain", "method not allowed");
                return;
            }
            String body = "{\"ok\":true,\"proxy\":true,\"engine\":\"open-m3u8\",\"port\":" + port + "}";
            send(ex, 200, "application/json; charset=utf-8", body);
        }
    }

    private final class ConfigHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String method = ex.getRequestMethod();
            if ("OPTIONS".equalsIgnoreCase(method)) {
                sendCors(ex, 204, new byte[0], "application/json");
                return;
            }
            if ("GET".equalsIgnoreCase(method)) {
                send(ex, 200, "application/json; charset=utf-8", configToJson(configRef.get()));
                return;
            }
            if ("POST".equalsIgnoreCase(method)) {
                byte[] raw = readAll(ex.getRequestBody());
                String json = new String(raw, StandardCharsets.UTF_8);
                try {
                    InjectConfig cfg = parseConfigJson(json);
                    configRef.set(cfg);
                    String resp = "{\"ok\":true,\"config\":" + configToJson(cfg) + "}";
                    send(ex, 200, "application/json; charset=utf-8", resp);
                } catch (Exception e) {
                    send(ex, 400, "application/json; charset=utf-8",
                            "{\"error\":" + jsonString(e.getMessage()) + "}");
                }
                return;
            }
            send(ex, 405, "text/plain", "method not allowed");
        }
    }

    private final class ProxyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                sendCors(ex, 204, new byte[0], "application/octet-stream");
                return;
            }
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                send(ex, 405, "text/plain", "method not allowed");
                return;
            }

            String query = ex.getRequestURI().getRawQuery();
            String target = queryParam(query, "url");
            if (target == null || target.length() == 0) {
                send(ex, 400, "application/json; charset=utf-8",
                        "{\"error\":\"missing url query parameter\"}");
                return;
            }
            try {
                target = java.net.URLDecoder.decode(target, "UTF-8");
            } catch (Exception ignored) {
                // keep as-is
            }

            URI uri;
            try {
                uri = new URI(target);
            } catch (Exception e) {
                send(ex, 400, "application/json; charset=utf-8",
                        "{\"error\":\"invalid url\"}");
                return;
            }
            String scheme = uri.getScheme();
            if (scheme == null ||
                    !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                send(ex, 400, "application/json; charset=utf-8",
                        "{\"error\":\"only http/https remote URLs are allowed\"}");
                return;
            }

            FetchResult remote;
            try {
                remote = fetchRemote(target);
            } catch (Exception e) {
                send(ex, 502, "application/json; charset=utf-8",
                        "{\"error\":" + jsonString("fetch failed: " + e.getMessage()) +
                                ",\"url\":" + jsonString(target) + "}");
                return;
            }

            Headers outHeaders = ex.getResponseHeaders();
            outHeaders.set("Access-Control-Allow-Origin", "*");
            outHeaders.set("Access-Control-Expose-Headers",
                    "X-Playlist-Rewritten, X-Proxy-Target, X-Playlist-Kind, X-Rewrite-Engine");
            outHeaders.set("Cache-Control", "no-store");
            outHeaders.set("X-Proxy-Target", target);
            outHeaders.set("X-Rewrite-Engine", "open-m3u8");

            if (remote.status >= 400) {
                outHeaders.set("Content-Type", remote.contentType != null
                        ? remote.contentType : "application/octet-stream");
                ex.sendResponseHeaders(remote.status, remote.body.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(remote.body);
                }
                return;
            }

            if (looksLikePlaylist(target, remote.contentType, remote.body)) {
                try {
                    Playlist playlist = PlaylistRewriteUtil.parse(remote.body, Encoding.UTF_8);
                    final String proxyBase = "http://" + HOST + ":" + port;
                    UriMapper mapper = new UriMapper() {
                        @Override
                        public String map(String absoluteUri) {
                            return PlaylistRewriteUtil.toProxyUrl(proxyBase, absoluteUri);
                        }
                    };
                    Playlist rewritten = PlaylistRewriteUtil.rewrite(
                            playlist, target, configRef.get(), mapper);
                    byte[] out = PlaylistRewriteUtil.write(rewritten, Encoding.UTF_8);

                    String kind = rewritten.hasMasterPlaylist() ? "master"
                            : rewritten.hasMediaPlaylist() ? "media" : "playlist";
                    outHeaders.set("Content-Type", "application/vnd.apple.mpegurl; charset=utf-8");
                    outHeaders.set("X-Playlist-Rewritten", "1");
                    outHeaders.set("X-Playlist-Kind", kind);
                    ex.sendResponseHeaders(200, out.length);
                    try (OutputStream os = ex.getResponseBody()) {
                        os.write(out);
                    }
                    return;
                } catch (ParseException | PlaylistException e) {
                    // Fall through: serve original body if parse fails
                    System.err.println("open-m3u8 parse/rewrite failed for " + target + ": " + e);
                } catch (Exception e) {
                    System.err.println("rewrite error for " + target + ": " + e);
                }
            }

            outHeaders.set("Content-Type", remote.contentType != null
                    ? remote.contentType : "application/octet-stream");
            ex.sendResponseHeaders(remote.status > 0 ? remote.status : 200, remote.body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(remote.body);
            }
        }
    }

    private final class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                sendCors(ex, 204, new byte[0], "text/plain");
                return;
            }
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())
                    && !"HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
                send(ex, 405, "text/plain", "method not allowed");
                return;
            }

            String path = ex.getRequestURI().getPath();
            if (path == null || path.equals("/") || path.length() == 0) {
                path = "/index.html";
            }
            // Prevent path traversal
            String rel = path.startsWith("/") ? path.substring(1) : path;
            if (rel.contains("..")) {
                send(ex, 400, "text/plain", "bad path");
                return;
            }
            File file = new File(staticRoot, rel);
            if (!file.getCanonicalPath().startsWith(staticRoot.getCanonicalPath())) {
                send(ex, 400, "text/plain", "bad path");
                return;
            }
            if (!file.isFile()) {
                send(ex, 404, "text/plain", "not found");
                return;
            }
            byte[] body = readFile(file);
            String ct = contentTypeFor(file.getName());
            Headers h = ex.getResponseHeaders();
            h.set("Content-Type", ct);
            h.set("Cache-Control", "no-store");
            h.set("Access-Control-Allow-Origin", "*");
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
    }

    // ---------------- config JSON (minimal hand-rolled) ----------------

    private static InjectConfig parseConfigJson(String json) {
        // Expected shape (from the player UI):
        // {
        //   "start": { "override": true, "timeOffset": 10.5, "precise": false },
        //   "ads": [ { "id":"user-ad-1", "offsetSec":30, "durationSec":15, "assetUri":"..." } ],
        //   "snapSegment": true,
        //   "resumeOffset": 0,
        //   "restrictSkip": true,
        //   "snap": "IN"
        // }
        StartData start = null;
        String startObj = jsonObject(json, "start");
        if (startObj != null) {
            boolean override = jsonBool(startObj, "override", false);
            if (override) {
                float to = (float) jsonNumber(startObj, "timeOffset", 0);
                boolean precise = jsonBool(startObj, "precise", false);
                start = new StartData(to, precise);
            }
        }

        List<InterstitialBreak> breaks = new ArrayList<InterstitialBreak>();
        String adsArr = jsonArray(json, "ads");
        if (adsArr != null) {
            List<String> items = splitJsonObjects(adsArr);
            int i = 0;
            for (String item : items) {
                i++;
                String uri = jsonStringValue(item, "assetUri");
                if (uri == null || uri.length() == 0) {
                    uri = jsonStringValue(item, "url");
                }
                if (uri == null || uri.length() == 0) {
                    continue;
                }
                String id = jsonStringValue(item, "id");
                if (id == null || id.length() == 0) {
                    id = PlaylistRewriteUtil.USER_AD_ID_PREFIX + i;
                }
                float off = (float) jsonNumber(item, "offsetSec", 0);
                float dur = (float) jsonNumber(item, "durationSec", 15);
                breaks.add(new InterstitialBreak(id, off, dur, uri));
            }
        }

        boolean snapSeg = jsonBool(json, "snapSegment", true);
        Float resume = (float) jsonNumber(json, "resumeOffset", 0);
        String restrict = null;
        if (jsonBool(json, "restrictSkip", false)) {
            restrict = "SKIP";
        }
        String snapAttr = jsonStringValue(json, "snap");

        return InjectConfig.builder()
                .withStartOverride(start)
                .withBreaks(breaks)
                .withSnapToSegment(snapSeg)
                .withDefaultResumeOffset(resume)
                .withDefaultRestrict(restrict)
                .withDefaultSnap(snapAttr)
                .build();
    }

    private static String configToJson(InjectConfig cfg) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"start\":{");
        if (cfg.startOverride != null) {
            sb.append("\"override\":true,\"timeOffset\":")
                    .append(cfg.startOverride.getTimeOffset())
                    .append(",\"precise\":")
                    .append(cfg.startOverride.isPrecise());
        } else {
            sb.append("\"override\":false,\"timeOffset\":0,\"precise\":false");
        }
        sb.append("},\"ads\":[");
        for (int i = 0; i < cfg.breaks.size(); i++) {
            InterstitialBreak b = cfg.breaks.get(i);
            if (i > 0) sb.append(',');
            sb.append('{')
                    .append("\"id\":").append(jsonString(b.id)).append(',')
                    .append("\"offsetSec\":").append(b.offsetSec).append(',')
                    .append("\"durationSec\":").append(b.durationSec).append(',')
                    .append("\"assetUri\":").append(jsonString(b.assetUri))
                    .append('}');
        }
        sb.append("],\"snapSegment\":").append(cfg.snapToSegment);
        if (cfg.defaultResumeOffset != null) {
            sb.append(",\"resumeOffset\":").append(cfg.defaultResumeOffset);
        }
        sb.append(",\"restrictSkip\":")
                .append(cfg.defaultRestrict != null && cfg.defaultRestrict.contains("SKIP"));
        if (cfg.defaultSnap != null) {
            sb.append(",\"snap\":").append(jsonString(cfg.defaultSnap));
        }
        sb.append('}');
        return sb.toString();
    }

    // ---------------- tiny JSON helpers (no external deps) ----------------

    private static String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
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

    private static String jsonObject(String json, String key) {
        int idx = indexOfKey(json, key);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return null;
        int i = skipWs(json, colon + 1);
        if (i >= json.length() || json.charAt(i) != '{') return null;
        return extractBalanced(json, i, '{', '}');
    }

    private static String jsonArray(String json, String key) {
        int idx = indexOfKey(json, key);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return null;
        int i = skipWs(json, colon + 1);
        if (i >= json.length() || json.charAt(i) != '[') return null;
        return extractBalanced(json, i, '[', ']');
    }

    private static int indexOfKey(String json, String key) {
        String needle = "\"" + key + "\"";
        return json.indexOf(needle);
    }

    private static int skipWs(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    private static String extractBalanced(String s, int start, char open, char close) {
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

    private static List<String> splitJsonObjects(String arrayLiteral) {
        List<String> out = new ArrayList<String>();
        if (arrayLiteral == null || arrayLiteral.length() < 2) return out;
        String inner = arrayLiteral.substring(1, arrayLiteral.length() - 1).trim();
        if (inner.length() == 0) return out;
        int i = 0;
        while (i < inner.length()) {
            i = skipWs(inner, i);
            if (i >= inner.length()) break;
            if (inner.charAt(i) == ',') {
                i++;
                continue;
            }
            if (inner.charAt(i) == '{') {
                String obj = extractBalanced(inner, i, '{', '}');
                if (obj == null) break;
                out.add(obj);
                i += obj.length();
            } else {
                break;
            }
        }
        return out;
    }

    private static boolean jsonBool(String json, String key, boolean def) {
        int idx = indexOfKey(json, key);
        if (idx < 0) return def;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return def;
        int i = skipWs(json, colon + 1);
        if (json.regionMatches(true, i, "true", 0, 4)) return true;
        if (json.regionMatches(true, i, "false", 0, 5)) return false;
        return def;
    }

    private static double jsonNumber(String json, String key, double def) {
        int idx = indexOfKey(json, key);
        if (idx < 0) return def;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return def;
        int i = skipWs(json, colon + 1);
        int j = i;
        if (j < json.length() && (json.charAt(j) == '-' || json.charAt(j) == '+')) j++;
        while (j < json.length()) {
            char c = json.charAt(j);
            if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                j++;
            } else break;
        }
        if (j == i) return def;
        try {
            return Double.parseDouble(json.substring(i, j));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String jsonStringValue(String json, String key) {
        int idx = indexOfKey(json, key);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return null;
        int i = skipWs(json, colon + 1);
        if (i >= json.length() || json.charAt(i) != '"') return null;
        StringBuilder sb = new StringBuilder();
        boolean esc = false;
        for (int p = i + 1; p < json.length(); p++) {
            char c = json.charAt(p);
            if (esc) {
                switch (c) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case 'u':
                        if (p + 4 < json.length()) {
                            try {
                                sb.append((char) Integer.parseInt(json.substring(p + 1, p + 5), 16));
                                p += 4;
                            } catch (Exception e) {
                                sb.append('u');
                            }
                        }
                        break;
                    default: sb.append(c);
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

    // ---------------- HTTP helpers ----------------

    private static final class FetchResult {
        final int status;
        final String contentType;
        final byte[] body;

        FetchResult(int status, String contentType, byte[] body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }
    }

    private static FetchResult fetchRemote(String target) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(target).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "open-m3u8-hls-player/1.0");
        conn.setRequestProperty("Accept", "*/*");
        int status = conn.getResponseCode();
        String ct = conn.getContentType();
        InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (in == null) {
            return new FetchResult(status, ct, new byte[0]);
        }
        try {
            return new FetchResult(status, ct, readAll(in));
        } finally {
            in.close();
            conn.disconnect();
        }
    }

    private static boolean looksLikePlaylist(String url, String contentType, byte[] body) {
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
        String head = new String(body, 0, n, StandardCharsets.UTF_8).trim().toLowerCase();
        return head.startsWith("#extm3u") || head.startsWith("#ext");
    }

    private static String queryParam(String rawQuery, String name) {
        if (rawQuery == null) return null;
        String[] parts = rawQuery.split("&");
        for (String p : parts) {
            int eq = p.indexOf('=');
            if (eq < 0) continue;
            String k = p.substring(0, eq);
            if (k.equals(name)) {
                return p.substring(eq + 1);
            }
        }
        return null;
    }

    private static void send(HttpExchange ex, int code, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        sendCors(ex, code, bytes, contentType);
    }

    private static void sendCors(HttpExchange ex, int code, byte[] body, String contentType) throws IOException {
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", contentType);
        h.set("Access-Control-Allow-Origin", "*");
        h.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        h.set("Access-Control-Allow-Headers", "Content-Type");
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

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static byte[] readFile(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        }
    }

    private static String contentTypeFor(String name) {
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
}
