package com.iheartradio.m3u8.demo;

import com.iheartradio.m3u8.Encoding;
import com.iheartradio.m3u8.ParseException;
import com.iheartradio.m3u8.PlaylistException;
import com.iheartradio.m3u8.PlaylistRewriteUtil;
import com.iheartradio.m3u8.PlaylistRewriteUtil.UriMapper;
import com.iheartradio.m3u8.PlaylistSsaiUtil;
import com.iheartradio.m3u8.data.MediaPlaylist;
import com.iheartradio.m3u8.data.Playlist;
import com.iheartradio.m3u8.data.PlaylistData;
import com.iheartradio.m3u8.data.PlaylistType;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * VOD SSAI proxy: one content URL + one ad URL + splice points (seconds).
 * Stitches the <em>same</em> ad pod at every splice using
 * {@link PlaylistSsaiUtil}, rewrites all URIs through this proxy, and serves
 * a playable HLS manifest for native players / hls.js.
 *
 * <pre>
 *   ./gradlew runSsaiProxy
 *   open http://127.0.0.1:8766/
 *
 *   # one-shot (creates session + returns stitched entry manifest):
 *   GET /play?content=&lt;content.m3u8&gt;&amp;ad=&lt;ad.m3u8&gt;&amp;splices=30,90
 *
 *   # or POST /api/session then play /s/{id}/manifest
 * </pre>
 */
public final class SsaiProxyServer {

    private static final int DEFAULT_PORT = 8766;
    private static final String BIND_HOST = "0.0.0.0";
    private static final String ENGINE = "open-m3u8-ssai";
    private static final long SESSION_TTL_MS = 2L * 60L * 60L * 1000L; // 2h
    /** Default max ad pod length (seconds). Longer creatives are trimmed to whole segments. */
    public static final float DEFAULT_MAX_AD_DURATION_SEC = 30f;

    private final int port;
    private final File staticRoot;
    private final Map<String, Session> sessions = new ConcurrentHashMap<String, Session>();
    private final AtomicLong sessionCounter = new AtomicLong(0);

    public SsaiProxyServer(int port, File staticRoot) {
        this.port = port;
        this.staticRoot = staticRoot;
    }

    public static void main(String[] args) throws Exception {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }
        File root = locateStaticRoot();
        SsaiProxyServer server = new SsaiProxyServer(port, root);
        server.start();
    }

    private static File locateStaticRoot() {
        List<File> candidates = new ArrayList<File>();
        // CWD (gradle runSsaiProxy sets workingDir to project root)
        candidates.add(new File("ssai-player"));
        candidates.add(new File(System.getProperty("user.dir", "."), "ssai-player"));
        // Common layouts when launched from a parent dir or IDE
        candidates.add(new File("open-m3u8/ssai-player"));
        candidates.add(new File(System.getProperty("user.dir", "."), "open-m3u8/ssai-player"));
        // Relative to this class file / jar (…/build/classes/java/main/… or …/build/libs/)
        try {
            java.net.URL loc = SsaiProxyServer.class.getProtectionDomain()
                    .getCodeSource().getLocation();
            if (loc != null) {
                File code = new File(loc.toURI());
                // build/classes/java/main → project root is ../../../../
                File dir = code.isFile() ? code.getParentFile() : code;
                for (int i = 0; i < 8 && dir != null; i++) {
                    candidates.add(new File(dir, "ssai-player"));
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
        File fallback = new File("ssai-player").getAbsoluteFile();
        System.err.println("WARNING: ssai-player/index.html not found; static root="
                + fallback.getAbsolutePath()
                + " (UI will 404). Run from open-m3u8 project root or set cwd correctly.");
        return fallback;
    }

    public void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(BIND_HOST, port), 0);
        server.createContext("/", new RootHandler());
        server.createContext("/api/health", new HealthHandler());
        server.createContext("/api/session", new SessionApiHandler());
        server.createContext("/play", new PlayHandler());
        server.createContext("/s/", new SessionResourceHandler());
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();

        System.out.println("SSAI VOD proxy (open-m3u8 segment stitch)");
        System.out.println("  Bind:     " + BIND_HOST + ":" + port);
        System.out.println("  UI:       http://127.0.0.1:" + port + "/");
        System.out.println("  Play:     http://127.0.0.1:" + port
                + "/play?content=<content.m3u8>&ad=<ad.m3u8>&splices=30,90");
        System.out.println("  Session:  POST http://127.0.0.1:" + port + "/api/session");
        System.out.println("  Manifest: http://127.0.0.1:" + port + "/s/{id}/manifest");
        System.out.println("  Engine:   PlaylistSsaiUtil.stitch + PlaylistRewriteUtil.rewriteUris");
        System.out.println("  Static:   " + staticRoot.getAbsolutePath());
    }

    // ---------------- session model ----------------

    static final class Session {
        final String id;
        final String contentUrl;
        final String adUrl;
        final float[] splices;
        /** Max ad pod length in seconds; {@code <= 0} means no trim. Default 30. */
        final float maxAdDurationSec;
        final long createdAtMs;
        private volatile Playlist cachedAdMedia;
        private volatile String adLoadError;
        private final Object adLock = new Object();

        Session(String id, String contentUrl, String adUrl, float[] splices, float maxAdDurationSec) {
            this.id = id;
            this.contentUrl = contentUrl;
            this.adUrl = adUrl;
            this.splices = splices == null ? new float[0] : Arrays.copyOf(splices, splices.length);
            this.maxAdDurationSec = maxAdDurationSec;
            this.createdAtMs = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - createdAtMs > SESSION_TTL_MS;
        }
    }

    private Session createSession(String contentUrl, String adUrl, float[] splices,
                                  float maxAdDurationSec) {
        expireOldSessions();
        String id = Long.toString(sessionCounter.incrementAndGet(), 36)
                + "-" + UUID.randomUUID().toString().substring(0, 8);
        Session s = new Session(id, contentUrl, adUrl, splices, maxAdDurationSec);
        sessions.put(id, s);
        return s;
    }

    private void expireOldSessions() {
        for (Map.Entry<String, Session> e : sessions.entrySet()) {
            if (e.getValue().isExpired()) {
                sessions.remove(e.getKey());
            }
        }
    }

    private Session requireSession(String id) {
        if (id == null) {
            return null;
        }
        Session s = sessions.get(id);
        if (s == null || s.isExpired()) {
            if (s != null) {
                sessions.remove(id);
            }
            return null;
        }
        return s;
    }

    // ---------------- handlers ----------------

    private final class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                sendCors(ex, 204, new byte[0], "application/json");
                return;
            }
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())
                    && !"HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
                send(ex, 405, "text/plain", "method not allowed");
                return;
            }
            String body = "{\"ok\":true,\"proxy\":true,\"mode\":\"ssai-vod\",\"engine\":\""
                    + ENGINE + "\",\"port\":" + port + ",\"sessions\":" + sessions.size() + "}";
            send(ex, 200, "application/json; charset=utf-8", body);
        }
    }

    /**
     * POST /api/session  JSON: { "contentUrl", "adUrl", "splices": [30, 90] }
     * GET  /api/session?id=...
     */
    private final class SessionApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String method = ex.getRequestMethod();
            if ("OPTIONS".equalsIgnoreCase(method)) {
                sendCors(ex, 204, new byte[0], "application/json");
                return;
            }
            if ("GET".equalsIgnoreCase(method)) {
                String id = queryParam(ex.getRequestURI().getRawQuery(), "id");
                Session s = requireSession(id);
                if (s == null) {
                    send(ex, 404, "application/json; charset=utf-8",
                            "{\"error\":\"session not found\"}");
                    return;
                }
                send(ex, 200, "application/json; charset=utf-8", sessionToJson(s, publicBase(ex)));
                return;
            }
            if ("POST".equalsIgnoreCase(method)) {
                String json = new String(readAll(ex.getRequestBody()), StandardCharsets.UTF_8);
                try {
                    SessionInput in = parseSessionInput(json);
                    Session s = createSession(in.contentUrl, in.adUrl, in.splices, in.maxAdDurationSec);
                    String resp = "{\"ok\":true,\"session\":" + sessionToJson(s, publicBase(ex)) + "}";
                    send(ex, 200, "application/json; charset=utf-8", resp);
                } catch (IllegalArgumentException e) {
                    send(ex, 400, "application/json; charset=utf-8",
                            "{\"error\":" + jsonString(e.getMessage()) + "}");
                } catch (Exception e) {
                    send(ex, 400, "application/json; charset=utf-8",
                            "{\"error\":" + jsonString(e.getMessage()) + "}");
                }
                return;
            }
            send(ex, 405, "text/plain", "method not allowed");
        }
    }

    /**
     * GET /play?content=...&ad=...&splices=30,90[&format=json]
     * Creates a session and redirects (or returns JSON) to the stitched entry manifest.
     */
    private final class PlayHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                sendCors(ex, 204, new byte[0], "text/plain");
                return;
            }
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                send(ex, 405, "text/plain", "method not allowed");
                return;
            }
            String q = ex.getRequestURI().getRawQuery();
            String content = decode(queryParam(q, "content"));
            String ad = decode(queryParam(q, "ad"));
            String splicesRaw = decode(queryParam(q, "splices"));
            String format = decode(queryParam(q, "format"));
            String maxAdRaw = decode(queryParam(q, "maxAdDurationSec"));
            if (maxAdRaw == null || maxAdRaw.isEmpty()) {
                maxAdRaw = decode(queryParam(q, "maxAd"));
            }
            try {
                if (content == null || content.isEmpty()) {
                    throw new IllegalArgumentException("missing content query parameter");
                }
                if (ad == null || ad.isEmpty()) {
                    throw new IllegalArgumentException("missing ad query parameter");
                }
                requireHttpUrl(content);
                requireHttpUrl(ad);
                float[] splices = parseSplices(splicesRaw);
                float maxAd = parseMaxAdDuration(maxAdRaw, DEFAULT_MAX_AD_DURATION_SEC);
                Session s = createSession(content, ad, splices, maxAd);
                String base = publicBase(ex);
                String manifestUrl = base + "/s/" + s.id + "/manifest";
                if ("json".equalsIgnoreCase(format)) {
                    send(ex, 200, "application/json; charset=utf-8",
                            "{\"ok\":true,\"session\":" + sessionToJson(s, base) + "}");
                    return;
                }
                Headers h = ex.getResponseHeaders();
                applyCorsHeaders(h);
                h.set("Location", manifestUrl);
                h.set("Cache-Control", "no-store");
                ex.sendResponseHeaders(302, -1);
                ex.close();
            } catch (IllegalArgumentException e) {
                send(ex, 400, "application/json; charset=utf-8",
                        "{\"error\":" + jsonString(e.getMessage()) + "}");
            }
        }
    }

    /**
     * GET /s/{id}/manifest  — entry: fetch content URL, stitch if media, rewrite URIs
     * GET /s/{id}/proxy?url= — proxy child playlists (stitch media) and segments
     */
    private final class SessionResourceHandler implements HttpHandler {
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
            // /s/{id}/manifest  or  /s/{id}/proxy
            String rest = path.startsWith("/s/") ? path.substring(3) : "";
            int slash = rest.indexOf('/');
            if (slash <= 0) {
                send(ex, 404, "application/json; charset=utf-8",
                        "{\"error\":\"expected /s/{id}/manifest or /s/{id}/proxy\"}");
                return;
            }
            String id = rest.substring(0, slash);
            String action = rest.substring(slash + 1);
            Session session = requireSession(id);
            if (session == null) {
                send(ex, 404, "application/json; charset=utf-8",
                        "{\"error\":\"session not found or expired\"}");
                return;
            }

            if ("manifest".equals(action) || action.startsWith("manifest?")) {
                handleManifest(ex, session);
                return;
            }
            if ("proxy".equals(action) || action.startsWith("proxy")) {
                handleProxy(ex, session);
                return;
            }
            send(ex, 404, "application/json; charset=utf-8",
                    "{\"error\":\"unknown session path; use manifest or proxy\"}");
        }
    }

    private void handleManifest(HttpExchange ex, Session session) throws IOException {
        try {
            byte[] out = processPlaylistUrl(ex, session, session.contentUrl, true);
            writePlaylistResponse(ex, out, session.contentUrl, "entry");
        } catch (SsaiHttpException e) {
            send(ex, e.status, "application/json; charset=utf-8",
                    "{\"error\":" + jsonString(e.getMessage()) + "}");
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 502, "application/json; charset=utf-8",
                    "{\"error\":" + jsonString("manifest failed: " + e.getMessage()) + "}");
        }
    }

    private void handleProxy(HttpExchange ex, Session session) throws IOException {
        String target = decode(queryParam(ex.getRequestURI().getRawQuery(), "url"));
        if (target == null || target.isEmpty()) {
            send(ex, 400, "application/json; charset=utf-8",
                    "{\"error\":\"missing url query parameter\"}");
            return;
        }
        try {
            requireHttpUrl(target);
        } catch (IllegalArgumentException e) {
            send(ex, 400, "application/json; charset=utf-8",
                    "{\"error\":" + jsonString(e.getMessage()) + "}");
            return;
        }

        String rangeHeader = firstHeader(ex.getRequestHeaders(), "Range");
        boolean likelyPlaylist = urlLooksLikePlaylist(target)
                && (rangeHeader == null || rangeHeader.trim().isEmpty());

        if (!likelyPlaylist) {
            try {
                streamRemote(ex, target, rangeHeader);
            } catch (Exception e) {
                try {
                    send(ex, 502, "application/json; charset=utf-8",
                            "{\"error\":" + jsonString("fetch failed: " + e.getMessage())
                                    + ",\"url\":" + jsonString(target) + "}");
                } catch (Exception ignored) {
                    // response may already be committed
                }
            }
            return;
        }

        try {
            byte[] out = processPlaylistUrl(ex, session, target, true);
            writePlaylistResponse(ex, out, target, "proxied");
        } catch (SsaiHttpException e) {
            send(ex, e.status, "application/json; charset=utf-8",
                    "{\"error\":" + jsonString(e.getMessage())
                            + ",\"url\":" + jsonString(target) + "}");
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 502, "application/json; charset=utf-8",
                    "{\"error\":" + jsonString("proxy failed: " + e.getMessage())
                            + ",\"url\":" + jsonString(target) + "}");
        }
    }

    /**
     * Fetch a remote playlist, stitch media (VOD SSAI), rewrite all URIs through this session proxy.
     *
     * @param stitchMedia if true, media playlists get the ad spliced in
     */
    private byte[] processPlaylistUrl(HttpExchange ex, Session session, String playlistUrl,
                                      boolean stitchMedia) throws Exception {
        FetchResult remote = fetchRemote(playlistUrl, null);
        if (remote.status >= 400) {
            throw new SsaiHttpException(remote.status > 0 ? remote.status : 502,
                    "origin returned HTTP " + remote.status + " for " + playlistUrl);
        }
        if (!looksLikePlaylist(playlistUrl, remote.contentType, remote.body)) {
            // Not a playlist — caller should have streamed; return raw
            return remote.body;
        }

        Playlist playlist;
        try {
            playlist = PlaylistRewriteUtil.parse(remote.body, Encoding.UTF_8);
        } catch (ParseException | PlaylistException e) {
            throw new SsaiHttpException(502, "failed to parse playlist: " + e.getMessage());
        }

        final String proxyBase = publicBase(ex) + "/s/" + session.id;
        UriMapper mapper = new UriMapper() {
            @Override
            public String map(String absoluteUri) {
                return PlaylistRewriteUtil.toProxyUrl(proxyBase, absoluteUri);
            }
        };

        Playlist out = playlist;

        if (stitchMedia && out.hasMediaPlaylist()) {
            Playlist adMedia = loadAdMediaPlaylist(session);
            List<PlaylistSsaiUtil.AdBreak> breaks = buildBreaks(session, adMedia);
            if (!breaks.isEmpty()) {
                out = PlaylistSsaiUtil.stitch(out, breaks, PlaylistSsaiUtil.StitchOptions.defaults());
            }
            // VOD: ensure ENDLIST if origin was VOD (already preserved by stitch).
            // Force playlist type VOD when origin declared it or had ENDLIST.
            out = ensureVodHints(out);
        }
        // Master: do not stitch; only rewrite child playlist URIs so each variant is stitched on fetch.

        out = PlaylistRewriteUtil.rewriteUris(out, playlistUrl, mapper);
        return PlaylistRewriteUtil.write(out, Encoding.UTF_8);
    }

    private static Playlist ensureVodHints(Playlist playlist) {
        if (playlist == null || !playlist.hasMediaPlaylist()) {
            return playlist;
        }
        MediaPlaylist media = playlist.getMediaPlaylist();
        // If origin had ENDLIST (not ongoing), keep it. If type missing and not ongoing, set VOD.
        if (!media.isOngoing() && !media.hasPlaylistType()) {
            MediaPlaylist m2 = media.buildUpon()
                    .withPlaylistType(PlaylistType.VOD)
                    .build();
            return playlist.buildUpon().withMediaPlaylist(m2).build();
        }
        return playlist;
    }

    private List<PlaylistSsaiUtil.AdBreak> buildBreaks(Session session, Playlist adMedia) {
        List<PlaylistSsaiUtil.AdBreak> breaks = new ArrayList<PlaylistSsaiUtil.AdBreak>();
        if (session.splices.length == 0) {
            return breaks;
        }
        // Sort copy for stable mid-roll order
        float[] sorted = Arrays.copyOf(session.splices, session.splices.length);
        Arrays.sort(sorted);
        for (int i = 0; i < sorted.length; i++) {
            float offset = sorted[i];
            PlaylistSsaiUtil.AdBreak.Builder b = PlaylistSsaiUtil.AdBreak.builder()
                    .withId("ssai-" + (i + 1))
                    .withAdPlaylist(adMedia, session.maxAdDurationSec)
                    .withEmitDateRange(true);
            if (offset <= 0f) {
                b.preRoll();
            } else {
                b.atOffsetSec(offset);
            }
            breaks.add(b.build());
        }
        return breaks;
    }

    /**
     * Fetch and cache the ad as a media playlist. If the ad URL is a master, follow the first variant.
     */
    private Playlist loadAdMediaPlaylist(Session session) throws Exception {
        if (session.cachedAdMedia != null) {
            return session.cachedAdMedia;
        }
        synchronized (session.adLock) {
            if (session.cachedAdMedia != null) {
                return session.cachedAdMedia;
            }
            if (session.adLoadError != null) {
                throw new SsaiHttpException(502, session.adLoadError);
            }
            try {
                // Absolutize ad segment / key / map URIs against the *ad* playlist base.
                // If left relative, rewriteUris after stitch resolves them against the content
                // playlist URL and produces 404s (e.g. ad ts under content path).
                Playlist ad = fetchAndParsePlaylist(session.adUrl);
                if (ad.hasMediaPlaylist()) {
                    session.cachedAdMedia = PlaylistRewriteUtil.absolutizeUris(ad, session.adUrl);
                    return session.cachedAdMedia;
                }
                if (ad.hasMasterPlaylist()) {
                    List<PlaylistData> variants = ad.getMasterPlaylist().getPlaylists();
                    if (variants == null || variants.isEmpty()) {
                        throw new IllegalStateException("ad master playlist has no variants");
                    }
                    String child = PlaylistRewriteUtil.resolveUri(session.adUrl, variants.get(0).getUri());
                    Playlist media = fetchAndParsePlaylist(child);
                    if (!media.hasMediaPlaylist()) {
                        throw new IllegalStateException("ad variant is not a media playlist: " + child);
                    }
                    session.cachedAdMedia = PlaylistRewriteUtil.absolutizeUris(media, child);
                    return session.cachedAdMedia;
                }
                throw new IllegalStateException("ad URL is neither master nor media playlist");
            } catch (Exception e) {
                session.adLoadError = "failed to load ad playlist: " + e.getMessage();
                throw new SsaiHttpException(502, session.adLoadError);
            }
        }
    }

    private Playlist fetchAndParsePlaylist(String url) throws Exception {
        FetchResult remote = fetchRemote(url, null);
        if (remote.status >= 400) {
            throw new IOException("HTTP " + remote.status + " for " + url);
        }
        return PlaylistRewriteUtil.parse(remote.body, Encoding.UTF_8);
    }

    private void writePlaylistResponse(HttpExchange ex, byte[] body, String target, String kind)
            throws IOException {
        Headers outHeaders = ex.getResponseHeaders();
        applyCorsHeaders(outHeaders);
        outHeaders.set("Cache-Control", "no-store");
        outHeaders.set("Content-Type", "application/vnd.apple.mpegurl; charset=utf-8");
        outHeaders.set("X-Proxy-Target", target == null ? "" : target);
        outHeaders.set("X-Rewrite-Engine", ENGINE);
        outHeaders.set("X-Playlist-Rewritten", "1");
        outHeaders.set("X-Playlist-Kind", kind);
        outHeaders.set("X-Ssai-Mode", "classic-stitch");
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
            // API under / is not used; only static files
            if (path.startsWith("/api/") || path.startsWith("/s/") || path.startsWith("/play")) {
                send(ex, 404, "text/plain", "not found");
                return;
            }
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
            applyCorsHeaders(h);
            h.set("Content-Type", ct);
            h.set("Cache-Control", "no-store");
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

    // ---------------- input parsing ----------------

    private static final class SessionInput {
        final String contentUrl;
        final String adUrl;
        final float[] splices;
        final float maxAdDurationSec;

        SessionInput(String contentUrl, String adUrl, float[] splices, float maxAdDurationSec) {
            this.contentUrl = contentUrl;
            this.adUrl = adUrl;
            this.splices = splices;
            this.maxAdDurationSec = maxAdDurationSec;
        }
    }

    private static SessionInput parseSessionInput(String json) {
        String content = jsonStringValue(json, "contentUrl");
        if (content == null || content.isEmpty()) {
            content = jsonStringValue(json, "content");
        }
        String ad = jsonStringValue(json, "adUrl");
        if (ad == null || ad.isEmpty()) {
            ad = jsonStringValue(json, "ad");
        }
        if (content == null || content.isEmpty()) {
            throw new IllegalArgumentException("contentUrl is required");
        }
        if (ad == null || ad.isEmpty()) {
            throw new IllegalArgumentException("adUrl is required");
        }
        requireHttpUrl(content);
        requireHttpUrl(ad);

        float[] splices;
        String arr = jsonArray(json, "splices");
        if (arr != null) {
            splices = parseSplicesArrayLiteral(arr);
        } else {
            String splicesStr = jsonStringValue(json, "splices");
            splices = parseSplices(splicesStr);
        }
        float maxAd = (float) jsonNumber(json, "maxAdDurationSec", Double.NaN);
        if (Double.isNaN(maxAd)) {
            maxAd = (float) jsonNumber(json, "maxAd", Double.NaN);
        }
        if (Double.isNaN(maxAd)) {
            maxAd = DEFAULT_MAX_AD_DURATION_SEC;
        }
        if (maxAd < 0f) {
            throw new IllegalArgumentException("maxAdDurationSec must be >= 0 (0 = no trim)");
        }
        return new SessionInput(content, ad, splices, maxAd);
    }

    private static float parseMaxAdDuration(String raw, float defaultSec) {
        if (raw == null || raw.trim().isEmpty()) {
            return defaultSec;
        }
        try {
            float v = Float.parseFloat(raw.trim());
            if (v < 0f) {
                throw new IllegalArgumentException("maxAdDurationSec must be >= 0 (0 = no trim)");
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid maxAdDurationSec: " + raw);
        }
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

    private static float[] parseSplices(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return new float[0];
        }
        String[] parts = raw.split("[,\\s]+");
        List<Float> list = new ArrayList<Float>();
        for (String p : parts) {
            if (p == null || p.isEmpty()) continue;
            try {
                list.add(Float.parseFloat(p.trim()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid splice point: " + p);
            }
        }
        float[] out = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            out[i] = list.get(i);
        }
        return out;
    }

    private static float[] parseSplicesArrayLiteral(String arrayLiteral) {
        // "[30, 90, 120.5]"
        String inner = arrayLiteral.trim();
        if (inner.startsWith("[")) {
            inner = inner.substring(1);
        }
        if (inner.endsWith("]")) {
            inner = inner.substring(0, inner.length() - 1);
        }
        return parseSplices(inner);
    }

    private static void requireHttpUrl(String url) {
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

    private String sessionToJson(Session s, String base) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"id\":").append(jsonString(s.id)).append(',');
        sb.append("\"contentUrl\":").append(jsonString(s.contentUrl)).append(',');
        sb.append("\"adUrl\":").append(jsonString(s.adUrl)).append(',');
        sb.append("\"splices\":[");
        for (int i = 0; i < s.splices.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(s.splices[i]);
        }
        sb.append("],");
        sb.append("\"maxAdDurationSec\":").append(s.maxAdDurationSec).append(',');
        sb.append("\"manifestUrl\":").append(jsonString(base + "/s/" + s.id + "/manifest")).append(',');
        sb.append("\"playUrl\":").append(jsonString(base + "/s/" + s.id + "/manifest"));
        sb.append('}');
        return sb.toString();
    }

    // ---------------- HTTP helpers (mirrors HlsPlayerServer patterns) ----------------

    private static final class SsaiHttpException extends Exception {
        final int status;

        SsaiHttpException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

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

    private static FetchResult fetchRemote(String target, String rangeHeader) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(target).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(rangeHeader != null && rangeHeader.length() > 0 ? 30000 : 120000);
        conn.setRequestProperty("User-Agent", "open-m3u8-ssai-proxy/1.0");
        conn.setRequestProperty("Accept", "*/*");
        if (rangeHeader != null && rangeHeader.trim().length() > 0) {
            conn.setRequestProperty("Range", rangeHeader.trim());
        }
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

    private static void streamRemote(HttpExchange ex, String target, String rangeHeader)
            throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(target).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(120000);
        conn.setRequestProperty("User-Agent", "open-m3u8-ssai-proxy/1.0");
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
        outHeaders.set("X-Ssai-Mode", "passthrough");
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
        String lower = url.toLowerCase();
        return lower.contains(".m3u8") || lower.contains("mpegurl") || lower.contains("m3u8");
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
        if (n <= 0) {
            return false;
        }
        String head = new String(body, 0, n, StandardCharsets.UTF_8).trim().toLowerCase();
        return head.startsWith("#extm3u") || head.startsWith("#ext");
    }

    private static String firstHeader(Headers headers, String name) {
        if (headers == null) return null;
        List<String> values = headers.get(name);
        if (values == null || values.isEmpty()) {
            values = headers.get(name.toLowerCase());
        }
        if (values == null || values.isEmpty()) return null;
        return values.get(0);
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

    private static String decode(String s) {
        if (s == null) return null;
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private String publicBase(HttpExchange ex) {
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host == null || host.trim().isEmpty()) {
            host = "127.0.0.1:" + port;
        }
        return "http://" + host.trim();
    }

    private static void send(HttpExchange ex, int code, String contentType, String body)
            throws IOException {
        sendCors(ex, code, body.getBytes(StandardCharsets.UTF_8), contentType);
    }

    /**
     * CORS for external players (hls.js demos, other origins).
     * Includes Chrome Private Network Access headers so a public HTTPS page can load
     * manifests from a private WSL/LAN IP (e.g. 172.x / 192.168.x).
     */
    private static void applyCorsHeaders(Headers h) {
        if (h == null) {
            return;
        }
        h.set("Access-Control-Allow-Origin", "*");
        h.set("Access-Control-Allow-Methods", "GET, HEAD, POST, OPTIONS");
        // Broad allow-list: hls.js may send Range, and browsers may preflight extra headers.
        h.set("Access-Control-Allow-Headers",
                "Content-Type, Range, Accept, Origin, X-Requested-With, "
                        + "Access-Control-Request-Method, Access-Control-Request-Headers, "
                        + "Access-Control-Request-Private-Network");
        h.set("Access-Control-Expose-Headers",
                "Content-Length, Content-Range, Accept-Ranges, Content-Type, "
                        + "X-Playlist-Rewritten, X-Proxy-Target, X-Playlist-Kind, "
                        + "X-Rewrite-Engine, X-Ssai-Mode");
        // Chrome Private Network Access (public site → private IP preflight)
        h.set("Access-Control-Allow-Private-Network", "true");
        h.set("Access-Control-Max-Age", "86400");
        // Helpful for timing APIs / debugging cross-origin loads
        h.set("Timing-Allow-Origin", "*");
        h.set("Cross-Origin-Resource-Policy", "cross-origin");
    }

    private static void sendCors(HttpExchange ex, int code, byte[] body, String contentType)
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
        if (lower.endsWith(".m3u8")) return "application/vnd.apple.mpegurl";
        return "application/octet-stream";
    }

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

    private static String jsonStringValue(String json, String key) {
        int idx = indexOfKey(json, key);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return null;
        int i = skipWs(json, colon + 1);
        if (i >= json.length() || json.charAt(i) != '"') return null;
        StringBuilder sb = new StringBuilder();
        i++;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char n = json.charAt(i + 1);
                sb.append(n);
                i += 2;
                continue;
            }
            if (c == '"') break;
            sb.append(c);
            i++;
        }
        return sb.toString();
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
        return json.indexOf("\"" + key + "\"");
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
}
