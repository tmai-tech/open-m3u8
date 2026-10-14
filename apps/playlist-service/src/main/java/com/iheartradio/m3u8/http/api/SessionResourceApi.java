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
        if (sessions == null) {
            DemoHttp.sendError(ex, 500, "sessions unavailable");
            return;
        }
        if (ex.getRequestURI() == null) {
            DemoHttp.sendError(ex, 400, "missing request uri");
            return;
        }

        String path = ex.getRequestURI().getPath();
        if (path == null) {
            path = "";
        }
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
        if ("patch.mpp".equals(action)) {
            handleAdPatch(ex, session);
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
        if (pipeline == null) {
            DemoHttp.sendError(ex, 500, "playlist pipeline unavailable");
            return;
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
                    DemoHttp.sendError(ex, 502, "fetch failed: " + DemoHttp.errorText(e));
                } catch (Exception ignored) {
                    // response may already be committed
                }
            }
            return;
        }
        String playlistTarget = target;
        if (session.playlistDelta) {
            playlistTarget = DemoHttp.forwardHlsSkip(target, ex.getRequestURI().getRawQuery());
        }
        handlePlaylist(ex, session, playlistTarget);
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
            DemoHttp.sendError(ex, 502, "vtt fetch failed: " + DemoHttp.errorText(e));
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

    private void handleAdPatch(HttpExchange ex, DemoSession session) throws IOException {
        String query = ex.getRequestURI().getRawQuery();
        String publishTime = null;
        if (query != null) {
            for (String part : query.split("&")) {
                if (part.startsWith("publishTime=")) {
                    publishTime = part.substring("publishTime=".length());
                }
            }
        }
        if (publishTime == null || publishTime.length() == 0) {
            DemoHttp.send(ex, 400, "text/plain", "publishTime required");
            return;
        }
        if (mpdPipeline == null) {
            DemoHttp.sendError(ex, 500, "mpd pipeline unavailable");
            return;
        }
        try {
            byte[] body = mpdPipeline.adPatch(session, publishTime);
            if (body == null) {
                DemoHttp.send(ex, 502, "text/plain", "empty patch");
                return;
            }
            if ("HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
                body = new byte[0];
            }
            DemoHttp.sendCors(ex, 200, body, "application/dash-patch+xml");
        } catch (DemoHttp.HttpException e) {
            DemoHttp.send(ex, e.status, "text/plain", DemoHttp.errorText(e));
        } catch (Exception e) {
            DemoHttp.send(ex, 502, "text/plain", "patch failed: " + DemoHttp.errorText(e));
        }
    }

    private void handleMpd(HttpExchange ex, DemoSession session, String mpdUrl)
            throws IOException {
        if (mpdPipeline == null) {
            DemoHttp.sendError(ex, 500, "mpd pipeline unavailable");
            return;
        }
        if (mpdUrl == null || mpdUrl.length() == 0) {
            DemoHttp.sendError(ex, 400, "mpd url is required");
            return;
        }
        try {
            DemoMpdPipeline.Result result = mpdPipeline.process(
                    session, mpdUrl, DemoHttp.publicBase(ex, port));
            if (result == null || result.body == null) {
                DemoHttp.sendError(ex, 502, "empty mpd");
                return;
            }
            DemoHttp.writePlaylistResponse(ex, result.body, mpdUrl, "mpd", strategyName(session));
        } catch (DemoHttp.HttpException e) {
            logPlayError(session, mpdUrl, e.status, DemoHttp.errorText(e));
            DemoHttp.sendError(ex, e.status, DemoHttp.errorText(e));
        } catch (Exception e) {
            logPlayError(session, mpdUrl, 502, DemoHttp.errorText(e));
            DemoHttp.sendError(ex, 502, "mpd rewrite failed: " + DemoHttp.errorText(e));
        }
    }

    private void handlePlaylist(HttpExchange ex, DemoSession session, String playlistUrl)
            throws IOException {
        if (pipeline == null) {
            DemoHttp.sendError(ex, 500, "playlist pipeline unavailable");
            return;
        }
        if (playlistUrl == null || playlistUrl.length() == 0) {
            DemoHttp.sendError(ex, 400, "playlist url is required");
            return;
        }
        try {
            DemoPlaylistPipeline.Result result = pipeline.process(
                    session, playlistUrl, DemoHttp.publicBase(ex, port));
            if (result == null || result.body == null) {
                DemoHttp.sendError(ex, 502, "empty playlist");
                return;
            }
            if (result.fallback) {
                Headers h = ex.getResponseHeaders();
                DemoHttp.applyCorsHeaders(ex);
                h.set("X-Rewrite-Fallback", "uri-only");
            }
            DemoHttp.writePlaylistResponse(ex, result.body, playlistUrl, result.kind,
                    strategyName(session));
        } catch (DemoHttp.HttpException e) {
            logPlayError(session, playlistUrl, e.status, DemoHttp.errorText(e));
            DemoHttp.sendError(ex, e.status, DemoHttp.errorText(e));
        } catch (Exception e) {
            logPlayError(session, playlistUrl, 502, DemoHttp.errorText(e));
            DemoHttp.sendError(ex, 502, "rewrite failed: " + DemoHttp.errorText(e));
        }
    }

    private static String strategyName(DemoSession session) {
        if (session == null || session.strategy == null) {
            return "";
        }
        return session.strategy.wireName();
    }

    private static void logPlayError(DemoSession session, String url, int status, String msg) {
        DemoLog.Event ev = DemoLog.event("error")
                .sid(session == null ? null : session.id)
                .put("url", url)
                .put("status", status)
                .put("msg", msg);
        if (session != null) {
            ev.play(session);
        }
        ev.write();
    }
}
