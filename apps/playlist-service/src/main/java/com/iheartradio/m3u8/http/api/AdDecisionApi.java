package com.iheartradio.m3u8.http.api;

import com.iheartradio.m3u8.http.DemoHttp;
import com.iheartradio.m3u8.http.ads.AdDecision;
import com.iheartradio.m3u8.http.ads.AdInventory;
import com.iheartradio.m3u8.http.ads.MediaFileProbe;
import com.iheartradio.m3u8.http.catalog.CatalogStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** POST /api/ads/decision. Playback does not call this. */
public final class AdDecisionApi implements HttpHandler {

    private final CatalogStore catalog;
    private final File mediaRoot;
    private final AdDecision decision = new AdDecision();

    public AdDecisionApi(CatalogStore catalog, File mediaRoot) {
        this.catalog = catalog;
        this.mediaRoot = mediaRoot;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            DemoHttp.sendCors(ex, 204, new byte[0], "application/json");
            return;
        }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            DemoHttp.send(ex, 405, "application/json; charset=utf-8",
                    "{\"error\":\"method not allowed\"}");
            return;
        }
        String body = new String(DemoHttp.readAll(ex.getRequestBody()), StandardCharsets.UTF_8);
        AdDecision.Request request;
        try {
            request = AdDecision.Request.parse(body);
        } catch (IllegalArgumentException e) {
            DemoHttp.send(ex, 400, "application/json; charset=utf-8",
                    "{\"error\":" + DemoHttp.jsonString(e.getMessage()) + "}");
            return;
        }
        File adsDir = mediaRoot == null ? null : new File(mediaRoot, "ads");
        String json = decision.decide(request, AdInventory.load(catalog, adsDir),
                new MediaFileProbe(mediaRoot)).toJson();
        DemoHttp.send(ex, 200, "application/json; charset=utf-8", json);
    }
}
