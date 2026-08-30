package com.iheartradio.m3u8.http.api;

import com.iheartradio.m3u8.http.DemoHttp;
import com.iheartradio.m3u8.http.DemoPlayerServer;
import com.iheartradio.m3u8.http.DemoSession;
import com.iheartradio.m3u8.http.session.SessionRegistry;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/** GET /play */
public final class PlayApi implements HttpHandler {

    private final int port;
    private final SessionRegistry sessions;

    public PlayApi(int port, SessionRegistry sessions) {
        this.port = port;
        this.sessions = sessions;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            DemoHttp.sendCors(ex, 204, new byte[0], "text/plain");
            return;
        }
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            DemoHttp.send(ex, 405, "text/plain", "method not allowed");
            return;
        }
        String q = ex.getRequestURI().getRawQuery();
        String content = DemoHttp.decode(DemoHttp.queryParam(q, "content"));
        String ad = DemoHttp.decode(DemoHttp.queryParam(q, "ad"));
        String splicesRaw = DemoHttp.decode(DemoHttp.queryParam(q, "splices"));
        String format = DemoHttp.decode(DemoHttp.queryParam(q, "format"));
        String strategyRaw = DemoHttp.decode(DemoHttp.queryParam(q, "strategy"));
        String maxAdRaw = DemoHttp.decode(DemoHttp.queryParam(q, "maxAdDurationSec"));
        if (maxAdRaw == null || maxAdRaw.isEmpty()) {
            maxAdRaw = DemoHttp.decode(DemoHttp.queryParam(q, "maxAd"));
        }
        try {
            DemoSession.Strategy strategy = DemoSession.Strategy.fromWire(strategyRaw);
            float[] splices = DemoSession.parseSplices(splicesRaw);
            float maxAd = DemoSession.parseMaxAdDuration(maxAdRaw,
                    DemoPlayerServer.DEFAULT_MAX_AD_DURATION_SEC);
            DemoSession s = sessions.create(DemoSession.fromPlayQuery(
                    sessions.nextId(), strategy, content, ad, splices, maxAd));
            String base = DemoHttp.publicBase(ex, port);
            String manifestUrl = base + "/s/" + s.id + "/manifest";
            if ("json".equalsIgnoreCase(format)) {
                DemoHttp.send(ex, 200, "application/json; charset=utf-8",
                        "{\"ok\":true,\"session\":" + s.toJson(base) + "}");
                return;
            }
            Headers h = ex.getResponseHeaders();
            DemoHttp.applyCorsHeaders(ex);
            h.set("Location", manifestUrl);
            h.set("Cache-Control", "no-store");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        } catch (IllegalArgumentException e) {
            DemoHttp.send(ex, 400, "application/json; charset=utf-8",
                    "{\"error\":" + DemoHttp.jsonString(e.getMessage()) + "}");
        }
    }
}
