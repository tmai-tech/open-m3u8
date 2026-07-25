package com.iheartradio.m3u8;

import com.iheartradio.m3u8.data.EncryptionData;
import com.iheartradio.m3u8.data.EncryptionMethod;
import com.iheartradio.m3u8.data.MediaPlaylist;
import com.iheartradio.m3u8.data.Playlist;
import com.iheartradio.m3u8.data.TrackData;
import com.iheartradio.m3u8.data.TrackInfo;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaylistSsaiUtilTest {

    @Test
    public void stitchMidRollInsertsAdsWithCuesAndDiscontinuities() throws Exception {
        Playlist content = contentPlaylist(
                track("http://cdn/c0.ts", 10f),
                track("http://cdn/c1.ts", 10f),
                track("http://cdn/c2.ts", 10f));

        PlaylistSsaiUtil.AdBreak mid = PlaylistSsaiUtil.AdBreak.builder()
                .withId("mid-1")
                .afterTrackIndex(0)
                .addAdSegment("http://ads/a0.ts", 5f)
                .addAdSegment("http://ads/a1.ts", 5f)
                .build();

        Playlist stitched = PlaylistSsaiUtil.stitch(content, Collections.singletonList(mid));
        List<TrackData> tracks = stitched.getMediaPlaylist().getTracks();

        // c0, a0, a1, c1, c2
        assertEquals(5, tracks.size());
        assertEquals("http://cdn/c0.ts", tracks.get(0).getUri());

        TrackData a0 = tracks.get(1);
        assertEquals("http://ads/a0.ts", a0.getUri());
        assertTrue(a0.hasDiscontinuity());
        assertTrue(a0.hasCueOut());
        assertEquals(10f, a0.getCueOut().getDuration(), 1e-4);
        assertFalse(a0.hasCueOutCont());

        TrackData a1 = tracks.get(2);
        assertTrue(a1.hasCueOutCont());
        assertEquals(5f, a1.getCueOutCont().getElapsedTime(), 1e-4);
        assertEquals(10f, a1.getCueOutCont().getDuration(), 1e-4);
        assertTrue(a1.hasCueIn());

        TrackData c1 = tracks.get(3);
        assertEquals("http://cdn/c1.ts", c1.getUri());
        assertTrue(c1.hasDiscontinuity());
        assertFalse(c1.hasCueIn());

        assertTrue(stitched.getMediaPlaylist().hasDateRanges());
        assertEquals("mid-1", stitched.getMediaPlaylist().getDateRanges().get(0).getId());
        assertEquals(10f, stitched.getMediaPlaylist().getDateRanges().get(0).getDuration(), 1e-4);
    }

    @Test
    public void stitchPreAndPostRoll() throws Exception {
        Playlist content = contentPlaylist(
                track("http://cdn/c0.ts", 10f),
                track("http://cdn/c1.ts", 10f));

        PlaylistSsaiUtil.AdBreak pre = PlaylistSsaiUtil.AdBreak.builder()
                .withId("pre")
                .preRoll()
                .addAdSegment("http://ads/pre.ts", 6f)
                .build();
        PlaylistSsaiUtil.AdBreak post = PlaylistSsaiUtil.AdBreak.builder()
                .withId("post")
                .postRoll()
                .addAdSegment("http://ads/post.ts", 4f)
                .build();

        Playlist stitched = PlaylistSsaiUtil.stitch(content, Arrays.asList(pre, post));
        List<TrackData> tracks = stitched.getMediaPlaylist().getTracks();
        assertEquals(4, tracks.size());
        assertEquals("http://ads/pre.ts", tracks.get(0).getUri());
        assertTrue(tracks.get(0).hasCueOut());
        assertTrue(tracks.get(0).hasCueIn());
        assertEquals("http://cdn/c0.ts", tracks.get(1).getUri());
        assertTrue(tracks.get(1).hasDiscontinuity());
        assertEquals("http://cdn/c1.ts", tracks.get(2).getUri());
        assertEquals("http://ads/post.ts", tracks.get(3).getUri());
        assertTrue(tracks.get(3).hasDiscontinuity());
        assertTrue(tracks.get(3).hasCueOut());
    }

    @Test
    public void stitchAtOffsetSnapsToSegmentBoundary() throws Exception {
        Playlist content = contentPlaylist(
                track("http://cdn/c0.ts", 10f),
                track("http://cdn/c1.ts", 10f),
                track("http://cdn/c2.ts", 10f));

        // 12s snaps nearest to 10s → after track 0
        PlaylistSsaiUtil.AdBreak mid = PlaylistSsaiUtil.AdBreak.builder()
                .withId("off")
                .atOffsetSec(12f)
                .addAdSegment("http://ads/a.ts", 5f)
                .build();

        Playlist stitched = PlaylistSsaiUtil.stitch(content, Collections.singletonList(mid));
        List<TrackData> tracks = stitched.getMediaPlaylist().getTracks();
        assertEquals("http://cdn/c0.ts", tracks.get(0).getUri());
        assertEquals("http://ads/a.ts", tracks.get(1).getUri());
        assertEquals("http://cdn/c1.ts", tracks.get(2).getUri());
    }

    @Test
    public void stitchFromAdMediaPlaylist() throws Exception {
        Playlist content = contentPlaylist(track("http://cdn/c0.ts", 10f), track("http://cdn/c1.ts", 10f));
        Playlist ad = contentPlaylist(track("http://ads/a0.ts", 3f), track("http://ads/a1.ts", 3f));

        PlaylistSsaiUtil.AdBreak mid = PlaylistSsaiUtil.AdBreak.builder()
                .withId("pod")
                .afterTrackIndex(0)
                .withAdPlaylist(ad)
                .build();

        Playlist stitched = PlaylistSsaiUtil.stitch(content, Collections.singletonList(mid));
        assertEquals(4, stitched.getMediaPlaylist().getTracks().size());
        assertEquals("http://ads/a0.ts", stitched.getMediaPlaylist().getTracks().get(1).getUri());
        assertEquals("http://ads/a1.ts", stitched.getMediaPlaylist().getTracks().get(2).getUri());
    }

    @Test
    public void stitchRaisesTargetDurationForLongerAds() throws Exception {
        Playlist content = new Playlist.Builder()
                .withExtended(true)
                .withCompatibilityVersion(3)
                .withMediaPlaylist(new MediaPlaylist.Builder()
                        .withTargetDuration(6)
                        .withMediaSequenceNumber(0)
                        .withIsOngoing(false)
                        .withTracks(Arrays.asList(track("http://cdn/c0.ts", 6f)))
                        .build())
                .build();

        PlaylistSsaiUtil.AdBreak pre = PlaylistSsaiUtil.AdBreak.builder()
                .preRoll()
                .addAdSegment("http://ads/long.ts", 15f)
                .build();

        Playlist stitched = PlaylistSsaiUtil.stitch(content, Collections.singletonList(pre));
        assertEquals(15, stitched.getMediaPlaylist().getTargetDuration());
    }

    @Test
    public void stitchEncryptedContentEmitsClearKeyOnAds() throws Exception {
        EncryptionData key = new EncryptionData.Builder()
                .withMethod(EncryptionMethod.AES)
                .withUri("https://keys.example/k0")
                .build();

        TrackData c0 = new TrackData.Builder()
                .withUri("http://cdn/c0.ts")
                .withTrackInfo(new TrackInfo(10f, null))
                .withEncryptionData(key)
                .build();
        TrackData c1 = new TrackData.Builder()
                .withUri("http://cdn/c1.ts")
                .withTrackInfo(new TrackInfo(10f, null))
                .withEncryptionData(key)
                .build();

        Playlist content = new Playlist.Builder()
                .withExtended(true)
                .withCompatibilityVersion(3)
                .withMediaPlaylist(new MediaPlaylist.Builder()
                        .withTargetDuration(10)
                        .withMediaSequenceNumber(0)
                        .withIsOngoing(false)
                        .withTracks(Arrays.asList(c0, c1))
                        .build())
                .build();

        PlaylistSsaiUtil.AdBreak mid = PlaylistSsaiUtil.AdBreak.builder()
                .afterTrackIndex(0)
                .addAdSegment("http://ads/a0.ts", 5f)
                .build();

        Playlist stitched = PlaylistSsaiUtil.stitch(content, Collections.singletonList(mid));
        TrackData ad = stitched.getMediaPlaylist().getTracks().get(1);
        assertTrue(ad.hasEncryptionData());
        assertEquals(EncryptionMethod.NONE, ad.getEncryptionData().getMethod());

        ByteArrayOutputStream os = new ByteArrayOutputStream();
        new PlaylistWriter(os, Format.EXT_M3U, Encoding.UTF_8).write(stitched);
        String text = os.toString(Encoding.UTF_8.value);
        assertTrue(text.contains("METHOD=NONE"));
        assertTrue(text.contains("#EXT-X-CUE-OUT"));
        assertTrue(text.contains("#EXT-X-CUE-IN"));
        assertTrue(text.contains("#EXT-X-DISCONTINUITY"));
    }

    @Test
    public void writeRoundTripStitchedPlaylist() throws Exception {
        Playlist content = contentPlaylist(
                track("http://cdn/c0.ts", 10f),
                track("http://cdn/c1.ts", 10f));
        PlaylistSsaiUtil.AdBreak mid = PlaylistSsaiUtil.AdBreak.builder()
                .withId("break-1")
                .afterTrackIndex(0)
                .addAdSegment("http://ads/a0.ts", 10f)
                .addAdSegment("http://ads/a1.ts", 10f)
                .withScte35Out("/DAlAAAA")
                .build();

        Playlist stitched = PlaylistSsaiUtil.stitch(content, Collections.singletonList(mid));
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        new PlaylistWriter(os, Format.EXT_M3U, Encoding.UTF_8).write(stitched);

        Playlist reparsed = new PlaylistParser(
                new ByteArrayInputStream(os.toByteArray()),
                Format.EXT_M3U,
                Encoding.UTF_8).parse();

        List<TrackData> tracks = reparsed.getMediaPlaylist().getTracks();
        assertEquals(4, tracks.size());
        assertTrue(tracks.get(1).hasCueOut());
        assertTrue(tracks.get(2).hasCueOutCont());
        assertTrue(tracks.get(2).hasCueIn());
        assertTrue(tracks.get(3).hasDiscontinuity());
        assertTrue(reparsed.getMediaPlaylist().hasDateRanges());
        assertEquals("break-1", reparsed.getMediaPlaylist().getDateRanges().get(0).getId());
    }

    @Test
    public void emptyBreaksReturnsSamePlaylist() {
        Playlist content = contentPlaylist(track("http://cdn/c0.ts", 10f));
        Playlist out = PlaylistSsaiUtil.stitch(content, Collections.<PlaylistSsaiUtil.AdBreak>emptyList());
        assertEquals(content, out);
    }

    @Test
    public void indexAfterOffsetHelpers() {
        List<TrackData> tracks = Arrays.asList(
                track("a", 10f), track("b", 10f), track("c", 10f));
        assertEquals(-1, PlaylistSsaiUtil.indexAfterOffset(tracks, 0f, true));
        assertEquals(0, PlaylistSsaiUtil.indexAfterOffset(tracks, 10f, true));
        assertEquals(1, PlaylistSsaiUtil.indexAfterOffset(tracks, 20f, true));
        assertEquals(2, PlaylistSsaiUtil.indexAfterOffset(tracks, 30f, true));
    }

    @Test
    public void trimTracksToMaxDurationKeepsWholeSegments() {
        List<TrackData> tracks = Arrays.asList(
                track("a0", 10f), track("a1", 10f), track("a2", 10f), track("a3", 10f));
        List<TrackData> trimmed = PlaylistSsaiUtil.trimTracksToMaxDuration(tracks, 30f);
        assertEquals(3, trimmed.size());
        assertEquals(30f, durationSum(trimmed), 1e-4);

        // First segment alone longer than max — still keep one segment
        List<TrackData> longFirst = PlaylistSsaiUtil.trimTracksToMaxDuration(
                Arrays.asList(track("big", 40f), track("next", 10f)), 30f);
        assertEquals(1, longFirst.size());
        assertEquals("big", longFirst.get(0).getUri());

        // max <= 0 → no trim
        assertEquals(4, PlaylistSsaiUtil.trimTracksToMaxDuration(tracks, 0f).size());
    }

    @Test
    public void stitchWithMaxAdDurationTrimsCueOutDuration() {
        Playlist content = contentPlaylist(
                track("http://cdn/c0.ts", 10f),
                track("http://cdn/c1.ts", 10f));
        Playlist ad = contentPlaylist(
                track("http://ads/a0.ts", 10f),
                track("http://ads/a1.ts", 10f),
                track("http://ads/a2.ts", 10f),
                track("http://ads/a3.ts", 10f));

        PlaylistSsaiUtil.AdBreak mid = PlaylistSsaiUtil.AdBreak.builder()
                .afterTrackIndex(0)
                .withAdPlaylist(ad, 30f)
                .build();
        Playlist stitched = PlaylistSsaiUtil.stitch(content, Collections.singletonList(mid));
        List<TrackData> tracks = stitched.getMediaPlaylist().getTracks();
        // c0 + 3 ads + c1
        assertEquals(5, tracks.size());
        assertTrue(tracks.get(1).hasCueOut());
        assertEquals(30f, tracks.get(1).getCueOut().getDuration(), 1e-4);
    }

    private static float durationSum(List<TrackData> tracks) {
        float s = 0f;
        for (TrackData t : tracks) {
            if (t.hasTrackInfo()) s += t.getTrackInfo().duration;
        }
        return s;
    }

    private static TrackData track(String uri, float duration) {
        return new TrackData.Builder()
                .withUri(uri)
                .withTrackInfo(new TrackInfo(duration, null))
                .build();
    }

    private static Playlist contentPlaylist(TrackData... tracks) {
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
}
