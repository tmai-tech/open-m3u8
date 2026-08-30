package com.iheartradio.m3u8.http.api;

import com.iheartradio.m3u8.http.DemoHttp;
import com.iheartradio.m3u8.http.catalog.CatalogStore;
import com.iheartradio.m3u8.http.catalog.Title;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.List;

/** GET /api/catalog */
public final class CatalogApi implements HttpHandler {

    private final CatalogStore catalog;

    public CatalogApi(CatalogStore catalog) {
        this.catalog = catalog;
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
        try {
            List<Title> titles = catalog.loadOrDiscover();
            DemoHttp.send(ex, 200, "application/json; charset=utf-8", CatalogStore.write(titles));
        } catch (Exception e) {
            DemoHttp.send(ex, 500, "application/json; charset=utf-8",
                    "{\"error\":" + DemoHttp.jsonString(e.getMessage()) + "}");
        }
    }
}
