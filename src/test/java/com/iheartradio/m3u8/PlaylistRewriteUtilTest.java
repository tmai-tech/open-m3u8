package com.iheartradio.m3u8;

import com.iheartradio.m3u8.data.DateRangeData;
import com.iheartradio.m3u8.data.MediaPlaylist;
import com.iheartradio.m3u8.data.Playlist;
import com.iheartradio.m3u8.data.StartData;
import com.iheartradio.m3u8.data.TrackData;
import com.iheartradio.m3u8.data.TrackInfo;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaylistRewriteUtilTest {

    @Test
    public void injectInterstitialAndStartThenWrite() throws Exception {
        final TrackData t0 = new TrackData.Builder()
                .withUri("seg0.ts")
                .withTrackInfo(new TrackInfo(10f, null))
                .build();
        final TrackData t1 = new TrackData.Builder()
                .withUri("seg1.ts")
                .withTrackInfo(new TrackInfo(10f, null))
                .build();
        final MediaPlaylist media = new MediaPlaylist.Builder()
                .withTargetDuration(10)
                .withMediaSequenceNumber(0)
                .withIsOngoing(false)
                .withTracks(Arrays.asList(t0, t1))
                .build();
        final Playlist original = new Playlist.Builder()
                .withCompatibilityVersion(3)
                .withExtended(true)
                .withMediaPlaylist(media)
                .build();

        final PlaylistRewriteUtil.InjectConfig cfg = PlaylistRewriteUtil.InjectConfig.builder()
                .withStartOverride(new StartData(5f, true))
                .addBreak(new PlaylistRewriteUtil.InterstitialBreak(
                        "user-ad-1", 10f, 15f, "https://ads.example.com/ad.m3u8"))
                .withSnapToSegment(true)
                .withDefaultResumeOffset(0f)
                .withDefaultRestrict("SKIP")
                .build();

        final Playlist rewritten = PlaylistRewriteUtil.injectMediaTags(original, cfg);
        assertEquals(PlaylistRewriteUtil.INTERSTITIAL_MIN_VERSION, rewritten.getCompatibilityVersion());
        assertTrue(rewritten.getMediaPlaylist().hasStartData());
        assertEquals(5f, rewritten.getMediaPlaylist().getStartData().getTimeOffset(), 1e-4);
        assertTrue(rewritten.getMediaPlaylist().getStartData().isPrecise());

        final List<DateRangeData> ranges = rewritten.getMediaPlaylist().getDateRanges();
        assertEquals(1, ranges.size());
        final DateRangeData dr = ranges.get(0);
        assertTrue(dr.isInterstitial());
        assertEquals("user-ad-1", dr.getId());
        assertEquals(15f, dr.getDuration(), 1e-4);
        assertEquals("https://ads.example.com/ad.m3u8", dr.getAssetUri());
        assertEquals("SKIP", dr.getRestrict());
        assertEquals(0f, dr.getResumeOffset(), 1e-4);
        assertTrue(dr.hasStartDate());

        // Synthetic PDT on first segment
        assertTrue(rewritten.getMediaPlaylist().getTracks().get(0).hasProgramDateTime());

        final String text = PlaylistRewriteUtil.writeToString(rewritten, Encoding.UTF_8);
        assertTrue(text.contains("#EXT-X-START:"));
        assertTrue(text.contains("TIME-OFFSET=5"));
        assertTrue(text.contains("#EXT-X-DATERANGE:"));
        assertTrue(text.contains("CLASS=\"com.apple.hls.interstitial\""));
        assertTrue(text.contains("X-ASSET-URI=\"https://ads.example.com/ad.m3u8\""));
        assertTrue(text.contains("X-RESTRICT=\"SKIP\""));
        assertTrue(text.contains("X-RESUME-OFFSET="));
        assertTrue(text.contains("#EXT-X-PROGRAM-DATE-TIME:"));
        assertFalse(text.contains("X-RESTRICTIONS="));
    }

    @Test
    public void rewriteUrisThroughProxyMapper() throws Exception {
        final String source = ""
                + "#EXTM3U\n"
                + "#EXT-X-VERSION:7\n"
                + "#EXT-X-TARGETDURATION:10\n"
                + "#EXT-X-MEDIA-SEQUENCE:0\n"
                + "#EXT-X-DATERANGE:ID=\"ad-1\",CLASS=\"com.apple.hls.interstitial\","
                + "START-DATE=2020-01-01T00:00:10.000Z,DURATION=15.0,"
                + "X-ASSET-URI=\"https://ads.example.com/ad.m3u8\",X-RESTRICT=\"SKIP\"\n"
                + "#EXT-X-PROGRAM-DATE-TIME:2020-01-01T00:00:00.000Z\n"
                + "#EXTINF:10.0,\n"
                + "seg0.ts\n"
                + "#EXTINF:10.0,\n"
                + "https://cdn.example.com/seg1.ts\n"
                + "#EXT-X-ENDLIST\n";

        final Playlist parsed = PlaylistRewriteUtil.parse(
                source.getBytes(Encoding.UTF_8.value), Encoding.UTF_8);

        final Playlist mapped = PlaylistRewriteUtil.rewriteUris(
                parsed,
                "https://cdn.example.com/video/prog.m3u8",
                new PlaylistRewriteUtil.UriMapper() {
                    @Override
                    public String map(String absoluteUri) {
                        return PlaylistRewriteUtil.toProxyUrl("http://127.0.0.1:8765", absoluteUri);
                    }
                });

        final String text = PlaylistRewriteUtil.writeToString(mapped, Encoding.UTF_8);
        assertTrue(text.contains("http://127.0.0.1:8765/proxy?url="));
        assertTrue(text.contains("X-ASSET-URI=\"http://127.0.0.1:8765/proxy?url="));
        // relative seg0 resolved against playlist URL then proxied
        assertTrue(text.contains("cdn.example.com"));
    }

    @Test
    public void parseAppleRestrictAndLegacyRestrictions() throws Exception {
        final String apple = "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:6\n"
                + "#EXT-X-DATERANGE:ID=\"a\",CLASS=\"com.apple.hls.interstitial\","
                + "START-DATE=2020-01-01T00:00:00.000Z,DURATION=5,X-ASSET-URI=\"https://a/x.m3u8\","
                + "X-RESTRICT=\"SKIP,JUMP\",X-SNAP=\"IN\",X-PLAYOUT-LIMIT=30.0,"
                + "X-CONTENT-MAY-VARY=YES,X-TIMELINE-OCCUPIES=\"RANGE\",X-TIMELINE-STYLE=\"HIGHLIGHT\","
                + "X-ASSET-LIST=\"https://a/list.json\"\n"
                + "#EXTINF:6.0,\nseg.ts\n#EXT-X-ENDLIST\n";

        final Playlist p = new PlaylistParser(
                new ByteArrayInputStream(apple.getBytes(Encoding.UTF_8.value)),
                Format.EXT_M3U, Encoding.UTF_8).parse();
        final DateRangeData dr = p.getMediaPlaylist().getDateRanges().get(0);
        assertEquals("SKIP,JUMP", dr.getRestrict());
        assertEquals("IN", dr.getSnap());
        assertEquals(30f, dr.getPlayoutLimit(), 1e-4);
        assertEquals(Boolean.TRUE, dr.getContentMayVary());
        assertEquals("RANGE", dr.getTimelineOccupies());
        assertEquals("HIGHLIGHT", dr.getTimelineStyle());
        assertEquals("https://a/list.json", dr.getAssetList());

        final String legacy = "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:6\n"
                + "#EXT-X-DATERANGE:ID=\"b\",START-DATE=2020-01-01T00:00:00.000Z,"
                + "X-RESTRICTIONS=\"SKIP\"\n"
                + "#EXTINF:6.0,\nseg.ts\n#EXT-X-ENDLIST\n";
        final Playlist p2 = new PlaylistParser(
                new ByteArrayInputStream(legacy.getBytes(Encoding.UTF_8.value)),
                Format.EXT_M3U, Encoding.UTF_8).parse();
        assertEquals("SKIP", p2.getMediaPlaylist().getDateRanges().get(0).getRestrictions());
    }

    @Test
    public void programDateTimeRoundTrip() throws Exception {
        final TrackData t = new TrackData.Builder()
                .withUri("http://example.com/seg0.ts")
                .withTrackInfo(new TrackInfo(4f, null))
                .withProgramDateTime("2024-06-15T12:00:00.000Z")
                .build();
        final MediaPlaylist media = new MediaPlaylist.Builder()
                .withTargetDuration(4)
                .withMediaSequenceNumber(0)
                .withIsOngoing(false)
                .withTracks(Arrays.asList(t))
                .build();
        final Playlist playlist = new Playlist.Builder()
                .withCompatibilityVersion(6)
                .withExtended(true)
                .withMediaPlaylist(media)
                .build();
        final String written = PlaylistRewriteUtil.writeToString(playlist, Encoding.UTF_8);
        assertTrue(written.contains("#EXT-X-PROGRAM-DATE-TIME:2024-06-15T12:00:00.000Z"));
        final Playlist again = PlaylistRewriteUtil.parse(written.getBytes(Encoding.UTF_8.value), Encoding.UTF_8);
        assertEquals("2024-06-15T12:00:00.000Z",
                again.getMediaPlaylist().getTracks().get(0).getProgramDateTime());
    }
}
