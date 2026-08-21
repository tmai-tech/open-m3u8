package com.iheartradio.m3u8.demo;

import com.iheartradio.m3u8.Encoding;
import com.iheartradio.m3u8.PlaylistRewriteUtil;
import com.iheartradio.m3u8.data.MediaPlaylist;
import com.iheartradio.m3u8.data.Playlist;
import com.iheartradio.m3u8.data.TrackData;
import com.iheartradio.m3u8.data.TrackInfo;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DemoPlaylistPipelineTest {

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
