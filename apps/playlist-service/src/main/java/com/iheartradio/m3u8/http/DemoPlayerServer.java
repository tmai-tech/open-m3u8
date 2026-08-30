package com.iheartradio.m3u8.http;

import com.iheartradio.m3u8.http.api.CatalogApi;
import com.iheartradio.m3u8.http.api.HealthApi;
import com.iheartradio.m3u8.http.api.IngestApi;
import com.iheartradio.m3u8.http.api.LogsApi;
import com.iheartradio.m3u8.http.api.MediaApi;
import com.iheartradio.m3u8.http.api.OriginApi;
import com.iheartradio.m3u8.http.api.PlayApi;
import com.iheartradio.m3u8.http.api.SessionApi;
import com.iheartradio.m3u8.http.api.SessionResourceApi;
import com.iheartradio.m3u8.http.api.StaticApi;
import com.iheartradio.m3u8.http.catalog.CatalogStore;
import com.iheartradio.m3u8.http.ingest.IngestService;
import com.iheartradio.m3u8.http.session.SessionRegistry;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * Unified HLS demo: one process, one session store, SSAI or SGAI chosen by the client.
 *
 * <pre>
 *   ./gradlew runDemo
 *   open http://127.0.0.1:8765/
 * </pre>
 */
public final class DemoPlayerServer {

    public static final int DEFAULT_PORT = 8765;
    public static final float DEFAULT_MAX_AD_DURATION_SEC = 30f;
    private static final String BIND_HOST = "0.0.0.0";
    private static final long SESSION_TTL_MS = 2L * 60L * 60L * 1000L;

    private final int port;
    private final File staticRoot;
    private final File mediaRoot;
    private final CatalogStore catalog;
    private final IngestService ingest;
    private final SessionRegistry sessions;
    private final DemoPlaylistPipeline pipeline;

    public DemoPlayerServer(int port, File staticRoot) {
        this(port, staticRoot, locateMediaRoot());
    }

    public DemoPlayerServer(int port, File staticRoot, File mediaRoot) {
        this.port = port;
        this.staticRoot = staticRoot;
        this.mediaRoot = mediaRoot;
        this.catalog = new CatalogStore(mediaRoot);
        this.ingest = new IngestService(catalog);
        this.sessions = new SessionRegistry(SESSION_TTL_MS);
        this.pipeline = new DemoPlaylistPipeline();
    }

    public static void main(String[] args) throws Exception {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }
        DemoPlayerServer server = new DemoPlayerServer(port, locateStaticRoot());
        server.start();
    }

    static File locateStaticRoot() {
        File[] candidates = new File[] {
                new File("apps/web-client"),
                new File(System.getProperty("user.dir", "."), "apps/web-client"),
                new File("open-m3u8/apps/web-client"),
                new File("demo"),
                new File(System.getProperty("user.dir", "."), "demo"),
                new File("hls-player"),
                new File(System.getProperty("user.dir", "."), "hls-player"),
                new File("open-m3u8/demo"),
                new File("ssai-player"),
        };
        try {
            java.net.URL loc = DemoPlayerServer.class.getProtectionDomain().getCodeSource().getLocation();
            if (loc != null) {
                File code = new File(loc.toURI());
                File dir = code.isFile() ? code.getParentFile() : code;
                for (int i = 0; i < 8 && dir != null; i++) {
                    File web = new File(dir, "apps/web-client");
                    if (web.isDirectory() && new File(web, "index.html").isFile()) {
                        return web.getAbsoluteFile();
                    }
                    File demo = new File(dir, "demo");
                    if (demo.isDirectory() && new File(demo, "index.html").isFile()) {
                        return demo.getAbsoluteFile();
                    }
                    File hls = new File(dir, "hls-player");
                    if (hls.isDirectory() && new File(hls, "index.html").isFile()) {
                        return hls.getAbsoluteFile();
                    }
                    dir = dir.getParentFile();
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        for (File f : candidates) {
            if (f != null && f.isDirectory() && new File(f, "index.html").isFile()) {
                return f.getAbsoluteFile();
            }
        }
        return new File("apps/web-client").getAbsoluteFile();
    }

    static File locateMediaRoot() {
        File[] candidates = new File[] {
                new File("media"),
                new File(System.getProperty("user.dir", "."), "media"),
                new File("open-m3u8/media"),
        };
        for (File f : candidates) {
            if (f != null && f.isDirectory()) {
                return f.getAbsoluteFile();
            }
        }
        return new File("media").getAbsoluteFile();
    }

    public void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(BIND_HOST, port), 0);
        server.createContext("/", new StaticApi(staticRoot));
        server.createContext("/api/health", new HealthApi(port, sessions));
        server.createContext("/api/origin", new OriginApi());
        server.createContext("/api/session", new SessionApi(port, sessions));
        server.createContext("/api/logs", new LogsApi());
        server.createContext("/api/catalog", new CatalogApi(catalog));
        server.createContext("/api/ingest", new IngestApi(ingest));
        server.createContext("/play", new PlayApi(port, sessions));
        server.createContext("/s/", new SessionResourceApi(port, sessions, pipeline));
        server.createContext("/media/", new MediaApi(mediaRoot));
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();
        DemoHttp.startPublicBaseDiscovery();
        String https = DemoHttp.advertisedPublicBase();

        System.out.println("HLS demo (open-m3u8 SSAI / SGAI)");
        System.out.println("  Bind:     " + BIND_HOST + ":" + port);
        System.out.println("  UI:       http://127.0.0.1:" + port + "/");
        System.out.println("  Uploads:  http://127.0.0.1:" + port + "/uploads.html");
        System.out.println("  HTTPS:    " + (https != null ? https : "(waiting for cloudflared)"));
        System.out.println("  Session:  POST http://127.0.0.1:" + port + "/api/session");
        System.out.println("  Play:     http://127.0.0.1:" + port
                + "/play?strategy=ssai&content=<m3u8>&ad=<m3u8>&splices=30,90");
        System.out.println("  Manifest: http://127.0.0.1:" + port + "/s/{id}/manifest");
        System.out.println("  Logs:     http://127.0.0.1:" + port + "/api/logs");
        System.out.println("  Catalog:  GET  http://127.0.0.1:" + port + "/api/catalog");
        System.out.println("  Ingest:   POST http://127.0.0.1:" + port + "/api/ingest");
        System.out.println("  Engine:   DemoPlaylistPipeline (injectMediaTags | stitch)");
        System.out.println("  Static:   " + staticRoot.getAbsolutePath());
        System.out.println("  Media:    " + mediaRoot.getAbsolutePath());
        System.out.println("  Packager: start separately (ffmpeg on PATH):");
        System.out.println("            java -cp … com.iheartradio.m3u8.http.DemoPackager");
        System.out.println("            or: python3 apps/playlist-service/scripts/demo_packager.py");
        System.out.println("  Log file: " + DemoLog.logFile().getAbsolutePath());
    }
}
