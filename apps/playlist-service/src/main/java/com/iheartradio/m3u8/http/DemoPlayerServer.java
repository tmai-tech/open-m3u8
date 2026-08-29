package com.iheartradio.m3u8.http;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Unified HLS demo: one session, one proxy, SSAI or SGAI chosen by the client.
 *
 * <pre>
 *   ./gradlew runDemo
 *   open http://127.0.0.1:8765/
 * </pre>
 */
public final class DemoPlayerServer {

    public static final int DEFAULT_PORT = 8765;
    public static final float DEFAULT_MAX_AD_DURATION_SEC = 30f;
    private static final String BIND_HOST = "0.0.0.0";
    private static final long SESSION_TTL_MS = 2L * 60L * 60L * 1000L;

    private final int port;
    private final File staticRoot;
    private final File mediaRoot;
    private final Map<String, DemoSession> sessions = new ConcurrentHashMap<String, DemoSession>();
    private final AtomicLong sessionCounter = new AtomicLong(0);
    private final DemoPlaylistPipeline pipeline = new DemoPlaylistPipeline();

    public DemoPlayerServer(int port, File staticRoot) {
        this(port, staticRoot, locateMediaRoot());
    }

    public DemoPlayerServer(int port, File staticRoot, File mediaRoot) {
        this.port = port;
        this.staticRoot = staticRoot;
        this.mediaRoot = mediaRoot;
    }

    public static void main(String[] args) throws Exception {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }
        DemoPlayerServer server = new DemoPlayerServer(port, locateStaticRoot());
        server.start();
    }

    static File locateStaticRoot() {
        File[] candidates = new File[] {
                new File("apps/web-client"),
                new File(System.getProperty("user.dir", "."), "apps/web-client"),
                new File("open-m3u8/apps/web-client"),
                new File("demo"),
                new File(System.getProperty("user.dir", "."), "demo"),
                new File("hls-player"),
                new File(System.getProperty("user.dir", "."), "hls-player"),
                new File("open-m3u8/demo"),
                new File("ssai-player"),
        };
        try {
            java.net.URL loc = DemoPlayerServer.class.getProtectionDomain().getCodeSource().getLocation();
            if (loc != null) {
                File code = new File(loc.toURI());
                File dir = code.isFile() ? code.getParentFile() : code;
                for (int i = 0; i < 8 && dir != null; i++) {
                    File web = new File(dir, "apps/web-client");
                    if (web.isDirectory() && new File(web, "index.html").isFile()) {
                        return web.getAbsoluteFile();
                    }
                    File demo = new File(dir, "demo");
                    if (demo.isDirectory() && new File(demo, "index.html").isFile()) {
                        return demo.getAbsoluteFile();
                    }
                    File hls = new File(dir, "hls-player");
                    if (hls.isDirectory() && new File(hls, "index.html").isFile()) {
                        return hls.getAbsoluteFile();
                    }
                    dir = dir.getParentFile();
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        for (File f : candidates) {
            if (f != null && f.isDirectory() && new File(f, "index.html").isFile()) {
                return f.getAbsoluteFile();
            }
        }
        return new File("apps/web-client").getAbsoluteFile();
    }

    static File locateMediaRoot() {
        File[] candidates = new File[] {
                new File("media"),
                new File(System.getProperty("user.dir", "."), "media"),
                new File("open-m3u8/media"),
        };
        for (File f : candidates) {
            if (f != null && f.isDirectory()) {
                return f.getAbsoluteFile();
            }
        }
        return new File("media").getAbsoluteFile();
    }

    public void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(BIND_HOST, port), 0);
        server.createContext("/", new RootHandler());
        server.createContext("/api/health", new HealthHandler());
        server.createContext("/api/origin", new OriginApiHandler());
        server.createContext("/api/session", new SessionApiHandler());
        server.createContext("/api/logs", new LogsApiHandler());
        server.createContext("/play", new PlayHandler());
        server.createContext("/s/", new SessionResourceHandler());
        server.createContext("/media/", new MediaHandler());
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();
        DemoHttp.startPublicBaseDiscovery();
        String https = DemoHttp.advertisedPublicBase();

        System.out.println("HLS demo (open-m3u8 SSAI / SGAI)");
        System.out.println("  Bind:     " + BIND_HOST + ":" + port);
        System.out.println("  UI:       http://127.0.0.1:" + port + "/");
        System.out.println("  HTTPS:    " + (https != null ? https : "(waiting for cloudflared)"));
        System.out.println("  Session:  POST http://127.0.0.1:" + port + "/api/session");
        System.out.println("  Play:     http://127.0.0.1:" + port
                + "/play?strategy=ssai&content=<m3u8>&ad=<m3u8>&splices=30,90");
        System.out.println("  Manifest: http://127.0.0.1:" + port + "/s/{id}/manifest");
        System.out.println("  Logs:     http://127.0.0.1:" + port + "/api/logs");
        System.out.println("  Engine:   DemoPlaylistPipeline (injectMediaTags | stitch)");
        System.out.println("  Static:   " + staticRoot.getAbsolutePath());
        System.out.println("  Media:    " + mediaRoot.getAbsolutePath());
        System.out.println("  Log file: " + DemoLog.logFile().getAbsolutePath());
    }

    DemoSession createSession(DemoSession session) {
        expireOldSessions();
        sessions.put(session.id, session);
        logSession(session);
        return session;
    }

    private static void logSession(DemoSession session) {
        if (session == null) {
            return;
        }
        StringBuilder offs = new StringBuilder("[");
        for (int i = 0; i < session.breaks.size(); i++) {
            if (i > 0) {
                offs.append(',');
            }
            offs.append(session.breaks.get(i).offsetSec);
        }
        offs.append(']');
        DemoLog.event("session")
                .sid(session.id)
                .put("strategy", session.strategy.wireName())
                .put("forceVod", session.forceVod)
                .put("contentUrl", session.contentUrl)
                .put("adUrl", session.adUrl)
                .put("breakCount", session.breaks.size())
                .putRaw("splices", offs.toString())
                .write();
    }

    DemoSession newSessionFromJson(String json) {
        String id = nextId();
        return createSession(DemoSession.fromJson(id, json));
    }

    private String nextId() {
        return Long.toString(sessionCounter.incrementAndGet(), 36)
                + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private void expireOldSessions() {
        for (Map.Entry<String, DemoSession> e : sessions.entrySet()) {
            if (e.getValue().isExpired(SESSION_TTL_MS)) {
                sessions.remove(e.getKey());
            }
        }
    }

    private DemoSession requireSession(String id) {
        if (id == null) {
            return null;
        }
        DemoSession s = sessions.get(id);
        if (s == null || s.isExpired(SESSION_TTL_MS)) {
            if (s != null) {
                sessions.remove(id);
            }
            return null;
        }
        return s;
    }

    /** Liveness for tests / curl. The demo page does not call this. */
    private final class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                DemoHttp.sendCors(ex, 204, new byte[0], "application/json");
                return;
            }
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())
                    && !"HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
                DemoHttp.send(ex, 405, "text/plain", "method not allowed");
                return;
            }
            DemoHttp.publicBase(ex, port);
            String pub = DemoHttp.advertisedPublicBase();
            String body = "{\"ok\":true,\"proxy\":true,\"engine\":\""
                    + DemoHttp.ENGINE + "\",\"port\":" + port
                    + ",\"strategies\":[\"sgai\",\"ssai\"],\"sessions\":"
                    + sessions.size()
                    + ",\"publicBase\":"
                    + (pub == null ? "null" : DemoHttp.jsonString(pub))
                    + "}";
            DemoHttp.send(ex, 200, "application/json; charset=utf-8", body);
        }
    }

    private final class LogsApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                DemoHttp.sendCors(ex, 204, new byte[0], "application/json");
                return;
            }
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())
                    && !"HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
                DemoHttp.send(ex, 405, "text/plain", "method not allowed");
                return;
            }
            String q = ex.getRequestURI().getRawQuery();
            String sid = DemoHttp.decode(DemoHttp.queryParam(q, "session"));
            if (sid == null) {
                sid = DemoHttp.decode(DemoHttp.queryParam(q, "id"));
            }
            int limit = DemoLog.DEFAULT_DUMP_LIMIT;
            String limitRaw = DemoHttp.decode(DemoHttp.queryParam(q, "limit"));
            if (limitRaw != null && limitRaw.length() > 0) {
                try {
                    limit = Integer.parseInt(limitRaw);
                } catch (NumberFormatException ignored) {
                    // keep default
                }
            }
            DemoHttp.send(ex, 200, "application/json; charset=utf-8", DemoLog.dumpJson(sid, limit));
        }
    }

    /** Same-origin fetch of a remote playlist/segment (poster / I-frame). Demo only. */
    private final class OriginApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                DemoHttp.sendCors(ex, 204, new byte[0], "application/json");
                return;
            }
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())
                    && !"HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
                DemoHttp.send(ex, 405, "text/plain", "method not allowed");
                return;
            }
            String raw = DemoHttp.decode(DemoHttp.queryParam(ex.getRequestURI().getRawQuery(), "url"));
            if (raw == null || raw.trim().isEmpty()) {
                DemoHttp.send(ex, 400, "application/json; charset=utf-8",
                        "{\"error\":\"url is required\"}");
                return;
            }
            String target = raw.trim();
            try {
                DemoHttp.requireHttpUrl(target);
            } catch (IllegalArgumentException e) {
                DemoHttp.send(ex, 400, "application/json; charset=utf-8",
                        "{\"error\":" + DemoHttp.jsonString(e.getMessage()) + "}");
                return;
            }
            String range = DemoHttp.firstHeader(ex.getRequestHeaders(), "Range");
            boolean playlist = target.toLowerCase().contains(".m3u8")
                    || target.toLowerCase().contains("playlist");
            try {
                if (playlist) {
                    DemoHttp.FetchResult r = DemoHttp.fetchRemote(target, null);
                    String text = new String(r.body, StandardCharsets.UTF_8);
                    if (text.indexOf("#EXTM3U") >= 0) {
                        final String originBase = "/api/origin?url=";
                        String rewritten = DemoHttp.rewritePlaylistUrisText(text, target,
                                new com.iheartradio.m3u8.ads.PlaylistRewriteUtil.UriMapper() {
                                    @Override
                                    public String map(String absoluteUrl) {
                                        try {
                                            return originBase
                                                    + java.net.URLEncoder.encode(absoluteUrl, "UTF-8");
                                        } catch (Exception e) {
                                            return originBase + absoluteUrl;
                                        }
                                    }
                                });
                        DemoHttp.send(ex, 200, "application/vnd.apple.mpegurl; charset=utf-8",
                                rewritten);
                        return;
                    }
                }
                DemoHttp.streamRemote(ex, target, range);
            } catch (Exception e) {
                DemoHttp.send(ex, 502, "application/json; charset=utf-8",
                        "{\"error\":" + DemoHttp.jsonString("origin fetch failed: " + e.getMessage())
                                + "}");
            }
        }
    }

    private final class SessionApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String method = ex.getRequestMethod();
            if ("OPTIONS".equalsIgnoreCase(method)) {
                DemoHttp.sendCors(ex, 204, new byte[0], "application/json");
                return;
            }
            if ("GET".equalsIgnoreCase(method)) {
                String id = DemoHttp.decode(DemoHttp.queryParam(ex.getRequestURI().getRawQuery(), "id"));
                DemoSession s = requireSession(id);
                if (s == null) {
                    DemoHttp.send(ex, 404, "application/json; charset=utf-8",
                            "{\"error\":\"session not found\"}");
                    return;
                }
                DemoHttp.send(ex, 200, "application/json; charset=utf-8",
                        "{\"ok\":true,\"session\":" + s.toJson(DemoHttp.publicBase(ex, port)) + "}");
                return;
            }
            if ("POST".equalsIgnoreCase(method)) {
                String json = new String(DemoHttp.readAll(ex.getRequestBody()), StandardCharsets.UTF_8);
                try {
                    DemoSession s = newSessionFromJson(json);
                    String resp = "{\"ok\":true,\"session\":"
                            + s.toJson(DemoHttp.publicBase(ex, port)) + "}";
                    DemoHttp.send(ex, 200, "application/json; charset=utf-8", resp);
                } catch (IllegalArgumentException e) {
                    DemoLog.event("error").put("evSrc", "session").put("msg", e.getMessage()).write();
                    DemoHttp.send(ex, 400, "application/json; charset=utf-8",
                            "{\"error\":" + DemoHttp.jsonString(e.getMessage()) + "}");
                } catch (Exception e) {
                    DemoLog.event("error").put("evSrc", "session").put("msg", e.getMessage()).write();
                    DemoHttp.send(ex, 400, "application/json; charset=utf-8",
                            "{\"error\":" + DemoHttp.jsonString(e.getMessage()) + "}");
                }
                return;
            }
            DemoHttp.send(ex, 405, "text/plain", "method not allowed");
        }
    }

    private final class PlayHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                DemoHttp.sendCors(ex, 204, new byte[0], "text/plain");
                return;
            }
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                DemoHttp.send(ex, 405, "text/plain", "method not allowed");
                return;
            }
            String q = ex.getRequestURI().getRawQuery();
            String content = DemoHttp.decode(DemoHttp.queryParam(q, "content"));
            String ad = DemoHttp.decode(DemoHttp.queryParam(q, "ad"));
            String splicesRaw = DemoHttp.decode(DemoHttp.queryParam(q, "splices"));
            String format = DemoHttp.decode(DemoHttp.queryParam(q, "format"));
            String strategyRaw = DemoHttp.decode(DemoHttp.queryParam(q, "strategy"));
            String maxAdRaw = DemoHttp.decode(DemoHttp.queryParam(q, "maxAdDurationSec"));
            if (maxAdRaw == null || maxAdRaw.isEmpty()) {
                maxAdRaw = DemoHttp.decode(DemoHttp.queryParam(q, "maxAd"));
            }
            try {
                DemoSession.Strategy strategy = DemoSession.Strategy.fromWire(strategyRaw);
                float[] splices = DemoSession.parseSplices(splicesRaw);
                float maxAd = DemoSession.parseMaxAdDuration(maxAdRaw, DEFAULT_MAX_AD_DURATION_SEC);
                DemoSession s = createSession(DemoSession.fromPlayQuery(
                        nextId(), strategy, content, ad, splices, maxAd));
                String base = DemoHttp.publicBase(ex, port);
                String manifestUrl = base + "/s/" + s.id + "/manifest";
                if ("json".equalsIgnoreCase(format)) {
                    DemoHttp.send(ex, 200, "application/json; charset=utf-8",
                            "{\"ok\":true,\"session\":" + s.toJson(base) + "}");
                    return;
                }
                Headers h = ex.getResponseHeaders();
                DemoHttp.applyCorsHeaders(ex);
                h.set("Location", manifestUrl);
                h.set("Cache-Control", "no-store");
                ex.sendResponseHeaders(302, -1);
                ex.close();
            } catch (IllegalArgumentException e) {
                DemoHttp.send(ex, 400, "application/json; charset=utf-8",
                        "{\"error\":" + DemoHttp.jsonString(e.getMessage()) + "}");
            }
        }
    }

    private final class SessionResourceHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                DemoHttp.sendCors(ex, 204, new byte[0], "text/plain");
                return;
            }
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())
                    && !"HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
                DemoHttp.send(ex, 405, "text/plain", "method not allowed");
                return;
            }

            String path = ex.getRequestURI().getPath();
            String rest = path.startsWith("/s/") ? path.substring(3) : "";
            int slash = rest.indexOf('/');
            if (slash <= 0) {
                DemoHttp.send(ex, 404, "application/json; charset=utf-8",
                        "{\"error\":\"expected /s/{id}/manifest or /s/{id}/proxy\"}");
                return;
            }
            String id = rest.substring(0, slash);
            String action = rest.substring(slash + 1);
            DemoSession session = requireSession(id);
            if (session == null) {
                DemoHttp.send(ex, 404, "application/json; charset=utf-8",
                        "{\"error\":\"session not found or expired\"}");
                return;
            }

            if ("manifest".equals(action) || action.startsWith("manifest")) {
                handlePlaylist(ex, session, session.contentUrl);
                return;
            }
            if ("proxy".equals(action) || action.startsWith("proxy")) {
                handleProxy(ex, session);
                return;
            }
            DemoHttp.send(ex, 404, "application/json; charset=utf-8",
                    "{\"error\":\"unknown session path; use manifest or proxy\"}");
        }
    }

    private void handleProxy(HttpExchange ex, DemoSession session) throws IOException {
        String target = DemoHttp.decode(DemoHttp.queryParam(ex.getRequestURI().getRawQuery(), "url"));
        if (target == null || target.isEmpty()) {
            DemoHttp.send(ex, 400, "application/json; charset=utf-8",
                    "{\"error\":\"missing url query parameter\"}");
            return;
        }
        try {
            DemoHttp.requireHttpUrl(target);
        } catch (IllegalArgumentException e) {
            DemoHttp.send(ex, 400, "application/json; charset=utf-8",
                    "{\"error\":" + DemoHttp.jsonString(e.getMessage()) + "}");
            return;
        }

        String rangeHeader = DemoHttp.firstHeader(ex.getRequestHeaders(), "Range");
        boolean likelyPlaylist = DemoHttp.urlLooksLikePlaylist(target)
                && (rangeHeader == null || rangeHeader.trim().isEmpty());
        if (!likelyPlaylist) {
            try {
                DemoHttp.streamRemote(ex, target, rangeHeader);
            } catch (Exception e) {
                try {
                    DemoHttp.send(ex, 502, "application/json; charset=utf-8",
                            "{\"error\":" + DemoHttp.jsonString("fetch failed: " + e.getMessage())
                                    + ",\"url\":" + DemoHttp.jsonString(target) + "}");
                } catch (Exception ignored) {
                    // response may already be committed
                }
            }
            return;
        }
        handlePlaylist(ex, session, target);
    }

    private void handlePlaylist(HttpExchange ex, DemoSession session, String playlistUrl)
            throws IOException {
        try {
            DemoPlaylistPipeline.Result result = pipeline.process(
                    session, playlistUrl, DemoHttp.publicBase(ex, port));
            if (result.fallback) {
                Headers h = ex.getResponseHeaders();
                DemoHttp.applyCorsHeaders(ex);
                h.set("X-Rewrite-Fallback", "uri-only");
            }
            DemoHttp.writePlaylistResponse(ex, result.body, playlistUrl, result.kind,
                    session.strategy.wireName());
        } catch (DemoHttp.HttpException e) {
            DemoLog.event("error")
                    .sid(session.id)
                    .put("url", playlistUrl)
                    .put("status", e.status)
                    .put("msg", e.getMessage())
                    .write();
            DemoHttp.send(ex, e.status, "application/json; charset=utf-8",
                    "{\"error\":" + DemoHttp.jsonString(e.getMessage())
                            + ",\"url\":" + DemoHttp.jsonString(playlistUrl) + "}");
        } catch (Exception e) {
            e.printStackTrace();
            DemoLog.event("error")
                    .sid(session.id)
                    .put("url", playlistUrl)
                    .put("status", 502)
                    .put("msg", e.getMessage())
                    .write();
            DemoHttp.send(ex, 502, "application/json; charset=utf-8",
                    "{\"error\":" + DemoHttp.jsonString("rewrite failed: " + e.getMessage())
                            + ",\"url\":" + DemoHttp.jsonString(playlistUrl) + "}");
        }
    }

    private final class MediaHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                DemoHttp.sendCors(ex, 204, new byte[0], "text/plain");
                return;
            }
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())
                    && !"HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
                DemoHttp.send(ex, 405, "text/plain", "method not allowed");
                return;
            }
            String path = ex.getRequestURI().getPath();
            String rel = path != null && path.startsWith("/media") ? path.substring("/media".length()) : path;
            if (!DemoHttp.serveStatic(ex, mediaRoot, rel)) {
                DemoHttp.send(ex, 404, "text/plain", "not found");
            }
        }
    }

    private final class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                DemoHttp.sendCors(ex, 204, new byte[0], "text/plain");
                return;
            }
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())
                    && !"HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
                DemoHttp.send(ex, 405, "text/plain", "method not allowed");
                return;
            }
            String path = ex.getRequestURI().getPath();
            if (path != null && (path.startsWith("/api/") || path.startsWith("/s/")
                    || path.startsWith("/media/") || path.equals("/play") || path.startsWith("/play/"))) {
                DemoHttp.send(ex, 404, "text/plain", "not found");
                return;
            }
            if (!DemoHttp.serveStatic(ex, staticRoot, path)) {
                DemoHttp.send(ex, 404, "text/plain", "not found");
            }
        }
    }
}
