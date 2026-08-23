package com.iheartradio.m3u8.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DemoPlayerServerTest {

    private HttpServer origin;
    private int originPort;
    private int demoPort;

    @Before
    public void setUp() throws Exception {
        origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin.setExecutor(Executors.newCachedThreadPool());
        origin.start();
        originPort = origin.getAddress().getPort();

        origin.createContext("/content.m3u8", ex -> write(ex, contentBody(), "application/vnd.apple.mpegurl"));
        origin.createContext("/ad.m3u8", ex -> write(ex, adBody(), "application/vnd.apple.mpegurl"));
        origin.createContext("/master.m3u8", ex -> write(ex, masterBody(), "application/vnd.apple.mpegurl"));

        File staticRoot = Files.createTempDirectory("demo-player").toFile();
        Files.write(new File(staticRoot, "index.html").toPath(),
                "<html>demo</html>".getBytes(StandardCharsets.UTF_8));

        demoPort = freePort();
        DemoPlayerServer server = new DemoPlayerServer(demoPort, staticRoot);
        Thread t = new Thread(() -> {
            try {
                server.start();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, "demo-player-test");
        t.setDaemon(true);
        t.start();
        waitForHealth(demoPort, 15000);
    }

    @After
    public void tearDown() {
        if (origin != null) {
            origin.stop(0);
        }
    }

    @Test
    public void healthListsBothStrategies() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + demoPort + "/api/health").openConnection();
        assertEquals(200, c.getResponseCode());
        String body = readFully(c.getInputStream());
        assertTrue(body.contains("\"sgai\""));
        assertTrue(body.contains("\"ssai\""));
        assertTrue(body.contains(DemoHttp.ENGINE));
    }

    @Test
    public void originRequiresHttpUrl() throws Exception {
        HttpURLConnection missing = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + demoPort + "/api/origin").openConnection();
        assertEquals(400, missing.getResponseCode());
        HttpURLConnection bad = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + demoPort + "/api/origin?url=file:///tmp/x").openConnection();
        assertEquals(400, bad.getResponseCode());
    }

    @Test
    public void sessionWithoutAdsProxiesContent() throws Exception {
        String json = "{\"strategy\":\"ssai\","
                + "\"contentUrl\":\"http://127.0.0.1:" + originPort + "/content.m3u8\"}";
        String playlist = createAndFetchManifest(json);
        assertFalse(playlist.contains("#EXT-X-CUE-OUT"));
        assertTrue(playlist.contains("#EXTINF"));
    }

    @Test
    public void sgaiSessionInjectsDateRange() throws Exception {
        String json = "{\"strategy\":\"sgai\","
                + "\"contentUrl\":\"http://127.0.0.1:" + originPort + "/content.m3u8\","
                + "\"adUrl\":\"http://127.0.0.1:" + originPort + "/ad.m3u8\","
                + "\"breaks\":[{\"offsetSec\":10,\"durationSec\":15}]}";
        String playlist = createAndFetchManifest(json);
        assertTrue(playlist.contains("#EXT-X-DATERANGE"));
        assertTrue(playlist.contains("com.apple.hls.interstitial"));
        assertFalse(playlist.contains("#EXT-X-CUE-OUT"));
        assertTrue(playlist.contains("/s/"));
        assertTrue(playlist.contains("/proxy?url="));
    }

    @Test
    public void ssaiSessionStitchesCues() throws Exception {
        String json = "{\"strategy\":\"ssai\","
                + "\"contentUrl\":\"http://127.0.0.1:" + originPort + "/content.m3u8\","
                + "\"adUrl\":\"http://127.0.0.1:" + originPort + "/ad.m3u8\","
                + "\"splices\":[10]}";
        String playlist = createAndFetchManifest(json);
        assertTrue(playlist.contains("#EXT-X-CUE-OUT"));
        assertTrue(playlist.contains("#EXT-X-CUE-IN"));
        assertFalse(playlist.contains("com.apple.hls.interstitial"));
    }

    @Test
    public void playStrategySgaiRedirects() throws Exception {
        String content = enc("http://127.0.0.1:" + originPort + "/content.m3u8");
        String ad = enc("http://127.0.0.1:" + originPort + "/ad.m3u8");
        HttpURLConnection c = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + demoPort + "/play?strategy=sgai&content="
                        + content + "&ad=" + ad + "&splices=10").openConnection();
        c.setInstanceFollowRedirects(false);
        assertEquals(302, c.getResponseCode());
        String loc = c.getHeaderField("Location");
        assertTrue(loc != null && loc.contains("/s/") && loc.endsWith("/manifest"));
    }

    @Test
    public void masterEntryDoesNotStitch() throws Exception {
        String json = "{\"strategy\":\"ssai\","
                + "\"contentUrl\":\"http://127.0.0.1:" + originPort + "/master.m3u8\","
                + "\"adUrl\":\"http://127.0.0.1:" + originPort + "/ad.m3u8\","
                + "\"splices\":[0]}";
        String playlist = createAndFetchManifest(json);
        assertTrue(playlist.contains("#EXT-X-STREAM-INF"));
        assertFalse(playlist.contains("#EXT-X-CUE-OUT"));
        assertTrue(playlist.contains("/proxy?url="));
    }

    private String createAndFetchManifest(String json) throws Exception {
        HttpURLConnection post = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + demoPort + "/api/session").openConnection();
        post.setRequestMethod("POST");
        post.setDoOutput(true);
        post.setRequestProperty("Content-Type", "application/json");
        post.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(200, post.getResponseCode());
        String sessionBody = readFully(post.getInputStream());
        String manifestUrl = extract(sessionBody, "manifestUrl");
        HttpURLConnection get = (HttpURLConnection) new URL(manifestUrl).openConnection();
        assertEquals(200, get.getResponseCode());
        return readFully(get.getInputStream());
    }

    private String contentBody() {
        return "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:0\n"
                + "#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:10.0,\n"
                + "http://127.0.0.1:" + originPort + "/seg/c0.ts\n#EXTINF:10.0,\n"
                + "http://127.0.0.1:" + originPort + "/seg/c1.ts\n#EXT-X-ENDLIST\n";
    }

    private String adBody() {
        return "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:0\n"
                + "#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:5.0,\n"
                + "http://127.0.0.1:" + originPort + "/ads/a0.ts\n#EXT-X-ENDLIST\n";
    }

    private String masterBody() {
        return "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-STREAM-INF:BANDWIDTH=100000\n"
                + "http://127.0.0.1:" + originPort + "/content.m3u8\n";
    }

    private static void write(com.sun.net.httpserver.HttpExchange ex, String body, String ct)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", ct);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void waitForHealth(int port, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Exception last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(
                        "http://127.0.0.1:" + port + "/api/health").openConnection();
                c.setConnectTimeout(500);
                c.setReadTimeout(500);
                if (c.getResponseCode() == 200) {
                    return;
                }
            } catch (Exception e) {
                last = e;
                Thread.sleep(50);
            }
        }
        throw new IllegalStateException("demo server did not become healthy", last);
    }

    private static int freePort() throws IOException {
        java.net.ServerSocket ss = new java.net.ServerSocket(0);
        int p = ss.getLocalPort();
        ss.close();
        return p;
    }

    private static String readFully(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) >= 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String extract(String json, String key) {
        String needle = "\"" + key + "\":\"";
        int i = json.indexOf(needle);
        if (i < 0) {
            return null;
        }
        int start = i + needle.length();
        return json.substring(start, json.indexOf('"', start)).replace("\\/", "/");
    }

    private static String enc(String s) throws Exception {
        return java.net.URLEncoder.encode(s, "UTF-8");
    }
}
