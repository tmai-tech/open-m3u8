package com.iheartradio.m3u8.http.api;

import com.iheartradio.m3u8.http.DemoHttp;
import com.iheartradio.m3u8.http.DemoLog;
import com.iheartradio.m3u8.http.DemoMpdPipeline;
import com.iheartradio.m3u8.http.DemoPlaylistPipeline;
import com.iheartradio.m3u8.http.DemoSession;
import com.iheartradio.m3u8.http.session.SessionRegistry;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/** GET /s/{id}/manifest, /s/{id}/proxy, and /s/{id}/assets/{breakId}.json */
public final class SessionResourceApi implements HttpHandler {

    private final int port;
    private final SessionRegistry sessions;
    private final DemoPlaylistPipeline pipeline;
    private final DemoMpdPipeline mpdPipeline;

    public SessionResourceApi(int port, SessionRegistry sessions, DemoPlaylistPipeline pipeline) {
        this(port, sessions, pipeline, new DemoMpdPipeline());
    }

    public SessionResourceApi(int port, SessionRegistry sessions, DemoPlaylistPipeline pipeline,
                              DemoMpdPipeline mpdPipeline) {
        this.port = port;
        this.sessions = sessions;
        this.pipeline = pipeline;
        this.mpdPipeline = mpdPipeline != null ? mpdPipeline : new DemoMpdPipeline();
    }

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
                    "{\"error\":\"expected /s/{id}/manifest, /s/{id}/proxy, or /s/{id}/assets/{breakId}.json\"}");
            return;
        }
        String id = rest.substring(0, slash);
        String action = rest.substring(slash + 1);
        DemoSession session = sessions.require(id);
        if (session == null) {
            DemoHttp.send(ex, 404, "application/json; charset=utf-8",
                    "{\"error\":\"session not found or expired\"}");
            return;
        }

        if ("manifest".equals(action) || action.startsWith("manifest")) {
            if (isDashSession(session, action)) {
                handleMpd(ex, session, dashContentUrl(session));
                return;
            }
            handlePlaylist(ex, session, session.contentUrl);
            return;
        }
        if ("proxy".equals(action) || action.startsWith("proxy")) {
            handleProxy(ex, session);
            return;
        }
        if (action.startsWith("alt/")) {
            handleAlt(ex, session, action.substring("alt/".length()));
            return;
        }
        if (action.startsWith("text/")) {
            handleText(ex, session, action.substring("text/".length()));
            return;
        }
        if (action.startsWith("assets/")) {
            handleAssets(ex, session, action.substring("assets/".length()));
            return;
        }
        DemoHttp.send(ex, 404, "application/json; charset=utf-8",
                "{\"error\":\"unknown session path; use manifest, proxy, or assets/{breakId}.json\"}");
    }

    private void handleAlt(HttpExchange ex, DemoSession session, String file) throws IOException {
        int q = file.indexOf('?');
        if (q >= 0) {
            file = file.substring(0, q);
        }
        String body = session.dashAlt(file);
        if (body == null) {
            DemoHttp.send(ex, 404, "application/json; charset=utf-8",
                    "{\"error\":\"alternate MPD not found\"}");
            return;
        }
        DemoHttp.send(ex, 200, "application/dash+xml; charset=utf-8", body);
    }

    private void handleText(HttpExchange ex, DemoSession session, String file) throws IOException {
        int q = file.indexOf('?');
        if (q >= 0) {
            file = file.substring(0, q);
        }
        String body = session.dashText(file);
        if (body == null) {
            DemoHttp.send(ex, 404, "text/plain; charset=utf-8", "subtitle slice not found");
            return;
        }
        DemoHttp.send(ex, 200, "text/vtt; charset=utf-8", body);
    }

    private void handleAssets(HttpExchange ex, DemoSession session, String rest) throws IOException {
        String breakId = rest == null ? "" : rest;
        int q = breakId.indexOf('?');
        if (q >= 0) {
            breakId = breakId.substring(0, q);
        }
        if (breakId.endsWith(".json")) {
            breakId = breakId.substring(0, breakId.length() - 5);
        }
        try {
            breakId = java.net.URLDecoder.decode(breakId, "UTF-8");
        } catch (Exception ignored) {
            // keep raw
        }
        String rawQuery = ex.getRequestURI().getRawQuery();
        double startOffset = DemoHttp.hlsStartOffset(rawQuery);
        String primaryId = DemoHttp.hlsPrimaryId(rawQuery);
        String json = pipeline.assetListJson(session, breakId, DemoHttp.publicBase(ex, port),
                startOffset, primaryId);
        if (json == null) {
            DemoHttp.send(ex, 404, "application/json; charset=utf-8",
                    "{\"error\":\"break not found\"}");
            return;
        }
        DemoHttp.send(ex, 200, "application/json; charset=utf-8", json);
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

        if (DemoPlaylistPipeline.isWebVttUri(target)) {
            handleVtt(ex, target);
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

    private void handleVtt(HttpExchange ex, String target) throws IOException {
        float shift = DemoPlaylistPipeline.parseVttShift(target);
        String origin = DemoPlaylistPipeline.stripVttShift(target);
        try {
            DemoHttp.FetchResult remote = DemoHttp.fetchRemote(origin, null);
            if (remote.status >= 400) {
                DemoHttp.send(ex, remote.status > 0 ? remote.status : 502, "text/plain",
                        "vtt origin HTTP " + remote.status);
                return;
            }
            byte[] body = remote.body != null ? remote.body : new byte[0];
            String text = new String(body, java.nio.charset.StandardCharsets.UTF_8);
            if (shift != 0f && text.regionMatches(true, 0, "WEBVTT", 0, 6)) {
                text = DemoPlaylistPipeline.shiftWebVttMpegts(text, shift);
                body = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
            DemoHttp.sendCors(ex, 200, body, "text/vtt; charset=utf-8");
        } catch (Exception e) {
            DemoHttp.send(ex, 502, "application/json; charset=utf-8",
                    "{\"error\":" + DemoHttp.jsonString("vtt fetch failed: " + e.getMessage())
                            + ",\"url\":" + DemoHttp.jsonString(origin) + "}");
        }
    }

    static boolean isDashSession(DemoSession session, String action) {
        if (action != null && action.toLowerCase().contains(".mpd")) {
            return true;
        }
        String url = session != null ? session.contentUrl : null;
        return url != null && url.toLowerCase().contains(".mpd");
    }

    static String dashContentUrl(DemoSession session) {
        String url = session.contentUrl;
        if (url != null && url.toLowerCase().contains(".mpd")) {
            return url;
        }
        return siblingMpd(url);
    }

    static String siblingMpd(String url) {
        if (url == null) {
            return url;
        }
        if (url.toLowerCase().contains(".mpd")) {
            return url;
        }
        int q = url.indexOf('?');
        String base = q >= 0 ? url.substring(0, q) : url;
        int slash = base.lastIndexOf('/');
        String dir = slash >= 0 ? base.substring(0, slash + 1) : "";
        return dir + "manifest.mpd";
    }

    private void handleMpd(HttpExchange ex, DemoSession session, String mpdUrl)
            throws IOException {
        try {
            DemoMpdPipeline.Result result = mpdPipeline.process(
                    session, mpdUrl, DemoHttp.publicBase(ex, port));
            DemoHttp.writePlaylistResponse(ex, result.body, mpdUrl, "mpd",
                    session.strategy.wireName());
        } catch (DemoHttp.HttpException e) {
            DemoLog.event("error")
                    .sid(session.id)
                    .put("url", mpdUrl)
                    .put("status", e.status)
                    .put("msg", e.getMessage())
                    .write();
            DemoHttp.send(ex, e.status, "application/json; charset=utf-8",
                    "{\"error\":" + DemoHttp.jsonString(e.getMessage())
                            + ",\"url\":" + DemoHttp.jsonString(mpdUrl) + "}");
        } catch (Exception e) {
            e.printStackTrace();
            DemoLog.event("error")
                    .sid(session.id)
                    .put("url", mpdUrl)
                    .put("status", 502)
                    .put("msg", e.getMessage())
                    .write();
            DemoHttp.send(ex, 502, "application/json; charset=utf-8",
                    "{\"error\":" + DemoHttp.jsonString("mpd rewrite failed: " + e.getMessage())
                            + ",\"url\":" + DemoHttp.jsonString(mpdUrl) + "}");
        }
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
}
