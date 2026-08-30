package com.iheartradio.m3u8.http.api;

import com.iheartradio.m3u8.http.DemoHttp;
import com.iheartradio.m3u8.http.DemoJobStatus;
import com.iheartradio.m3u8.http.DemoLog;
import com.iheartradio.m3u8.http.catalog.CatalogStore;
import com.iheartradio.m3u8.http.catalog.Title;
import com.iheartradio.m3u8.http.ingest.IngestService;
import com.iheartradio.m3u8.http.ingest.JobLog;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.Collections;

/** GET / POST / DELETE /api/ingest */
public final class IngestApi implements HttpHandler {

    private final IngestService ingest;

    public IngestApi(IngestService ingest) {
        this.ingest = ingest;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            DemoHttp.sendCors(ex, 204, new byte[0], "application/json");
            return;
        }
        if ("GET".equalsIgnoreCase(ex.getRequestMethod())
                || "HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJob(ex);
            return;
        }
        if ("DELETE".equalsIgnoreCase(ex.getRequestMethod())) {
            deleteJob(ex);
            return;
        }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            DemoHttp.send(ex, 405, "text/plain", "method not allowed");
            return;
        }
        try {
            IngestService.Result result = ingest.accept(ex);
            Title t = result.title;
            boolean dup = t != null && t.status == DemoJobStatus.DUPLICATE;
            DemoLog.event("ingest")
                    .put("id", t != null ? t.id : null)
                    .put("status", t != null && t.status != null
                            ? t.status.wire : DemoJobStatus.QUEUED.wire)
                    .put("bytes", result.inboxFile != null ? result.inboxFile.length() : 0)
                    .write();
            DemoHttp.send(ex, dup ? 409 : 202, "application/json; charset=utf-8",
                    CatalogStore.write(Collections.singletonList(t)));
        } catch (IllegalArgumentException e) {
            DemoHttp.send(ex, 400, "application/json; charset=utf-8",
                    "{\"error\":" + DemoHttp.jsonString(e.getMessage()) + "}");
        } catch (Exception e) {
            e.printStackTrace();
            DemoHttp.send(ex, 500, "application/json; charset=utf-8",
                    "{\"error\":" + DemoHttp.jsonString(e.getMessage()) + "}");
        }
    }

    private void sendJob(HttpExchange ex) throws IOException {
        String id = DemoHttp.decode(DemoHttp.queryParam(ex.getRequestURI().getRawQuery(), "id"));
        if (id == null || id.length() == 0) {
            DemoHttp.send(ex, 400, "application/json; charset=utf-8",
                    "{\"error\":\"id is required\"}");
            return;
        }
        if (!JobLog.validId(id)) {
            DemoHttp.send(ex, 400, "application/json; charset=utf-8",
                    "{\"error\":\"invalid id\"}");
            return;
        }
        try {
            Title t = ingest.catalog().find(id);
            String log = ingest.logs().read(id);
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            if (t != null) {
                sb.append("\"id\":").append(DemoHttp.jsonString(t.id));
                sb.append(",\"title\":").append(DemoHttp.jsonString(t.title));
                sb.append(",\"status\":").append(DemoHttp.jsonString(
                        t.status != null ? t.status.wire : DemoJobStatus.READY.wire));
                sb.append(",\"sub\":").append(DemoHttp.jsonString(t.sub));
                sb.append(",\"url\":").append(DemoHttp.jsonString(t.url));
                sb.append(",\"error\":").append(DemoHttp.jsonString(t.error));
            } else {
                sb.append("\"id\":").append(DemoHttp.jsonString(id));
                sb.append(",\"status\":\"unknown\"");
            }
            sb.append(",\"log\":").append(DemoHttp.jsonString(log));
            sb.append('}');
            DemoHttp.send(ex, 200, "application/json; charset=utf-8", sb.toString());
        } catch (Exception e) {
            DemoHttp.send(ex, 500, "application/json; charset=utf-8",
                    "{\"error\":" + DemoHttp.jsonString(e.getMessage()) + "}");
        }
    }

    private void deleteJob(HttpExchange ex) throws IOException {
        String id = DemoHttp.decode(DemoHttp.queryParam(ex.getRequestURI().getRawQuery(), "id"));
        if (id == null || id.length() == 0) {
            DemoHttp.send(ex, 400, "application/json; charset=utf-8",
                    "{\"error\":\"id is required\"}");
            return;
        }
        try {
            Title t = ingest.delete(id);
            DemoLog.event("ingest")
                    .put("id", id)
                    .put("status", "deleted")
                    .write();
            DemoHttp.send(ex, 200, "application/json; charset=utf-8",
                    "{\"id\":" + DemoHttp.jsonString(id)
                            + ",\"deleted\":true"
                            + ",\"was\":" + DemoHttp.jsonString(
                            t != null && t.status != null ? t.status.wire : null)
                            + "}");
        } catch (IngestService.NotFoundException e) {
            DemoHttp.send(ex, 404, "application/json; charset=utf-8",
                    "{\"error\":" + DemoHttp.jsonString(e.getMessage()) + "}");
        } catch (IngestService.DeleteBlockedException e) {
            StringBuilder sb = new StringBuilder("{\"error\":");
            sb.append(DemoHttp.jsonString(e.getMessage()));
            sb.append(",\"usedBy\":[");
            for (int i = 0; i < e.usedBy.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(DemoHttp.jsonString(e.usedBy.get(i)));
            }
            sb.append("]}");
            DemoHttp.send(ex, 409, "application/json; charset=utf-8", sb.toString());
        } catch (IllegalArgumentException e) {
            DemoHttp.send(ex, 400, "application/json; charset=utf-8",
                    "{\"error\":" + DemoHttp.jsonString(e.getMessage()) + "}");
        } catch (Exception e) {
            e.printStackTrace();
            DemoHttp.send(ex, 500, "application/json; charset=utf-8",
                    "{\"error\":" + DemoHttp.jsonString(e.getMessage()) + "}");
        }
    }
}
