package com.iheartradio.m3u8.http;

import com.iheartradio.m3u8.Encoding;
import com.iheartradio.m3u8.ads.PlaylistRewriteUtil;
import com.iheartradio.m3u8.data.MediaPlaylist;
import com.iheartradio.m3u8.data.Playlist;
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
        String skipped = pipeline.assetListJson(session, "live-1", "http://127.0.0.1:8765", 12d);
        assertEquals("{\"ASSETS\":[]}", skipped);
        String dump = DemoLog.dumpJson("live-off", 20);
        assertTrue(dump.contains("_HLS_start_offset greater than 0"));
        assertTrue(dump.contains("\"startOffset\":12"));
    }

    @Test
    public void hlsStartOffsetMissingIsZero() {
        assertEquals(0d, DemoHttp.hlsStartOffset(null), 0d);
        assertEquals(0d, DemoHttp.hlsStartOffset(""), 0d);
        assertEquals(0d, DemoHttp.hlsStartOffset("_HLS_primary_id=abc"), 0d);
        assertEquals(0d, DemoHttp.hlsStartOffset("_HLS_start_offset=0"), 0d);
        assertEquals(12.5d, DemoHttp.hlsStartOffset("_HLS_start_offset=12.5&_HLS_primary_id=x"), 0d);
        assertTrue(Double.isNaN(DemoHttp.hlsStartOffset("_HLS_start_offset=nope")));
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
