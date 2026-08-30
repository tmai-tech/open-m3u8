package com.iheartradio.m3u8.http.api;

import com.iheartradio.m3u8.http.DemoHttp;
import com.iheartradio.m3u8.http.DemoLog;
import com.iheartradio.m3u8.http.DemoSession;
import com.iheartradio.m3u8.http.session.SessionRegistry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** GET / POST /api/session */
public final class SessionApi implements HttpHandler {

    private final int port;
    private final SessionRegistry sessions;

    public SessionApi(int port, SessionRegistry sessions) {
        this.port = port;
        this.sessions = sessions;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        if ("OPTIONS".equalsIgnoreCase(method)) {
            DemoHttp.sendCors(ex, 204, new byte[0], "application/json");
            return;
        }
        if ("GET".equalsIgnoreCase(method)) {
            String id = DemoHttp.decode(DemoHttp.queryParam(ex.getRequestURI().getRawQuery(), "id"));
            DemoSession s = sessions.require(id);
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
                DemoSession s = sessions.createFromJson(json);
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
