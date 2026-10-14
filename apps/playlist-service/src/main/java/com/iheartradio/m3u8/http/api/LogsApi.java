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
        String event = DemoHttp.decode(DemoHttp.queryParam(q, "ev"));
        if (event == null || event.length() == 0) {
            event = DemoHttp.decode(DemoHttp.queryParam(q, "event"));
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
        long before = -1L;
        String beforeRaw = DemoHttp.decode(DemoHttp.queryParam(q, "before"));
        if (beforeRaw != null && beforeRaw.length() > 0) {
            try {
                before = Long.parseLong(beforeRaw);
            } catch (NumberFormatException ignored) {
                before = -1L;
            }
        }
        try {
            DemoHttp.send(ex, 200, "application/json; charset=utf-8",
                    DemoLog.pageJson(sid, event, limit, before));
        } catch (Exception e) {
            DemoHttp.sendError(ex, 500, DemoHttp.errorText(e));
        }
    }
}
