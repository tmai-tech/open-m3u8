package com.iheartradio.m3u8.http.api;

import com.iheartradio.m3u8.http.DemoHttp;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** GET /api/origin?url= — same-origin fetch of a remote playlist/segment. Demo only. */
public final class OriginApi implements HttpHandler {

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
