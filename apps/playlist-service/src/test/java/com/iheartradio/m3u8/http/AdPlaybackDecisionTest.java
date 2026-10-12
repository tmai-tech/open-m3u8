package com.iheartradio.m3u8.http;

import com.iheartradio.m3u8.Encoding;
import com.iheartradio.m3u8.ads.PlaylistRewriteUtil;
import com.iheartradio.m3u8.data.MediaPlaylist;
import com.iheartradio.m3u8.data.Playlist;
import com.iheartradio.m3u8.data.TrackData;
import com.iheartradio.m3u8.data.TrackInfo;
import com.iheartradio.m3u8.http.DemoHttp.FetchResult;
import com.iheartradio.m3u8.http.DemoHttp.OriginFetch;
import com.iheartradio.m3u8.http.ads.AdBreakPicker;
import com.iheartradio.m3u8.http.catalog.CatalogStore;
import com.iheartradio.m3u8.http.catalog.Title;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class AdPlaybackDecisionTest {

    private static final String AD_MEDIA = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4.0,\nad.ts\n";
    private static final String AD_MPD = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\""
            + " mediaPresentationDuration=\"PT4S\" minBufferTime=\"PT2S\">"
            + "<Period id=\"ad\" duration=\"PT4S\">"
            + "<AdaptationSet contentType=\"video\" mimeType=\"video/mp4\">"
            + "<Representation id=\"v\" bandwidth=\"800000\">"
            + "<SegmentTemplate timescale=\"1000\" initialization=\"init.m4s\""
            + " media=\"ad_$Number$.m4s\" startNumber=\"0\">"
            + "<SegmentTimeline><S t=\"0\" d=\"4000\"/></SegmentTimeline>"
            + "</SegmentTemplate></Representation></AdaptationSet></Period></MPD>";

    @Test
    public void serverChosenBreakStillWritesTheCue() throws Exception {
        DemoSession session = DemoSession.fromJson("cue",
                "{\"strategy\":\"sgai\",\"contentUrl\":\"http://cdn/prog.m3u8\","
                        + "\"snapToSegment\":false,\"breaks\":["
                        + "{\"id\":\"gone\",\"offsetSec\":1,\"ads\":[]},"
                        + "{\"id\":\"keep\",\"offsetSec\":12,\"fill\":true,\"durationSec\":32,\"maxAds\":3}"
                        + "]}");
        assertEquals(1, session.breaks.size());
        assertTrue(session.breaks.get(0).fill);
        assertEquals(3, session.breaks.get(0).maxAds);
        String text = PlaylistRewriteUtil.writeToString(
                new DemoPlaylistPipeline().apply(session, content(), null, "http://127.0.0.1:8765"),
                Encoding.UTF_8);
        assertTrue(text.contains("X-ASSET-LIST=\"http://127.0.0.1:8765/s/cue/assets/keep.json\""));
        assertTrue(text.contains("DURATION=32"));
        assertFalse(text.contains("ID=\"gone\""));
    }

    @Test
    public void pinnedAssetListStaysOnTheCardUrl() throws Exception {
        File root = Files.createTempDirectory("ad-pin").toFile();
        addEligible(root, "house", 12f, false);
        DemoSession session = DemoSession.fromJson("pin",
                "{\"strategy\":\"sgai\",\"contentUrl\":\"http://cdn/prog.m3u8\",\"breaks\":["
                        + "{\"id\":\"b\",\"offsetSec\":12,\"ads\":["
                        + "{\"assetUri\":\"http://ads/pinned.m3u8\",\"durationSec\":8}]}]}");
        DemoPlaylistPipeline pipeline = new DemoPlaylistPipeline(mediaFetch());
        pipeline.setPicker(new AdBreakPicker(new CatalogStore(root), root));
        String list = pipeline.assetListJson(session, "b", "http://127.0.0.1:8765");
        assertTrue(list, list.contains("pinned.m3u8"));
        assertFalse(list, list.contains("/media/titles/house/"));
    }

    @Test
    public void unpinnedHlsAssetListWalksInventoryAndStaysStable() throws Exception {
        File root = Files.createTempDirectory("ad-walk").toFile();
        addEligible(root, "alpha", 4f, false);
        addEligible(root, "beta", 6f, false);
        DemoSession session = DemoSession.fromJson("walk",
                "{\"strategy\":\"sgai\",\"contentUrl\":\"http://cdn/prog.m3u8\",\"breaks\":["
                        + "{\"id\":\"keep\",\"offsetSec\":12,\"fill\":true,\"durationSec\":32,\"maxAds\":3}]}");
        DemoPlaylistPipeline pipeline = new DemoPlaylistPipeline(mediaFetch());
        pipeline.setPicker(new AdBreakPicker(new CatalogStore(root), root));
        String first = pipeline.assetListJson(session, "keep", "http://127.0.0.1:8765");
        String second = pipeline.assetListJson(session, "keep", "http://127.0.0.1:8765");
        assertEquals(first, second);
        assertTrue(first, first.contains("%2Fmedia%2Ftitles%2Falpha%2Fmaster.m3u8"));
        assertTrue(first, first.contains("%2Fmedia%2Ftitles%2Fbeta%2Fmaster.m3u8"));
        assertTrue(first, first.contains("/s/walk/proxy?url="));
        String dump = DemoLog.dumpJson("walk", 20);
        assertTrue(dump.contains("\"ev\":\"decision\""));
        assertTrue(dump.contains("\"fill\":true"));
    }

    @Test
    public void liveStartOffsetIsEmptyWhenThePickerIsOn() {
        File root = new File(".");
        DemoSession session = DemoSession.fromJson("live-dec",
                "{\"strategy\":\"sgai\",\"contentUrl\":\"http://cdn/live.m3u8\","
                        + "\"adUrl\":\"http://ads/grok.m3u8\",\"liveCadenceSec\":60,\"livePlayoutLimit\":15}");
        DemoPlaylistPipeline pipeline = new DemoPlaylistPipeline();
        pipeline.setPicker(new AdBreakPicker(new CatalogStore(root), root));
        String skipped = pipeline.assetListJson(session, "live-1", "http://127.0.0.1:8765", 12d, "sess-abc");
        assertEquals("{\"ASSETS\":[]}", skipped);
        String dump = DemoLog.dumpJson("live-dec", 10);
        assertTrue(dump.contains("\"reason\":\"start-offset\""));
    }

    @Test
    public void ssaiStitchesTheUrlTheDecisionReturned() throws Exception {
        File root = Files.createTempDirectory("ad-ssai").toFile();
        addEligible(root, "clip", 10f, false);
        DemoSession session = DemoSession.fromJson("ssai-dec",
                "{\"strategy\":\"ssai\",\"contentUrl\":\"http://cdn/prog.m3u8\",\"breaks\":["
                        + "{\"id\":\"pod\",\"offsetSec\":0,\"fill\":true,\"durationSec\":10,\"maxAds\":1}]}");
        DemoPlaylistPipeline pipeline = new DemoPlaylistPipeline(mediaFetch());
        pipeline.setPicker(new AdBreakPicker(new CatalogStore(root), root));
        String text = PlaylistRewriteUtil.writeToString(
                pipeline.apply(session, content(), null, "http://127.0.0.1:8765"), Encoding.UTF_8);
        assertTrue(text, text.contains("ad.ts"));
        assertTrue(text, text.contains("#EXT-X-CUE-OUT") || text.contains("CUE-OUT"));
    }

    @Test
    public void dashSgaiStoresAltMpdForTheDecidedUrl() throws Exception {
        File root = Files.createTempDirectory("ad-dash").toFile();
        addEligible(root, "clip", 16f, true);
        DemoSession session = DemoSession.fromJson("dash-dec",
                "{\"strategy\":\"sgai\",\"contentUrl\":\"http://cdn/prog.mpd\",\"breaks\":["
                        + "{\"id\":\"pod\",\"offsetSec\":12,\"fill\":true,\"durationSec\":16,\"maxAds\":1}]}");
        DemoMpdPipeline pipeline = new DemoMpdPipeline(mpdFetch(), new com.iheartradio.dash.MpdIo());
        pipeline.setPicker(new AdBreakPicker(new CatalogStore(root), root));
        java.util.Map<String, String> alts = new LinkedHashMap<String, String>();
        assertEquals(1, pipeline.sgaiEvents(session, alts, "http://127.0.0.1:8765").size());
        assertNotNull(alts.get("pod"));
        assertTrue(alts.get("pod").contains("AdaptationSet"));
    }

    @Test
    public void dashFillWithNoMpdIsNoFill() throws Exception {
        File root = Files.createTempDirectory("ad-nompd").toFile();
        addEligible(root, "clip", 16f, false);
        DemoSession session = DemoSession.fromJson("dash-none",
                "{\"strategy\":\"sgai\",\"contentUrl\":\"http://cdn/prog.mpd\",\"breaks\":["
                        + "{\"id\":\"pod\",\"offsetSec\":12,\"fill\":true,\"durationSec\":16,\"maxAds\":1}]}");
        DemoMpdPipeline pipeline = new DemoMpdPipeline(mpdFetch(), new com.iheartradio.dash.MpdIo());
        pipeline.setPicker(new AdBreakPicker(new CatalogStore(root), root));
        java.util.Map<String, String> alts = new LinkedHashMap<String, String>();
        assertTrue(pipeline.sgaiEvents(session, alts, "http://127.0.0.1:8765").isEmpty());
        assertTrue(alts.isEmpty());
        String dump = DemoLog.dumpJson("dash-none", 10);
        assertTrue(dump.contains("\"reason\":\"none-eligible\""));
    }

    private static Playlist content() {
        return new Playlist.Builder()
                .withExtended(true)
                .withCompatibilityVersion(3)
                .withMediaPlaylist(new MediaPlaylist.Builder()
                        .withTargetDuration(10)
                        .withMediaSequenceNumber(0)
                        .withIsOngoing(false)
                        .withTracks(Arrays.asList(new TrackData.Builder()
                                .withUri("http://cdn/c0.ts")
                                .withTrackInfo(new TrackInfo(10f, null))
                                .build()))
                        .build())
                .build();
    }

    private static void addEligible(File root, String id, float durationSec, boolean dash) throws Exception {
        Title t = new Title();
        t.id = id;
        t.title = id;
        t.url = "/media/titles/" + id + "/master.m3u8";
        if (dash) {
            t.dashUrl = "/media/titles/" + id + "/manifest.mpd";
        }
        t.durationSec = durationSec;
        t.status = DemoJobStatus.READY;
        t.adEligible = true;
        CatalogStore store = new CatalogStore(root);
        List<Title> titles = new ArrayList<Title>(store.load());
        titles.add(t);
        store.save(titles);
        File dir = new File(root, "titles/" + id);
        dir.mkdirs();
        Files.write(new File(dir, "master.m3u8").toPath(), AD_MEDIA.getBytes(StandardCharsets.UTF_8));
        if (dash) {
            Files.write(new File(dir, "manifest.mpd").toPath(), AD_MPD.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static OriginFetch mediaFetch() {
        return new OriginFetch() {
            @Override
            public FetchResult fetch(String url) {
                return new FetchResult(200, "application/vnd.apple.mpegurl",
                        AD_MEDIA.getBytes(StandardCharsets.UTF_8));
            }
        };
    }

    private static OriginFetch mpdFetch() {
        return new OriginFetch() {
            @Override
            public FetchResult fetch(String url) {
                if (url != null && url.contains(".mpd")) {
                    return new FetchResult(200, "application/dash+xml",
                            AD_MPD.getBytes(StandardCharsets.UTF_8));
                }
                return new FetchResult(404, "text/plain", new byte[0]);
            }
        };
    }
}
