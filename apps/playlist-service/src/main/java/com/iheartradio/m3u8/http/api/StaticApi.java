package com.iheartradio.m3u8.http.api;

import com.iheartradio.m3u8.http.DemoHttp;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.File;
import java.io.IOException;

/** GET / — web-client static files. */
public final class StaticApi implements HttpHandler {

    private final File staticRoot;

    public StaticApi(File staticRoot) {
        this.staticRoot = staticRoot;
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
