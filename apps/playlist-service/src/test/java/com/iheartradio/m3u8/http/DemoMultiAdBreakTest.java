package com.iheartradio.m3u8.http;

import com.iheartradio.m3u8.Encoding;
import com.iheartradio.m3u8.ads.PlaylistRewriteUtil;
import com.iheartradio.m3u8.data.MediaPlaylist;
import com.iheartradio.m3u8.data.Playlist;
import com.iheartradio.m3u8.data.TrackData;
import com.iheartradio.m3u8.data.TrackInfo;
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
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Multi-ad-per-break: shared {@code ads[]} for SSAI stitch and SGAI {@code X-ASSET-LIST}.
 */
public class DemoMultiAdBreakTest {

    private static final String MULTI_ADS = ""
            + "\"snapToSegment\":false,"
            + "\"breaks\":["
            + "{\"id\":\"break-1\",\"offsetSec\":12,\"ads\":["
            + "{\"assetUri\":\"http://ADS_A\",\"durationSec\":8}]},"
            + "{\"id\":\"break-2\",\"offsetSec\":24,\"ads\":["
            + "{\"assetUri\":\"http://ADS_B\",\"durationSec\":5},"
            + "{\"assetUri\":\"http://ADS_C\",\"durationSec\":6}]}"
            + "]";

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
        origin.createContext("/ad-a.m3u8", ex -> write(ex, adBody("a", 8f), "application/vnd.apple.mpegurl"));
        origin.createContext("/ad-b.m3u8", ex -> write(ex, adBody("b", 5f), "application/vnd.apple.mpegurl"));
        origin.createContext("/ad-c.m3u8", ex -> write(ex, adBody("c", 6f), "application/vnd.apple.mpegurl"));

        File staticRoot = Files.createTempDirectory("demo-multi-ad").toFile();
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
        }, "demo-multi-ad-test");
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
    public void fromJsonKeepsTwoAdsOnSecondBreak() {
        DemoSession session = DemoSession.fromJson("s-parse", sessionJson("ssai"));
        assertEquals(2, session.breaks.size());
        assertEquals(12f, session.breaks.get(0).offsetSec, 1e-4);
        assertEquals(1, session.breaks.get(0).ads.size());
        assertEquals(24f, session.breaks.get(1).offsetSec, 1e-4);
        assertEquals(2, session.breaks.get(1).ads.size());
        assertTrue(session.breaks.get(1).ads.get(0).assetUri.contains("/ad-b.m3u8"));
        assertTrue(session.breaks.get(1).ads.get(1).assetUri.contains("/ad-c.m3u8"));
    }

    @Test
    public void ssaiManifestStitchesTwoAdsAtSecondBreak() throws Exception {
        Playlist content = media(
                track("http://cdn/c0.ts", 10f),
                track("http://cdn/c1.ts", 10f),
                track("http://cdn/c2.ts", 10f),
                track("http://cdn/c3.ts", 10f));
        DemoSession session = DemoSession.fromJson("s-ssai",
                "{\"strategy\":\"ssai\",\"contentUrl\":\"http://cdn/prog.m3u8\","
                        + "\"snapToSegment\":false,\"breaks\":["
                        + "{\"id\":\"break-1\",\"offsetSec\":12,\"ads\":["
                        + "{\"assetUri\":\"http://ads/a.m3u8\",\"durationSec\":8}]},"
                        + "{\"id\":\"break-2\",\"offsetSec\":24,\"ads\":["
                        + "{\"assetUri\":\"http://ads/b.m3u8\",\"durationSec\":5},"
                        + "{\"assetUri\":\"http://ads/c.m3u8\",\"durationSec\":6}]}"
                        + "]}");
        session.putCachedAd("http://ads/a.m3u8", media(track("http://ads/a0.ts", 8f)));
        session.putCachedAd("http://ads/b.m3u8", media(track("http://ads/b0.ts", 5f)));
        session.putCachedAd("http://ads/c.m3u8", media(track("http://ads/c0.ts", 6f)));

        String text = PlaylistRewriteUtil.writeToString(
                new DemoPlaylistPipeline().apply(session, content), Encoding.UTF_8);

        assertFalse(text.contains("com.apple.hls.interstitial"));
        assertFalse(text.contains("X-ASSET-LIST="));
        assertEquals(3, count(text, "#EXT-X-CUE-OUT"));
        assertTrue(text.contains("#EXT-X-CUE-OUT:8"));
        assertTrue(text.contains("#EXT-X-CUE-OUT:5"));
        assertTrue(text.contains("#EXT-X-CUE-OUT:6"));

        int c0 = text.indexOf("http://cdn/c0.ts");
        int a0 = text.indexOf("http://ads/a0.ts");
        int c1 = text.indexOf("http://cdn/c1.ts");
        int b0 = text.indexOf("http://ads/b0.ts");
        int cAd = text.indexOf("http://ads/c0.ts");
        int c2 = text.indexOf("http://cdn/c2.ts");
        assertTrue(c0 >= 0 && a0 > c0 && c1 > a0);
        assertTrue(b0 > c1 && cAd > b0 && c2 > cAd);
    }

    @Test
    public void sgaiManifestUsesOneAssetListPerBreak() throws Exception {
        Playlist content = media(
                track("http://cdn/c0.ts", 10f),
                track("http://cdn/c1.ts", 10f),
                track("http://cdn/c2.ts", 10f),
                track("http://cdn/c3.ts", 10f));
        DemoSession session = DemoSession.fromJson("s-sgai",
                "{\"strategy\":\"sgai\",\"contentUrl\":\"http://cdn/prog.m3u8\","
                        + "\"snapToSegment\":false,\"breaks\":["
                        + "{\"id\":\"break-1\",\"offsetSec\":12,\"ads\":["
                        + "{\"assetUri\":\"http://ads/a.m3u8\",\"durationSec\":8}]},"
                        + "{\"id\":\"break-2\",\"offsetSec\":24,\"ads\":["
                        + "{\"assetUri\":\"http://ads/b.m3u8\",\"durationSec\":5},"
                        + "{\"assetUri\":\"http://ads/c.m3u8\",\"durationSec\":6}]}"
                        + "]}");
        session.putCachedAd("http://ads/a.m3u8", media(track("http://ads/a0.ts", 8f)));
        session.putCachedAd("http://ads/b.m3u8", media(track("http://ads/b0.ts", 5f)));
        session.putCachedAd("http://ads/c.m3u8", media(track("http://ads/c0.ts", 6f)));

        String text = PlaylistRewriteUtil.writeToString(
                new DemoPlaylistPipeline().apply(session, content, null, "http://127.0.0.1:8765"),
                Encoding.UTF_8);

        assertEquals(2, count(text, "#EXT-X-DATERANGE"));
        assertEquals(2, count(text, "com.apple.hls.interstitial"));
        assertFalse(text.contains("X-ASSET-URI="));
        assertFalse(text.contains("#EXT-X-CUE-OUT"));
        assertTrue(text.contains("X-ASSET-LIST=\"http://127.0.0.1:8765/s/s-sgai/assets/break-1.json\""));
        assertTrue(text.contains("X-ASSET-LIST=\"http://127.0.0.1:8765/s/s-sgai/assets/break-2.json\""));
        assertTrue(text.contains("START-DATE=2020-01-01T00:00:12.000Z"));
        assertTrue(text.contains("START-DATE=2020-01-01T00:00:24.000Z"));
        assertTrue(text.contains("DURATION=8"));
        assertTrue(text.contains("DURATION=11"));

        String list = new DemoPlaylistPipeline().assetListJson(
                session, "break-2", "http://127.0.0.1:8765");
        assertNotNull(list);
        assertEquals(2, count(list, "\"URI\""));
        assertTrue(list.contains("/s/s-sgai/proxy?url="));
        assertTrue(list.contains(PlaylistRewriteUtil.urlEncode("http://ads/b.m3u8")));
        assertTrue(list.contains(PlaylistRewriteUtil.urlEncode("http://ads/c.m3u8")));
        assertTrue(list.contains("\"DURATION\":5"));
        assertTrue(list.contains("\"DURATION\":6"));
        assertFalse(list.contains("http://ads/a.m3u8"));
    }

    @Test
    public void skippedAdIsDroppedFromSsaiPodAndSgaiList() throws Exception {
        Playlist content = media(
                track("http://cdn/c0.ts", 10f),
                track("http://cdn/c1.ts", 10f),
                track("http://cdn/c2.ts", 10f));
        String json = "{\"strategy\":\"ssai\",\"contentUrl\":\"http://cdn/prog.m3u8\","
                + "\"snapToSegment\":false,\"breaks\":["
                + "{\"id\":\"break-2\",\"offsetSec\":10,\"ads\":["
                + "{\"assetUri\":\"http://ads/b.m3u8\",\"durationSec\":5},"
                + "{\"assetUri\":\"http://ads/c.m3u8\",\"durationSec\":6}]}"
                + "]}";
        DemoSession ssai = DemoSession.fromJson("s-skip-ssai", json);
        ssai.putCachedAd("http://ads/b.m3u8", media(track("http://ads/b0.ts", 5f)));
        ssai.putCachedAd("http://ads/c.m3u8", media(track("http://ads/c0.ts", 6f)));
        ssai.skipAd("http://ads/b.m3u8");

        String stitched = PlaylistRewriteUtil.writeToString(
                new DemoPlaylistPipeline().apply(ssai, content), Encoding.UTF_8);
        assertFalse(stitched.contains("http://ads/b0.ts"));
        assertTrue(stitched.contains("http://ads/c0.ts"));
        assertEquals(1, count(stitched, "#EXT-X-CUE-OUT"));

        DemoSession sgai = DemoSession.fromJson("s-skip-sgai",
                json.replace("\"ssai\"", "\"sgai\""));
        sgai.putCachedAd("http://ads/c.m3u8", media(track("http://ads/c0.ts", 6f)));
        sgai.skipAd("http://ads/b.m3u8");
        String list = new DemoPlaylistPipeline().assetListJson(
                sgai, "break-2", "http://127.0.0.1:8765");
        assertNotNull(list);
        assertEquals(1, count(list, "\"URI\""));
        assertTrue(list.contains(PlaylistRewriteUtil.urlEncode("http://ads/c.m3u8")));
        assertFalse(list.contains(PlaylistRewriteUtil.urlEncode("http://ads/b.m3u8")));
    }

    @Test
    public void ssaiHttpManifestHasTwoCueWindowsAndTwoAdsAtSecondBreak() throws Exception {
        String playlist = createAndFetchManifest(sessionJson("ssai"));
        assertTrue(playlist.contains("#EXT-X-CUE-OUT"));
        assertFalse(playlist.contains("com.apple.hls.interstitial"));
        assertFalse(playlist.contains("X-ASSET-LIST="));
        assertTrue(playlist.contains("a0.ts"));
        assertTrue(playlist.contains("b0.ts"));
        assertTrue(playlist.contains("c0.ts"));

        int a = indexOfAd(playlist, "a0");
        int b = indexOfAd(playlist, "b0");
        int c = indexOfAd(playlist, "c0");
        assertTrue("ad A then B then C", a >= 0 && b > a && c > b);
        assertEquals(3, count(playlist, "#EXT-X-CUE-OUT"));
        assertTrue(playlist.contains("ID=\"break-2-1\""));
        assertTrue(playlist.contains("ID=\"break-2-2\""));
    }

    @Test
    public void sgaiHttpManifestAndAssetListForMultiAdBreak() throws Exception {
        SessionFetch fetched = createSession(sessionJson("sgai"));
        HttpURLConnection get = (HttpURLConnection) new URL(fetched.manifestUrl).openConnection();
        assertEquals(200, get.getResponseCode());
        String playlist = readFully(get.getInputStream());

        assertTrue(playlist.contains("#EXT-X-DATERANGE"));
        assertTrue(playlist.contains("com.apple.hls.interstitial"));
        assertTrue(playlist.contains("X-ASSET-LIST="));
        assertFalse(playlist.contains("X-ASSET-URI="));
        assertFalse(playlist.contains("#EXT-X-CUE-OUT"));
        assertEquals(2, count(playlist, "X-ASSET-LIST="));
        assertTrue(playlist.contains("/s/" + fetched.id + "/assets/break-1.json"));
        assertTrue(playlist.contains("/s/" + fetched.id + "/assets/break-2.json"));
        String listAttr = extractQuoted(playlist, "X-ASSET-LIST=", 1);
        assertNotNull(listAttr);
        assertTrue(listAttr.contains("/assets/break-2.json"));
        assertFalse(listAttr.contains("/proxy?url="));

        HttpURLConnection listGet = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + demoPort + "/s/" + fetched.id + "/assets/break-2.json")
                .openConnection();
        assertEquals(200, listGet.getResponseCode());
        assertTrue(listGet.getContentType().contains("json"));
        String list = readFully(listGet.getInputStream());
        assertTrue(list.contains("\"ASSETS\""));
        assertEquals(2, count(list, "\"URI\""));
        assertTrue(list.contains("/proxy?url="));
        assertTrue(list.contains("ad-b.m3u8") || list.contains("ad-b"));
        assertTrue(list.contains("ad-c.m3u8") || list.contains("ad-c"));
        assertTrue(list.contains("\"DURATION\":5") || list.contains("\"DURATION\":5.0"));
        assertTrue(list.contains("\"DURATION\":6") || list.contains("\"DURATION\":6.0"));

        HttpURLConnection missing = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + demoPort + "/s/" + fetched.id + "/assets/no-such.json")
                .openConnection();
        assertEquals(404, missing.getResponseCode());
    }

    @Test
    public void wrapFlatBreakRowsAsSingleAd() {
        DemoSession session = DemoSession.fromJson("s-flat",
                "{\"strategy\":\"ssai\",\"contentUrl\":\"http://cdn/prog.m3u8\","
                        + "\"breaks\":[{\"offsetSec\":12,\"durationSec\":8,\"assetUri\":\"http://ads/a.m3u8\"}]}");
        assertEquals(1, session.breaks.size());
        assertEquals(1, session.breaks.get(0).ads.size());
        assertEquals("http://ads/a.m3u8", session.breaks.get(0).ads.get(0).assetUri);
        assertEquals(8f, session.breaks.get(0).ads.get(0).durationSec, 1e-4);
    }

    private String sessionJson(String strategy) {
        String ads = MULTI_ADS
                .replace("http://ADS_A", "http://127.0.0.1:" + originPort + "/ad-a.m3u8")
                .replace("http://ADS_B", "http://127.0.0.1:" + originPort + "/ad-b.m3u8")
                .replace("http://ADS_C", "http://127.0.0.1:" + originPort + "/ad-c.m3u8");
        return "{\"strategy\":\"" + strategy + "\","
                + "\"contentUrl\":\"http://127.0.0.1:" + originPort + "/content.m3u8\","
                + ads + "}";
    }

    private String createAndFetchManifest(String json) throws Exception {
        return createSession(json).playlist;
    }

    private SessionFetch createSession(String json) throws Exception {
        HttpURLConnection post = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + demoPort + "/api/session").openConnection();
        post.setRequestMethod("POST");
        post.setDoOutput(true);
        post.setRequestProperty("Content-Type", "application/json");
        post.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(200, post.getResponseCode());
        String sessionBody = readFully(post.getInputStream());
        String id = extractJsonString(sessionBody, "id");
        String manifestUrl = extractJsonString(sessionBody, "manifestUrl");
        HttpURLConnection get = (HttpURLConnection) new URL(manifestUrl).openConnection();
        assertEquals(200, get.getResponseCode());
        return new SessionFetch(id, manifestUrl, readFully(get.getInputStream()));
    }

    private String contentBody() {
        return "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:0\n"
                + "#EXT-X-PLAYLIST-TYPE:VOD\n"
                + "#EXTINF:10.0,\nhttp://127.0.0.1:" + originPort + "/seg/c0.ts\n"
                + "#EXTINF:10.0,\nhttp://127.0.0.1:" + originPort + "/seg/c1.ts\n"
                + "#EXTINF:10.0,\nhttp://127.0.0.1:" + originPort + "/seg/c2.ts\n"
                + "#EXTINF:10.0,\nhttp://127.0.0.1:" + originPort + "/seg/c3.ts\n"
                + "#EXT-X-ENDLIST\n";
    }

    private String adBody(String tag, float dur) {
        return "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:" + ((int) Math.ceil(dur)) + "\n"
                + "#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n"
                + "#EXTINF:" + dur + ",\n"
                + "http://127.0.0.1:" + originPort + "/ads/" + tag + "0.ts\n"
                + "#EXT-X-ENDLIST\n";
    }

    private static Playlist media(TrackData... tracks) {
        return new Playlist.Builder()
                .withExtended(true)
                .withCompatibilityVersion(3)
                .withMediaPlaylist(new MediaPlaylist.Builder()
                        .withTargetDuration(10)
                        .withMediaSequenceNumber(0)
                        .withIsOngoing(false)
                        .withTracks(Arrays.asList(tracks))
                        .build())
                .build();
    }

    private static TrackData track(String uri, float dur) {
        return new TrackData.Builder()
                .withUri(uri)
                .withTrackInfo(new TrackInfo(dur, null))
                .build();
    }

    private static int count(String text, String needle) {
        int n = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            n++;
            from += needle.length();
        }
        return n;
    }

    private static int indexOfAd(String playlist, String tag) {
        String[] needles = new String[] {
                "/ads/" + tag + ".ts",
                "%2Fads%2F" + tag + ".ts",
                "ads%2F" + tag + ".ts"
        };
        for (String n : needles) {
            int i = playlist.indexOf(n);
            if (i >= 0) {
                return i;
            }
        }
        return -1;
    }

    private static String extractQuoted(String text, String key, int which) {
        int from = 0;
        int seen = 0;
        while (from < text.length()) {
            int i = text.indexOf(key, from);
            if (i < 0) {
                return null;
            }
            int q = text.indexOf('"', i + key.length());
            if (q < 0) {
                return null;
            }
            int end = text.indexOf('"', q + 1);
            if (end < 0) {
                return null;
            }
            if (seen == which) {
                return text.substring(q + 1, end);
            }
            seen++;
            from = end + 1;
        }
        return null;
    }

    private static String extractJsonString(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1).replace("\\/", "/") : null;
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

    private static final class SessionFetch {
        final String id;
        final String manifestUrl;
        final String playlist;

        SessionFetch(String id, String manifestUrl, String playlist) {
            this.id = id;
            this.manifestUrl = manifestUrl;
            this.playlist = playlist;
        }
    }
}
