package com.iheartradio.m3u8.http.ads;

import com.iheartradio.m3u8.http.DemoHttp;
import com.iheartradio.m3u8.http.DemoJobStatus;
import com.iheartradio.m3u8.http.ads.AdDecision.Probe;
import com.iheartradio.m3u8.http.ads.AdDecision.Request;
import com.iheartradio.m3u8.http.ads.AdDecision.Result;
import com.iheartradio.m3u8.http.ads.AdDecision.Unavailable;
import com.iheartradio.m3u8.http.ads.AdInventory.Creative;
import com.iheartradio.m3u8.http.api.AdDecisionApi;
import com.iheartradio.m3u8.http.catalog.CatalogStore;
import com.iheartradio.m3u8.http.catalog.Title;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.io.File;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AdDecisionTest {

    private static final String PINNED = ""
            + "{"
            + "\"sessionId\":\"1-aeb5f397\","
            + "\"mode\":\"vod\","
            + "\"format\":\"hls\","
            + "\"strategy\":\"sgai\","
            + "\"contentUrl\":\"http://127.0.0.1:8765/media/titles/summer-in-mars-by-carlos-carlo-milite/master.m3u8\","
            + "\"breakId\":\"user-ad-1\","
            + "\"offsetSec\":12,"
            + "\"durationSec\":16,"
            + "\"maxAds\":1,"
            + "\"startOffset\":0,"
            + "\"pinned\":[{"
            + "\"url\":\"http://127.0.0.1:8765/media/titles/grok-video-8e30b4bc-5d97-4e1e-ab64-129b5/master.m3u8\","
            + "\"durationSec\":16"
            + "}],"
            + "\"skipped\":[]"
            + "}";

    private final AdDecision decision = new AdDecision();

    @Test
    public void pinnedUrlIsReturnedAsSent() {
        Result result = decision.decide(Request.parse(PINNED));
        assertEquals("{\"fill\":true,\"breakId\":\"user-ad-1\",\"ads\":[{"
                + "\"url\":\"http://127.0.0.1:8765/media/titles/grok-video-8e30b4bc-5d97-4e1e-ab64-129b5/master.m3u8\","
                + "\"durationSec\":16}]}", result.toJson());
        Request req = Request.parse(PINNED);
        assertEquals("vod", req.mode);
        assertEquals("hls", req.format);
        assertEquals("sgai", req.strategy);
        assertEquals(12f, req.offsetSec, 0.01f);
    }

    @Test
    public void emptyPinFillsFromInventoryAndTrimsToTheSlot() {
        String body = "{"
                + "\"sessionId\":\"1-aeb5f397\","
                + "\"mode\":\"vod\","
                + "\"format\":\"hls\","
                + "\"strategy\":\"sgai\","
                + "\"breakId\":\"user-ad-1\","
                + "\"offsetSec\":12,"
                + "\"durationSec\":16,"
                + "\"maxAds\":1,"
                + "\"startOffset\":0,"
                + "\"pinned\":[],"
                + "\"skipped\":[]"
                + "}";
        List<Creative> inventory = Arrays.asList(new Creative(
                "grok-clip",
                "http://127.0.0.1:8765/media/ads/grok-clip/master.m3u8",
                "http://127.0.0.1:8765/media/ads/grok-clip/manifest.mpd",
                15f,
                true));
        Result result = decision.decide(Request.parse(body), inventory, AdDecision.DECLARED);
        assertEquals("{\"fill\":true,\"breakId\":\"user-ad-1\",\"ads\":[{"
                + "\"url\":\"http://127.0.0.1:8765/media/ads/grok-clip/master.m3u8\","
                + "\"durationSec\":15}]}", result.toJson());
    }

    @Test
    public void dashFillUsesTheMpd() {
        String body = "{"
                + "\"breakId\":\"user-ad-1\","
                + "\"format\":\"dash\","
                + "\"durationSec\":16,"
                + "\"maxAds\":1,"
                + "\"pinned\":[]"
                + "}";
        List<Creative> inventory = Arrays.asList(new Creative(
                "grok-clip",
                "/media/ads/grok-clip/master.m3u8",
                "/media/ads/grok-clip/manifest.mpd",
                20f,
                true));
        Result result = decision.decide(Request.parse(body), inventory, AdDecision.DECLARED);
        assertEquals("/media/ads/grok-clip/manifest.mpd", result.ads.get(0).url);
        assertEquals(16f, result.ads.get(0).durationSec, 0.01f);
    }

    @Test
    public void measuredMediaIsTrimmedToTheCreativeCap() throws Exception {
        Probe probe = new Probe() {
            @Override
            public float mediaSeconds(String url) {
                return 40f;
            }
        };
        Result result = decision.decide(Request.parse(PINNED), null, probe);
        assertEquals(16f, result.ads.get(0).durationSec, 0.01f);

        Probe shorter = new Probe() {
            @Override
            public float mediaSeconds(String url) {
                return 10f;
            }
        };
        Result cut = decision.decide(Request.parse(PINNED), null, shorter);
        assertEquals(10f, cut.ads.get(0).durationSec, 0.01f);
    }

    @Test
    public void startOffsetIsNoFill() {
        String body = PINNED.replace("\"startOffset\":0", "\"startOffset\":12.5")
                .replace("\"breakId\":\"user-ad-1\"", "\"breakId\":\"live-1577836800\"");
        Result result = decision.decide(Request.parse(body));
        assertEquals("{\"fill\":false,\"breakId\":\"live-1577836800\",\"ads\":[],"
                + "\"reason\":\"start-offset\"}", result.toJson());
        assertTrue(AdDecision.startOffsetBlocks(Double.NaN));
        assertFalse(AdDecision.startOffsetBlocks(0d));
    }

    @Test
    public void noEligibleCreative() {
        String body = "{\"breakId\":\"user-ad-1\",\"durationSec\":16,\"pinned\":[],\"skipped\":[]}";
        Result empty = decision.decide(Request.parse(body));
        assertEquals(AdDecision.NONE_ELIGIBLE, empty.reason);
        assertFalse(empty.fill);

        List<Creative> silent = Arrays.asList(new Creative(
                "quiet", "/media/ads/quiet/master.m3u8", null, 12f, false));
        Result skippedAudio = decision.decide(Request.parse(body), silent, AdDecision.DECLARED);
        assertEquals(AdDecision.NONE_ELIGIBLE, skippedAudio.reason);
    }

    @Test
    public void unavailableCreativeIsSkippedThenReported() throws Exception {
        Probe down = new Probe() {
            @Override
            public float mediaSeconds(String url) throws Unavailable {
                throw new Unavailable(url);
            }
        };
        Result failed = decision.decide(Request.parse(PINNED), null, down);
        assertEquals(AdDecision.UNAVAILABLE, failed.reason);

        String body = "{"
                + "\"sessionId\":\"s\","
                + "\"breakId\":\"user-ad-1\","
                + "\"durationSec\":16,"
                + "\"maxAds\":1,"
                + "\"pinned\":["
                + "{\"url\":\"http://ads/missing.m3u8\",\"durationSec\":8},"
                + "{\"url\":\"http://ads/ok.m3u8\",\"durationSec\":8}"
                + "]"
                + "}";
        Probe firstDown = new Probe() {
            @Override
            public float mediaSeconds(String url) throws Unavailable {
                if (url.contains("missing")) {
                    throw new Unavailable(url);
                }
                return 8f;
            }
        };
        Result next = decision.decide(Request.parse(body), null, firstDown);
        assertTrue(next.fill);
        assertEquals("http://ads/ok.m3u8", next.ads.get(0).url);
    }

    @Test
    public void skippedUrlAndDuplicateArePassedOver() {
        String body = "{"
                + "\"breakId\":\"b\","
                + "\"durationSec\":30,"
                + "\"maxAds\":2,"
                + "\"pinned\":["
                + "{\"url\":\"http://ads/a.m3u8\",\"durationSec\":8},"
                + "{\"url\":\"http://ads/a.m3u8\",\"durationSec\":8},"
                + "{\"url\":\"http://ads/b.m3u8\",\"durationSec\":8}"
                + "],"
                + "\"skipped\":[\"http://ads/a.m3u8\"]"
                + "}";
        Result result = decision.decide(Request.parse(body));
        assertEquals(1, result.ads.size());
        assertEquals("http://ads/b.m3u8", result.ads.get(0).url);
    }

    @Test
    public void sameSessionKeepsTheSameCreative() {
        List<Creative> inventory = Arrays.asList(
                new Creative("a", "http://ads/a.m3u8", null, 10f, true),
                new Creative("b", "http://ads/b.m3u8", null, 10f, true));
        String template = "{\"sessionId\":\"SID\",\"breakId\":\"user-ad-1\","
                + "\"durationSec\":12,\"maxAds\":1,\"pinned\":[]}";
        Set<String> seen = new HashSet<String>();
        String stable = null;
        for (int i = 0; i < 40; i++) {
            String body = template.replace("SID", "s-" + i);
            Result result = decision.decide(Request.parse(body), inventory, AdDecision.DECLARED);
            assertTrue(result.fill);
            seen.add(result.ads.get(0).url);
            if (i == 0) {
                stable = result.ads.get(0).url;
            }
        }
        assertEquals(2, seen.size());
        Result again = decision.decide(Request.parse(template.replace("SID", "s-0")),
                inventory, AdDecision.DECLARED);
        assertEquals(stable, again.ads.get(0).url);
    }

    @Test
    public void missingBreakIsRejected() {
        try {
            Request.parse("{\"durationSec\":16}");
        } catch (IllegalArgumentException e) {
            assertEquals("breakId is required", e.getMessage());
            return;
        }
        throw new AssertionError("expected breakId");
    }

    @Test
    public void catalogAndPackInventory() throws Exception {
        File root = Files.createTempDirectory("ad-inv").toFile();
        Title ready = new Title();
        ready.id = "house";
        ready.title = "House";
        ready.url = "/media/titles/house/master.m3u8";
        ready.dashUrl = "/media/titles/house/manifest.mpd";
        ready.durationSec = 12f;
        ready.status = DemoJobStatus.READY;
        ready.adEligible = true;
        Title content = new Title();
        content.id = "film";
        content.title = "Film";
        content.url = "/media/titles/film/master.m3u8";
        content.durationSec = 90f;
        content.status = DemoJobStatus.READY;
        File pack = new File(root, "ads/grok-clip");
        pack.mkdirs();
        Files.write(new File(pack, "master.m3u8").toPath(),
                ("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\n"
                        + "v720.m3u8\n").getBytes(StandardCharsets.UTF_8));
        Files.write(new File(pack, "v720.m3u8").toPath(),
                ("#EXTM3U\n#EXTINF:6.0,\na.m4s\n#EXTINF:9.0,\nb.m4s\n")
                        .getBytes(StandardCharsets.UTF_8));
        Files.write(new File(pack, "manifest.mpd").toPath(),
                "<MPD mediaPresentationDuration=\"PT15S\"></MPD>"
                        .getBytes(StandardCharsets.UTF_8));

        List<Creative> loaded = AdInventory.load(Arrays.asList(ready, content), new File(root, "ads"));
        assertEquals(2, loaded.size());
        assertEquals("house", loaded.get(0).id);
        assertEquals("grok-clip", loaded.get(1).id);
        assertEquals(15f, loaded.get(1).durationSec, 0.01f);
        assertEquals("/media/ads/grok-clip/manifest.mpd", loaded.get(1).dashUrl);

        String written = CatalogStore.write(Arrays.asList(ready));
        Title round = CatalogStore.parse(written).get(0);
        assertTrue(round.adEligible);
        assertFalse(CatalogStore.parse(CatalogStore.write(Arrays.asList(content))).get(0).adEligible);
        assertEquals(15f, AdInventory.isoDuration("PT15S"), 0.01f);
        assertEquals(65f, AdInventory.isoDuration("PT1M5S"), 0.01f);
    }

    @Test
    public void localFileProbeTrimsAndRejectsAMissingPack() throws Exception {
        File root = Files.createTempDirectory("ad-probe").toFile();
        File dir = new File(root, "ads/clip");
        dir.mkdirs();
        Files.write(new File(dir, "master.m3u8").toPath(),
                "#EXTM3U\n#EXTINF:4.0,\na.m4s\n#EXTINF:6.0,\nb.m4s\n"
                        .getBytes(StandardCharsets.UTF_8));
        MediaFileProbe probe = new MediaFileProbe(root);
        assertEquals(10f, probe.mediaSeconds("http://127.0.0.1:8765/media/ads/clip/master.m3u8"), 0.01f);
        try {
            probe.mediaSeconds("/media/ads/missing/master.m3u8");
        } catch (Unavailable e) {
            String body = "{"
                    + "\"breakId\":\"user-ad-1\","
                    + "\"durationSec\":16,"
                    + "\"pinned\":[{\"url\":\"/media/ads/missing/master.m3u8\",\"durationSec\":16}]"
                    + "}";
            Result result = decision.decide(Request.parse(body), null, probe);
            assertEquals(AdDecision.UNAVAILABLE, result.reason);
            return;
        }
        throw new AssertionError("expected missing creative");
    }

    @Test
    public void postReturnsTheDecision() throws Exception {
        File root = Files.createTempDirectory("ad-api").toFile();
        File pack = new File(root, "ads/grok-clip");
        pack.mkdirs();
        Files.write(new File(pack, "master.m3u8").toPath(),
                "#EXTM3U\n#EXTINF:15.0,\na.m4s\n".getBytes(StandardCharsets.UTF_8));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/ads/decision", new AdDecisionApi(new CatalogStore(root), root));
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        try {
            String filled = post(server.getAddress().getPort(), "{"
                    + "\"breakId\":\"user-ad-1\",\"durationSec\":16,\"maxAds\":1,\"pinned\":[]}");
            assertTrue(filled.contains("\"fill\":true"));
            assertTrue(filled.contains("\"url\":\"/media/ads/grok-clip/master.m3u8\""));
            assertEquals(15d, DemoHttp.jsonNumber(
                    DemoHttp.jsonArray(filled, "ads").substring(1), "durationSec", 0), 0.01);

            String none = post(server.getAddress().getPort(),
                    "{\"breakId\":\"live-1\",\"startOffset\":4,\"pinned\":["
                            + "{\"url\":\"http://ads/a.m3u8\",\"durationSec\":15}]}");
            assertTrue(none.contains("\"fill\":false"));
            assertTrue(none.contains("\"reason\":\"start-offset\""));

            HttpURLConnection bad = open(server.getAddress().getPort(), "{\"durationSec\":1}");
            assertEquals(400, bad.getResponseCode());
            HttpURLConnection get = open(server.getAddress().getPort(), null);
            get.setRequestMethod("GET");
            assertEquals(405, get.getResponseCode());
        } finally {
            server.stop(0);
        }
    }

    private static String post(int port, String body) throws Exception {
        HttpURLConnection c = open(port, body);
        assertEquals(200, c.getResponseCode());
        return new String(DemoHttp.readAll(c.getInputStream()), StandardCharsets.UTF_8);
    }

    private static HttpURLConnection open(int port, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + "/api/ads/decision").openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(3000);
        c.setReadTimeout(3000);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream out = c.getOutputStream();
            out.write(body.getBytes(StandardCharsets.UTF_8));
            out.close();
        }
        return c;
    }
}
