package com.iheartradio.m3u8.http.api;

import com.iheartradio.m3u8.http.DemoHttp;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * GET /patch/** → the livesim2 host. Shaka resolves a path-absolute
 * {@code PatchLocation} against the session manifest URL, so a later patch
 * that writes {@code /patch/...} again would 404 on this host.
 */
public final class PatchProxyApi implements HttpHandler {

    static final String UPSTREAM = "https://livesim2.dashif.org";

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            DemoHttp.sendCors(ex, 204, new byte[0], "application/dash-patch+xml");
            return;
        }
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())
                && !"HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
            DemoHttp.send(ex, 405, "text/plain", "method not allowed");
            return;
        }
        String path = ex.getRequestURI() == null ? null : ex.getRequestURI().getRawPath();
        String query = ex.getRequestURI() == null ? null : ex.getRequestURI().getRawQuery();
        String target = UPSTREAM + (path == null ? "/patch/" : path)
                + (query == null || query.length() == 0 ? "" : "?" + query);
        try {
            DemoHttp.FetchResult remote = DemoHttp.fetchRemote(target, null);
            byte[] body = remote == null || remote.body == null ? new byte[0] : remote.body;
            int status = remote == null ? 502 : remote.status;
            String type = remote == null || remote.contentType == null
                    ? "application/dash-patch+xml" : remote.contentType;
            if (status < 400 && body.length > 0 && body.length < 1_000_000) {
                String text = new String(body, StandardCharsets.UTF_8);
                String rewritten = text.replace(">/patch/", ">" + UPSTREAM + "/patch/");
                if (!rewritten.equals(text)) {
                    body = rewritten.getBytes(StandardCharsets.UTF_8);
                }
            }
            if ("HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
                body = new byte[0];
            }
            DemoHttp.sendCors(ex, status > 0 ? status : 502, body, type);
        } catch (Exception e) {
            DemoHttp.sendError(ex, 502, "patch fetch failed: " + DemoHttp.errorText(e));
        }
    }
}
