package com.iheartradio.m3u8;

import com.iheartradio.m3u8.data.DateRangeData;
import com.iheartradio.m3u8.data.DefineData;
import com.iheartradio.m3u8.data.MediaPlaylist;
import com.iheartradio.m3u8.data.Playlist;
import com.iheartradio.m3u8.data.TrackData;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SgaiPlaylistTest {

    @Test
    public void parseSgaiInterstitialPlaylist() throws Exception {
        final Playlist playlist = TestUtil.parsePlaylistFromResource("sgaiInterstitialPlaylist.m3u8");
        final MediaPlaylist mediaPlaylist = playlist.getMediaPlaylist();

        assertEquals(11, playlist.getCompatibilityVersion());
        assertTrue(mediaPlaylist.hasDefines());
        assertEquals(2, mediaPlaylist.getDefines().size());

        final DefineData userId = mediaPlaylist.getDefines().get(0);
        assertEquals("userId", userId.getName());
        assertEquals("user-12345", userId.getValue());

        final DefineData sessionToken = mediaPlaylist.getDefines().get(1);
        assertEquals("sessionToken", sessionToken.getName());
        assertEquals("tok-abc-xyz", sessionToken.getValue());

        assertTrue(mediaPlaylist.hasDateRanges());
        assertEquals(1, mediaPlaylist.getDateRanges().size());

        final DateRangeData adBreak = mediaPlaylist.getDateRanges().get(0);
        assertEquals("adbreak-1", adBreak.getId());
        assertEquals(Constants.INTERSTITIAL_CLASS, adBreak.getClassAttribute());
        assertTrue(adBreak.isInterstitial());
        assertEquals("2024-06-15T12:00:00.000Z", adBreak.getStartDate());
        assertEquals(30.0f, adBreak.getDuration(), 1e-6);
        assertTrue(adBreak.getAssetUri().contains("ads.example.com/vast"));
        assertEquals("SKIP,JUMP", adBreak.getRestrictions());
        assertEquals(0.0f, adBreak.getResumeOffset(), 1e-6);

        final List<TrackData> tracks = mediaPlaylist.getTracks();
        assertEquals(3, tracks.size());
        assertFalse(tracks.get(0).hasDiscontinuity());
        assertFalse(tracks.get(1).hasDiscontinuity());
        assertTrue(tracks.get(2).hasDiscontinuity());
    }

    @Test
    public void writeAndRoundTripSgaiPlaylist() throws Exception {
        final DateRangeData dateRange = new DateRangeData.Builder()
                .withId("pod-1")
                .withClassAttribute(Constants.INTERSTITIAL_CLASS)
                .withStartDate("2025-01-01T00:00:00.000Z")
                .withDuration(15.5f)
                .withAssetUri("https://ads.example.com/vmap")
                .withRestrictions("SKIP")
                .withResumeOffset(0f)
                .build();

        final DefineData define = new DefineData.Builder()
                .withName("uid")
                .withValue("42")
                .build();

        final TrackData track = new TrackData.Builder()
                .withUri("http://example.com/seg0.ts")
                .withTrackInfo(new com.iheartradio.m3u8.data.TrackInfo(10f, null))
                .build();

        final TrackData afterBreak = new TrackData.Builder()
                .withUri("http://example.com/seg1.ts")
                .withTrackInfo(new com.iheartradio.m3u8.data.TrackInfo(10f, null))
                .withDiscontinuity(true)
                .build();

        final MediaPlaylist mediaPlaylist = new MediaPlaylist.Builder()
                .withTargetDuration(10)
                .withMediaSequenceNumber(0)
                .withIsOngoing(false)
                .withDefines(Arrays.asList(define))
                .withDateRanges(Arrays.asList(dateRange))
                .withTracks(Arrays.asList(track, afterBreak))
                .build();

        final Playlist playlist = new Playlist.Builder()
                .withCompatibilityVersion(11)
                .withExtended(true)
                .withMediaPlaylist(mediaPlaylist)
                .build();

        final ByteArrayOutputStream os = new ByteArrayOutputStream();
        new PlaylistWriter(os, Format.EXT_M3U, Encoding.UTF_8).write(playlist);
        final String written = os.toString(Encoding.UTF_8.value);

        assertTrue(written.contains("#EXT-X-VERSION:11"));
        assertTrue(written.contains("#EXT-X-DEFINE:NAME=\"uid\",VALUE=\"42\"")
                || (written.contains("NAME=\"uid\"") && written.contains("VALUE=\"42\"")));
        assertTrue(written.contains("#EXT-X-DATERANGE:"));
        assertTrue(written.contains("ID=\"pod-1\""));
        assertTrue(written.contains("CLASS=\"com.apple.hls.interstitial\""));
        assertTrue(written.contains("START-DATE=2025-01-01T00:00:00.000Z"));
        assertTrue(written.contains("DURATION=15.5"));
        assertTrue(written.contains("X-ASSET-URI="));
        assertTrue(written.contains("X-RESTRICTIONS=\"SKIP\""));
        assertTrue(written.contains("X-RESUME-OFFSET=0.0") || written.contains("X-RESUME-OFFSET=0"));
        assertTrue(written.contains("#EXT-X-DISCONTINUITY"));

        final Playlist reparsed = new PlaylistParser(
                new ByteArrayInputStream(written.getBytes(Encoding.UTF_8.value)),
                Format.EXT_M3U,
                Encoding.UTF_8).parse();

        assertEquals(11, reparsed.getCompatibilityVersion());
        assertEquals(1, reparsed.getMediaPlaylist().getDefines().size());
        assertEquals("uid", reparsed.getMediaPlaylist().getDefines().get(0).getName());
        assertEquals(1, reparsed.getMediaPlaylist().getDateRanges().size());
        assertTrue(reparsed.getMediaPlaylist().getDateRanges().get(0).isInterstitial());
        assertTrue(reparsed.getMediaPlaylist().getTracks().get(1).hasDiscontinuity());
    }
}
