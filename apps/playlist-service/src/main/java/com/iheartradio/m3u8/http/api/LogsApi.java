package com.iheartradio.m3u8.http.api;

import com.iheartradio.m3u8.http.DemoHttp;
import com.iheartradio.m3u8.http.DemoLog;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/** GET /api/logs */
public final class LogsApi implements HttpHandler {

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
