package com.iheartradio.m3u8.http.api;

import com.iheartradio.m3u8.http.DemoHttp;
import com.iheartradio.m3u8.http.DemoLog;
import com.iheartradio.m3u8.http.DemoPlaylistPipeline;
import com.iheartradio.m3u8.http.DemoSession;
import com.iheartradio.m3u8.http.session.SessionRegistry;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/** GET /s/{id}/manifest and /s/{id}/proxy */
public final class SessionResourceApi implements HttpHandler {

    private final int port;
    private final SessionRegistry sessions;
    private final DemoPlaylistPipeline pipeline;

    public SessionResourceApi(int port, SessionRegistry sessions, DemoPlaylistPipeline pipeline) {
        this.port = port;
        this.sessions = sessions;
        this.pipeline = pipeline;
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
                    "{\"error\":\"expected /s/{id}/manifest or /s/{id}/proxy\"}");
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
}
