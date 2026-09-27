package com.iheartradio.m3u8.http;

import com.iheartradio.m3u8.Encoding;
import com.iheartradio.m3u8.ads.PlaylistRewriteUtil;
import com.iheartradio.m3u8.data.MapInfo;
import com.iheartradio.m3u8.data.MediaPlaylist;
import com.iheartradio.m3u8.data.Playlist;
import com.iheartradio.m3u8.data.ServerControlData;
import com.iheartradio.m3u8.data.SkipData;
import com.iheartradio.m3u8.data.TrackData;
import com.iheartradio.m3u8.data.TrackInfo;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DemoLiveCadenceTest {

    @Test
    public void oneDateRangeAtLastPdtMinusHoldBack() throws Exception {
        Playlist content = liveWindow("2020-01-01T00:00:10.000Z", 10f, 6);
        DemoSession session = DemoSession.fromJson("live-cad",
                "{\"strategy\":\"sgai\",\"contentUrl\":\"http://cdn/live.m3u8\","
                        + "\"adUrl\":\"http://ads/grok.m3u8\",\"liveCadenceSec\":60,\"livePlayoutLimit\":15}");
        String text = PlaylistRewriteUtil.writeToString(
                new DemoPlaylistPipeline().apply(session, content, null, "http://127.0.0.1:8765"),
                Encoding.UTF_8);

        assertEquals(1, count(text, "#EXT-X-DATERANGE"));
        assertTrue(text.contains("CLASS=\"com.apple.hls.interstitial\""));
        // Last segment PDT 00:01:00 minus 3×TARGETDURATION(10s) = 00:00:30.
        assertTrue(text.contains("START-DATE=2020-01-01T00:00:30.000Z"));
        assertTrue(text.contains("ID=\"live-1577836860\"") || text.contains("ID=\"live-"));
        assertTrue(text.contains("/s/live-cad/assets/live-"));
        assertTrue(text.contains("X-PLAYOUT-LIMIT=15"));
        assertTrue(text.contains("X-ASSET-LIST="));
        assertFalse(text.contains("X-RESUME-OFFSET="));
        assertFalse(text.contains("CUE="));
        assertFalse(text.contains("#EXT-X-CUE-OUT"));
    }

    @Test
    public void fullRewriteKeepsCanSkipUntilAndTheLiveDateRange() throws Exception {
        Playlist content = liveWindow("2020-01-01T00:00:10.000Z", 10f, 6);
        content = content.buildUpon()
                .withCompatibilityVersion(9)
                .withMediaPlaylist(content.getMediaPlaylist().buildUpon()
                        .withServerControlData(new ServerControlData.Builder()
                                .withCanSkipUntil(60f)
                                .withHoldBack(30f)
                                .build())
                        .build())
                .build();
        DemoSession session = DemoSession.fromJson("live-skip",
                "{\"strategy\":\"sgai\",\"contentUrl\":\"http://cdn/live.m3u8\","
                        + "\"adUrl\":\"http://ads/grok.m3u8\",\"liveCadenceSec\":60,\"livePlayoutLimit\":15}");
        Playlist applied = new DemoPlaylistPipeline().apply(
                session, content, "http://cdn/live.m3u8", "http://127.0.0.1:8765");
        Playlist rewritten = PlaylistRewriteUtil.rewriteUris(applied, "http://cdn/live.m3u8",
                new PlaylistRewriteUtil.UriMapper() {
                    @Override
                    public String map(String absoluteUri) {
                        return DemoPlaylistPipeline.mapOriginUri(
                                absoluteUri, "http://127.0.0.1:8765/s/live-skip", "http://127.0.0.1:8765");
                    }
                });
        String text = PlaylistRewriteUtil.writeToString(rewritten, Encoding.UTF_8);
        assertTrue(text, text.contains("CAN-SKIP-UNTIL=60"));
        assertTrue(text, text.contains("#EXT-X-SERVER-CONTROL:"));
        assertEquals(1, count(text, "#EXT-X-DATERANGE"));
        assertTrue(text.contains("START-DATE=2020-01-01T00:00:30.000Z"));
        assertTrue(text.contains("ID=\"live-"));
    }

    @Test
    public void deltaRewriteKeepsSkipAndOneLiveDateRange() throws Exception {
        Playlist content = liveWindow("2020-01-01T00:00:40.000Z", 10f, 3);
        content = content.buildUpon()
                .withCompatibilityVersion(9)
                .withMediaPlaylist(content.getMediaPlaylist().buildUpon()
                        .withMediaSequenceNumber(100)
                        .withServerControlData(new ServerControlData.Builder()
                                .withCanSkipUntil(60f)
                                .build())
                        .withSkipData(new SkipData.Builder().withSkippedSegments(3).build())
                        .build())
                .build();
        DemoSession session = DemoSession.fromJson("live-delta",
                "{\"strategy\":\"sgai\",\"contentUrl\":\"http://cdn/live.m3u8\","
                        + "\"adUrl\":\"http://ads/grok.m3u8\",\"liveCadenceSec\":60,\"livePlayoutLimit\":15}");
        Playlist applied = new DemoPlaylistPipeline().apply(
                session, content, "http://cdn/live.m3u8", "http://127.0.0.1:8765");
        String text = PlaylistRewriteUtil.writeToString(applied, Encoding.UTF_8);
        assertTrue(text, text.contains("SKIPPED-SEGMENTS=3"));
        assertTrue(text, text.contains("CAN-SKIP-UNTIL=60"));
        assertFalse(text, text.contains("http://cdn/old"));
        assertEquals(3, count(text, "#EXTINF"));
        assertEquals(1, count(text, "#EXT-X-DATERANGE"));
        // Tail starts at 00:00:40, so the 30s hold-back clamps to that first PDT.
        assertTrue(text.contains("START-DATE=2020-01-01T00:00:40.000Z"));
    }

    @Test
    public void livePlaylistAdvertisesSkipAndASkipRequestReturnsTheTail() throws Exception {
        Playlist content = liveWindow("2020-01-01T00:00:00.000Z", 10f, 10);
        DemoSession session = DemoSession.fromJson("live-adv",
                "{\"strategy\":\"sgai\",\"contentUrl\":\"http://cdn/live.m3u8\","
                        + "\"adUrl\":\"http://ads/grok.m3u8\",\"liveCadenceSec\":60,\"livePlayoutLimit\":15}");
        Playlist full = DemoPlaylistPipeline.applyDeliveryDirectives(
                new DemoPlaylistPipeline().apply(session, content, "http://cdn/live.m3u8",
                        "http://127.0.0.1:8765"),
                "http://cdn/live.m3u8");
        String fullText = PlaylistRewriteUtil.writeToString(full, Encoding.UTF_8);
        assertTrue(fullText, fullText.contains("CAN-SKIP-UNTIL=60"));
        assertFalse(fullText.contains("EXT-X-SKIP"));
        assertEquals(10, count(fullText, "#EXTINF"));
        java.util.List<TrackData> withMap = new java.util.ArrayList<TrackData>(full.getMediaPlaylist().getTracks());
        withMap.set(0, withMap.get(0).buildUpon()
                .withMapInfo(new MapInfo("http://cdn/init.mp4", null)).build());
        full = full.buildUpon()
                .withMediaPlaylist(full.getMediaPlaylist().buildUpon().withTracks(withMap).build())
                .build();

        Playlist delta = DemoPlaylistPipeline.applyDeliveryDirectives(full, "http://cdn/live.m3u8?_HLS_skip=YES");
        String deltaText = PlaylistRewriteUtil.writeToString(delta, Encoding.UTF_8);
        assertTrue(deltaText, deltaText.contains("SKIPPED-SEGMENTS="));
        assertTrue(count(deltaText, "#EXTINF") < 10);
        assertTrue(deltaText.contains("#EXT-X-MEDIA-SEQUENCE:100"));
        assertTrue(deltaText.contains("#EXT-X-MAP:URI=\"http://cdn/init.mp4\""));
        assertTrue(deltaText, deltaText.contains("#EXT-X-PROGRAM-DATE-TIME:2020-01-01T00:00:30.000Z"));
        assertEquals(1, count(deltaText, "#EXT-X-DATERANGE"));
    }

    @Test
    public void proxyForwardsHlsSkipOntoTheOriginUrl() {
        assertEquals("https://cdn.example/live.m3u8?_HLS_skip=YES",
                DemoHttp.forwardHlsSkip("https://cdn.example/live.m3u8",
                        "url=https%3A%2F%2Fcdn.example%2Flive.m3u8&_HLS_skip=YES"));
        assertEquals("https://cdn.example/live.m3u8?cdn=1&_HLS_skip=v2",
                DemoHttp.forwardHlsSkip("https://cdn.example/live.m3u8?cdn=1",
                        "_HLS_skip=v2"));
        assertEquals("https://cdn.example/live.m3u8",
                DemoHttp.forwardHlsSkip("https://cdn.example/live.m3u8", "url=https%3A%2F%2Fx"));
        assertEquals("https://cdn.example/live.m3u8?_HLS_skip=YES",
                DemoHttp.forwardHlsSkip("https://cdn.example/live.m3u8?_HLS_skip=YES",
                        "_HLS_skip=v2"));
    }

    @Test
    public void liveCadenceFreezesStartDateWithinTheMinute() throws Exception {
        DemoSession session = DemoSession.fromJson("live-frz",
                "{\"strategy\":\"sgai\",\"contentUrl\":\"http://cdn/live.m3u8\","
                        + "\"adUrl\":\"http://ads/grok.m3u8\",\"liveCadenceSec\":60,\"livePlayoutLimit\":15}");
        DemoPlaylistPipeline pipeline = new DemoPlaylistPipeline();
        String first = PlaylistRewriteUtil.writeToString(
                pipeline.apply(session, liveWindow("2020-01-01T00:00:10.000Z", 10f, 6),
                        "http://cdn/live.m3u8", "http://127.0.0.1:8765"),
                Encoding.UTF_8);
        assertTrue(first.contains("START-DATE=2020-01-01T00:00:30.000Z"));
        String later = PlaylistRewriteUtil.writeToString(
                pipeline.apply(session, liveWindow("2020-01-01T00:00:30.000Z", 10f, 6),
                        "http://cdn/live.m3u8", "http://127.0.0.1:8765"),
                Encoding.UTF_8);
        assertTrue(later.contains("START-DATE=2020-01-01T00:00:30.000Z"));
        assertFalse(later.contains("START-DATE=2020-01-01T00:00:50.000Z"));
    }

    @Test
    public void startDateHonorsPdtResetNotDurationSum() throws Exception {
        TrackData a = new TrackData.Builder()
                .withUri("http://cdn/a.ts")
                .withProgramDateTime("2020-01-01T00:00:00.000Z")
                .withTrackInfo(new TrackInfo(60f, null))
                .build();
        TrackData b = new TrackData.Builder()
                .withUri("http://cdn/b.ts")
                .withProgramDateTime("2020-01-01T00:10:00.000Z")
                .withTrackInfo(new TrackInfo(60f, null))
                .build();
        Playlist content = new Playlist.Builder()
                .withExtended(true)
                .withCompatibilityVersion(7)
                .withMediaPlaylist(new MediaPlaylist.Builder()
                        .withTargetDuration(60)
                        .withMediaSequenceNumber(1)
                        .withIsOngoing(true)
                        .withTracks(Arrays.asList(a, b))
                        .build())
                .build();
        DemoSession session = DemoSession.fromJson("live-gap",
                "{\"strategy\":\"sgai\",\"contentUrl\":\"http://cdn/live.m3u8\","
                        + "\"adUrl\":\"http://ads/grok.m3u8\",\"liveCadenceSec\":60,\"livePlayoutLimit\":15}");
        String text = PlaylistRewriteUtil.writeToString(
                new DemoPlaylistPipeline().apply(session, content, "http://cdn/live.m3u8",
                        "http://127.0.0.1:8765"),
                Encoding.UTF_8);
        // Last segment PDT 00:10:00 minus 3×60s hold-back = 00:07:00.
        assertTrue(text.contains("START-DATE=2020-01-01T00:07:00.000Z"));
        assertFalse(text.contains("START-DATE=2020-01-01T00:02:00.000Z"));
        assertEquals(1, count(text, "#EXT-X-DATERANGE"));
    }

    @Test
    public void longDvrGetsOneFrozenRangeNotEveryMinute() throws Exception {
        Playlist content = liveWindow("2020-01-01T00:00:00.000Z", 10f, 150);
        DemoSession session = DemoSession.fromJson("live-dvr",
                "{\"strategy\":\"sgai\",\"contentUrl\":\"http://cdn/live.m3u8\","
                        + "\"adUrl\":\"http://ads/grok.m3u8\",\"liveCadenceSec\":60,\"livePlayoutLimit\":15}");
        String text = PlaylistRewriteUtil.writeToString(
                new DemoPlaylistPipeline().apply(session, content, "http://cdn/live.m3u8",
                        "http://127.0.0.1:8765"),
                Encoding.UTF_8);
        assertEquals(1, count(text, "#EXT-X-DATERANGE"));
        // Last PDT 00:24:50 minus 3×10s = 00:24:20.
        assertTrue(text.contains("START-DATE=2020-01-01T00:24:20.000Z"));
    }

    @Test
    public void vodAdPlaylistDoesNotGetLiveCadence() throws Exception {
        Playlist ad = new Playlist.Builder()
                .withExtended(true)
                .withCompatibilityVersion(7)
                .withMediaPlaylist(new MediaPlaylist.Builder()
                        .withTargetDuration(10)
                        .withMediaSequenceNumber(0)
                        .withIsOngoing(false)
                        .withTracks(Arrays.asList(
                                new TrackData.Builder()
                                        .withUri("http://ads/a0.ts")
                                        .withTrackInfo(new TrackInfo(15f, null))
                                        .build()))
                        .build())
                .build();
        DemoSession session = DemoSession.fromJson("live-ad",
                "{\"strategy\":\"sgai\",\"contentUrl\":\"http://cdn/live.m3u8\","
                        + "\"adUrl\":\"http://ads/grok.m3u8\",\"liveCadenceSec\":60}");
        String text = PlaylistRewriteUtil.writeToString(
                new DemoPlaylistPipeline().apply(session, ad, "http://ads/grok.m3u8",
                        "http://127.0.0.1:8765"),
                Encoding.UTF_8);
        assertFalse(text.contains("#EXT-X-DATERANGE"));
    }

    @Test
    public void audioRenditionDoesNotGetLiveCadence() throws Exception {
        Playlist audio = liveWindow("2020-01-01T00:00:00.000Z", 2f, 10);
        DemoSession session = DemoSession.fromJson("live-aud",
                "{\"strategy\":\"sgai\",\"contentUrl\":\"http://cdn/live.m3u8\","
                        + "\"adUrl\":\"http://ads/grok.m3u8\",\"liveCadenceSec\":60}");
        String text = PlaylistRewriteUtil.writeToString(
                new DemoPlaylistPipeline().apply(session, audio,
                        "http://cdn/channel1-audio_eng=64000.m3u8",
                        "http://127.0.0.1:8765"),
                Encoding.UTF_8);
        assertFalse(text.contains("#EXT-X-DATERANGE"));
    }

    @Test
    public void snapshotDoesNotUseLiveCadence() throws Exception {
        Playlist content = liveWindow("2020-01-01T00:00:10.000Z", 10f, 6);
        DemoSession session = DemoSession.fromJson("live-snap",
                "{\"strategy\":\"sgai\",\"contentUrl\":\"http://cdn/live.m3u8\","
                        + "\"adUrl\":\"http://ads/grok.m3u8\",\"liveCadenceSec\":60,\"forceVod\":true,"
                        + "\"breaks\":[{\"offsetSec\":10,\"durationSec\":15}]}");
        String text = PlaylistRewriteUtil.writeToString(
                new DemoPlaylistPipeline().apply(session, content), Encoding.UTF_8);
        assertFalse(text.contains("ID=\"live-"));
        assertTrue(text.contains("X-RESUME-OFFSET="));
    }

    @Test
    public void liveSlotAssetListUsesSessionAdUrl() {
        DemoSession session = DemoSession.fromJson("live-json",
                "{\"strategy\":\"sgai\",\"contentUrl\":\"http://cdn/live.m3u8\","
                        + "\"adUrl\":\"http://ads/grok.m3u8\",\"liveCadenceSec\":60,\"livePlayoutLimit\":15}");
        String json = new DemoPlaylistPipeline().assetListJson(
                session, "live-1577836860", "http://127.0.0.1:8765");
        assertTrue(json.contains("\"ASSETS\""));
        assertTrue(json.contains("/s/live-json/proxy?url="));
        assertTrue(json.contains("grok.m3u8"));
    }

    @Test
    public void liveAssetListEmptyWhenHlsStartOffsetGreaterThanZero() {
        DemoSession session = DemoSession.fromJson("live-off",
                "{\"strategy\":\"sgai\",\"contentUrl\":\"http://cdn/live.m3u8\","
                        + "\"adUrl\":\"http://ads/grok.m3u8\",\"liveCadenceSec\":60,\"livePlayoutLimit\":15}");
        DemoPlaylistPipeline pipeline = new DemoPlaylistPipeline();
        String withAds = pipeline.assetListJson(session, "live-1", "http://127.0.0.1:8765", 0d);
        assertTrue(withAds.contains("grok.m3u8"));
        String skipped = pipeline.assetListJson(session, "live-1", "http://127.0.0.1:8765", 12d,
                "sess-abc");
        assertEquals("{\"ASSETS\":[]}", skipped);
        String dump = DemoLog.dumpJson("live-off", 20);
        assertTrue(dump.contains("_HLS_start_offset greater than 0"));
        assertTrue(dump.contains("\"startOffset\":12"));
        assertTrue(dump.contains("\"primaryId\":\"sess-abc\""));
    }

    @Test
    public void hlsStartOffsetMissingIsZero() {
        assertEquals(0d, DemoHttp.hlsStartOffset(null), 0d);
        assertEquals(0d, DemoHttp.hlsStartOffset(""), 0d);
        assertEquals(0d, DemoHttp.hlsStartOffset("_HLS_primary_id=abc"), 0d);
        assertEquals(0d, DemoHttp.hlsStartOffset("_HLS_start_offset=0"), 0d);
        assertEquals(12.5d, DemoHttp.hlsStartOffset("_HLS_start_offset=12.5&_HLS_primary_id=x"), 0d);
        assertTrue(Double.isNaN(DemoHttp.hlsStartOffset("_HLS_start_offset=nope")));
        assertEquals("abc", DemoHttp.hlsPrimaryId("_HLS_primary_id=abc&_HLS_start_offset=12"));
        assertEquals(null, DemoHttp.hlsPrimaryId("_HLS_start_offset=0"));
    }

    private static Playlist liveWindow(String pdt, float seg, int n) {
        TrackData[] tracks = new TrackData[n];
        for (int i = 0; i < n; i++) {
            TrackData.Builder b = new TrackData.Builder()
                    .withUri("http://cdn/l" + i + ".ts")
                    .withTrackInfo(new TrackInfo(seg, null));
            if (i == 0) {
                b.withProgramDateTime(pdt);
            }
            tracks[i] = b.build();
        }
        return new Playlist.Builder()
                .withExtended(true)
                .withCompatibilityVersion(7)
                .withMediaPlaylist(new MediaPlaylist.Builder()
                        .withTargetDuration((int) Math.ceil(seg))
                        .withMediaSequenceNumber(100)
                        .withIsOngoing(true)
                        .withTracks(Arrays.asList(tracks))
                        .build())
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
}
