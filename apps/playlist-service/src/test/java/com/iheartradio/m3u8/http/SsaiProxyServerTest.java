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
import static org.junit.Assert.assertTrue;

/**
 * End-to-end: origin fixtures + SsaiProxyServer → stitched playable media playlist.
 */
public class SsaiProxyServerTest {

    private HttpServer origin;
    private int originPort;
    private int proxyPort;
    private File staticRoot;

    @Before
    public void setUp() throws Exception {
        origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        originPort = 0; // filled after start
        origin.setExecutor(Executors.newCachedThreadPool());
        origin.start();
        originPort = origin.getAddress().getPort();

        origin.createContext("/content.m3u8", ex -> {
            String body = "#EXTM3U\n"
                    + "#EXT-X-VERSION:3\n"
                    + "#EXT-X-TARGETDURATION:10\n"
                    + "#EXT-X-MEDIA-SEQUENCE:0\n"
                    + "#EXT-X-PLAYLIST-TYPE:VOD\n"
                    + "#EXTINF:10.0,\n"
                    + "http://127.0.0.1:" + originPort + "/seg/c0.ts\n"
                    + "#EXTINF:10.0,\n"
                    + "http://127.0.0.1:" + originPort + "/seg/c1.ts\n"
                    + "#EXTINF:10.0,\n"
                    + "http://127.0.0.1:" + originPort + "/seg/c2.ts\n"
                    + "#EXT-X-ENDLIST\n";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/vnd.apple.mpegurl");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        });
        origin.createContext("/ad-simple.m3u8", ex -> {
            byte[] body = (
                    "#EXTM3U\n"
                            + "#EXT-X-VERSION:3\n"
                            + "#EXT-X-TARGETDURATION:10\n"
                            + "#EXT-X-MEDIA-SEQUENCE:0\n"
                            + "#EXT-X-PLAYLIST-TYPE:VOD\n"
                            + "#EXTINF:5.0,\n"
                            + "http://127.0.0.1:" + originPort + "/ads/a0.ts\n"
                            + "#EXTINF:5.0,\n"
                            + "http://127.0.0.1:" + originPort + "/ads/a1.ts\n"
                            + "#EXT-X-ENDLIST\n"
            ).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/vnd.apple.mpegurl");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        origin.createContext("/seg/", ex -> {
            byte[] body = new byte[] { 0, 1, 2, 3 };
            ex.getResponseHeaders().set("Content-Type", "video/mp2t");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        origin.createContext("/ads/", ex -> {
            byte[] body = new byte[] { 9, 9, 9 };
            ex.getResponseHeaders().set("Content-Type", "video/mp2t");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });

        staticRoot = Files.createTempDirectory("ssai-player").toFile();
        Files.write(new File(staticRoot, "index.html").toPath(),
                "<html>ssai</html>".getBytes(StandardCharsets.UTF_8));

        proxyPort = findFreePort();
        final SsaiProxyServer server = new SsaiProxyServer(proxyPort, staticRoot);
        Thread proxyThread = new Thread(() -> {
            try {
                server.start();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, "ssai-proxy-test");
        proxyThread.setDaemon(true);
        proxyThread.start();
        waitForHealth(proxyPort, 15000);
    }

    @After
    public void tearDown() {
        if (origin != null) {
            origin.stop(0);
        }
    }

    @Test
    public void sessionStitchesAdAtSplicePoints() throws Exception {
        String content = "http://127.0.0.1:" + originPort + "/content.m3u8";
        String ad = "http://127.0.0.1:" + originPort + "/ad-simple.m3u8";
        String json = "{"
                + "\"contentUrl\":\"" + content + "\","
                + "\"adUrl\":\"" + ad + "\","
                + "\"splices\":[10,20]"
                + "}";

        HttpURLConnection post = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + proxyPort + "/api/session").openConnection();
        post.setRequestMethod("POST");
        post.setDoOutput(true);
        post.setRequestProperty("Content-Type", "application/json");
        post.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(200, post.getResponseCode());
        String sessionBody = readFully(post.getInputStream());
        assertTrue(sessionBody.contains("manifestUrl"));

        String manifestUrl = extractJsonString(sessionBody, "manifestUrl");
        assertTrue(manifestUrl.contains("/s/"));
        assertTrue(manifestUrl.endsWith("/manifest"));

        HttpURLConnection get = (HttpURLConnection) new URL(manifestUrl).openConnection();
        assertEquals(200, get.getResponseCode());
        String ct = get.getContentType();
        assertTrue(ct != null && ct.contains("mpegurl"));
        String playlist = readFully(get.getInputStream());

        assertTrue(playlist.contains("#EXTM3U"));
        assertTrue(playlist.contains("#EXT-X-CUE-OUT"));
        assertTrue(playlist.contains("#EXT-X-CUE-IN"));
        assertTrue(playlist.contains("#EXT-X-DISCONTINUITY"));
        assertTrue(playlist.contains("#EXT-X-ENDLIST"));
        assertTrue(playlist.contains("/s/"));
        assertTrue(playlist.contains("/proxy?url="));
        // CUE-OUT tags only (not CUE-OUT-CONT) — one per splice
        assertEquals(2, countOccurrences(playlist, "#EXT-X-CUE-OUT:"));
        assertEquals(2, countOccurrences(playlist, "#EXT-X-CUE-OUT-CONT:"));
        assertEquals(7, countOccurrences(playlist, "#EXTINF:"));
    }

    @Test
    public void playJsonCreatesSession() throws Exception {
        String content = urlEncode("http://127.0.0.1:" + originPort + "/content.m3u8");
        String ad = urlEncode("http://127.0.0.1:" + originPort + "/ad-simple.m3u8");
        URL play = new URL("http://127.0.0.1:" + proxyPort
                + "/play?content=" + content + "&ad=" + ad + "&splices=0&format=json");
        HttpURLConnection conn = (HttpURLConnection) play.openConnection();
        conn.setInstanceFollowRedirects(false);
        assertEquals(200, conn.getResponseCode());
        String body = readFully(conn.getInputStream());
        assertTrue(body.contains("manifestUrl"));
        assertTrue(body.contains("\"splices\":[0"));
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) >= 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        return bos.toByteArray();
    }

    private static String readFully(InputStream in) throws IOException {
        return new String(readAll(in), StandardCharsets.UTF_8);
    }

    private static int findFreePort() throws IOException {
        java.net.ServerSocket ss = new java.net.ServerSocket(0);
        int p = ss.getLocalPort();
        ss.close();
        return p;
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
                Thread.sleep(100);
            }
        }
        throw new IllegalStateException("proxy did not become healthy", last);
    }

    private static String extractJsonString(String json, String key) {
        String needle = "\"" + key + "\":\"";
        int i = json.indexOf(needle);
        if (i < 0) return null;
        int start = i + needle.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end).replace("\\/", "/");
    }

    private static int countOccurrences(String hay, String needle) {
        int count = 0;
        int i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) {
            count++;
            i += needle.length();
        }
        return count;
    }

    private static String urlEncode(String s) throws Exception {
        return java.net.URLEncoder.encode(s, "UTF-8");
    }
}
