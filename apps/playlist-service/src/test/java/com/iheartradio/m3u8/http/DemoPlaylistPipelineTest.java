package com.iheartradio.m3u8.http;

import com.iheartradio.m3u8.Encoding;
import com.iheartradio.m3u8.ads.PlaylistRewriteUtil;
import com.iheartradio.m3u8.data.MediaPlaylist;
import com.iheartradio.m3u8.data.Playlist;
import com.iheartradio.m3u8.data.StartData;
import com.iheartradio.m3u8.data.TrackData;
import com.iheartradio.m3u8.data.TrackInfo;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DemoPlaylistPipelineTest {

    @Test
    public void emptyBreaksLeavesContentUnchanged() throws Exception {
        Playlist content = media(
                track("http://cdn/c0.ts", 10f),
                track("http://cdn/c1.ts", 10f));
        DemoSession session = DemoSession.fromJson("s0",
                "{\"strategy\":\"ssai\",\"contentUrl\":\"http://cdn/prog.m3u8\"}");

        Playlist out = new DemoPlaylistPipeline().apply(session, content);
        String text = PlaylistRewriteUtil.writeToString(out, Encoding.UTF_8);

        assertFalse(text.contains("#EXT-X-CUE-OUT"));
        assertTrue(text.contains("http://cdn/c0.ts"));
        assertTrue(text.contains("http://cdn/c1.ts"));
    }

    @Test
    public void sgaiInjectsDateRangeNotCues() throws Exception {
        Playlist content = media(
                track("http://cdn/c0.ts", 10f),
                track("http://cdn/c1.ts", 10f));
        DemoSession session = DemoSession.fromJson("s1",
                "{\"strategy\":\"sgai\",\"contentUrl\":\"http://cdn/prog.m3u8\","
                        + "\"adUrl\":\"http://ads/ad.m3u8\","
                        + "\"breaks\":[{\"offsetSec\":10,\"durationSec\":15}]}");

        Playlist out = new DemoPlaylistPipeline().apply(session, content);
        String text = PlaylistRewriteUtil.writeToString(out, Encoding.UTF_8);

        assertTrue(text.contains("#EXT-X-DATERANGE"));
        assertTrue(text.contains("com.apple.hls.interstitial"));
        assertTrue(text.contains("X-ASSET-URI=\"http://ads/ad.m3u8\""));
        assertTrue(text.contains("X-PLAYOUT-LIMIT=15"));
        assertFalse(text.contains("#EXT-X-CUE-OUT"));
    }

    @Test
    public void ssaiStitchesCuesNotInterstitialClass() throws Exception {
        Playlist content = media(
                track("http://cdn/c0.ts", 10f),
                track("http://cdn/c1.ts", 10f),
                track("http://cdn/c2.ts", 10f));
        Playlist ad = media(track("http://ads/a0.ts", 5f), track("http://ads/a1.ts", 5f));

        DemoSession session = DemoSession.fromJson("s2",
                "{\"strategy\":\"ssai\",\"contentUrl\":\"http://cdn/prog.m3u8\","
                        + "\"adUrl\":\"http://ads/ad.m3u8\",\"splices\":[10]}");
        session.setCachedAdMedia(ad);

        Playlist out = new DemoPlaylistPipeline().apply(session, content);
        String text = PlaylistRewriteUtil.writeToString(out, Encoding.UTF_8);

        assertTrue(text.contains("#EXT-X-CUE-OUT"));
        assertTrue(text.contains("#EXT-X-CUE-IN"));
        assertTrue(text.contains("#EXT-X-DISCONTINUITY"));
        assertFalse(text.contains("com.apple.hls.interstitial"));
    }

    @Test
    public void ssaiUsesPerBreakDurationAndAdUrl() throws Exception {
        Playlist content = media(
                track("http://cdn/c0.ts", 10f),
                track("http://cdn/c1.ts", 10f),
                track("http://cdn/c2.ts", 10f));
        Playlist adA = media(track("http://ads/a0.ts", 4f), track("http://ads/a1.ts", 4f),
                track("http://ads/a2.ts", 4f), track("http://ads/a3.ts", 4f));
        Playlist adB = media(track("http://ads/b0.ts", 5f), track("http://ads/b1.ts", 5f));

        DemoSession session = DemoSession.fromJson("s-multi",
                "{\"strategy\":\"ssai\",\"contentUrl\":\"http://cdn/prog.m3u8\","
                        + "\"breaks\":["
                        + "{\"offsetSec\":10,\"durationSec\":8,\"assetUri\":\"http://ads/a.m3u8\"},"
                        + "{\"offsetSec\":20,\"durationSec\":5,\"assetUri\":\"http://ads/b.m3u8\"}"
                        + "]}");
        session.putCachedAd("http://ads/a.m3u8", adA);
        session.putCachedAd("http://ads/b.m3u8", adB);

        Playlist out = new DemoPlaylistPipeline().apply(session, content);
        String text = PlaylistRewriteUtil.writeToString(out, Encoding.UTF_8);
        assertTrue(text.contains("http://ads/a0.ts"));
        assertTrue(text.contains("http://ads/a1.ts"));
        assertFalse(text.contains("http://ads/a2.ts"));
        assertTrue(text.contains("http://ads/b0.ts"));
        assertFalse(text.contains("http://ads/b1.ts"));
        assertTrue(text.contains("#EXT-X-CUE-OUT:8"));
        assertTrue(text.contains("#EXT-X-CUE-OUT:5"));
    }

    @Test
    public void snapshotAsVodAddsEndlistAndDropsStart() throws Exception {
        Playlist live = new Playlist.Builder()
                .withExtended(true)
                .withCompatibilityVersion(6)
                .withMediaPlaylist(new MediaPlaylist.Builder()
                        .withTargetDuration(6)
                        .withMediaSequenceNumber(100)
                        .withIsOngoing(true)
                        .withStartData(new StartData(36f, false))
                        .withTracks(Arrays.asList(
                                track("http://cdn/a.ts", 6f),
                                track("http://cdn/b.ts", 6f)))
                        .build())
                .build();
        Playlist out = DemoPlaylistPipeline.snapshotAsVod(live);
        String text = PlaylistRewriteUtil.writeToString(out, Encoding.UTF_8);
        assertTrue(text.contains("#EXT-X-ENDLIST"));
        assertTrue(text.contains("#EXT-X-PLAYLIST-TYPE:VOD"));
        assertFalse(text.contains("#EXT-X-START"));
        assertFalse(out.getMediaPlaylist().isOngoing());
    }

    @Test
    public void snapshotAsVodTextAddsEndlistAndDropsStart() {
        String live = "#EXTM3U\n"
                + "#EXT-X-VERSION:6\n"
                + "#EXT-X-MEDIA-SEQUENCE:5585863797\n"
                + "#EXT-X-TARGETDURATION:1\n"
                + "#EXT-X-START:TIME-OFFSET=10\n"
                + "#EXTINF:0.32, no desc\n"
                + "hls/seg.m4s\n";
        String out = DemoPlaylistPipeline.snapshotAsVodText(live);
        assertTrue(out.contains("#EXT-X-ENDLIST"));
        assertTrue(out.contains("#EXT-X-PLAYLIST-TYPE:VOD"));
        assertFalse(out.contains("#EXT-X-START"));
        assertTrue(out.contains("#EXT-X-MEDIA-SEQUENCE:5585863797"));
    }

    @Test
    public void snapshotAsVodTextLeavesMasterAlone() {
        String master = "#EXTM3U\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=100000\n"
                + "low.m3u8\n";
        assertEquals(master, DemoPlaylistPipeline.snapshotAsVodText(master));
    }

    @Test
    public void forceVodProcessAddsEndlistOnUnifiedLikeWindow() throws Exception {
        final String media = "#EXTM3U\n"
                + "#EXT-X-VERSION:6\n"
                + "#EXT-X-MEDIA-SEQUENCE:5585863797\n"
                + "#EXT-X-TARGETDURATION:1\n"
                + "#USP-X-TIMESTAMP-MAP:MPEGTS=1,LOCAL=2026-08-23T09:13:34.720000Z\n"
                + "#EXT-X-MAP:URI=\"hls/init.m4s\"\n"
                + "#EXT-X-PROGRAM-DATE-TIME:2026-08-23T09:13:34.720000Z\n"
                + "#EXTINF:0.32, no desc\n"
                + "hls/seg.m4s\n";
        DemoPlaylistPipeline pipeline = new DemoPlaylistPipeline(url ->
                new DemoHttp.FetchResult(200, "application/vnd.apple.mpegurl",
                        media.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        DemoSession session = DemoSession.fromJson("s-fv",
                "{\"strategy\":\"ssai\",\"contentUrl\":\"http://cdn/live.m3u8\",\"forceVod\":true}");
        DemoPlaylistPipeline.Result result = pipeline.process(session, "http://cdn/live.m3u8",
                "http://127.0.0.1:8765");
        String text = new String(result.body, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text.contains("#EXT-X-ENDLIST"));
        assertTrue(text.contains("#EXT-X-PLAYLIST-TYPE:VOD"));
        assertFalse(text.contains("#EXT-X-START"));
        assertTrue(text.contains("#EXT-X-MEDIA-SEQUENCE:5585863797"));
        assertTrue(text.contains("#EXT-X-MAP"));
        assertTrue(text.contains("#EXT-X-PROGRAM-DATE-TIME:2026-08-23T09:13:34.720000Z"));
    }

    @Test
    public void forceVodFallbackAddsEndlistWhenParseFails() throws Exception {
        final String unparsable = "#EXTM3U\n"
                + "#EXT-X-TARGETDURATION:1\n"
                + "#EXT-X-MEDIA-SEQUENCE:1\n"
                + "#EXTINF:not-a-duration,\n"
                + "seg.ts\n";
        DemoPlaylistPipeline pipeline = new DemoPlaylistPipeline(url ->
                new DemoHttp.FetchResult(200, "application/vnd.apple.mpegurl",
                        unparsable.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        DemoSession session = DemoSession.fromJson("s-fb",
                "{\"strategy\":\"ssai\",\"contentUrl\":\"http://cdn/bad.m3u8\",\"forceVod\":true}");
        DemoPlaylistPipeline.Result result = pipeline.process(session, "http://cdn/bad.m3u8",
                "http://127.0.0.1:8765");
        assertTrue(result.fallback);
        String text = new String(result.body, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text.contains("#EXT-X-ENDLIST"));
        assertTrue(text.contains("#EXT-X-PLAYLIST-TYPE:VOD"));
    }

    @Test
    public void ssaiStitchMarsFmp4WithTearsOfSteelTsMixesContainers() throws Exception {
        final String contentMaster = "#EXTM3U\n#EXT-X-VERSION:7\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=2100000,RESOLUTION=1280x720,CODECS=\"avc1.640029,mp4a.40.2\"\n"
                + "v720.m3u8\n";
        final String contentMedia = "#EXTM3U\n#EXT-X-VERSION:7\n"
                + "#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n"
                + "#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-MAP:URI=\"init.m4s\"\n"
                + "#EXTINF:4.0,\nv720_0.m4s\n"
                + "#EXTINF:4.0,\nv720_1.m4s\n"
                + "#EXTINF:4.0,\nv720_2.m4s\n"
                + "#EXTINF:4.0,\nv720_3.m4s\n"
                + "#EXT-X-ENDLIST\n";
        final String adMaster = "#EXTM3U\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=493000,CODECS=\"mp4a.40.2,avc1.66.30\"\n"
                + "tos.ts.m3u8\n";
        final String adMedia = "#EXTM3U\n#EXT-X-VERSION:1\n"
                + "#EXT-X-MEDIA-SEQUENCE:1\n#EXT-X-TARGETDURATION:4\n"
                + "#EXTINF:4, no desc\ntos-1.ts\n"
                + "#EXTINF:4, no desc\ntos-2.ts\n"
                + "#EXTINF:4, no desc\ntos-3.ts\n"
                + "#EXT-X-ENDLIST\n";
        DemoPlaylistPipeline pipeline = new DemoPlaylistPipeline(url -> {
            String body;
            if (url.endsWith("/master.m3u8") && url.contains("/mars/")) {
                body = contentMaster;
            } else if (url.endsWith("/v720.m3u8")) {
                body = contentMedia;
            } else if (url.contains("/tos/.m3u8") || url.endsWith("/tos.m3u8")) {
                body = adMaster;
            } else if (url.contains("tos.ts.m3u8") || url.contains("/tos/")) {
                body = adMedia;
            } else {
                body = contentMedia;
            }
            return new DemoHttp.FetchResult(200, "application/vnd.apple.mpegurl",
                    body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        });
        DemoSession session = DemoSession.fromJson("s-mars-ssai",
                "{\"strategy\":\"ssai\",\"contentUrl\":\"http://cdn/mars/master.m3u8\","
                        + "\"breaks\":[{\"offsetSec\":10,\"durationSec\":8,"
                        + "\"assetUri\":\"http://cdn/tos.m3u8\"}]}");

        DemoPlaylistPipeline.Result media = pipeline.process(session,
                "http://cdn/mars/v720.m3u8", "http://127.0.0.1:8765");
        String text = new String(media.body, java.nio.charset.StandardCharsets.UTF_8);

        assertFalse(media.fallback);
        assertTrue(text.contains("#EXT-X-CUE-OUT:8"));
        assertTrue(text.contains("#EXT-X-DISCONTINUITY"));
        assertTrue(text.contains("#EXT-X-MAP"));
        assertTrue(text.contains("init.m4s"));
        assertTrue(text.contains("v720_0.m4s"));
        assertTrue(text.contains("tos-1.ts"));
        assertTrue(text.contains("tos-2.ts"));

        int cue = text.indexOf("#EXT-X-CUE-OUT:8");
        int firstAd = text.indexOf("tos-1.ts", cue);
        int resumeMap = text.indexOf("#EXT-X-MAP", firstAd);
        assertTrue(firstAd > cue);
        assertTrue("fMP4 content must re-announce MAP after the TS ad", resumeMap > firstAd);

        // This mix is what hls.js reports as mediaError / bufferAppendError:
        // MSE SourceBuffer is video/mp4 (content) and cannot append MPEG-TS ad bytes.
        assertTrue(text.contains(".m4s"));
        assertTrue(text.contains(".ts"));
    }

    @Test
    public void forceVodSsaiStitchesThenPins() throws Exception {
        final String live = "#EXTM3U\n"
                + "#EXT-X-VERSION:3\n"
                + "#EXT-X-TARGETDURATION:10\n"
                + "#EXT-X-MEDIA-SEQUENCE:100\n"
                + "#EXT-X-START:TIME-OFFSET=36\n"
                + "#EXTINF:10.0,\nhttp://cdn/c0.ts\n"
                + "#EXTINF:10.0,\nhttp://cdn/c1.ts\n"
                + "#EXTINF:10.0,\nhttp://cdn/c2.ts\n";
        final String ad = "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXTINF:5.0,\nhttp://ads/a0.ts\n#EXT-X-ENDLIST\n";
        DemoPlaylistPipeline pipeline = new DemoPlaylistPipeline(url -> {
            String body = url.contains("/ads/") ? ad : live;
            return new DemoHttp.FetchResult(200, "application/vnd.apple.mpegurl",
                    body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        });
        DemoSession session = DemoSession.fromJson("s-snap-ssai",
                "{\"strategy\":\"ssai\",\"contentUrl\":\"http://cdn/live.m3u8\",\"forceVod\":true,"
                        + "\"breaks\":[{\"offsetSec\":10,\"durationSec\":5,\"assetUri\":\"http://ads/ad.m3u8\"}]}");
        DemoPlaylistPipeline.Result first = pipeline.process(session, "http://cdn/live.m3u8",
                "http://127.0.0.1:8765");
        String text = new String(first.body, java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(first.fallback);
        assertTrue(text.contains("#EXT-X-ENDLIST"));
        assertTrue(text.contains("#EXT-X-PLAYLIST-TYPE:VOD"));
        assertFalse(text.contains("#EXT-X-START"));
        assertTrue(text.contains("#EXT-X-CUE-OUT"));
        assertTrue(text.contains("#EXT-X-DISCONTINUITY"));
        assertTrue(text.contains("http://ads/a0.ts") || text.contains("/proxy?url="));

        DemoPlaylistPipeline.Result second = pipeline.process(session, "http://cdn/live.m3u8",
                "http://127.0.0.1:8765");
        assertEquals(new String(first.body, java.nio.charset.StandardCharsets.UTF_8),
                new String(second.body, java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    public void forceVodSgaiInjectsDateRange() throws Exception {
        final String live = "#EXTM3U\n"
                + "#EXT-X-VERSION:3\n"
                + "#EXT-X-TARGETDURATION:10\n"
                + "#EXT-X-MEDIA-SEQUENCE:100\n"
                + "#EXT-X-PROGRAM-DATE-TIME:2026-01-01T00:00:00.000Z\n"
                + "#EXTINF:10.0,\nhttp://cdn/c0.ts\n"
                + "#EXTINF:10.0,\nhttp://cdn/c1.ts\n"
                + "#EXTINF:10.0,\nhttp://cdn/c2.ts\n";
        DemoPlaylistPipeline pipeline = new DemoPlaylistPipeline(url ->
                new DemoHttp.FetchResult(200, "application/vnd.apple.mpegurl",
                        live.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        DemoSession session = DemoSession.fromJson("s-snap-sgai",
                "{\"strategy\":\"sgai\",\"contentUrl\":\"http://cdn/live.m3u8\",\"forceVod\":true,"
                        + "\"adUrl\":\"http://ads/ad.m3u8\","
                        + "\"breaks\":[{\"offsetSec\":10,\"durationSec\":5}]}");
        DemoPlaylistPipeline.Result result = pipeline.process(session, "http://cdn/live.m3u8",
                "http://127.0.0.1:8765");
        String text = new String(result.body, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text.contains("#EXT-X-ENDLIST"));
        assertTrue(text.contains("#EXT-X-DATERANGE"));
        assertTrue(text.contains("com.apple.hls.interstitial"));
        assertFalse(text.contains("#EXT-X-CUE-OUT"));
    }

    @Test
    public void masterIsUnchangedByApply() throws Exception {
        Playlist master = new Playlist.Builder()
                .withExtended(true)
                .withCompatibilityVersion(3)
                .withMasterPlaylist(new com.iheartradio.m3u8.data.MasterPlaylist.Builder()
                        .withPlaylists(Collections.singletonList(
                                new com.iheartradio.m3u8.data.PlaylistData.Builder()
                                        .withUri("low.m3u8")
                                        .withStreamInfo(new com.iheartradio.m3u8.data.StreamInfo.Builder()
                                                .withBandwidth(100000)
                                                .build())
                                        .build()))
                        .build())
                .build();
        DemoSession session = DemoSession.fromJson("s3",
                "{\"strategy\":\"ssai\",\"contentUrl\":\"http://cdn/master.m3u8\","
                        + "\"adUrl\":\"http://ads/ad.m3u8\",\"splices\":[0]}");
        Playlist out = new DemoPlaylistPipeline().apply(session, master);
        assertTrue(out.hasMasterPlaylist());
        assertFalse(out.hasMediaPlaylist());
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
}
