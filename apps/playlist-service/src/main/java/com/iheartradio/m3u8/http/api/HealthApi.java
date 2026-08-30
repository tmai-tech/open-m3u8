package com.iheartradio.m3u8.http.api;

import com.iheartradio.m3u8.http.DemoHttp;
import com.iheartradio.m3u8.http.session.SessionRegistry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/** GET /api/health */
public final class HealthApi implements HttpHandler {

    private final int port;
    private final SessionRegistry sessions;

    public HealthApi(int port, SessionRegistry sessions) {
        this.port = port;
        this.sessions = sessions;
    }

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
        DemoHttp.publicBase(ex, port);
        String pub = DemoHttp.advertisedPublicBase();
        String body = "{\"ok\":true,\"proxy\":true,\"engine\":\""
                + DemoHttp.ENGINE + "\",\"port\":" + port
                + ",\"strategies\":[\"sgai\",\"ssai\"],\"sessions\":"
                + sessions.size()
                + ",\"publicBase\":"
                + (pub == null ? "null" : DemoHttp.jsonString(pub))
                + "}";
        DemoHttp.send(ex, 200, "application/json; charset=utf-8", body);
    }
}
