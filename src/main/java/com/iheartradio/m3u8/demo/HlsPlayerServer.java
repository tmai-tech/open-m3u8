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
    /** Bind all interfaces so Windows browsers can reach WSL2 (not only 127.0.0.1). */
    private static final String BIND_HOST = "0.0.0.0";

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
        // 0.0.0.0: reachable from Windows host via localhost forwarding and via WSL eth0 IP
        HttpServer server = HttpServer.create(new InetSocketAddress(BIND_HOST, port), 0);
        server.createContext("/", new RootHandler());
        server.createContext("/api/health", new HealthHandler());
        server.createContext("/api/rewrite-config", new ConfigHandler());
        server.createContext("/proxy", new ProxyHandler());
        // Bounded pool — unlimited cached threads + full-buffer segments caused OOM under ABR
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();
        System.out.println("HLS player (open-m3u8 rewrite proxy)");
        System.out.println("  Bind:   " + BIND_HOST + ":" + port);
        System.out.println("  UI:     http://127.0.0.1:" + port + "/");
        System.out.println("  Proxy:  http://127.0.0.1:" + port + "/proxy?url=<encoded-m3u8>");
        System.out.println("  Config: POST/GET http://127.0.0.1:" + port + "/api/rewrite-config");
        System.out.println("  Static: " + staticRoot.getAbsolutePath());
        System.out.println("  Engine: open-m3u8 PlaylistParser / PlaylistWriter / PlaylistRewriteUtil");
        System.out.println("  Tip:    If the Windows browser times out on 127.0.0.1, try the WSL IP on port " + port);
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

            // Forward Range so fMP4 / BYTERANGE ad + content segments work through the proxy
            // (hls.js issues Range requests for EXT-X-BYTERANGE / EXT-X-MAP).
            String rangeHeader = firstHeader(ex.getRequestHeaders(), "Range");

            // Stream media segments (do not buffer multi‑MB bodies — that caused OOM under ABR).
            // Only buffer full body for playlists that need rewrite.
            boolean likelyPlaylist = urlLooksLikePlaylist(target)
                    && (rangeHeader == null || rangeHeader.trim().isEmpty());

            if (!likelyPlaylist) {
                try {
                    streamRemote(ex, target, rangeHeader);
                } catch (Exception e) {
                    // If client already got headers this may fail silently
                    try {
                        send(ex, 502, "application/json; charset=utf-8",
                                "{\"error\":" + jsonString("fetch failed: " + e.getMessage()) +
                                        ",\"url\":" + jsonString(target) + "}");
                    } catch (Exception ignored) {
                        // response may already be committed
                    }
                }
                return;
            }

            FetchResult remote;
            try {
                remote = fetchRemote(target, rangeHeader);
            } catch (Exception e) {
                send(ex, 502, "application/json; charset=utf-8",
                        "{\"error\":" + jsonString("fetch failed: " + e.getMessage()) +
                                ",\"url\":" + jsonString(target) + "}");
                return;
            }

            Headers outHeaders = ex.getResponseHeaders();
            outHeaders.set("Access-Control-Allow-Origin", "*");
            outHeaders.set("Access-Control-Expose-Headers",
                    "X-Playlist-Rewritten, X-Proxy-Target, X-Playlist-Kind, X-Rewrite-Engine, "
                            + "Content-Range, Accept-Ranges");
            outHeaders.set("Cache-Control", "no-store");
            outHeaders.set("X-Proxy-Target", target);
            outHeaders.set("X-Rewrite-Engine", "open-m3u8");
            if (remote.acceptRanges != null && remote.acceptRanges.length() > 0) {
                outHeaders.set("Accept-Ranges", remote.acceptRanges);
            } else {
                outHeaders.set("Accept-Ranges", "bytes");
            }
            if (remote.contentRange != null && remote.contentRange.length() > 0) {
                outHeaders.set("Content-Range", remote.contentRange);
            }

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
                final String proxyBase = requestProxyBase(ex);
                UriMapper mapper = new UriMapper() {
                    @Override
                    public String map(String absoluteUri) {
                        return PlaylistRewriteUtil.toProxyUrl(proxyBase, absoluteUri);
                    }
                };
                try {
                    Playlist playlist = PlaylistRewriteUtil.parse(remote.body, Encoding.UTF_8);
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
                    // Never return a raw playlist with relative URIs — hls.js would resolve
                    // them against /proxy and break. Fall back to line-level URI rewrite.
                    System.err.println("open-m3u8 parse/rewrite failed for " + target + ": " + e
                            + " — falling back to URI rewrite only");
                    try {
                        String text = new String(remote.body, StandardCharsets.UTF_8);
                        String fixed = rewritePlaylistUrisText(text, target, mapper);
                        byte[] out = fixed.getBytes(StandardCharsets.UTF_8);
                        outHeaders.set("Content-Type", "application/vnd.apple.mpegurl; charset=utf-8");
                        outHeaders.set("X-Playlist-Rewritten", "1");
                        outHeaders.set("X-Playlist-Kind", "fallback");
                        outHeaders.set("X-Rewrite-Fallback", "uri-only");
                        ex.sendResponseHeaders(200, out.length);
                        try (OutputStream os = ex.getResponseBody()) {
                            os.write(out);
                        }
                        return;
                    } catch (Exception fallbackEx) {
                        System.err.println("URI fallback rewrite failed for " + target + ": " + fallbackEx);
                    }
                } catch (Exception e) {
                    System.err.println("rewrite error for " + target + ": " + e);
                }
            }

            // Buffered non-playlist path (rare for .m3u8 URLs that aren't playlists)
            outHeaders.set("Content-Type", remote.contentType != null
                    ? remote.contentType : "application/octet-stream");
            int status = remote.status > 0 ? remote.status : 200;
            ex.sendResponseHeaders(status, remote.body.length);
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
        final String contentRange;
        final String acceptRanges;

        FetchResult(int status, String contentType, byte[] body) {
            this(status, contentType, body, null, null);
        }

        FetchResult(int status, String contentType, byte[] body,
                    String contentRange, String acceptRanges) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
            this.contentRange = contentRange;
            this.acceptRanges = acceptRanges;
        }
    }

    private static FetchResult fetchRemote(String target) throws IOException {
        return fetchRemote(target, null);
    }

    private static FetchResult fetchRemote(String target, String rangeHeader) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(target).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        // Range responses are small; full-object fetches (rare for media) may need longer
        conn.setReadTimeout(rangeHeader != null && rangeHeader.length() > 0 ? 30000 : 120000);
        conn.setRequestProperty("User-Agent", "open-m3u8-hls-player/1.0");
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

    /**
     * Stream remote media through without buffering the entire body in heap.
     * Used for segments / init maps / anything that is not a rewriteable playlist.
     */
    private static void streamRemote(HttpExchange ex, String target, String rangeHeader) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(target).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(120000);
        conn.setRequestProperty("User-Agent", "open-m3u8-hls-player/1.0");
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
        outHeaders.set("Access-Control-Allow-Origin", "*");
        outHeaders.set("Access-Control-Expose-Headers",
                "X-Proxy-Target, X-Rewrite-Engine, Content-Range, Accept-Ranges");
        outHeaders.set("Cache-Control", "no-store");
        outHeaders.set("X-Proxy-Target", target);
        outHeaders.set("X-Rewrite-Engine", "open-m3u8");
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
        long responseLen = contentLength >= 0 ? contentLength : 0;
        // 0 means unknown length → HttpServer uses chunked transfer
        if (contentLength < 0) {
            responseLen = 0;
        }
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
            // For 206, use contentLength if known; otherwise 0 (chunked)
            long len = (contentLength >= 0) ? contentLength : 0;
            ex.sendResponseHeaders(status > 0 ? status : 200, len);
            try (OutputStream os = ex.getResponseBody()) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    os.write(buf, 0, n);
                }
            }
        } finally {
            try { in.close(); } catch (IOException ignored) { }
            conn.disconnect();
        }
    }

    private static boolean urlLooksLikePlaylist(String url) {
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
        // query-only masters sometimes omit extension; treat unknown as playlist-safe
        // only when explicitly m3u-ish in the full URL
        String lower = url.toLowerCase();
        return lower.contains(".m3u8") || lower.contains("mpegurl") || lower.contains("m3u8");
    }

    private static String firstHeader(Headers headers, String name) {
        if (headers == null) return null;
        List<String> values = headers.get(name);
        if (values == null || values.isEmpty()) {
            // HttpExchange may use canonical case
            values = headers.get(name.toLowerCase());
        }
        if (values == null || values.isEmpty()) return null;
        return values.get(0);
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
        h.set("Access-Control-Allow-Headers", "Content-Type, Range");
        h.set("Access-Control-Expose-Headers",
                "Content-Range, Accept-Ranges, X-Playlist-Rewritten, X-Proxy-Target, "
                        + "X-Playlist-Kind, X-Rewrite-Engine");
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

    /** Public base URL for rewritten child URIs (matches how the client reached us). */
    private String requestProxyBase(HttpExchange ex) {
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host == null || host.trim().isEmpty()) {
            host = "127.0.0.1:" + port;
        }
        // Always http for this local demo server
        return "http://" + host.trim();
    }

    /**
     * Line-level URI rewrite when open-m3u8 cannot parse a playlist.
     * Rewrites bare URI lines and common URI="..." attributes through the proxy.
     */
    private static String rewritePlaylistUrisText(String text, String playlistUrl, UriMapper mapper) {
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length() + 256);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            // strip CR if present
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            String trimmed = line.trim();
            if (trimmed.length() == 0) {
                out.append(line);
            } else if (trimmed.startsWith("#")) {
                out.append(rewriteAttrUrisInTag(line, playlistUrl, mapper));
            } else {
                // URI line (segment or child playlist)
                out.append(PlaylistRewriteUtil.mapRef(playlistUrl, trimmed, mapper));
            }
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static String rewriteAttrUrisInTag(String line, String playlistUrl, UriMapper mapper) {
        // Rewrite URI="...", X-ASSET-URI="...", X-ASSET-LIST="..."
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
                // unquoted value until comma or end
                int end = result.indexOf(',', q);
                if (end < 0) end = result.length();
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
}
